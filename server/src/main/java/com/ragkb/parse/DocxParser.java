package com.ragkb.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX 解析器(OOML 是有限结构化格式,标题层级、列表、表格都是显式属性,不需要猜)。
 *
 * 标题层级的两条来源,按可靠性排序:
 * 1. 段落的大纲级别(w:outlineLvl)—— 这是 Word 内部真正用来生成目录的字段;
 * 2. 段落样式名(Heading 1 / 标题 1;中文版 Word 常见的是样式 id "1"/"2"/"3")。
 * 只在第 1 条缺失时才退回第 2 条,因为手工把某段刷成"标题样式"的文字在正文里很常见。
 *
 * 表格按行成块、单元格用 " | " 连接:跨行拆开会让表头与数据行分离,
 * 检索到"某个数值"却看不到它属于哪一列。
 */
@Component
public class DocxParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|标题)\\s*(\\d+)$");

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("docx");
    }

    @Override
    public List<RawBlock> parse(InputStream content) throws IOException {
        List<RawBlock> blocks = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(content)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(document, paragraph, blocks);
                } else if (element instanceof XWPFTable table) {
                    appendTable(table, blocks);
                }
            }
        }
        return blocks;
    }

    private void appendParagraph(XWPFDocument document, XWPFParagraph paragraph, List<RawBlock> blocks) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        String stripped = text.strip();
        int level = headingLevel(document, paragraph);
        if (level > 0) {
            blocks.add(RawBlock.heading(level, stripped));
        } else if (paragraph.getNumID() != null) {
            blocks.add(RawBlock.listItem(stripped));
        } else {
            blocks.add(RawBlock.paragraph(stripped));
        }
    }

    private void appendTable(XWPFTable table, List<RawBlock> blocks) {
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder();
            for (XWPFTableCell cell : row.getTableCells()) {
                if (!line.isEmpty()) {
                    line.append(" | ");
                }
                line.append(cell.getText().replace('\n', ' ').strip());
            }
            if (!line.toString().isBlank()) {
                blocks.add(RawBlock.tableRow(line.toString()));
            }
        }
    }

    private int headingLevel(XWPFDocument document, XWPFParagraph paragraph) {
        int fromOutline = outlineLevel(paragraph);
        if (fromOutline > 0) {
            return Math.min(fromOutline, 6);
        }
        String styleId = paragraph.getStyle();
        if (styleId == null || styleId.isBlank()) {
            return 0;
        }
        String styleName = resolveStyleName(document, styleId);
        Matcher matcher = HEADING_STYLE.matcher(styleName.strip());
        if (matcher.matches()) {
            return Math.min(Integer.parseInt(matcher.group(1)), 6);
        }
        return 0;
    }

    private int outlineLevel(XWPFParagraph paragraph) {
        if (paragraph.getCTP() == null || paragraph.getCTP().getPPr() == null) {
            return 0;
        }
        var outline = paragraph.getCTP().getPPr().getOutlineLvl();
        if (outline == null || outline.getVal() == null) {
            return 0;
        }
        return outline.getVal().intValue() + 1;
    }

    /** 样式 id 与样式名常常不同(中文 Word 把"标题 1"的 id 存成 "1"),这里查回可读名。 */
    private String resolveStyleName(XWPFDocument document, String styleId) {
        if (document.getStyles() == null) {
            return styleId;
        }
        XWPFStyle style = document.getStyles().getStyle(styleId);
        if (style == null || style.getName() == null) {
            return styleId;
        }
        return style.getName();
    }
}
