package com.ragkb.parse;

/**
 * 带原文下标的完整结构块:切片器的工作对象。
 *
 * @param kind         块类型
 * @param headingLevel 标题层级;非标题为 0
 * @param page         页码,可能为 null
 * @param text         块文本
 * @param charStart    相对 {@link ParsedDocument#fullText()} 的起始下标(含)
 * @param charEnd      相对 {@link ParsedDocument#fullText()} 的结束下标(不含)
 */
public record StructuralBlock(
        BlockKind kind,
        int headingLevel,
        Integer page,
        String text,
        int charStart,
        int charEnd) {

    public boolean isHeading() {
        return kind == BlockKind.HEADING;
    }

    public int charLength() {
        return charEnd - charStart;
    }
}
