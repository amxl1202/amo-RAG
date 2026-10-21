package com.ragkb.eval;

import java.util.List;

/**
 * 一条评估用例。
 *
 * {@code expectedKeywords} 不写"标准答案",而是写"正确片段里必然出现的几个词"。
 * 这样做的好处是比对成本低:不需要人工给每个问题写出完整答案,也不需要模型来判断
 * 答案对不对(那会引入第二个不确定源)。只要命中的片段同时包含这几个词,
 * 就认为"检索到了该找到的地方"。
 *
 * 代价是它衡量的是<b>检索是否找对了位置</b>,不衡量"答案写得好不好"。
 * 后者要靠生成层的指标(引用正确率、答案支持率),两层不能混。
 *
 * @param id               用例编号,数据集内唯一
 * @param question         问题原文
 * @param expectedKeywords 正确片段里必然出现的词;文档外用例为空白列表
 * @param type             题型
 * @param shouldRefuse     是否应当拒答;与 {@code type == OUT_OF_SCOPE} 必须一致
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedKeywords,
        EvalCaseType type,
        boolean shouldRefuse) {

    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例 id 不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("用例 " + id + " 的 question 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("用例 " + id + " 的 type 不能为空");
        }
        expectedKeywords = expectedKeywords == null ? List.of() : List.copyOf(expectedKeywords);
        // 挡住复制粘贴造成的低级错误:题型是"文档外"却标了不拒答,或反之
        boolean outOfScope = type == EvalCaseType.OUT_OF_SCOPE;
        if (shouldRefuse != outOfScope) {
            throw new IllegalArgumentException("用例 " + id + " 的 shouldRefuse 与 type 不一致:"
                    + " type=" + type + " 但 shouldRefuse=" + shouldRefuse);
        }
        // 正向用例没有关键词就等于没有判据,指标会被算成"永远未命中"
        if (!outOfScope && expectedKeywords.isEmpty()) {
            throw new IllegalArgumentException("用例 " + id + " 是正向用例,expectedKeywords 不能为空");
        }
        if (expectedKeywords.stream().anyMatch(k -> k == null || k.isBlank())) {
            throw new IllegalArgumentException("用例 " + id + " 的 expectedKeywords 含空白项");
        }
    }

    /** 给定片段是否同时包含全部期望词。 */
    public boolean matches(String chunkContent) {
        if (chunkContent == null || expectedKeywords.isEmpty()) {
            return false;
        }
        for (String keyword : expectedKeywords) {
            if (!chunkContent.contains(keyword)) {
                return false;
            }
        }
        return true;
    }
}
