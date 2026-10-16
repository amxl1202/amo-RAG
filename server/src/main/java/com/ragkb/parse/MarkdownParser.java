package com.ragkb.parse;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器。
 *
 * 它比 PDF 好处理的原因很直接:结构是显式写出来的(#、-、```),不需要猜。
 *
 * 标记剥离的取舍:
 * - 标题去掉前导 "#" 与尾部 "#":这两个符号不携带词义,留着只会污染向量与"所属章节"字段;
 * - 引用去掉前导 ">":同理;
 * - 代码块去掉 ``` 围栏,但块内原样保留;
 * - 列表项的 "-"/"1." 保留:它是行内容的一部分,去掉后无法区分项目与普通段落;
 * - 表格行原样保留(含 |---| 分隔行):丢了就还原不回一张表。
 *
 * 续行处理:Markdown 允许"懒续行"—— 引用或列表项换行后,下一行可以不写标记。
 * 不认这条规则的话,一个三行的引用会变成三个独立块,语义被切断。
 */
@Component
public class MarkdownParser implements DocumentParser {

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+\\S.*$");
    private static final Pattern BLOCK_QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
    private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md", "markdown");
    }

    @Override
    public List<RawBlock> parse(InputStream content) throws IOException {
        List<String> lines = readLines(content);
        List<RawBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        // 上一行是列表项或引用时,未带标记的行按"懒续行"并回上一块
        boolean lazyContinuable = false;

        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);

            if (FENCE.matcher(line).matches()) {
                flushParagraph(paragraph, blocks);
                lazyContinuable = false;
                index = collectCodeBlock(lines, index, blocks);
                continue;
            }

            if (line.isBlank() || THEMATIC_BREAK.matcher(line).matches()) {
                flushParagraph(paragraph, blocks);
                lazyContinuable = false;
                index++;
                continue;
            }

            Matcher heading = ATX_HEADING.matcher(line.strip());
            if (heading.matches()) {
                flushParagraph(paragraph, blocks);
                lazyContinuable = false;
                blocks.add(RawBlock.heading(heading.group(1).length(), heading.group(2).strip()));
                index++;
                continue;
            }

            Matcher quote = BLOCK_QUOTE.matcher(line);
            if (quote.matches()) {
                flushParagraph(paragraph, blocks);
                String quoted = quote.group(1).strip();
                if (quoted.isEmpty()) {
                    // 单独的 ">" 是块内空行,作为分段落处理
                    lazyContinuable = false;
                } else {
                    appendToLast(blocks, BlockKind.QUOTE, quoted);
                    lazyContinuable = true;
                }
                index++;
                continue;
            }

            if (TABLE_ROW.matcher(line).matches()) {
                flushParagraph(paragraph, blocks);
                lazyContinuable = false;
                blocks.add(RawBlock.tableRow(line.strip()));
                index++;
                continue;
            }

            if (LIST_ITEM.matcher(line).matches()) {
                flushParagraph(paragraph, blocks);
                blocks.add(RawBlock.listItem(line.strip()));
                lazyContinuable = true;
                index++;
                continue;
            }

            if (lazyContinuable) {
                appendToLast(blocks, blocks.get(blocks.size() - 1).kind(), line.strip());
                index++;
                continue;
            }

            // 普通正文行继续并入当前段落(不按单个换行切断,否则英文文档会被切碎)
            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(line.strip());
            index++;
        }
        flushParagraph(paragraph, blocks);
        return blocks;
    }

    /**
     * 把文本续写到最后一个块(懒续行)。
     *
     * 两个前置条件缺一不可:最后一个块的类型必须与本次一致,且必须是可续写的类型。
     * 少了第一个条件,标题会把紧跟其后的引用吞掉 —— 这是第一版真实踩过的坑:
     * "# 标题" 后面接 "> 引用",两者被并成一个 HEADING 块,层级元数据直接失真。
     */
    private void appendToLast(List<RawBlock> blocks, BlockKind kind, String line) {
        if (blocks.isEmpty() || !isContinuable(kind) || blocks.get(blocks.size() - 1).kind() != kind) {
            blocks.add(new RawBlock(kind, 0, null, line));
            return;
        }
        int last = blocks.size() - 1;
        RawBlock previous = blocks.get(last);
        blocks.set(last, new RawBlock(previous.kind(), previous.headingLevel(), previous.page(),
                previous.text() + "\n" + line));
    }

    /** 只有列表项与引用存在"不写标记的续行",段落走 paragraph 缓冲,标题/表格/代码不续写。 */
    private boolean isContinuable(BlockKind kind) {
        return kind == BlockKind.LIST_ITEM || kind == BlockKind.QUOTE;
    }

    private int collectCodeBlock(List<String> lines, int start, List<RawBlock> blocks) {
        StringBuilder code = new StringBuilder();
        int index = start + 1;
        while (index < lines.size() && !FENCE.matcher(lines.get(index)).matches()) {
            if (!code.isEmpty()) {
                code.append('\n');
            }
            code.append(lines.get(index));
            index++;
        }
        if (!code.isEmpty() && !code.toString().isBlank()) {
            blocks.add(RawBlock.code(code.toString()));
        }
        // 越过收尾围栏;若文件在围栏未闭合处结束,index 会等于行数,循环自然终止
        return index < lines.size() ? index + 1 : index;
    }

    private void flushParagraph(StringBuilder paragraph, List<RawBlock> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(RawBlock.paragraph(paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    /** 统一按 UTF-8 读行,并剥掉 Windows 记事本可能留下的 BOM。 */
    static List<String> readLines(InputStream content) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null) {
                line = stripBom(line);
            }
            while (line != null) {
                lines.add(line);
                line = reader.readLine();
            }
        }
        return lines;
    }

    static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
