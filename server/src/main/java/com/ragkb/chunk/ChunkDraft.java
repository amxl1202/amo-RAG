package com.ragkb.chunk;

/**
 * 切片草稿:切片器的产出,落库前的形态。
 *
 * 与 {@link com.ragkb.parse.StructuralBlock} 的区别是"已经过决策":
 * 结构块是解析器的客观产出,切片是切片器对"怎么切才检索得到"的主观判断。
 *
 * @param index        文档内顺序号,从 0 开始,必须连续
 * @param sectionPath  所属章节路径,如 "2. 技术栈 &gt; 2.1 持久层";文档开头无标题的正文为 null
 * @param headingLevel 章节路径最后一级的层级;无章节为 0
 * @param content      切片正文
 * @param tokenCount   切片正文的 token 数,由 {@link TokenCounter} 算出,不接受估算值
 * @param charStart    相对 {@link com.ragkb.parse.ParsedDocument#fullText()} 的起始下标(含)
 * @param charEnd      结束下标(不含)
 * @param pageStart    起始页码;来源无页概念时为 null
 * @param pageEnd      结束页码;来源无页概念时为 null
 */
public record ChunkDraft(
        int index,
        String sectionPath,
        int headingLevel,
        String content,
        int tokenCount,
        int charStart,
        int charEnd,
        Integer pageStart,
        Integer pageEnd) {

    public ChunkDraft {
        if (index < 0) {
            throw new IllegalArgumentException("index 不能为负");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空白");
        }
        if (tokenCount <= 0) {
            throw new IllegalArgumentException("tokenCount 必须由 TokenCounter 计算,不接受 0 或负数");
        }
        if (charStart < 0 || charEnd <= charStart) {
            throw new IllegalArgumentException(
                    "字符区间非法: [" + charStart + ", " + charEnd + ")");
        }
        if (headingLevel < 0 || headingLevel > 6) {
            throw new IllegalArgumentException("headingLevel 越界: " + headingLevel);
        }
    }

    /** 按最终展示顺序覆盖 index(切片器在内部重组顺序时用)。 */
    public ChunkDraft withIndex(int newIndex) {
        return new ChunkDraft(newIndex, sectionPath, headingLevel, content, tokenCount,
                charStart, charEnd, pageStart, pageEnd);
    }
}
