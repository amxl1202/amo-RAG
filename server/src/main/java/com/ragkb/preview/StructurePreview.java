package com.ragkb.preview;

import com.ragkb.chunk.ChunkDraft;
import com.ragkb.chunk.ChunkSplitter;
import com.ragkb.chunk.TokenCounter;
import com.ragkb.parse.BlockKind;
import com.ragkb.parse.DocumentParser;
import com.ragkb.parse.DocxParser;
import com.ragkb.parse.MarkdownParser;
import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.ParserRegistry;
import com.ragkb.parse.PdfParser;
import com.ragkb.parse.PlainTextParser;
import com.ragkb.parse.RawBlock;
import com.ragkb.parse.StructuralBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构预览命令行工具:不启动 Spring、不连数据库,把一个文件的解析/切片结果打出来。
 *
 * 存在的理由很实际:切片参数必须由语料实测数据决定,而这份数据要先能看到结构才谈得上统计。
 * 做成不依赖数据库的命令行工具,就能在数据库没起来时把参数定下来;
 * 也就意味着切片逻辑不需要等整条链路接通才能验证。
 *
 * 两种模式:
 * - 默认:打印结构块与 token 分布,用来定 target / max / overlap;
 * - {@code --chunks}:调用切片器并把每一片打出来,同时自动校验下标是否能回溯原文。
 *
 * 用法见 scripts/preview-structure.sh。
 *
 * TODO(清理): 这是开发工具,放在 main 源集里会被打进生产 jar。
 *  更合适的位置是独立的 tools 模块或 test 源集;现在不动是因为挪走之后每次预览要多绕一步,
 *  等真要部署 jar 时再挪。
 */
public final class StructurePreview {

    /** 切片器实现的默认全名;约定放这里,省得每次敲一长串。 */
    private static final String DEFAULT_SPLITTER = "com.ragkb.chunk.StructureAwareChunkSplitter";

    private static final int TEXT_PREVIEW_LIMIT = 56;
    /** 边界预览的字数:每片只看首尾这么多字,句子有没有被拦腰截断一眼能看出来。 */
    private static final int EDGE_PREVIEW_LIMIT = 16;

    private StructurePreview() {
    }

    public static void main(String[] args) throws IOException {
        List<String> files = new ArrayList<>();
        String splitterClass = DEFAULT_SPLITTER;
        boolean chunksMode = false;
        for (String arg : args) {
            if (arg.startsWith("--splitter=")) {
                splitterClass = arg.substring("--splitter=".length());
            } else if (arg.equals("--chunks")) {
                chunksMode = true;
            } else {
                files.add(arg);
            }
        }
        if (files.isEmpty()) {
            System.err.println("用法: StructurePreview [--chunks] [--splitter=<全限定类名>] <文件...>");
            System.exit(2);
        }

        ParserRegistry registry = new ParserRegistry(List.of(
                new PdfParser(), new DocxParser(), new MarkdownParser(), new PlainTextParser()));
        TokenCounter tokenCounter = new TokenCounter();
        ChunkSplitter splitter = chunksMode ? loadSplitter(splitterClass) : null;

        for (String file : files) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                System.err.println("跳过(不是文件): " + file);
                continue;
            }
            report(registry, tokenCounter, path, splitter);
        }
    }

    private static void report(ParserRegistry registry, TokenCounter tokenCounter,
                               Path path, ChunkSplitter splitter) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = extensionOf(fileName);
        DocumentParser parser = registry.require(extension);

        List<RawBlock> raw;
        try (InputStream in = Files.newInputStream(path)) {
            raw = parser.parse(in);
        }
        ParsedDocument document = ParsedDocument.of(raw);

        System.out.println("=".repeat(100));
        System.out.printf("文件: %s%n", fileName);
        System.out.printf("解析器: %s   结构块: %d   归一化全文: %d 字符 / %d token (cl100k_base)%n",
                parser.getClass().getSimpleName(), document.blocks().size(),
                document.charLength(), tokenCounter.count(document.fullText()));

        if (splitter == null) {
            printBlocks(document, tokenCounter);
        } else {
            printChunks(document, tokenCounter, splitter, fileName);
        }
    }

    private static void printBlocks(ParsedDocument document, TokenCounter tokenCounter) {
        System.out.println("-".repeat(100));
        System.out.printf("%-5s %-10s %-4s %-4s %-18s %-7s %s%n",
                "#", "类型", "层级", "页", "字符区间", "token", "内容");
        System.out.println("-".repeat(100));

        Map<BlockKind, Integer> kindCount = new EnumMap<>(BlockKind.class);
        List<Integer> paragraphTokens = new ArrayList<>();
        List<Integer> allTokens = new ArrayList<>();
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

    private static void printChunks(ParsedDocument document, TokenCounter tokenCounter,
                                    ChunkSplitter splitter, String fileName) {
        System.out.printf("切片器: %s%n", splitter.getClass().getSimpleName());
        List<ChunkDraft> chunks;
        try {
            chunks = splitter.split(document);
        } catch (RuntimeException e) {
            System.out.println("切片失败: " + e);
            System.out.println();
            return;
        }
        System.out.printf("切片数: %d   平均 %d token/片%n", chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream().mapToInt(ChunkDraft::tokenCount).sum() / chunks.size());
        System.out.println("-".repeat(100));
        System.out.printf("%3s  %5s  %-18s %s%n", "#", "token", "字符区间", "章节 | 首…尾");
        System.out.println("-".repeat(100));

        List<Integer> tokens = new ArrayList<>();
        int offsetBroken = 0;
        for (ChunkDraft chunk : chunks) {
            tokens.add(chunk.tokenCount());
            String section = chunk.sectionPath() == null ? "-" : chunk.sectionPath();
            System.out.printf("%3d  %5d  [%6d, %6d)   %s | %s…%s%n",
                    chunk.index(), chunk.tokenCount(), chunk.charStart(), chunk.charEnd(),
                    section, head(chunk.content()), tail(chunk.content()));
            if (!contentMatchesSource(document, chunk)) {
                offsetBroken++;
            }
        }

        System.out.println("-".repeat(100));
        System.out.println("切片统计:");
        System.out.printf("  token: 最小 %d / 中位 %d / p90 %d / 最大 %d%n",
                tokens.stream().mapToInt(Integer::intValue).min().orElse(0),
                percentile(tokens, 0.50), percentile(tokens, 0.90), max(tokens));
        System.out.printf("  下标可回溯: %d/%d 通过%s%n",
                chunks.size() - offsetBroken, chunks.size(),
                offsetBroken == 0 ? "" : "   ← 有切片取不回原文,这是硬伤");
        int indexBroken = checkIndexSequence(chunks);
        System.out.printf("  index 连续: %s%n", indexBroken == 0 ? "是" : "否,存在跳号或重复");
        if (offsetBroken > 0) {
            for (ChunkDraft chunk : chunks) {
                if (!contentMatchesSource(document, chunk)) {
                    System.out.printf("    不一致: #%d [%d, %d) 声明 %d token%n",
                            chunk.index(), chunk.charStart(), chunk.charEnd(), chunk.tokenCount());
                }
            }
        }
        System.out.println();
    }

    /**
     * 切片声明的内容必须等于原文对应区间。
     *
     * 只允许首尾空白差异 —— 切片器把块拼接起来时多一个换行不算错,
     * 但内容本身对不上就说明下标算错了,引用会指到别处。
     */
    private static boolean contentMatchesSource(ParsedDocument document, ChunkDraft chunk) {
        if (chunk.charEnd() > document.charLength()) {
            return false;
        }
        String source = document.slice(chunk.charStart(), chunk.charEnd());
        return source.strip().equals(chunk.content().strip());
    }

    private static int checkIndexSequence(List<ChunkDraft> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).index() != i) {
                return -1;
            }
        }
        return 0;
    }

    private static ChunkSplitter loadSplitter(String className) {
        try {
            Class<?> type = Class.forName(className);
            if (!ChunkSplitter.class.isAssignableFrom(type)) {
                System.err.println(className + " 存在但没有实现 ChunkSplitter 接口");
                System.exit(1);
            }
            return (ChunkSplitter) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            System.err.println("找不到切片器实现: " + className);
            System.err.println("  需要新建 server/src/main/java/" + className.replace('.', '/') + ".java");
            System.err.println("  或指定已有的实现: --splitter=<全限定类名>");
            System.exit(1);
            return null;
        } catch (ReflectiveOperationException e) {
            System.err.println(className + " 无法实例化(需要公开的无参构造): " + e);
            System.exit(1);
            return null;
        }
    }

    private static String head(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= EDGE_PREVIEW_LIMIT ? flat : flat.substring(0, EDGE_PREVIEW_LIMIT);
    }

    private static String tail(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= EDGE_PREVIEW_LIMIT ? flat : flat.substring(flat.length() - EDGE_PREVIEW_LIMIT);
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
