package com.ragkb.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从问题里抽取可用于字串匹配的词。
 *
 * <h2>为什么必须有这一步</h2>
 * 直接把整句问题丢给 {@code ILIKE '%…%'} 是不可行的:问题长这样
 * "固定长度切分的根本问题是什么?",而切片里是陈述句"……不知道文本里哪一处是断点"。
 * 整句子串在语料里根本不存在,召回恒为 0 —— 这不是检索坏了,是查询构造错了。
 * 关键词通道本来的职责就是"用短词做精确匹配",所以先抽词。
 *
 * <h2>抽词策略</h2>
 * 没有词典、没有分词器,只做最朴素的两件事:
 * <ul>
 *   <li><b>中文</b>:按连续汉字段取 2-gram(滑动窗口)。单字丢掉的理由是它几乎必中,
 *       只会稀释排序;2-gram 已经能表达"断点""向量""切片"这类关键概念。</li>
 *   <li><b>英文/数字</b>:整串保留(产品型号、错误码、参数名正是这个通道存在的理由)。</li>
 * </ul>
 * 停用词表是硬编码的一小撮疑问词。它是<b>占位性质</b>的:真正该由评估集调出来,
 * 而不是拍脑袋列一长串 —— 列得越长,越容易把有效词误杀。
 */
public final class QueryTerms {

    /** 上限:词太多会让 SQL 的 alternation 变长、也让噪声词挤进来。 */
    private static final int MAX_TERMS = 16;

    /** 只保留一小撮高频疑问/虚词。它的作用是压噪声,不是做完整停用词表。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么", "为什", "么样", "哪些", "如何", "是否", "这个", "那个",
            "可以", "需要", "我们", "他们", "的是", "了吗", "一下", "请问");

    private QueryTerms() {
    }

    /**
     * 抽取匹配词,保持出现顺序并去重。
     *
     * @return 不含空白项;输入为空时返回空列表
     */
    public static List<String> extract(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder hanRun = new StringBuilder();
        StringBuilder alnumRun = new StringBuilder();

        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (isHan(c)) {
                flushAlnum(terms, alnumRun);
                hanRun.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushHan(terms, hanRun);
                alnumRun.append(c);
            } else {
                flushHan(terms, hanRun);
                flushAlnum(terms, alnumRun);
            }
        }
        flushHan(terms, hanRun);
        flushAlnum(terms, alnumRun);

        List<String> result = new ArrayList<>(Math.min(terms.size(), MAX_TERMS));
        for (String term : terms) {
            if (result.size() >= MAX_TERMS) {
                break;
            }
            if (term.length() >= 2 && !STOP_WORDS.contains(term)) {
                result.add(term);
            }
        }
        return List.copyOf(result);
    }

    /** 把词拼成正则 alternation;词只含汉字与字母数字,不需要额外转义。 */
    public static String toRegex(List<String> terms) {
        return String.join("|", terms);
    }

    private static void flushHan(Set<String> terms, StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        String text = run.toString();
        run.setLength(0);
        // 单字不保留:extract 最后会按长度 ≥ 2 过滤,这里即使加进去也会被丢掉。
        // 保留一个"加了再滤掉"的分支只会让人误以为单字有机会成为检索词。
        for (int i = 0; i + 2 <= text.length(); i++) {
            terms.add(text.substring(i, i + 2));
        }
    }

    private static void flushAlnum(Set<String> terms, StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        String text = run.toString();
        run.setLength(0);
        // 英文整词不拆:型号、错误码拆开就失去意义
        terms.add(text);
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
