package com.ragkb.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份文档的解析结果:归一化全文 + 按阅读顺序排列的结构块。
 *
 * 归一化约定(切片器依赖它,不能随便改):
 * 1. 全文 = 各块 text 以单个 '\n' 连接;
 * 2. 块内保留原有换行;
 * 3. charStart / charEnd 是相对 fullText 的左闭右开区间,且各块首尾相接、无空隙。
 *
 * 第 3 条是本类存在的意义:切片的"可回溯"必须落在可验证的下标上,
 * 而不是靠切片器自己记位置。
 */
public record ParsedDocument(String fullText, List<StructuralBlock> blocks) {

    private static final String BLOCK_SEPARATOR = "\n";

    public static ParsedDocument of(List<RawBlock> rawBlocks) {
        if (rawBlocks == null || rawBlocks.isEmpty()) {
            return new ParsedDocument("", List.of());
        }
        StringBuilder text = new StringBuilder();
        List<StructuralBlock> blocks = new ArrayList<>(rawBlocks.size());
        for (RawBlock raw : rawBlocks) {
            String blockText = normalize(raw.text());
            if (blockText.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(BLOCK_SEPARATOR);
            }
            int start = text.length();
            text.append(blockText);
            int end = text.length();
            blocks.add(new StructuralBlock(raw.kind(), raw.headingLevel(), raw.page(), blockText, start, end));
        }
        return new ParsedDocument(text.toString(), List.copyOf(blocks));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int charLength() {
        return fullText.length();
    }

    /** 按下标取原文片段,越界时抛出而不是静默截断 —— 静默截断会让引用指错位置。 */
    public String slice(int charStart, int charEnd) {
        if (charStart < 0 || charEnd > fullText.length() || charStart > charEnd) {
            throw new IndexOutOfBoundsException(
                    "切片区间越界: [" + charStart + ", " + charEnd + "), 全文长度 " + fullText.length());
        }
        return fullText.substring(charStart, charEnd);
    }

    private static String normalize(String raw) {
        // 去掉行尾空白与连续空行,避免 PDF/OCR 常见的排版噪声进入 token 统计
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder sb = new StringBuilder();
        boolean lastBlank = false;
        for (String line : lines) {
            String trimmed = stripTrailing(line);
            boolean blank = trimmed.isEmpty();
            if (blank && lastBlank) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(trimmed);
            lastBlank = blank;
        }
        return sb.toString().strip();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
