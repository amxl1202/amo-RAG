package com.ragkb.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份文档的解析结果:归一化全文 + 按阅读顺序排列的结构块。
 *
 * 归一化约定(切片器依赖它,不能随便改):
 * 1. 全文 = 各块 text 以单个 '\n' 连接;
 * 2. 块内保留原有换行;
 * 3. charStart / charEnd 是相对 fullText 的左闭右开区间。
 *
 * 关于第 3 条要说准:相邻两块之间<b>恰好隔一个字符</b>(那个 '\n'),并不是紧贴。
 * 即 {@code blocks[i+1].charStart == blocks[i].charEnd + 1}。
 * 由此带来两种不同的取回语义,都是刻意的:
 * <ul>
 *   <li>单块:{@code slice(charStart, charEnd)} 精确等于该块文本(分隔符在区间之外);</li>
 *   <li>跨块:{@code slice(首块.charStart, 末块.charEnd)} 会把中间的换行符一并取回。
 *       切片器合并多个块时正是依赖这一点。</li>
 * </ul>
 *
 * 为什么坐标不会错位:{@code start} / {@code end} 是在<b>同一次拼接</b>里
 * append 前后取的长度,不是事后再估算位置。只要拼接逻辑不变,坐标与全文必然自洽。
 * 反过来说,一旦有人改动分隔符(比如改成两个换行),这两个语义都会跟着变 ——
 * 所以下方有专门的契约测试守着。
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
