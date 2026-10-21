package com.ragkb.eval;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private static final Path DEFAULT_CASES = Path.of("samples", "eval", "cases.json");
    private static final Path DOT_ENV = Path.of(".env");

    private RetrievalComparison() {
    }

    private record Config(String name, BiFunction<String, Integer, List<String>> retriever) {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> dotEnv = loadDotEnv(DOT_ENV);
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
        configs.add(new Config("ILIKE", (q, limit) -> contents(ilike.search(q, limit))));
        configs.add(new Config("TRGM", (q, limit) -> contents(trgm.search(q, limit))));
        if (vectorReady) {
            configs.add(new Config("VECTOR", (q, limit) -> contents(vector.search(q, limit))));
            for (int k : new int[]{0, 20, 60}) {
                configs.add(new Config("HYBRID k=" + k, (q, limit) -> {
                    List<ScoredChunk> left = vector.search(q, limit * 2);
                    List<ScoredChunk> right = trgm.search(q, limit * 2);
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
                    : new JdbcVectorStoreRepository(jdbc, envOrDefault(env, "EMBEDDING_MODEL", "text-embedding-v3"));
        }

        private boolean available() {
            return repository != null && repository.count() > 0;
        }

        private List<ScoredChunk> search(String question, int limit) {
            float[] vector = cache.computeIfAbsent(question, client::embed);
            return repository.search(vector, limit);
        }
    }

    private static EmbeddingClient embeddingClient(Map<String, String> env) {
        String apiKey = envOrDefault(env, "DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            return null;
        }
        EmbeddingProperties properties = new EmbeddingProperties(
                apiKey,
                envOrDefault(env, "EMBEDDING_MODEL", EmbeddingProperties.DEFAULT_MODEL),
                512,
                10,
                envOrDefault(env, "EMBEDDING_ENDPOINT", EmbeddingProperties.DEFAULT_ENDPOINT),
                30, 3);
        return new DashScopeEmbeddingClient(properties);
    }

    private static DriverManagerDataSource dataSource(Map<String, String> env) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(envOrDefault(env, "DB_URL", "jdbc:postgresql://localhost:5433/rag_kb"));
        dataSource.setUsername(envOrDefault(env, "DB_USERNAME", "postgres"));
        dataSource.setPassword(envOrDefault(env, "DB_PASSWORD", "rag123456"));
        return dataSource;
    }

    /** 进程环境优先,其次 .env —— 这样 CI 里 export 的变量能覆盖本地文件。 */
    private static String envOrDefault(Map<String, String> dotEnv, String name, String fallback) {
        String fromProcess = System.getenv(name);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        String fromFile = dotEnv.get(name);
        return fromFile == null || fromFile.isBlank() ? fallback : fromFile;
    }

    private static Map<String, String> loadDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读不了就当作没有 .env:CLI 应该退回到进程环境变量,而不是直接崩掉
            return values;
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\""))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private static List<String> contents(List<ScoredChunk> hits) {
        return hits.stream().map(ScoredChunk::content).toList();
    }
}
