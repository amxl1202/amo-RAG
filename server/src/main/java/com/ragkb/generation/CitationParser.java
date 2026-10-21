package com.ragkb.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把模型输出解析成"逐句 + 引用"的结构,并剔除材料里不存在的编号。
 *
 * <h2>为什么要剔除,而不是把编造的编号也展示出来</h2>
 * 编号的唯一价值是"点开能到原文"。一个指向不存在材料的编号,点开是空的,
 * 比没有编号更糟 —— 读者会以为系统找错了地方,而不是模型编了东西。
 * 所以非法编号既不进引用列表,也从展示文本里删掉,只留在清单里供排查。
 *
 * <h2>识别要宽,判据要严</h2>
 * 实测模型会写出 {@code [ E1]}(方括号里多一个空格)。按"严格格式"匹配会漏掉
 * 这个编号,于是一句有依据的话被算成"无引用",引用覆盖率跟着虚低。
 * 所以括号形态、空格、大小写、全角逗号都容忍;但<b>编号本身必须存在于本次材料里</b>,
 * 这一条不让步。
 *
 * <h2>为什么同时给出三种文本</h2>
 * <ul>
 *   <li>{@code raw}:模型原样。排查时唯一可信的输入,别的地方都动过它。</li>
 *   <li>{@code sanitized}:删掉非法编号。给前端渲染 —— 用户不该看到死链。</li>
 *   <li>每句的 {@code plain}:删掉<b>全部</b>编号。给自检用:带着 {@code [E1]} 去问
 *       "这句有没有材料支撑",模型会把编号本身当成依据。</li>
 * </ul>
 */
public class CitationParser {

    /** 三种括号形态。全角的在中文输出里很常见,【】则是模型自由发挥的产物。 */
    private static final char[][] BRACKETS = {
            {'[', ']'},
            {'［', '］'},
            {'【', '】'},
    };

    /** 句末标点。换行也算:模型常把并列结论分行写,一行一个意思。 */
    private static final String SENTENCE_ENDINGS = "。！？；!?;\n";

    /** 从 {@code start} 起的一个编号标记:{@code end} 是右括号后的下标,{@code labels} 已规范化。 */
    private record Marker(int end, List<String> labels) {
    }

    /**
     * @param answer 模型输出
     * @param prompt 本次的提示词 —— 编号表必须用它,不能用检索结果另算一遍
     * @throws IllegalArgumentException 参数为空
     */
    public ParsedAnswer parse(String answer, Prompt prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("解析引用必须提供本次提示词");
        }
        if (answer == null || answer.isBlank()) {
            return new ParsedAnswer("", "", List.of(), List.of(), List.of(), 0);
        }

        List<ParsedSentence> sentences = new ArrayList<>();
        Map<String, Citation> cited = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        StringBuilder sanitized = new StringBuilder(answer.length());

        StringBuilder raw = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        List<Citation> sentenceCitations = new ArrayList<>();
        boolean sentenceHasUnknown = false;

        int i = 0;
        while (i < answer.length()) {
            Marker marker = markerAt(answer, i);
            if (marker != null) {
                raw.append(answer, i, marker.end());
                // 先整体判定,再决定要不要收:一条标记里既有真编号又有假编号时整条删掉 ——
                // 若先把真编号记进去,展示文本里已经没有这条标记了,引用面板却还挂着它,
                // 读者会看到一个"正文里找不到对应编号"的引用
                List<Citation> resolved = new ArrayList<>(marker.labels().size());
                boolean allKnown = true;
                for (String label : marker.labels()) {
                    Optional<Citation> citation = prompt.citationOf(label);
                    if (citation.isPresent()) {
                        resolved.add(citation.get());
                    } else {
                        unknown.add(label);
                        sentenceHasUnknown = true;
                        allKnown = false;
                    }
                }
                if (allKnown) {
                    for (Citation citation : resolved) {
                        if (sentenceCitations.stream()
                                .noneMatch(existing -> existing.label().equalsIgnoreCase(citation.label()))) {
                            sentenceCitations.add(citation);
                        }
                        cited.putIfAbsent(citation.label(), citation);
                    }
                    sanitized.append(answer, i, marker.end());
                }
                i = marker.end();
                continue;
            }

            char c = answer.charAt(i);
            raw.append(c);
            plain.append(c);
            sanitized.append(c);
            i++;
            if (isSentenceEnd(answer, i - 1, c)) {
                flush(sentences, raw, plain, sentenceCitations, sentenceHasUnknown);
                sentenceCitations = new ArrayList<>();
                sentenceHasUnknown = false;
            }
        }
        flush(sentences, raw, plain, sentenceCitations, sentenceHasUnknown);

        int uncited = (int) sentences.stream()
                .filter(sentence -> !sentence.blank() && !sentence.cited())
                .count();
        return new ParsedAnswer(answer.strip(), sanitized.toString().strip(), sentences,
                List.copyOf(cited.values()), List.copyOf(unknown), uncited);
    }

    /**
     * 若从 {@code start} 起是一个合法形态的编号标记,返回它;否则返回 null。
     *
     * 只判断"长得像不像标记",不判断"编号是否存在" —— 后者要用编号表回查。
     * 两件事分开,才能做到"形态宽容、存在性严格"。
     */
    private static Marker markerAt(String text, int start) {
        char open = text.charAt(start);
        char[] pair = null;
        for (char[] candidate : BRACKETS) {
            if (candidate[0] == open) {
                pair = candidate;
                break;
            }
        }
        if (pair == null) {
            return null;
        }
        int close = text.indexOf(pair[1], start + 1);
        if (close < 0) {
            return null;
        }
        List<String> labels = labelsOf(text.substring(start + 1, close));
        return labels.isEmpty() ? null : new Marker(close + 1, labels);
    }

    /**
     * 解析括号里的内容,返回规范化后的编号列表;不是编号则返回空列表。
     *
     * 规范化包括:去掉全部空白(实测输出会有 {@code [ E1]})、统一成大写 E、
     * 把 {@code E01} 收敛成 {@code E1}(否则会和编号表里的 {@code E1} 对不上)。
     */
    private static List<String> labelsOf(String inner) {
        String compact = inner.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        // 一条标记里并列多个编号:[E1, E2] / [E1、2] / [E1;E2]
        for (String part : compact.split("[,，、;；]")) {
            String digits = part.startsWith("E") || part.startsWith("e") ? part.substring(1) : part;
            if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
                return List.of();
            }
            try {
                labels.add("E" + Integer.parseInt(digits));
            } catch (NumberFormatException e) {
                // 位数长到溢出 Integer 的"编号"不可能是真的,按非编号处理
                return List.of();
            }
        }
        return labels;
    }

    private static boolean isSentenceEnd(String text, int index, char c) {
        if (SENTENCE_ENDINGS.indexOf(c) >= 0) {
            return true;
        }
        // 英文句点只在后面是空白或结尾时才算句末,否则会把 3.14 / example.com 切断
        return c == '.' && (index + 1 == text.length() || Character.isWhitespace(text.charAt(index + 1)));
    }

    private static void flush(List<ParsedSentence> sentences, StringBuilder raw, StringBuilder plain,
                              List<Citation> citations, boolean hasUnknown) {
        if (raw.isEmpty()) {
            return;
        }
        sentences.add(new ParsedSentence(raw.toString(), plain.toString(), citations, hasUnknown));
        raw.setLength(0);
        plain.setLength(0);
    }
}
