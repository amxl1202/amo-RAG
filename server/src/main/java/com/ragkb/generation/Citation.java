package com.ragkb.generation;

/**
 * 一条"可被引用的证据":证据本体 + 编号。
 *
 * <h2>为什么编号要包一层,而不是让证据自己带 label 字段</h2>
 * 编号是<b>这一次提问</b>的局部产物:同一个切片在 A 问题里可能是 E1,在 B 问题里可能是 E4。
 * 把它挂到证据上,就需要在建好提示词之后再"回填",一旦出现克隆/复用就会串号。
 * 用组合的方式表达"这条证据在这次回答里是第几号",从类型上就杜绝了串号。
 *
 * @param label    编号,形如 {@code E1}(不含方括号)
 * @param evidence 证据本体
 */
public record Citation(String label, Evidence evidence) {

    public Citation {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("引用编号不能为空");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("引用必须带证据本体: " + label);
        }
    }

    /** 写进提示词与答案里的标记,形如 {@code [E1]}。 */
    public String marker() {
        return "[" + label + "]";
    }

    public long chunkId() {
        return evidence.chunkId();
    }

    public long documentId() {
        return evidence.documentId();
    }

    public String content() {
        return evidence.content();
    }

    public String documentTitle() {
        return evidence.documentTitle();
    }

    public String sectionPath() {
        return evidence.sectionPath();
    }
}
