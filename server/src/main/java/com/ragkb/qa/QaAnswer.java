package com.ragkb.qa;

import com.ragkb.generation.Citation;
import com.ragkb.generation.EvidenceEvaluator;

import java.util.List;

/**
 * 一次问答的结果。
 *
 * <h2>为什么把中间量全部带上</h2>
 * 这个对象同时服务两件事:接口返回给前端、跑批算指标。两者需要的字段不一样 ——
 * 前端要答案与引用,跑批要门控分数、耗时、丢弃条数。如果只留"最终答案",
 * 指标掉了就没法判断是检索弱了、门控太严,还是自检删多了。
 *
 * @param question        问题原文
 * @param status          最终状态
 * @param text            展示文本;拒答时是拒答固定串
 * @param citations       答案真正引用到的切片
 * @param gate            阶段一判定(含向量 top-1 与关键词覆盖率)
 * @param verdict         阶段二判定;未走到这一步时为 null
 * @param unknownLabels   被剔除的编造编号
 * @param candidateCount  融合后的候选条数
 * @param usedCount       实际放进提示词的条数
 * @param degraded        是否有一路检索降级
 * @param promptTokens    模型输入 token
 * @param completionTokens 模型输出 token
 * @param retrievalMillis 检索耗时
 * @param answerMillis    生成 + 自检的总耗时。刻意不叫 generationMillis:自检是第二次模型调用,
 *                        算进"生成"会让这个数比单次生成大近一倍,排查延迟时容易被误导
 */
public record QaAnswer(
        String question,
        Status status,
        String text,
        List<Citation> citations,
        EvidenceEvaluator.Gate gate,
        EvidenceEvaluator.Verdict verdict,
        List<String> unknownLabels,
        int candidateCount,
        int usedCount,
        boolean degraded,
        int promptTokens,
        int completionTokens,
        long retrievalMillis,
        long answerMillis) {

    public enum Status {
        /** 正常作答。 */
        ANSWERED,
        /** 拒答:证据不足、模型自述材料不足,或自检把句子全剔光。 */
        REFUSED,
        /** 模型调用失败。与拒答必须分开:一个该修服务,一个是对的行为。 */
        FAILED
    }

    public QaAnswer {
        citations = List.copyOf(citations);
        unknownLabels = List.copyOf(unknownLabels);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public long totalMillis() {
        return retrievalMillis + answerMillis;
    }

    /** 拒答原因;作答时为 null。 */
    public EvidenceEvaluator.Reason reason() {
        return verdict != null ? verdict.reason() : gate.reason();
    }
}
