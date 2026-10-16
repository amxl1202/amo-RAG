package com.ragkb.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PDF 解析器(PDFBox 2.0)。
 *
 * PDF 里没有"标题"这个概念 —— 只有"在某坐标上以某字号画了一串字"。
 * 所以结构只能反推,本实现用两条证据:
 * 1. 字号:正文行字号的中位数是基准,明显更大的行按倍数分成 1~3 级标题;
 * 2. 排版位置:行右端没有顶到正文右边界 = 这一行是段落最后一行。
 *
 * 第 2 条是必须的:PDF 里的段落是被硬换行切成一行行的,不合并的话
 * 每一行都会变成独立结构块,切片就退化成了"按视觉行切",语义完全丢失。
 *
 * 已知局限:双栏排版、扫描件(无文本层)不在本实现覆盖范围内;
 * 扫描件应当在更前面用 OCR 处理,而不是塞给解析器猜。
 */
@Component
public class PdfParser implements DocumentParser {

    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("^(?:第[一二三四五六七八九十百零〇0-9]+[章节篇部]|\\d+(?:\\.\\d+){0,2})\\s*[、.．]?\\s*\\S+$");
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[•·▪◦○●◆■□▲△※]|[-*+]|\\d{1,3}[.)、]|[（(]\\d+[)）]|[一二三四五六七八九十]+[、.．])\\s*\\S.*$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。！？.!?；;:：]\\s*$");

    /** 判定"这一行没写满"的比例阈值:右端未达到正文右边界 90% 即认为段落在此结束。 */
    private static final double PARAGRAPH_END_RATIO = 0.90;
    /** 标题的最小放大倍数(相对正文基准字号)。 */
    private static final double HEADING_SCALE = 1.12;
    /**
     * 放大倍数 → 标题层级的分档。
     * 这三个数是对着 {@code BinaryParserStructureTest} 里的样本定的(18pt 标题 / 11pt 正文 → 一级),
     * 换语料(尤其是中文 Word 导出的 PDF:标题1 三号 16pt、正文 小四 12pt)后要重新核对,
     * 不能当成普适常数。
     */
    private static final double LEVEL_1_SCALE = 1.5;
    private static final double LEVEL_2_SCALE = 1.3;
    /** 段落末行再短也可能只是刚好换行,配一个绝对宽度兜底,避免把整页并成一段。 */
    private static final double PARAGRAPH_END_RATIO_STRICT = 0.70;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("pdf");
    }

    @Override
    public List<RawBlock> parse(InputStream content) throws IOException {
        try (PDDocument document = PDDocument.load(content)) {
            if (document.getNumberOfPages() == 0) {
                return List.of();
            }
            LineCollectingStripper stripper = new LineCollectingStripper();
            // 不按位置排序时,PDFBox 会按内容流顺序输出,坐标判断段落边界就失去意义
            stripper.setSortByPosition(true);
            stripper.getText(document);
            return toBlocks(stripper.pages());
        }
    }

    private List<RawBlock> toBlocks(List<PdfPage> pages) {
        List<RawBlock> blocks = new ArrayList<>();
        for (PdfPage page : pages) {
            if (page.lines().isEmpty()) {
                continue;
            }
            float bodySize = estimateBodyFontSize(page.lines());
            double rightEdge = page.lines().stream().mapToDouble(PdfLine::rightEdge).max().orElse(0d);
            StringBuilder paragraph = new StringBuilder();

            for (PdfLine line : page.lines()) {
                int level = headingLevel(line, bodySize);
                if (level > 0) {
                    flushParagraph(paragraph, page.number(), blocks);
                    blocks.add(RawBlock.heading(level, line.text()).onPage(page.number()));
                    continue;
                }
                if (LIST_ITEM.matcher(line.text()).matches()) {
                    flushParagraph(paragraph, page.number(), blocks);
                    blocks.add(RawBlock.listItem(line.text()).onPage(page.number()));
                    continue;
                }
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line.text());
                if (endsParagraph(line, rightEdge)) {
                    flushParagraph(paragraph, page.number(), blocks);
                }
            }
            flushParagraph(paragraph, page.number(), blocks);
        }
        return blocks;
    }

    private void flushParagraph(StringBuilder paragraph, int page, List<RawBlock> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(RawBlock.paragraph(paragraph.toString()).onPage(page));
            paragraph.setLength(0);
        }
    }

    private int headingLevel(PdfLine line, float bodySize) {
        String text = line.text();
        // 标题不会是一整段:超过 60 字的行即使字号大,也更可能是封面大字或页眉
        if (text.length() > 60) {
            return 0;
        }
        double scale = bodySize <= 0 ? 1d : line.fontSize() / bodySize;
        if (scale >= HEADING_SCALE) {
            if (scale >= LEVEL_1_SCALE) {
                return 1;
            }
            return scale >= LEVEL_2_SCALE ? 2 : 3;
        }
        // 字号与正文一致但符合"编号 + 短标题"模式,同样按标题处理(取最深层级)
        return NUMBERED_HEADING.matcher(text.strip()).matches() ? 3 : 0;
    }

    private boolean endsParagraph(PdfLine line, double rightEdge) {
        if (rightEdge <= 0) {
            return true;
        }
        String text = line.text();
        if (SENTENCE_END.matcher(text).matches()) {
            return true;
        }
        double ratio = line.rightEdge() / rightEdge;
        // 明显没写满 → 段落结束;写满但以句末标点收尾 → 也结束
        return ratio < PARAGRAPH_END_RATIO_STRICT
                || (ratio < PARAGRAPH_END_RATIO && !text.endsWith("、") && !text.endsWith(","));
    }

    /** 用"字符数加权"求字号众数,比取平均值稳:页面里少量大标题不会把基准带偏。 */
    private float estimateBodyFontSize(List<PdfLine> lines) {
        Map<Integer, Integer> weight = new HashMap<>();
        for (PdfLine line : lines) {
            int bucket = Math.round(line.fontSize() * 2);
            weight.merge(bucket, line.text().length(), Integer::sum);
        }
        return weight.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey() / 2f)
                .orElse(0f);
    }

    private record PdfLine(String text, float fontSize, double leftEdge, double rightEdge) {
    }

    private record PdfPage(int number, List<PdfLine> lines) {
    }

    /** 收集每个视觉行的文本、字号与左右端坐标 —— 段落边界的判断全靠这三样。 */
    private static final class LineCollectingStripper extends PDFTextStripper {

        private final List<PdfPage> pages = new ArrayList<>();
        private final StringBuilder lineBuffer = new StringBuilder();
        private List<PdfLine> currentLines = new ArrayList<>();
        private float lineFontSize;
        private double lineLeft = Double.MAX_VALUE;
        private double lineRight = Double.MIN_VALUE;

        private LineCollectingStripper() throws IOException {
            super();
        }

        private List<PdfPage> pages() {
            return pages;
        }

        @Override
        protected void writePageStart() throws IOException {
            currentLines = new ArrayList<>();
            pages.add(new PdfPage(pages.size() + 1, currentLines));
            resetLine();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.isBlank()) {
                return;
            }
            lineBuffer.append(text);
            for (TextPosition position : positions) {
                if (position.getFontSizeInPt() > lineFontSize) {
                    lineFontSize = position.getFontSizeInPt();
                }
                double left = position.getXDirAdj();
                double right = left + position.getWidthDirAdj();
                if (left < lineLeft) {
                    lineLeft = left;
                }
                if (right > lineRight) {
                    lineRight = right;
                }
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushLine();
        }

        @Override
        protected void writePageEnd() throws IOException {
            flushLine();
        }

        private void flushLine() {
            String line = lineBuffer.toString().strip();
            if (!line.isEmpty()) {
                currentLines.add(new PdfLine(line, lineFontSize,
                        lineLeft == Double.MAX_VALUE ? 0d : lineLeft,
                        lineRight == Double.MIN_VALUE ? 0d : lineRight));
            }
            resetLine();
        }

        private void resetLine() {
            lineBuffer.setLength(0);
            lineFontSize = 0f;
            lineLeft = Double.MAX_VALUE;
            lineRight = Double.MIN_VALUE;
        }
    }
}
