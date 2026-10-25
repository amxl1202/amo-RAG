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
    /**
     * 同一行内两段文字之间超过这个比例的字号宽度,就补一个空格。
     *
     * <p>PDF 里"两个词之间有空隙"往往不是空格字符,而是<b>两段 showText 的坐标差</b>。
     * 不补的话 {@code alpha} 和 {@code beta} 会被拼成 {@code alphabeta} —— 检索时
     * "alpha beta" 这个短语永远命中不了,而且日志里看不出任何异常。
     * 0.22 是对着 Courier 11pt 的样本定的:一个字符宽约 0.6em,半个字符(0.3em)以上才补,
     * 取 0.22 留一点余量,避免把正常字距也当成词间距。
     */
    private static final double WORD_GAP_RATIO = 0.22;

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
                appendWrapped(paragraph, line.text());
                if (endsParagraph(line, rightEdge)) {
                    flushParagraph(paragraph, page.number(), blocks);
                }
            }
            flushParagraph(paragraph, page.number(), blocks);
        }
        return blocks;
    }

    /**
     * 把视觉行接进段落,按语言决定要不要空格。
     *
     * <h2>为什么不直接用换行符</h2>
     * PDF 的段内换行是<b>排版结果</b>,不是句子边界。下游切句若把 {@code \n} 当句末,
     * 一句话会被切成两三截(已知缺陷 P-23)。所以这里在解析层就把视觉行接起来,
     * 段落内部不再留换行 —— 换行只保留在块与块之间,那里才是真的边界。
     *
     * <h2>为什么中文不加空格</h2>
     * 中文换行处没有空格,补进去就成了"这句 话"这种脏数据,既影响 embedding
     * 也会让引用里出现多余空格。英文则必须补,否则 {@code thequickbrown} 会变成一个词。
     */
    private void appendWrapped(StringBuilder paragraph, String text) {
        if (paragraph.isEmpty()) {
            paragraph.append(text);
            return;
        }
        paragraph.append(separatorBetween(paragraph.charAt(paragraph.length() - 1), text.charAt(0)))
                .append(text);
    }

    /**
     * 两个相邻片段之间该插入什么。
     *
     * <p>包级可见是为了能单测:中英文的规则不同,而用 PDFBox 的标准 14 字体造不出中文样本,
     * 只能把这条判断从"必须造一份 PDF"里解放出来。
     */
    static String separatorBetween(char previous, char next) {
        if (previous == ' ' || next == ' ') {
            return "";
        }
        // 中文换行处没有空格;英文必须有,否则两个词会粘成一个
        return isCjk(previous) || isCjk(next) ? "" : " ";
    }

    /** 是否是中日韩字符(这类文字换行处不需要空格)。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 常用汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3000 && c <= 0x303F)  // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
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
        // 列表项优先于"编号标题"判定:中文编号列表几乎都没有内部空格
        // ("1. 首先安装JDK" 会命中 NUMBERED_HEADING),一旦当标题处理,
        // sectionPath 就会变成"第二章 > 1. 首先安装JDK > ...",结构语义全错
        if (LIST_ITEM.matcher(text).matches()) {
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

    /**
     * 行右端是否已经"写满"来决定段落是否在此结束。
     *
     * 这里刻意<b>不</b>把"以句末标点结尾"当成独立判据 —— 第一版就是这么写的,
     * 在真实中文 PDF 上立刻出错:段内换行经常恰好断在"。"之后(一句话跨越两行),
     * 于是整段被从中间劈开,每个视觉行都变成了独立段落。
     * 现在标点只用来给"边界情况"加权:明显写不满 → 必结束;写不太满且有句末标点 → 结束;
     * 顶满右边界 → 无论以什么标点结尾,都说明这段话还没完。
     */
    private boolean endsParagraph(PdfLine line, double rightEdge) {
        if (rightEdge <= 0) {
            return true;
        }
        double ratio = line.rightEdge() / rightEdge;
        if (ratio < PARAGRAPH_END_RATIO_STRICT) {
            return true;
        }
        return ratio < PARAGRAPH_END_RATIO && SENTENCE_END.matcher(line.text()).matches();
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
        /** 上一段 showText 的最右端,用来判断"这两段之间原本有个空格"。 */
        private double lastChunkRight = Double.MIN_VALUE;
        /** 上一段 showText 的最后一个字符,用来判断是否中文(中文不加空格)。 */
        private char lastChar = 0;

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
            appendWithGap(text, positions);
            double chunkRight = Double.MIN_VALUE;
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
                if (right > chunkRight) {
                    chunkRight = right;
                }
            }
            lastChunkRight = chunkRight;
        }

        /**
         * 按坐标差决定要不要补空格。
         *
         * <p>这是 BUG-1 的修复点:PDF 里同一个视觉行经常被拆成多段 {@code showText},
         * 段间的空隙没有对应的空格字符,于是 {@code alpha} + {@code beta} 直接拼成
         * {@code alphabeta}。补空格的判据是"前一段的右端"到"这一段左端"的距离,
         * 而不是字符本身 —— 字符里根本没有空格。
         *
         * <p>中文不补:中文换行/分段处本来就没有空格,补了就是脏数据。
         */
        private void appendWithGap(String text, List<TextPosition> positions) {
            if (lineBuffer.length() > 0 && !positions.isEmpty()) {
                double chunkLeft = positions.get(0).getXDirAdj();
                double gap = chunkLeft - lastChunkRight;
                char first = text.charAt(0);
                boolean needsSpace = gap > lineFontSize * WORD_GAP_RATIO
                        && lastChar != 0 && !isCjk(lastChar) && !isCjk(first);
                if (needsSpace && lineBuffer.charAt(lineBuffer.length() - 1) != ' ') {
                    lineBuffer.append(' ');
                }
            }
            lineBuffer.append(text);
            lastChar = text.charAt(text.length() - 1);
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
            lastChunkRight = Double.MIN_VALUE;
            lastChar = 0;
            lineFontSize = 0f;
            lineLeft = Double.MAX_VALUE;
            lineRight = Double.MIN_VALUE;
        }
    }
}
