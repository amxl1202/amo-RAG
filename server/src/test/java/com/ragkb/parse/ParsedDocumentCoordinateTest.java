package com.ragkb.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁定 {@link ParsedDocument} 的坐标契约。
 *
 * 这条契约是整条引用链的地基:切片、重叠、引用回查全都建立在
 * "slice(charStart, charEnd) 能取回原文"之上。它此前只靠注释维持 ——
 * 一旦有人改动块间分隔符,出错方式是"不报错但引用悄悄指偏",很难排查。
 *
 * 刻意断言的是"关系"而不是写死数字:坐标的具体数值会随语料变化,
 * 而"相邻块恰好隔一个换行符""单块能精确取回"这些关系才是契约本身。
 */
class ParsedDocumentCoordinateTest {

    private static ParsedDocument doc(RawBlock... blocks) {
        return ParsedDocument.of(Arrays.asList(blocks));
    }

    @Test
    @DisplayName("每个块都能用自身坐标精确取回原文")
    void everyBlockIsRetrievableByItsOwnCoordinates() {
        ParsedDocument document = doc(
                RawBlock.heading(1, "第一章"),
                RawBlock.paragraph("第一段正文。"),
                RawBlock.tableRow("列一 | 列二"),
                RawBlock.paragraph("第三段正文。"));

        assertThat(document.blocks()).hasSize(4);
        for (StructuralBlock block : document.blocks()) {
            assertThat(document.slice(block.charStart(), block.charEnd()))
                    .as("块 %s 的坐标必须能精确取回它自己的文本", block.kind())
                    .isEqualTo(block.text());
        }
    }

    @Test
    @DisplayName("相邻两块之间恰好隔一个换行符,不是紧贴")
    void adjacentBlocksAreSeparatedByExactlyOneNewline() {
        ParsedDocument document = doc(
                RawBlock.heading(1, "第一章"),
                RawBlock.paragraph("第一段正文。"),
                RawBlock.paragraph("第二段正文。"));

        List<StructuralBlock> blocks = document.blocks();
        for (int i = 1; i < blocks.size(); i++) {
            assertThat(blocks.get(i).charStart() - blocks.get(i - 1).charEnd())
                    .as("第 %d 块与上一块之间应恰好隔 1 个字符(分隔用的换行符)", i)
                    .isEqualTo(1);
        }
        // 那 1 个字符确实就是换行符
        for (int i = 1; i < blocks.size(); i++) {
            assertThat(document.fullText().charAt(blocks.get(i).charStart() - 1))
                    .isEqualTo('\n');
        }
    }

    @Test
    @DisplayName("跨块取回会把中间的换行符一并带出 —— 切片合并依赖这个行为")
    void crossBlockSliceIncludesSeparators() {
        ParsedDocument document = doc(
                RawBlock.paragraph("甲"),
                RawBlock.paragraph("乙"),
                RawBlock.paragraph("丙"));

        List<StructuralBlock> blocks = document.blocks();
        String merged = document.slice(blocks.get(0).charStart(), blocks.get(2).charEnd());

        assertThat(merged).isEqualTo("甲\n乙\n丙");
        assertThat(merged).contains("\n");
    }

    @Test
    @DisplayName("全文等于各块文本以单个换行连接")
    void fullTextIsBlocksJoinedBySingleNewline() {
        ParsedDocument document = doc(
                RawBlock.heading(2, "小节"),
                RawBlock.paragraph("正文一。"),
                RawBlock.paragraph("正文二。"));

        String joined = document.blocks().stream()
                .map(StructuralBlock::text)
                .collect(Collectors.joining("\n"));

        assertThat(document.fullText()).isEqualTo(joined);
        assertThat(document.charLength()).isEqualTo(joined.length());
    }

    @Test
    @DisplayName("块内单换行保留,连续空行被压缩成一个")
    void blockKeepsSingleNewlinesButCollapsesBlankLines() {
        ParsedDocument document = doc(RawBlock.paragraph("第一行\n第二行\n\n\n第三行"));

        assertThat(document.blocks()).hasSize(1);
        assertThat(document.blocks().get(0).text())
                .as("块内换行必须保留,否则切片就没有多行正文了")
                .isEqualTo("第一行\n第二行\n\n第三行");
        // 压缩之后仍然能精确取回
        assertThat(document.slice(0, document.charLength()))
                .isEqualTo(document.blocks().get(0).text());
    }

    @Test
    @DisplayName("空白块在构造阶段就被拒绝,坐标系里不会出现空洞")
    void blankBlockCannotEnterTheCoordinateSystem() {
        // PDF/OCR 常见的空行噪声如果在 RawBlock 阶段被放行,就会在全文里留下
        // 一段没有块覆盖的区域 —— 那时坐标仍然自洽,但"每块都能取回"就不成立了。
        assertThatThrownBy(() -> RawBlock.paragraph("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RawBlock.paragraph("\n\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空输入产出空文档,不产生任何块")
    void emptyInputYieldsEmptyDocument() {
        ParsedDocument document = ParsedDocument.of(List.of());

        assertThat(document.isEmpty()).isTrue();
        assertThat(document.charLength()).isZero();
        assertThat(document.fullText()).isEmpty();
    }
}
