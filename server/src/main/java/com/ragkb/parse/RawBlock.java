package com.ragkb.parse;

/**
 * 解析器直接产出的结构块:还没有字符下标。
 *
 * 下标由 {@link ParsedDocument#of} 统一切出来 —— 让解析器各自维护 offset 是
 * 一个必然出错的安排(每个实现都要自己数一遍全文长度),所以这里不带下标。
 *
 * @param kind         块类型
 * @param headingLevel 标题层级;非标题为 0
 * @param page         所在页码(从 1 开始);格式本身没有页概念的(MD/TXT/DOCX)一律为 null
 * @param text         块的文本内容,不做 Markdown 语法剥离,保留原文
 */
public record RawBlock(BlockKind kind, int headingLevel, Integer page, String text) {

    public RawBlock {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空白块");
        }
        boolean levelValid = kind == BlockKind.HEADING
                ? headingLevel >= 1 && headingLevel <= 6
                : headingLevel == 0;
        if (!levelValid) {
            throw new IllegalArgumentException("headingLevel 与 kind 不匹配: " + kind + " / " + headingLevel);
        }
    }

    public static RawBlock heading(int level, String text) {
        return new RawBlock(BlockKind.HEADING, level, null, text);
    }

    public static RawBlock paragraph(String text) {
        return new RawBlock(BlockKind.PARAGRAPH, 0, null, text);
    }

    public static RawBlock listItem(String text) {
        return new RawBlock(BlockKind.LIST_ITEM, 0, null, text);
    }

    public static RawBlock tableRow(String text) {
        return new RawBlock(BlockKind.TABLE_ROW, 0, null, text);
    }

    public static RawBlock quote(String text) {
        return new RawBlock(BlockKind.QUOTE, 0, null, text);
    }

    public static RawBlock code(String text) {
        return new RawBlock(BlockKind.CODE, 0, null, text);
    }

    /** 补上页码,用于 PDF 这类有页概念的来源。 */
    public RawBlock onPage(int pageNumber) {
        return new RawBlock(kind, headingLevel, pageNumber, text);
    }
}
