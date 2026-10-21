package com.ragkb.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 跑批:对每条正向用例调一次检索,算检索层指标。
 *
 * <h2>为什么先有它、后有检索</h2>
 * 检索还没实现时就把跑批与数据结构搭好,指标全 0 也是合格的 —— 它验证的是
 * "数据集能读、校验能过、指标能算、输出格式稳定"。等检索接上,只换一个
 * {@link Retriever} 实现,其余不用动。反过来(先做检索、最后补评估)的代价是:
 * 中间每一次调整都只能靠肉眼看输出判断好坏。
 *
 * <h2>三个指标怎么算</h2>
 * <ul>
 *   <li><b>recall@5</b> = 第 1~5 位里出现"命中片段"的用例数 / 正向用例数。
 *       命中 = 该片段同时包含用例的全部期望词。</li>
 *   <li><b>recall@20</b> = 同上,放宽到前 20 位。它和 recall@5 的差距反映
 *       "排序质量":差距大说明能找到但排不上去,该调融合权重或索引参数。</li>
 *   <li><b>MRR</b> = 每个用例 (1 / 首次命中的位次) 的平均。首次命中在第 1 位记 1.0,
 *       第 4 位记 0.25,没命中记 0。它比 recall 更敏感于"排得靠不靠前"。</li>
 * </ul>
 * 文档外负例不进这三个指标 —— 它们衡量的是"该不该拒答",属于生成层。
 * 把负例混进召回率是常见错误:负例本来就没有目标片段,混进去只会把指标稀释。
 */
public class EvalRunner {

    /** recall@5 的口径:喂给模型的候选通常只取 3~5 条,第 5 位之后基本用不上。 */
    public static final int RECALL_K_NEAR = 5;

    /** recall@20 的口径:用来区分"找不到"和"找到了但排太后"。 */
    public static final int RECALL_K_FAR = 20;

    private static final String GENERATION_NOTE =
            "生成流程尚未接入:引用正确率 / 答案支持率 / 拒答准确率 / 误拒率暂为空(null 表示算不出,不是 0)";

    /**
     * 检索能力的最小抽象:给问题和数量,还回片段正文(按相关度降序)。
     *
     * 之所以用"片段正文"而不是领域对象,是为了让评估层不依赖检索层的实现细节 ——
     * 后续无论是纯向量、纯关键词还是融合检索,都能用一句 lambda 适配:
     * <pre>{@code
     * Retriever r = (q, k) -> hybridRetriever.search(q, k).stream()
     *         .map(RetrievedChunk::content).toList();
     * }</pre>
     */
    @FunctionalInterface
    public interface Retriever {
        List<String> retrieve(String question, int limit);
    }

    /** 空检索器:检索未接入时用它跑通框架,指标恒为 0。 */
    public static Retriever empty() {
        return (question, limit) -> List.of();
    }

    public EvalReport run(EvalDataset dataset, Retriever retriever, String retrieverName) {
        int evaluable = 0;
        int hitNear = 0;
        int hitFar = 0;
        double mrrSum = 0;
        List<EvalReport.FailedCase> failed = new ArrayList<>();

        for (EvalCase evalCase : dataset.cases()) {
            if (evalCase.shouldRefuse()) {
                continue;
            }
            evaluable++;
            List<String> chunks;
            try {
                chunks = retriever.retrieve(evalCase.question(), RECALL_K_FAR);
            } catch (RuntimeException e) {
                // 单条检索炸掉不该让整批跑不动,更不该被算成"没命中"而悄悄拉低指标
                failed.add(new EvalReport.FailedCase(evalCase.id(), evalCase.question(),
                        "检索异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
                continue;
            }
            int rank = firstHitRank(chunks, evalCase);
            if (rank == 0) {
                failed.add(new EvalReport.FailedCase(evalCase.id(), evalCase.question(),
                        "前 " + RECALL_K_FAR + " 位未命中(期望词: " + evalCase.expectedKeywords() + ")"));
                continue;
            }
            if (rank <= RECALL_K_NEAR) {
                hitNear++;
            }
            hitFar++;
            mrrSum += 1.0 / rank;
        }

        EvalReport.Retrieval retrieval = evaluable == 0
                ? new EvalReport.Retrieval(0, 0, 0)
                : new EvalReport.Retrieval(
                        (double) hitNear / evaluable,
                        (double) hitFar / evaluable,
                        mrrSum / evaluable);

        EvalReport.Generation generation = new EvalReport.Generation(null, null, null, null);
        return EvalReport.of(retrieverName, dataset, retrieval, generation, GENERATION_NOTE, failed);
    }

    /** 返回首次命中的位次(从 1 开始),未命中返回 0。 */
    private int firstHitRank(List<String> chunks, EvalCase evalCase) {
        if (chunks == null) {
            return 0;
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (evalCase.matches(chunks.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }
}
