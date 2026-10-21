package com.ragkb.generation;

import java.util.List;
import java.util.Optional;

/**
 * 拼装好的提示词,以及"编号 → 证据"的对照表。
 *
 * <h2>为什么提示词和对照表必须一起返回</h2>
 * 答案里的 {@code [E2]} 只有配上这张表才能翻译成"哪份文档的哪一段"。
 * 如果建提示词时把表丢掉、解析答案时再按检索结果重新编号,两次编号只要有一处
 * 顺序不同,引用就会指到别的切片上 —— 而且答案读起来完全通顺,不会报错。
 * 所以这里把"发出去的提示词"和"它的编号表"绑成一个不可分的对象。
 *
 * @param question       用户问题原文
 * @param systemPrompt   系统提示词
 * @param userPrompt     用户提示词(含编号材料)
 * @param material       材料段原文(不含问题)。自检要把材料单独再发一次,
 *                       从这里取而不是从 userPrompt 里切字符串 —— 切字符串的代码
 *                       在提示词格式改动时会静默切错
 * @param citations      编号表,按提示词里的出现顺序
 * @param contextTokens  材料段的 token 数(本地口径,偏高;见 TokenCounter 的校准说明)
 * @param droppedCount   因超出预算而没有放进提示词的证据条数
 */
public record Prompt(
        String question,
        String systemPrompt,
        String userPrompt,
        String material,
        List<Citation> citations,
        int contextTokens,
        int droppedCount) {

    public Prompt {
        citations = List.copyOf(citations);
    }

    /** 提示词里没有任何材料 —— 上层应当直接拒答,不必再调模型。 */
    public boolean isEmpty() {
        return citations.isEmpty();
    }

    /**
     * 按编号回查证据。
     *
     * @param label 形如 {@code E1};也接受带方括号的 {@code [E1]},省去调用方自己剥壳
     * @return 编号不存在时返回空 —— 模型编造的编号必须落到这个分支上
     */
    public Optional<Citation> citationOf(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String normalized = label.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        for (Citation citation : citations) {
            if (citation.label().equalsIgnoreCase(normalized)) {
                return Optional.of(citation);
            }
        }
        return Optional.empty();
    }
}
