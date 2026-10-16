package com.ragkb.preview;

import com.ragkb.chunk.TokenCounter;
import com.ragkb.parse.BlockKind;
import com.ragkb.parse.DocumentParser;
import com.ragkb.parse.DocxParser;
import com.ragkb.parse.MarkdownParser;
import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.ParserRegistry;
import com.ragkb.parse.PdfParser;
import com.ragkb.parse.PlainTextParser;
import com.ragkb.parse.StructuralBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构预览命令行工具:不启动 Spring、不连数据库,把一个文件解析后的结构原样打出来。
 *
 * 存在的理由很实际:切片参数(target / max / overlap)必须由语料实测数据决定,
 * 而这份数据要先能看到结构才谈得上统计。把它做成不依赖数据库的命令行工具,
 * 就能在数据库还没起来时先把参数定下来。
 *
 * 用法见 scripts/preview-structure.sh。
 */
public final class StructurePreview {

    private static final int TEXT_PREVIEW_LIMIT = 56;

    private StructurePreview() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("用法: StructurePreview <文件路径> [更多文件...]");
            System.exit(2);
        }
        ParserRegistry registry = new ParserRegistry(List.of(
                new PdfParser(), new DocxParser(), new MarkdownParser(), new PlainTextParser()));
        TokenCounter tokenCounter = new TokenCounter();

        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.isRegularFile(path)) {
                System.err.println("跳过(不是文件): " + arg);
                continue;
            }
            report(registry, tokenCounter, path);
        }
    }

    private static void report(ParserRegistry registry, TokenCounter tokenCounter, Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = extensionOf(fileName);
        DocumentParser parser = registry.require(extension);

        List<com.ragkb.parse.RawBlock> raw;
        try (InputStream in = Files.newInputStream(path)) {
            raw = parser.parse(in);
        }
        ParsedDocument document = ParsedDocument.of(raw);

        System.out.println("=".repeat(100));
        System.out.printf("文件: %s%n", fileName);
        System.out.printf("解析器: %s   结构块: %d   归一化全文: %d 字符 / %d token (cl100k_base)%n",
                parser.getClass().getSimpleName(), document.blocks().size(),
                document.charLength(), tokenCounter.count(document.fullText()));
        System.out.println("-".repeat(100));
        System.out.printf("%-5s %-10s %-4s %-4s %-18s %-7s %s%n",
                "#", "类型", "层级", "页", "字符区间", "token", "内容");
        System.out.println("-".repeat(100));

        Map<BlockKind, Integer> kindCount = new EnumMap<>(BlockKind.class);
        List<Integer> paragraphTokens = new java.util.ArrayList<>();
        List<Integer> allTokens = new java.util.ArrayList<>();
        int maxTableRowTokens = 0;
        int maxBlockTokens = 0;

        for (int i = 0; i < document.blocks().size(); i++) {
            StructuralBlock block = document.blocks().get(i);
            int tokens = tokenCounter.count(block.text());
            kindCount.merge(block.kind(), 1, Integer::sum);
            allTokens.add(tokens);
            if (block.kind() == BlockKind.PARAGRAPH) {
                paragraphTokens.add(tokens);
            }
            if (block.kind() == BlockKind.TABLE_ROW) {
                maxTableRowTokens = Math.max(maxTableRowTokens, tokens);
            }
            maxBlockTokens = Math.max(maxBlockTokens, tokens);

            System.out.printf("%-5d %-10s %-4s %-4s %-18s %-7d %s%n",
                    i,
                    block.kind(),
                    block.isHeading() ? block.headingLevel() : "-",
                    block.page() == null ? "-" : block.page(),
                    "[" + block.charStart() + ", " + block.charEnd() + ")",
                    tokens,
                    preview(block.text()));
        }
        printStats(kindCount, paragraphTokens, allTokens, maxBlockTokens, maxTableRowTokens);
    }

    private static void printStats(Map<BlockKind, Integer> kindCount, List<Integer> paragraphTokens,
                                   List<Integer> allTokens, int maxBlockTokens, int maxTableRowTokens) {
        System.out.println("-".repeat(100));
        System.out.println("结构统计:");
        kindCount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Enum::ordinal)))
                .forEach(entry -> System.out.printf("  %-10s %d 块%n", entry.getKey(), entry.getValue()));
        System.out.printf("  段落 token: 中位 %d / p90 %d / 最大 %d%n",
                percentile(paragraphTokens, 0.50), percentile(paragraphTokens, 0.90), max(paragraphTokens));
        System.out.printf("  全部块 token: 中位 %d / p90 %d / 最大 %d%n",
                percentile(allTokens, 0.50), percentile(allTokens, 0.90), maxBlockTokens);
        System.out.printf("  最长表格行: %d token (max 取值不能低于它,否则表格行必被切开)%n", maxTableRowTokens);
        System.out.println();
    }

    private static int max(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static int percentile(List<Integer> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int index = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
        return sorted.get(index);
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= TEXT_PREVIEW_LIMIT
                ? flat
                : flat.substring(0, TEXT_PREVIEW_LIMIT) + "…";
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
