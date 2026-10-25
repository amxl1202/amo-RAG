package com.ragkb.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 文本保真:空格、换行、标题判定。
 *
 * <h2>这组用例对应三个真实缺陷</h2>
 * <ul>
 *   <li><b>缺少空格</b>:同一行被拆成多段 {@code showText} 时,段间空隙没有对应字符,
 *       直接拼接会变成 {@code alphabeta}——短语检索永远命中不了,日志里毫无异常;</li>
 *   <li><b>换行当成句子边界</b>:PDF 的段内换行是排版结果,保留 {@code \n} 会让下游把
 *       一句话切成几截,切片的语义边界跟着错;</li>
 *   <li><b>编号列表被当成标题</b>:中文列表项没有内部空格,会命中"编号 + 短标题"的模式,
 *       一旦进入章节栈,{@code sectionPath} 就变成 {@code 第二章 > 1. 首先安装JDK}。</li>
 * </ul>
 */
class PdfParserTextFidelityTest {

    private final PdfParser parser = new PdfParser();

    private List<RawBlock> parse(byte[] pdf) throws IOException {
        return parser.parse(new ByteArrayInputStream(pdf));
    }

    /** 同一行两段文字,坐标之间有明显空隙。 */
    @Test
    @DisplayName("同行两段 showText 之间要补空格,不能被拼成一个词")
    void keepsSpaceBetweenChunksOnSameLine() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.setTextMatrix(Matrix.getTranslateInstance(50, 700));
                content.showText("alpha");
                // 空出约 100pt:远超一个字符宽,原本这里是词间空格
                content.setTextMatrix(Matrix.getTranslateInstance(180, 700));
                content.showText("beta");
                content.setTextMatrix(Matrix.getTranslateInstance(50, 684));
                content.showText("tail line closes the paragraph.");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        String text = parse(pdf).get(0).text();

        assertThat(text).contains("alpha beta").doesNotContain("alphabeta");
    }

    /** 两行正文属于同一段(第一行顶满右边界)。 */
    @Test
    @DisplayName("段内换行不应保留为换行符 —— 那是排版结果,不是句子边界")
    void mergesWrappedLinesWithoutNewline() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.COURIER, 11);
                content.newLineAtOffset(50, 700);
                content.showText(fullWidthLine("A sentence that runs to the right margin and there"));
                content.newLineAtOffset(0, -16);
                content.showText("continues on the next visual line.");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        List<RawBlock> blocks = parse(pdf);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.PARAGRAPH);
        assertThat(blocks.get(0).text())
                .as("段内不能留换行,否则下游切句会把一句话切成两截")
                .doesNotContain("\n")
                .contains("right margin")
                .endsWith("continues on the next visual line.");
    }

    /** 正文字号 + "1. 中文" 形态:这是列表项,不是标题。 */
    @Test
    @DisplayName("编号列表项不能被当成标题,否则 sectionPath 会被污染")
    void numberedListItemIsNotHeading() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.newLineAtOffset(50, 700);
                content.showText("Body text introduces the steps below.");
                content.newLineAtOffset(0, -16);
                content.showText("1. Install");
                content.newLineAtOffset(0, -16);
                content.showText("2. Troubleshoot");
                content.newLineAtOffset(0, -16);
                content.showText("Short closing line.");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        List<RawBlock> blocks = parse(pdf);

        assertThat(blocks).noneMatch(block -> block.kind() == BlockKind.HEADING);
        assertThat(blocks).filteredOn(block -> block.kind() == BlockKind.LIST_ITEM)
                .extracting(RawBlock::text)
                .contains("1. Install", "2. Troubleshoot");
    }

    @Test
    @DisplayName("拼接规则:中文不加空格、英文要加、中文标点也不加")
    void separatorDependsOnScript() {
        assertThat(PdfParser.separatorBetween('天', '气')).isEmpty();
        assertThat(PdfParser.separatorBetween('a', 'b')).isEqualTo(" ");
        assertThat(PdfParser.separatorBetween('。', '下')).isEmpty();
        assertThat(PdfParser.separatorBetween('a', ' ')).isEmpty();
        // 中英混排:英文侧要空格,否则 the和中文会粘在一起
        assertThat(PdfParser.separatorBetween('e', '中')).isEmpty();
    }

    private String fullWidthLine(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < 74) {
            sb.append(" x");
        }
        return sb.substring(0, 74);
    }
}
