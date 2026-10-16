package com.ragkb.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 二进制格式解析器测试。
 *
 * 用代码在内存里生成 PDF / DOCX 再解析,而不是靠仓库里放样本文件:
 * 样本文件只能验证"这一份恰好能跑",而且一旦改了解析逻辑,也没人知道该改哪里。
 * 这里每个断言都写明"为了让某条规则成立,我在测试里造了什么条件"。
 *
 * PDF 用 Helvetica(PDF 内置字体)写英文,所以本测试只覆盖英文版式的规则;
 * 中文版式走同一套字号/坐标判定,不额外分叉。
 */
class BinaryParserStructureTest {

    private final PdfParser pdfParser = new PdfParser();
    private final DocxParser docxParser = new DocxParser();

    @Test
    @DisplayName("PDF:字号明显更大的行判为标题,层级由放大倍数决定")
    void pdfDetectsHeadingByFontSize() throws IOException {
        byte[] pdf = buildPdf();
        List<RawBlock> blocks = pdfParser.parse(new ByteArrayInputStream(pdf));

        assertThat(blocks).isNotEmpty();
        RawBlock first = blocks.get(0);
        assertThat(first.kind()).isEqualTo(BlockKind.HEADING);
        assertThat(first.headingLevel()).isEqualTo(1);
        assertThat(first.text()).isEqualTo("1 Overview");

        // 页码必须带出来:PDF 的引用定位要靠它
        assertThat(blocks).allSatisfy(block -> assertThat(block.page()).isEqualTo(1));
    }

    @Test
    @DisplayName("PDF:顶满正文宽度的相邻行必须合并成一段,段末短行不另起一段")
    void pdfMergesWrappedLinesIntoOneParagraph() throws IOException {
        List<RawBlock> blocks = pdfParser.parse(new ByteArrayInputStream(buildPdf()));

        List<RawBlock> paragraphs = blocks.stream()
                .filter(block -> block.kind() == BlockKind.PARAGRAPH)
                .toList();

        // 每段在 PDF 里是"两行顶满 + 一行没写满",合并后应当是 3 行一段,而不是 6 行一块或 6 段
        assertThat(paragraphs).hasSize(2);
        assertThat(paragraphs.get(0).text().lines().count()).isEqualTo(3);
        assertThat(paragraphs.get(0).text()).startsWith("The quick brown fox");
    }

    @Test
    @DisplayName("PDF:顶满右边界的行为段落中间时,不能因为以句末标点结尾就断开")
    void pdfDoesNotSplitAtMidParagraphPeriod() throws IOException {
        List<RawBlock> blocks = pdfParser.parse(new ByteArrayInputStream(buildPdfWithMidParagraphPeriod()));

        List<RawBlock> paragraphs = blocks.stream()
                .filter(block -> block.kind() == BlockKind.PARAGRAPH)
                .toList();

        // 回归用例:第一版把"以。或.结尾"当成独立判据,真实中文 PDF 里段内换行
        // 经常恰好断在句号后,结果一整段被劈成两块。现在只有"没写满"才算段落结束。
        assertThat(paragraphs).hasSize(1);
        assertThat(paragraphs.get(0).text().lines()).hasSize(3);
        assertThat(paragraphs.get(0).text().lines().toList().get(0)).endsWith(".");
    }

    @Test
    @DisplayName("DOCX:大纲级别与标题样式都能识别出层级")
    void docxDetectsHeadingLevel() throws IOException {
        byte[] docx = buildDocx();
        List<RawBlock> blocks = docxParser.parse(new ByteArrayInputStream(docx));

        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.HEADING);
        assertThat(blocks.get(0).headingLevel()).isEqualTo(1);
        assertThat(blocks.get(0).text()).isEqualTo("Project Overview");
    }

    @Test
    @DisplayName("DOCX:表格按行成块、单元格以 | 连接,列表项识别为 LIST_ITEM")
    void docxTableAndList() throws IOException {
        List<RawBlock> blocks = docxParser.parse(new ByteArrayInputStream(buildDocx()));

        assertThat(blocks.stream().filter(b -> b.kind() == BlockKind.TABLE_ROW).count()).isEqualTo(2);
        RawBlock header = blocks.stream()
                .filter(b -> b.kind() == BlockKind.TABLE_ROW)
                .findFirst()
                .orElseThrow();
        assertThat(header.text()).isEqualTo("Field | Type");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.kind()).isEqualTo(BlockKind.LIST_ITEM);
            assertThat(block.text()).isEqualTo("First item");
        });
    }

    /**
     * 造一份 PDF:一个 18pt 的一级标题 + 两段正文。
     * 每段都是"两行顶满正文宽度 + 一行明显没写满",用来验证:
     * 顶满的行要合并,没写满的行收尾但不另起新段。
     *
     * 正文刻意用等宽字体(Courier):比例字体的实际宽度和字符数不成正比,
     * 手写两行"看起来一样长"的句子很容易差出 10% 宽度,把测试变成碰运气。
     * 等宽之后宽度就等价于字符数,判定规则是否生效一目了然。
     */
    private byte[] buildPdf() throws IOException {
        String body1 = fullWidthLine("The quick brown fox jumps over the lazy dog while the");
        String body2 = fullWidthLine("lines into a single paragraph because both of them reach");
        String body4 = fullWidthLine("A second paragraph starts here and it also occupies");
        String body5 = fullWidthLine("exercised again with a different sentence that fills");

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, 18);
                content.newLineAtOffset(50, 780);
                content.showText("1 Overview");
                content.endText();

                content.beginText();
                content.setFont(PDType1Font.COURIER, 11);
                content.newLineAtOffset(50, 720);
                content.showText(body1);
                content.newLineAtOffset(0, -16);
                content.showText(body2);
                content.newLineAtOffset(0, -16);
                content.showText("This short line ends the first paragraph.");

                content.newLineAtOffset(0, -32);
                content.showText(body4);
                content.newLineAtOffset(0, -16);
                content.showText(body5);
                content.newLineAtOffset(0, -16);
                content.showText("And this one closes it.");
                content.endText();
            }

            document.save(out);
            return out.toByteArray();
        }
    }

    /** Courier 11pt 下 A4 正文宽度约容得下 75 个字符,造 74 字符即为"顶满"。 */
    private String fullWidthLine(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < 74) {
            sb.append(" x");
        }
        return sb.substring(0, 74);
    }

    /** 造一份正文段落中段内换行以句号结尾的 PDF:整段应当合并,不能被劈成两块。 */
    private byte[] buildPdfWithMidParagraphPeriod() throws IOException {
        String line1 = fullWidthLine("First visual line of one paragraph that happens to end").substring(0, 73) + ".";
        String line2 = fullWidthLine("Second visual line of the very same paragraph ending here");
        String line3 = "Short closing line.";

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.COURIER, 11);
                content.newLineAtOffset(50, 700);
                content.showText(line1);
                content.newLineAtOffset(0, -16);
                content.showText(line2);
                content.newLineAtOffset(0, -16);
                content.showText(line3);
                content.endText();
            }

            document.save(out);
            return out.toByteArray();
        }
    }

    /** 造一份 DOCX:Heading1 标题 + 正文段 + 带编号的列表项 + 2x2 表格。 */
    private byte[] buildDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Project Overview");

            XWPFParagraph body = document.createParagraph();
            body.createRun().setText("This paragraph has no heading style and must be a PARAGRAPH.");

            XWPFParagraph listItem = document.createParagraph();
            listItem.getCTP().addNewPPr().addNewNumPr().addNewNumId().setVal(BigInteger.ONE);
            listItem.createRun().setText("First item");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Field");
            table.getRow(0).getCell(1).setText("Type");
            table.getRow(1).getCell(0).setText("status");
            table.getRow(1).getCell(1).setText("varchar");

            document.write(out);
            return out.toByteArray();
        }
    }
}
