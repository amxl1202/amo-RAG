package com.ragkb.eval;

import com.ragkb.retrieval.JdbcKeywordRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.vector.ScoredChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 检索方案对比:同一份评估集上跑多组配置,输出一张对比表。
 *
 * <h2>为什么对比可以只靠关键词通道</h2>
 * 向量通道需要调外部接口(要 API Key,且按量计费)。在 Key 就绪之前,用
 * <b>ILIKE 与 TRGM 两种关键词模式充当"两路"</b>来做融合实验 —— 它们候选集不同、
 * 排序依据也不同,足以验证 RRF 的机制与 k 的取值影响。
 * 等向量可用时,把其中一路换成向量检索即可,融合代码不用改。
 *
 * <h2>这张表怎么读</h2>
 * <ul>
 *   <li>recall@5 与 recall@20 的差距反映排序质量:能找到但排不上去,差距就大;</li>
 *   <li>MRR 比 recall 更敏感于"排得靠不靠前";</li>
 *   <li>失败数看的是"前 20 位都没命中"的用例条数 —— 它比百分比更直观。</li>
 * </ul>
 */
public final class RetrievalComparison {

    private RetrievalComparison() {
    }

    /**
     * 一组待对比的配置。
     *
     * @param name     表里显示的名字
     * @param retriever 从"问题 + 条数"到片段正文的适配器
     */
    private record Config(String name, BiFunction<String, Integer, List<String>> retriever) {
    }

    public static void main(String[] args) throws Exception {
        var casesPath = java.nio.file.Path.of(args.length > 0 ? args[0] : "samples/eval/cases.json");
        EvalDataset dataset = EvalDataset.load(casesPath);

        // 用 DriverManagerDataSource 而不是 PGSimpleDataSource:JDBC 驱动是 runtime scope,
        // 主代码在编译期看不到 org.postgresql.* 包。按类名加载即可。
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(env("DB_URL", "jdbc:postgresql://localhost:5433/rag_kb"));
        dataSource.setUsername(env("DB_USERNAME", "postgres"));
        dataSource.setPassword(env("DB_PASSWORD", "rag123456"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        JdbcKeywordRetriever ilike = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.ILIKE);
        JdbcKeywordRetriever trgm = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.TRGM);
        RrfFuser fuser = new RrfFuser();

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("ILIKE", (q, limit) -> contents(ilike.search(q, limit))));
        configs.add(new Config("TRGM", (q, limit) -> contents(trgm.search(q, limit))));
        for (int k : new int[]{0, 20, 60}) {
            configs.add(new Config("RRF k=" + k, (q, limit) -> {
                // 每路多取一些再融合:融合前给的名次太少,RRF 没有区分的余地
                List<ScoredChunk> left = ilike.search(q, limit * 2);
                List<ScoredChunk> right = trgm.search(q, limit * 2);
                return fuser.fuse(left, right, k, limit).stream()
                        .map(hit -> hit.chunk().content())
                        .toList();
            }));
        }

        System.out.printf("%-12s %10s %11s %8s %8s%n", "方案", "recall@5", "recall@20", "MRR", "失败数");
        System.out.println("-".repeat(54));
        EvalRunner runner = new EvalRunner();
        for (Config config : configs) {
            EvalRunner.Retriever adapter = config.retriever()::apply;
            EvalReport report = runner.run(dataset, adapter, config.name());
            System.out.printf("%-12s %10.4f %11.4f %8.4f %8d%n",
                    config.name(),
                    report.retrieval().recallAt5(),
                    report.retrieval().recallAt20(),
                    report.retrieval().mrr(),
                    report.failedCases().size());
        }
        System.out.println();
        System.out.println("用例数 " + dataset.cases().size()
                + "(参与召回 " + dataset.evaluableCount() + ",文档外 " + dataset.refusalCaseCount() + ")");
        System.err.println("说明:向量通道需要 DASHSCOPE_API_KEY,当前用 ILIKE/TRGM 两路代替做融合实验;"
                + "向量可用后把其中一路换成 VectorRetriever 即可,融合代码不变。");
    }

    private static List<String> contents(List<ScoredChunk> hits) {
        return hits.stream().map(ScoredChunk::content).toList();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
