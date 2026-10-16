package com.ragkb.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本类解析器的结构判定测试。
 *
 * 这里断言的不是"能解析出文字"(那种测试永远过),而是**结构判定是否符合预期**:
 * 标题层级、续行合并、块类型。这三样错了,切片策略再对也白搭。
 */
class TextParserStructureTest {

    private final MarkdownParser markdown = new MarkdownParser();
    private final PlainTextParser plainText = new PlainTextParser();

    @Test
    @DisplayName("Markdown:标题层级取自 # 的数量,标记被剥离")
    void markdownHeadingLevel() {
        List<RawBlock> blocks = parseMarkdown("# 一级\n\n## 二级\n\n### 三级\n\n正文段落。\n");

        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.HEADING);
        assertThat(blocks.get(0).headingLevel()).isEqualTo(1);
        assertThat(blocks.get(0).text()).isEqualTo("一级");
        assertThat(blocks.get(2).headingLevel()).isEqualTo(3);
        assertThat(blocks.get(3).kind()).isEqualTo(BlockKind.PARAGRAPH);
    }

    @Test
    @DisplayName("Markdown:块引用的懒续行必须合并,且不能吞掉标题")
    void markdownQuoteLazyContinuation() {
        List<RawBlock> blocks = parseMarkdown("""
                # 标题

                > 第一行引用
                > 第二行引用
                > 第三行引用

                正文。
                """);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.HEADING);
        assertThat(blocks.get(1).kind()).isEqualTo(BlockKind.QUOTE);
        assertThat(blocks.get(1).text()).isEqualTo("第一行引用\n第二行引用\n第三行引用");
        assertThat(blocks.get(2).kind()).isEqualTo(BlockKind.PARAGRAPH);
    }

    @Test
    @DisplayName("Markdown:列表项的未标记续行并入该项,而不是新起一段")
    void markdownListItemContinuation() {
        List<RawBlock> blocks = parseMarkdown("""
                - 第一项
                  续行内容
                - 第二项
                """);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.LIST_ITEM);
        assertThat(blocks.get(0).text()).isEqualTo("- 第一项\n续行内容");
        assertThat(blocks.get(1).text()).isEqualTo("- 第二项");
    }

    @Test
    @DisplayName("Markdown:代码块整体成块,围栏去掉但内部换行保留")
    void markdownCodeBlock() {
        List<RawBlock> blocks = parseMarkdown("""
                说明。

                ```java
                int a = 1;
                int b = 2;
                ```

                结尾。
                """);

        assertThat(blocks).extracting(RawBlock::kind)
                .containsExactly(BlockKind.PARAGRAPH, BlockKind.CODE, BlockKind.PARAGRAPH);
        assertThat(blocks.get(1).text()).isEqualTo("int a = 1;\nint b = 2;");
    }

    @Test
    @DisplayName("纯文本:短行+章节模式判为标题,句末带标点的行不判标题")
    void plainTextHeadingHeuristic() {
        List<RawBlock> blocks = parseText("""
                第一章 系统概述

                1.1 背景

                本系统用于验证结构识别。这一行很长所以不会被当成标题处理掉。

                第二章 实现
                """);

        assertThat(blocks).extracting(RawBlock::kind)
                .containsExactly(
                        BlockKind.HEADING,
                        BlockKind.HEADING,
                        BlockKind.PARAGRAPH,
                        BlockKind.HEADING);
        assertThat(blocks.get(0).headingLevel()).isEqualTo(1);
        assertThat(blocks.get(1).headingLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("下标不变量:每个块的 charStart/charEnd 必须能原样取回块文本")
    void charOffsetsAreTraceable() {
        ParsedDocument document = ParsedDocument.of(parseMarkdown("# 标题\n\n正文第一段。\n\n正文第二段。\n"));

        assertThat(document.blocks()).isNotEmpty();
        for (StructuralBlock block : document.blocks()) {
            assertThat(document.slice(block.charStart(), block.charEnd()))
                    .as("块 %d 的下标必须能取回原文", document.blocks().indexOf(block))
                    .isEqualTo(block.text());
        }
    }

    @Test
    @DisplayName("下标不变量:块之间首尾相接,拼接后等于全文")
    void blocksCoverFullText() {
        ParsedDocument document = ParsedDocument.of(parseMarkdown("# A\n\n段落一。\n\n## B\n\n段落二。\n"));

        StringBuilder rebuilt = new StringBuilder();
        int expectedStart = 0;
        for (StructuralBlock block : document.blocks()) {
            assertThat(block.charStart()).isEqualTo(expectedStart);
            if (!rebuilt.isEmpty()) {
                rebuilt.append('\n');
            }
            rebuilt.append(block.text());
            expectedStart = block.charEnd() + 1;
        }
        assertThat(rebuilt.toString()).isEqualTo(document.fullText());
    }

    private List<RawBlock> parseMarkdown(String text) {
        return parse(markdown, text);
    }

    private List<RawBlock> parseText(String text) {
        return parse(plainText, text);
    }

    private List<RawBlock> parse(DocumentParser parser, String text) {
        try {
            return parser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("解析失败", e);
        }
    }
}
