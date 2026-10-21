package com.ragkb.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 评估跑批的命令行入口:读数据集 → 跑一次 → 把 JSON 打到 stdout。
 *
 * 不启动 Spring、不连数据库 —— 和结构预览工具同一个理由:评估要能在任何环境下重复跑,
 * 不能因为数据库没起就没法度量。
 *
 * 用法:
 * <pre>
 * scripts/eval-run.sh                        # 用默认数据集 samples/eval/cases.json
 * scripts/eval-run.sh samples/eval/cases.json
 * </pre>
 * 指标定义打到 stderr,JSON 只走 stdout —— 这样 {@code > report.json} 重定向不会混入说明文字。
 */
public final class EvalMain {

    private static final Path DEFAULT_CASES = Path.of("samples", "eval", "cases.json");

    private EvalMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && ("-h".equals(args[0]) || "--help".equals(args[0]))) {
            printUsage();
            return;
        }
        Path casesPath = args.length > 0 ? Path.of(args[0]) : DEFAULT_CASES;
        if (!Files.isRegularFile(casesPath)) {
            System.err.println("找不到评估集: " + casesPath.toAbsolutePath());
            System.err.println("用 scripts/eval-run.sh <cases.json> 指定,或先创建默认的 samples/eval/cases.json");
            System.exit(2);
        }

        EvalDataset dataset = EvalDataset.load(casesPath);

        // 检索尚未实现,先用空检索器跑通框架:此时三个检索指标都是 0,属于预期结果
        EvalReport report = new EvalRunner().run(dataset, EvalRunner.empty(), "EMPTY(检索尚未接入)");

        System.out.println(report.toJson());
        printLegend(dataset, casesPath);
    }

    private static void printLegend(EvalDataset dataset, Path casesPath) {
        long factoid = dataset.countOf(EvalCaseType.FACTOID);
        long multiHop = dataset.countOf(EvalCaseType.MULTI_HOP);
        long tableValue = dataset.countOf(EvalCaseType.TABLE_VALUE);
        long outOfScope = dataset.countOf(EvalCaseType.OUT_OF_SCOPE);

        System.err.println();
        System.err.println("---- 指标口径 ----");
        System.err.println("数据集: " + casesPath.toAbsolutePath());
        System.err.println("  用例总数 " + dataset.cases().size()
                + "  = 事实 " + factoid + " / 跨段落 " + multiHop
                + " / 表格数值 " + tableValue + " / 文档外 " + outOfScope);
        System.err.println("  recall@5  前 5 位里出现命中片段的用例比例(命中 = 片段包含全部期望词)");
        System.err.println("  recall@20 同上,放宽到前 20 位;与 recall@5 的差距反映排序质量");
        System.err.println("  mrr       每个用例 (1 / 首次命中位次) 的平均;未命中记 0");
        System.err.println("  生成层四项暂为 null:生成流程未接入,null 表示算不出,不是 0");
        System.err.println("  文档外负例(" + outOfScope + " 条)不参与以上三个指标 —— 它们衡量的是拒答,属于生成层");
    }

    private static void printUsage() {
        System.out.println("""
                用法: scripts/eval-run.sh [cases.json]

                读评估集并跑一次检索评估,JSON 输出到 stdout,指标口径输出到 stderr。
                不带参数时使用 samples/eval/cases.json。

                数据集格式:
                [{"id":"c01","question":"…","expectedKeywords":["…"],"type":"FACTOID","shouldRefuse":false}]""");
    }
}
