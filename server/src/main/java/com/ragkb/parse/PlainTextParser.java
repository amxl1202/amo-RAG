package com.ragkb.parse;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本解析器(.txt)。
 *
 * 这里没有"真结构"可读,只能靠排版约定猜。猜的规则全部写在下面的正则里,可按语料调整:
 * 1. 空行分段;
 * 2. 短行(≤40 字符)、不以句末标点结尾、且命中章节编号模式 → 判为标题;
 * 3. 命中项目符号 → 判为列表项;
 * 4. 其余并入当前段落。
 *
 * 明确的局限:命中不了编号模式的标题(比如通篇加粗的 Word 标题导出的 txt)会被当成正文。
 * 这是 TXT 格式本身的信息损失,不是实现缺陷 —— PDF/DOCX 走各自的解析器可以拿到真结构。
 */
@Component
public class PlainTextParser implements DocumentParser {

    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千零〇0-9]+[章篇部节]\\s*\\S*$");
    private static final Pattern CN_ORDER = Pattern.compile("^[一二三四五六七八九十]+[、.．]\\s*\\S+$");
    private static final Pattern NUM_HEADING = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})[、.．]?\\s+(\\S.*)$");
    private static final Pattern LIST_ITEM =
            Pattern.compile("^\\s*(?:[-*+•·○●]|\\d{1,3}[.)、]|[（(]\\d+[)）]|[a-zA-Z][.)])\\s+\\S.*$");
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。！？；：.!?;:]\\s*$");

    private static final int HEADING_MAX_LENGTH = 40;

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("txt");
    }

    @Override
    public List<RawBlock> parse(InputStream content) throws IOException {
        List<String> lines = MarkdownParser.readLines(content);
        List<RawBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                flushParagraph(paragraph, blocks);
                continue;
            }

            int level = headingLevel(line);
            if (level > 0) {
                flushParagraph(paragraph, blocks);
                blocks.add(RawBlock.heading(level, line));
                continue;
            }

            if (LIST_ITEM.matcher(line).matches()) {
                flushParagraph(paragraph, blocks);
                blocks.add(RawBlock.listItem(line));
                continue;
            }

            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }
        flushParagraph(paragraph, blocks);
        return blocks;
    }

    /** 返回 0 表示不是标题。 */
    private int headingLevel(String line) {
        if (line.length() > HEADING_MAX_LENGTH || SENTENCE_END.matcher(line).matches()) {
            return 0;
        }
        if (CHAPTER.matcher(line).matches() || CN_ORDER.matcher(line).matches()) {
            return 1;
        }
        Matcher numbered = NUM_HEADING.matcher(line);
        if (numbered.matches()) {
            // "1.2.3 标题" → 三级;注意"1. 这是列表项"是单段编号,交给列表规则处理
            int segments = numbered.group(1).split("\\.").length;
            if (segments >= 2) {
                return Math.min(segments, 6);
            }
        }
        return 0;
    }

    private void flushParagraph(StringBuilder paragraph, List<RawBlock> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(RawBlock.paragraph(paragraph.toString()));
            paragraph.setLength(0);
        }
    }
}
