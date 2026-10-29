package com.ragkb.eval;

import com.ragkb.common.DotEnv;
import com.ragkb.embedding.DashScopeEmbeddingClient;
import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.embedding.EmbeddingProperties;
import com.ragkb.retrieval.JdbcKeywordRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.vector.JdbcVectorStoreRepository;
import com.ragkb.vector.ScoredChunk;
import com.ragkb.vector.VectorStoreRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 检索方案对比:同一份评估集上跑多组配置,输出一张对比表。
 *
 * <h2>为什么这个 CLI 自己读 .env</h2>
 * Spring 的 {@code spring.config.import} 只在容器里生效,命令行工具读的是进程环境。
 * 不在命令行里读一遍,使用者就得先 export 一堆变量才能跑 —— 这层摩擦会让人干脆不跑评估。
 * 这里做最小解析:只支持 {@code KEY=VALUE} 与 {@code #} 注释。
 *
 * <h2>为什么要缓存问题的向量</h2>
 * 同一份评估集会跑 6 组配置,每组都要把同样的 8 个问题向量化一遍 —— 不缓存就是 48 次调用、
 * 6 倍成本。缓存只对"离线跑批"成立:线上每次提问都是新文本,没有可缓存的重复。
 *
 * <h2>这张表怎么读</h2>
 * <ul>
 *   <li>recall@5 与 recall@20 的差距反映排序质量:能找到但排不上去,差距就大;</li>
 *   <li>MRR 比 recall 更敏感于"排得靠不靠前";</li>
 *   <li>失败数是"前 20 位都没命中"的用例条数,比百分比更直观。</li>
 * </ul>
 */
public final class RetrievalComparison {

    /**
     * 评估跑批固定用租户 1:语料与用例都属于它。
     * 不跟随登录用户 —— 评估不是"某个人的会话",而是一次离线回归。
     */
    private static final long EVAL_TENANT = 1L;

    private static final Path DEFAULT_CASES = Path.of("samples", "eval", "cases.json");
    private static final Path DOT_ENV = Path.of(".env");

    private RetrievalComparison() {
    }

    private record Config(String name, BiFunction<String, Integer, List<String>> retriever) {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> dotEnv = DotEnv.load(DOT_ENV);
        Path casesPath = Path.of(args.length > 0 ? args[0] : DEFAULT_CASES.toString());
        EvalDataset dataset = EvalDataset.load(casesPath);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource(dotEnv));
        JdbcKeywordRetriever ilike = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.ILIKE);
        JdbcKeywordRetriever trgm = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.TRGM);
        RrfFuser fuser = new RrfFuser();

        EmbeddingClient embeddingClient = embeddingClient(dotEnv);
        VectorRetrieverHolder vector = new VectorRetrieverHolder(embeddingClient, jdbc, dotEnv);
        boolean vectorReady = embeddingClient != null && vector.available();

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("ILIKE", (q, limit) -> contents(ilike.search(q, limit, EVAL_TENANT))));
        configs.add(new Config("TRGM", (q, limit) -> contents(trgm.search(q, limit, EVAL_TENANT))));
        // 中文二元组通道:与 TRGM(三元组相似度)对照,量化"没有中文分词"的代价
        com.ragkb.retrieval.JdbcBigramRetriever bigram =
                new com.ragkb.retrieval.JdbcBigramRetriever(jdbc);
        configs.add(new Config("BIGRAM", (q, limit) -> contents(bigram.search(q, limit, EVAL_TENANT))));
        // 查询改写只喂关键词通道:向量通道本来就擅长"换个说法",再改是白花钱
        com.ragkb.generation.ChatModel chatModel = chatModel(dotEnv);
        if (vectorReady && chatModel != null) {
            com.ragkb.retrieval.QueryRewriter rewriter = new com.ragkb.retrieval.QueryRewriter(chatModel);
            configs.add(new Config("HYBRID+RW", (q, limit) -> {
                String rewritten = rewriter.rewrite(q);
                List<ScoredChunk> left = vector.search(q, limit * 2, EVAL_TENANT);
                List<ScoredChunk> right = trgm.search(rewritten, limit * 2, EVAL_TENANT);
                return fuser.fuse(left, right, 60, limit).stream()
                        .map(hit -> hit.chunk().content())
                        .toList();
            }));
        }
        if (vectorReady) {
            configs.add(new Config("VECTOR", (q, limit) -> contents(vector.search(q, limit, EVAL_TENANT))));
            for (int k : new int[]{0, 20, 60}) {
                configs.add(new Config("HYBRID k=" + k, (q, limit) -> {
                    List<ScoredChunk> left = vector.search(q, limit * 2, EVAL_TENANT);
                    List<ScoredChunk> right = trgm.search(q, limit * 2, EVAL_TENANT);
                    return fuser.fuse(left, right, k, limit).stream()
                            .map(hit -> hit.chunk().content())
                            .toList();
                }));
            }
        }

        System.out.printf("%-14s %10s %11s %8s %8s%n", "方案", "recall@5", "recall@20", "MRR", "失败数");
        System.out.println("-".repeat(56));
        EvalRunner runner = new EvalRunner();
        for (Config config : configs) {
            EvalReport report = runner.run(dataset, config.retriever()::apply, config.name());
            System.out.printf("%-14s %10.4f %11.4f %8.4f %8d%n",
                    config.name(),
                    report.retrieval().recallAt5(),
                    report.retrieval().recallAt20(),
                    report.retrieval().mrr(),
                    report.failedCases().size());
        }
        System.out.println();
        System.out.println("用例数 " + dataset.cases().size()
                + "(参与召回 " + dataset.evaluableCount() + ",文档外 " + dataset.refusalCaseCount() + ")");
        if (!vectorReady) {
            System.err.println("向量通道未启用:缺 DASHSCOPE_API_KEY,或库里没有当前模型的向量。");
        }
    }

    /** 向量检索 + 问题向量缓存。包一层是因为 VectorRetriever 每次都会真的调接口。 */
    private static final class VectorRetrieverHolder {
        private final EmbeddingClient client;
        private final VectorStoreRepository repository;
        private final Map<String, float[]> cache = new HashMap<>();

        private VectorRetrieverHolder(EmbeddingClient client, JdbcTemplate jdbc, Map<String, String> env) {
            this.client = client;
            this.repository = client == null ? null
                    : new JdbcVectorStoreRepository(jdbc, DotEnv.get(env, "EMBEDDING_MODEL", "text-embedding-v3"));
        }

        private boolean available() {
            return repository != null && repository.count() > 0;
        }

        private List<ScoredChunk> search(String question, int limit, long tenantId) {
            float[] vector = cache.computeIfAbsent(question, client::embedQuery);
            return repository.search(vector, limit, tenantId);
        }
    }

    /** 改写要用对话模型;没有 Key 时返回 null(该项配置直接跳过)。 */
    private static com.ragkb.generation.ChatModel chatModel(Map<String, String> env) {
        String apiKey = DotEnv.get(env, "DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            return null;
        }
        com.ragkb.generation.ChatProperties defaults = com.ragkb.generation.ChatProperties.defaults();
        com.ragkb.generation.ChatProperties properties = new com.ragkb.generation.ChatProperties(
                apiKey,
                DotEnv.get(env, "CHAT_MODEL", defaults.model()),
                DotEnv.get(env, "CHAT_ENDPOINT", defaults.endpoint()),
                // 改写要稳定:温度调低,避免同一问题两次改写差别很大
                0.0,
                256,
                5,
                2);
        return new com.ragkb.generation.DashScopeChatModel(properties);
    }

    private static EmbeddingClient embeddingClient(Map<String, String> env) {
        String apiKey = DotEnv.get(env, "DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            return null;
        }
        EmbeddingProperties properties = new EmbeddingProperties(
                apiKey,
                DotEnv.get(env, "EMBEDDING_MODEL", EmbeddingProperties.DEFAULT_MODEL),
                512,
                10,
                DotEnv.get(env, "EMBEDDING_ENDPOINT", EmbeddingProperties.DEFAULT_ENDPOINT),
                30, 3);
        return new DashScopeEmbeddingClient(properties);
    }

    private static DriverManagerDataSource dataSource(Map<String, String> env) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(DotEnv.get(env, "DB_URL", "jdbc:postgresql://localhost:5433/rag_kb"));
        dataSource.setUsername(DotEnv.get(env, "DB_USERNAME", "postgres"));
        dataSource.setPassword(DotEnv.get(env, "DB_PASSWORD", "rag123456"));
        return dataSource;
    }

    private static List<String> contents(List<ScoredChunk> hits) {
        return hits.stream().map(ScoredChunk::content).toList();
    }
}
