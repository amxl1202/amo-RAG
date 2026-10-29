package com.ragkb.retrieval;

import com.ragkb.generation.ChatException;
import com.ragkb.generation.ChatModel;

import java.util.Set;

/**
 * 查询改写:把口语化的提问压成"关键词串",只喂给关键词通道。
 *
 * <h2>为什么只改关键词通道</h2>
 * 向量通道本来就擅长"意思相近但用词不同",再改写等于把用户的句子换个说法,
 * 收益有限而成本固定。关键词通道恰好相反:它按字面匹配,用户的问法里没有文档的术语
 * 就直接落空 —— 那是改写真正能帮上忙的地方。
 *
 * <h2>为什么改写结果是"关键词"而不是"更完整的问题"</h2>
 * 试过让模型把问题扩写成一句话,结果关键词通道的匹配反而变差:插入的虚词把
 * 覆盖率的分子/分母一起抬高了。压成名词短语更符合这一路的打分方式。
 *
 * <h2>实测结论:不采用(2026-10-29)</h2>
 * 在 59 条评估集上,"HYBRID + 改写"的 recall@5 仍是 1.0000,但 <b>MRR 从 0.8731 掉到 0.8306</b>。
 * 原因是 RRF 按名次融合:改写让关键词通道的命中变多、名次更"自信",
 * 反而把向量通道的好结果挤到后面。加上每题多一次模型调用(约 +1 秒、成本翻倍),
 * 没有理由接线。<b>保留这个类只为让那次对比可复现</b>,{@code QaService} 没有用它。
 *
 * <h2>失败怎么办</h2>
 * 改写是<b>增强</b>,不是必需。调用失败或输出为空时退回原问题 ——
 * 让"增强环节挂掉"变成"功能退化"而不是"问答失败"。
 */
public class QueryRewriter {

    /** 输出最长保留多少字符:关键词串不需要长,长了只是噪声。 */
    private static final int MAX_REWRITE_CHARS = 120;

    private static final String SYSTEM_PROMPT = """
            你把用户的问题改写成用于「关键词检索」的查询串。
            要求:
            1. 只保留名词性关键词与专有名词(参数名、产品名、错误码、数值),去掉"为什么""怎么样""请问"这类虚词。
            2. 用空格分隔关键词,不要写成句子,不要输出解释、标点或代码块。
            3. 最多 8 个词。用户问题本身已经是关键词时,原样返回。
            """;

    private final ChatModel model;

    public QueryRewriter(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("改写需要一个模型");
        }
        this.model = model;
    }

    /**
     * @param question 用户原始提问
     * @return 关键词串;改写失败或结果为空时返回原问题
     */
    public String rewrite(String question) {
        if (question == null || question.isBlank()) {
            return question;
        }
        try {
            String raw = model.complete(SYSTEM_PROMPT, question).content();
            String cleaned = clean(raw);
            return cleaned.isBlank() ? question : cleaned;
        } catch (ChatException e) {
            // 增强环节失败不该让整条链路失败 —— 退回原问法
            return question;
        }
    }

    /**
     * 清洗模型输出:去围栏、去行首标号、压空白、限长。
     *
     * <p>经验上模型有三种常见跑偏:套代码块、写成编号列表、把解释也带上。
     * 只取第一行能同时挡掉后两种,代价是偶尔丢掉换行写的关键词 —— 可接受。
     */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 先把围栏符号抹掉再取行:直接取第一行会拿到 ``` 本身,结果被判成空输出
        for (String line : raw.replace("`", " ").split("\\R")) {
            String candidate = line.replaceAll("^[0-9]+[.、)]\\s*", "")
                    .replaceAll("\\s+", " ")
                    .strip();
            if (!candidate.isEmpty()) {
                return candidate.length() > MAX_REWRITE_CHARS
                        ? candidate.substring(0, MAX_REWRITE_CHARS)
                        : candidate;
            }
        }
        return "";
    }

    /** 供测试断言用:系统提示词里必须明确"不要解释"。 */
    static Set<String> requiredInstructionKeywords() {
        return Set.of("关键词", "不要输出解释");
    }

    static String systemPrompt() {
        return SYSTEM_PROMPT;
    }
}
