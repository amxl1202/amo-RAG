package com.ragkb.eval;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.common.DotEnv;
import com.ragkb.embedding.DashScopeEmbeddingClient;
import com.ragkb.embedding.EmbeddingClient;
import com.ragkb.embedding.EmbeddingProperties;
import com.ragkb.generation.AnswerGenerator;
import com.ragkb.generation.ChatProperties;
import com.ragkb.generation.DashScopeChatModel;
import com.ragkb.generation.EvidenceEvaluator;
import com.ragkb.generation.PromptBuilder;
import com.ragkb.qa.DocumentTitles;
import com.ragkb.qa.QaAnswer;
import com.ragkb.qa.QaService;
import com.ragkb.retrieval.HybridRetriever;
import com.ragkb.retrieval.JdbcKeywordRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.retrieval.VectorRetriever;
import com.ragkb.vector.JdbcVectorStoreRepository;
import com.ragkb.vector.ScoredChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路跑批:检索 → 门控 → 生成 → 引用 → 自检,一次跑出两层指标。
 *
 * <h2>为什么生成层指标要单独一个工具</h2>
 * {@link EvalRunner} 只调检索,不花钱、可以反复跑;这个工具每题要调两次模型,
 * 一次成本换来的是"答案对不对"的证据。两者分开,改检索参数时可以只跑便宜的那个。
 *
 * <h2>四个生成层指标的口径</h2>
 * <ul>
 *   <li><b>引用正确率</b> = 引用到的切片里,同时含该题全部期望词的比例。
 *       分母是引用总条数,不是题目数 —— 一条答案引了三条、其中两条错的,要能体现出来。</li>
 *   <li><b>答案支持率</b> = 自检判定"有材料支撑"的句子 / 参与自检的句子。
 *       <b>判定者是自检模型,不是人工</b>:它衡量的是"生成文本有多少通过了自家校验",
 *       不是"答案客观正确率"。这个区别必须在报告里写清,否则会被读成后者。</li>
 *   <li><b>拒答准确率</b> = 文档外问题里被正确拒答的比例。</li>
 *   <li><b>误拒率</b> = 文档内问题里被错误拒答的比例。它和拒答准确率方向相反,
 *       只看其中一个就能把系统调成"全都拒答"或"全都作答"。</li>
 * </ul>
 *
 * <h2>还会打印一张阈值校准表</h2>
 * 每题一行:向量 top-1、关键词覆盖率、最终状态。两个阈值必须从这张表里两组数的
 * 分界处取,而不是拍一个;表要和阈值一起留档,否则日后没人说得清数字怎么来的。
 *
 * 用法:
 * <pre>
 * java -cp ... com.ragkb.eval.AnswerEval [cases.json] [--thresholds=0.45,0.5]
 * </pre>
 */
public final class AnswerEval {

    private static final Path DEFAULT_CASES = Path.of("samples", "eval", "cases.json");
    private static final Path DOT_ENV = Path.of(".env");

    private AnswerEval() {
    }

    /** 单题的记录,既是明细也是校准数据。 */
    private record CaseResult(EvalCase evalCase, QaAnswer answer,
                              double vectorTop1, double keywordCoverage,
                              int citationTotal, int citationCorrect) {
    }

    public static void main(String[] args) throws IOException {
        Path casesPath = DEFAULT_CASES;
        EvidenceEvaluator.Thresholds thresholds = EvidenceEvaluator.Thresholds.calibrated();
        for (String arg : args) {
            if (arg.startsWith("--thresholds=")) {
                String[] parts = arg.substring("--thresholds=".length()).split(",");
                thresholds = new EvidenceEvaluator.Thresholds(
                        Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            } else {
                casesPath = Path.of(arg);
            }
        }

        Map<String, String> dotEnv = DotEnv.load(DOT_ENV);
        EvalDataset dataset = EvalDataset.load(casesPath);
        String apiKey = DotEnv.get(dotEnv, "DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            System.err.println("缺少 DASHSCOPE_API_KEY:生成层跑批需要调用向量与对话两个接口");
            System.exit(2);
            return;
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource(dotEnv));
        EmbeddingClient embeddingClient = embeddingClient(dotEnv, apiKey);
        String embeddingModel = DotEnv.get(dotEnv, "EMBEDDING_MODEL", EmbeddingProperties.DEFAULT_MODEL);
        VectorRetriever vectorRetriever = new VectorRetriever(embeddingClient,
                new JdbcVectorStoreRepository(jdbc, embeddingModel));
        JdbcKeywordRetriever keywordRetriever = new JdbcKeywordRetriever(jdbc, JdbcKeywordRetriever.Mode.TRGM);

        long timeoutMillis = Long.parseLong(DotEnv.get(dotEnv, "RETRIEVAL_TIMEOUT_MS", "3000"));
        // 跑批时单通道超时要比线上宽松:线上要的是响应速度,跑批要的是"这一路的真实结果",
        // 用线上 800ms 会把偶发的慢响应记成降级,指标里就多出一批"通道挂了"
        HybridRetriever hybrid = new HybridRetriever(vectorRetriever, keywordRetriever,
                HybridRetriever.defaultExecutor(), timeoutMillis);

        ChatProperties chatProperties = chatProperties(dotEnv, apiKey);
        DashScopeChatModel chatModel = new DashScopeChatModel(chatProperties);
        DocumentTitles titles = documentTitles(jdbc);

        QaService service = new QaService(hybrid, new RrfFuser(), new PromptBuilder(new TokenCounter()),
                new AnswerGenerator(chatModel), new EvidenceEvaluator(thresholds), chatModel, titles, 20);

        System.out.printf("评估集 %s(%d 条),阈值 相似度≥%.2f 或 覆盖率≥%.2f%n",
                casesPath, dataset.cases().size(),
                thresholds.minVectorSimilarity(), thresholds.minKeywordCoverage());
        System.out.printf("%-6s %-6s %6s %6s %6s  %s%n", "用例", "应拒答", "top1", "覆盖率", "状态", "说明");
        System.out.println("-".repeat(96));

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : dataset.cases()) {
            QaAnswer answer = service.ask(evalCase.question());
            double top1 = answer.gate().vectorTop1();
            double coverage = answer.gate().keywordCoverage();
            int total = answer.citations().size();
            int correct = 0;
            for (var citation : answer.citations()) {
                if (evalCase.matches(citation.content())) {
                    correct++;
                }
            }
            results.add(new CaseResult(evalCase, answer, top1, coverage, total, correct));
            System.out.printf("%-6s %-7s %6.3f %7.3f %-7s %s%n",
                    evalCase.id(), evalCase.shouldRefuse() ? "是" : "否", top1, coverage,
                    answer.status(), describe(evalCase, answer, correct, total));
        }

        Metrics metrics = compute(results);
        System.out.println();
        System.out.println("---- 生成层指标 ----");
        System.out.printf("  引用正确率   %.4f  (%d/%d 条引用命中期望词)%n",
                metrics.citationPrecision, metrics.correctCitations, metrics.totalCitations);
        System.out.printf("  答案支持率   %.4f  (自检保留 %d/%d 句;判定者是自检模型,不是人工)%n",
                metrics.answerSupportRate, metrics.keptSentences, metrics.judgedSentences);
        System.out.printf("  拒答准确率   %.4f  (%d/%d 条文档外问题被正确拒答)%n",
                metrics.refusalAccuracy, metrics.correctRefusals, metrics.outOfScope);
        System.out.printf("  误拒率       %.4f  (%d/%d 条文档内问题被错误拒答)%n",
                metrics.falseRefusalRate, metrics.falseRefusals, metrics.inScope);
        System.out.printf("  编造编号次数 %d(应恒为 0;不为 0 说明提示词需要收紧)%n", metrics.unknownLabels);
        System.out.printf("  用量         %d token,耗时 %.1f 秒%n",
                metrics.totalTokens, metrics.totalMillis / 1000.0);
        System.exit(metrics.passed() ? 0 : 1);
    }

    /** 单题一行的说明文字,失败原因要能一眼看出是"拒了不该拒的"还是"引了不该引的"。 */
    private static String describe(EvalCase evalCase, QaAnswer answer, int correct, int total) {
        if (evalCase.shouldRefuse()) {
            return answer.status() == QaAnswer.Status.REFUSED
                    ? "正确拒答(" + answer.reason() + ")"
                    : "应拒答却作答了";
        }
        if (answer.status() == QaAnswer.Status.FAILED) {
            return "模型调用失败";
        }
        if (answer.status() == QaAnswer.Status.REFUSED) {
            return "误拒(" + answer.reason() + ")";
        }
        if (total == 0) {
            return "作答但没有任何引用";
        }
        return "引用 " + correct + "/" + total + " 命中";
    }

    private record Metrics(double citationPrecision, int correctCitations, int totalCitations,
                           double answerSupportRate, int keptSentences, int judgedSentences,
                           double refusalAccuracy, int correctRefusals, int outOfScope,
                           double falseRefusalRate, int falseRefusals, int inScope,
                           int unknownLabels, int totalTokens, long totalMillis) {

        /** 两个阈值下的硬性验收:文档外全拒、文档内 0 误拒。 */
        boolean passed() {
            return outOfScope > 0 && correctRefusals == outOfScope && falseRefusals == 0;
        }
    }

    private static Metrics compute(List<CaseResult> results) {
        int totalCitations = 0;
        int correctCitations = 0;
        int judged = 0;
        int kept = 0;
        int outOfScope = 0;
        int correctRefusals = 0;
        int inScope = 0;
        int falseRefusals = 0;
        int unknown = 0;
        int tokens = 0;
        long millis = 0;

        for (CaseResult result : results) {
            QaAnswer answer = result.answer();
            totalCitations += result.citationTotal();
            correctCitations += result.citationCorrect();
            unknown += answer.unknownLabels().size();
            tokens += answer.totalTokens();
            millis += answer.totalMillis();
            if (answer.verdict() != null) {
                judged += answer.verdict().kept() + answer.verdict().dropped();
                kept += answer.verdict().kept();
            }
            if (result.evalCase().shouldRefuse()) {
                outOfScope++;
                if (answer.status() == QaAnswer.Status.REFUSED) {
                    correctRefusals++;
                }
            } else {
                inScope++;
                if (answer.status() == QaAnswer.Status.REFUSED) {
                    falseRefusals++;
                }
            }
        }
        return new Metrics(
                ratio(correctCitations, totalCitations), correctCitations, totalCitations,
                ratio(kept, judged), kept, judged,
                ratio(correctRefusals, outOfScope), correctRefusals, outOfScope,
                ratio(falseRefusals, inScope), falseRefusals, inScope,
                unknown, tokens, millis);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    /** 标题来自 documents 表;查不到时给占位,而不是让 "null" 混进提示词。 */
    private static DocumentTitles documentTitles(JdbcTemplate jdbc) {
        Map<Long, String> cache = new HashMap<>();
        jdbc.query("SELECT id, original_name FROM documents",
                rs -> {
                    cache.put(rs.getLong("id"), rs.getString("original_name"));
                });
        return documentId -> cache.getOrDefault(documentId, "文档#" + documentId);
    }

    private static ChatProperties chatProperties(Map<String, String> env, String apiKey) {
        ChatProperties defaults = ChatProperties.defaults();
        return new ChatProperties(
                apiKey,
                DotEnv.get(env, "CHAT_MODEL", defaults.model()),
                DotEnv.get(env, "CHAT_ENDPOINT", defaults.endpoint()),
                defaults.temperature(), defaults.maxTokens(), defaults.timeoutSeconds(), defaults.maxAttempts());
    }

    private static EmbeddingClient embeddingClient(Map<String, String> env, String apiKey) {
        EmbeddingProperties defaults = EmbeddingProperties.defaults();
        return new DashScopeEmbeddingClient(new EmbeddingProperties(
                apiKey,
                DotEnv.get(env, "EMBEDDING_MODEL", defaults.model()),
                defaults.dimension(),
                defaults.batchSize(),
                DotEnv.get(env, "EMBEDDING_ENDPOINT", defaults.endpoint()),
                defaults.timeoutSeconds(),
                defaults.maxAttempts()));
    }

    private static DriverManagerDataSource dataSource(Map<String, String> env) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(DotEnv.get(env, "DB_URL", "jdbc:postgresql://localhost:5433/rag_kb"));
        dataSource.setUsername(DotEnv.get(env, "DB_USERNAME", "postgres"));
        dataSource.setPassword(DotEnv.get(env, "DB_PASSWORD", "rag123456"));
        return dataSource;
    }

}
