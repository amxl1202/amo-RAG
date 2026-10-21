package com.ragkb.generation;

import java.util.List;

/**
 * 调模型,把提示词变成答案。
 *
 * <h2>这一层为什么这么薄</h2>
 * 它只做三件事:没有材料时不调模型、把模型的话原样带回来、认出"模型自述的拒答"。
 * 引用校验、证据校验都放在别的类里,因为那些是<b>判断</b>,而这里是<b>调用</b>。
 * 混在一起的话,想单独测"引用了不存在的编号"就得先能造出一个模型。
 *
 * <h2>没有材料时为什么不调</h2>
 * 材料段为空的提示词等于让模型凭常识作答,而这一层的全部约束都建立在"有材料"之上。
 * 直接返回拒答既省一次调用,也避免"这次为什么答了"这种事后无法解释的行为。
 */
public class AnswerGenerator {

    private final ChatModel model;

    public AnswerGenerator(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("ChatModel 不能为空");
        }
        this.model = model;
    }

    /**
     * @param prompt 已拼好的提示词;材料为空时直接返回拒答,不调用模型
     * @return 模型输出与用量
     * @throws ChatException 调用失败。上层应当把"服务不可用"与"拒答"分开呈现给用户
     */
    public GeneratedAnswer generate(Prompt prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        if (prompt.isEmpty()) {
            return GeneratedAnswer.skippedWithoutEvidence();
        }
        ChatResponse response = model.complete(prompt.systemPrompt(), prompt.userPrompt());
        if (response.content().isBlank()) {
            // 客户端已挡过一次,这里再挡:ChatModel 是可替换的(换成本地模型、换实现),
            // 而空答案一旦流到接口层,前端看到的是一个"成功但什么都没有"的响应,
            // 既不像拒答也不像故障,排起来最费时间
            throw new ChatException("模型返回空答案,没有可展示的内容", false);
        }
        return new GeneratedAnswer(response.content(), looksLikeRefusal(response.content()),
                response.model(), response.promptTokens(), response.completionTokens(),
                response.latencyMillis(), false);
    }

    /**
     * 判断模型输出是不是"材料中未提及"。
     *
     * 容忍三种常见偏差:句末自己补了标点、把固定串包在引号里、固定串前后带了
     * "抱歉"之类的客套。做法是先抹掉空白与标点再判断包含关系 —— 直接比较原文会因为
     * 一个句号而判错,而判错的后果是"拒答"被读成"答了",误拒率随之失真。
     *
     * @param text 模型输出;null 视为不是拒答(空输出由调用方另行处理)
     */
    public static boolean looksLikeRefusal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return normalize(text).contains(normalize(PromptBuilder.INSUFFICIENT));
    }

    /** 去掉空白与常见标点,只留下内容字符。中英文标点都要去:模型两种都会用。 */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || isPunctuation(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isPunctuation(char c) {
        return switch (c) {
            case '。', '，', '、', '；', '：', '！', '？', '“', '”', '‘', '’',
                 '（', '）', '《', '》', '【', '】', '…', '—', '·',
                 '.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']' -> true;
            default -> false;
        };
    }

    /** 一次批量生成的用量合计,供评估报告使用。 */
    public static int totalTokens(List<GeneratedAnswer> answers) {
        return answers.stream().mapToInt(GeneratedAnswer::totalTokens).sum();
    }
}
