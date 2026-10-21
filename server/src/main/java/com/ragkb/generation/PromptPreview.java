package com.ragkb.generation;

import com.ragkb.chunk.ChunkDraft;
import com.ragkb.chunk.StructureAwareChunkSplitter;
import com.ragkb.chunk.TokenCounter;
import com.ragkb.parse.DocumentParser;
import com.ragkb.parse.DocxParser;
import com.ragkb.parse.MarkdownParser;
import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.ParserRegistry;
import com.ragkb.parse.PdfParser;
import com.ragkb.parse.PlainTextParser;
import com.ragkb.parse.RawBlock;
import com.ragkb.retrieval.QueryTerms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 提示词预览:把一个文件的切片拼成提示词并打印出来。
 *
 * <h2>为什么需要它</h2>
 * 提示词是唯一"只能靠人读"才能验收的产物。单测能证明编号与正文的先后关系、
 * 预算没有超、没有截断,但证明不了"读起来像不像话" —— 材料块之间有没有粘连、
 * 章节路径会不会长到把正文挤走、模型看到的第一句究竟是什么,都只有真读一遍才知道。
 *
 * <h2>这里的排序不是检索</h2>
 * 它按"问题抽出的词在这片里出现了几个"做粗排,只为了让预览取到相关的几条。
 * 真实排序由检索层负责(向量 + 关键词 + RRF),预览不复刻,也不参与评估。
 * 换句话说:这里的挑选只影响"看哪几条",不影响"提示词长什么样"。
 *
 * 用法:
 * <pre>
 * scripts/preview-prompt.sh "问题" samples/rag-notes.pdf
 * scripts/preview-prompt.sh --files samples/rag-notes.md -- 问题
 * </pre>
 */
public final class PromptPreview {

    /** 预览取几条:与 PromptBuilder 的默认条数一致,免得"预览好看、线上塞 5 条"两回事。 */
    private static final int PREVIEW_LIMIT = PromptBuilder.defaultLimits().maxEvidence();

    private PromptPreview() {
    }

    public static void main(String[] args) throws IOException {
        String question = null;
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--question=")) {
                question = arg.substring("--question=".length());
            } else {
                files.add(arg);
            }
        }
        // 约定:最后一个位置参数是问题,其余是文件 —— 命令行里写"文件… 问题"最自然
        if (question == null && files.size() >= 2) {
            question = files.remove(files.size() - 1);
        }
        if (question == null || files.isEmpty()) {
            System.err.println("用法: PromptPreview <文件...> \"问题\"");
            System.err.println("  或: PromptPreview --question=\"问题\" <文件...>");
            System.exit(2);
        }

        TokenCounter tokenCounter = new TokenCounter();
        List<Evidence> ranked = rank(question, files);
        Prompt prompt = new PromptBuilder(tokenCounter).build(question, ranked);

        print(prompt, ranked.size());
    }

    /** 解析每个文件、切片、按问题词的命中数粗排。 */
    private static List<Evidence> rank(String question, List<String> files) throws IOException {
        ParserRegistry registry = new ParserRegistry(List.of(
                new PdfParser(), new DocxParser(), new MarkdownParser(), new PlainTextParser()));
        List<String> terms = QueryTerms.extract(question);
        List<Evidence> candidates = new ArrayList<>();

        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            Path path = Path.of(files.get(fileIndex));
            if (!Files.isRegularFile(path)) {
                System.err.println("跳过(不是文件): " + path);
                continue;
            }
            String fileName = path.getFileName().toString();
            DocumentParser parser = registry.require(extensionOf(fileName));
            List<RawBlock> raw;
            try (InputStream in = Files.newInputStream(path)) {
                raw = parser.parse(in);
            }
            ParsedDocument document = ParsedDocument.of(raw);
            List<ChunkDraft> drafts = new StructureAwareChunkSplitter().split(document);
            for (ChunkDraft draft : drafts) {
                double overlap = overlapScore(draft.content(), terms);
                if (overlap <= 0) {
                    continue;
                }
                candidates.add(new Evidence(draft.index(), fileIndex + 1L, fileName,
                        draft.sectionPath(), draft.charStart(), draft.charEnd(),
                        draft.pageStart(), draft.pageEnd(), draft.content(), overlap));
            }
        }
        candidates.sort(Comparator.comparingDouble(Evidence::score).reversed()
                .thenComparingLong(Evidence::chunkId));
        return candidates;
    }

    private static double overlapScore(String content, List<String> terms) {
        int hit = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                hit++;
            }
        }
        return hit;
    }

    private static void print(Prompt prompt, int candidateCount) {
        System.out.println("=".repeat(100));
        System.out.printf("问题: %s%n", prompt.question());
        System.out.printf("候选片段: %d   入选: %d   预算丢弃: %d   材料 token: %d%n",
                candidateCount, prompt.citations().size(), prompt.droppedCount(), prompt.contextTokens());
        System.out.println("=".repeat(100));
        System.out.println("---- 系统提示词 ----");
        System.out.println(prompt.systemPrompt());
        System.out.println("---- 用户提示词 ----");
        System.out.println(prompt.userPrompt());
        System.out.println("---- 编号对照表 ----");
        for (Citation citation : prompt.citations()) {
            Evidence evidence = citation.evidence();
            System.out.printf("%-5s chunkId=%-5d 《%s》 章节=%s 页=%s 字符区间=[%d, %d)%n",
                    citation.marker(), citation.chunkId(), evidence.documentTitle(),
                    evidence.sectionPath() == null ? "-" : evidence.sectionPath(),
                    evidence.pageStart() == null ? "-" : evidence.pageStart() + "-" + evidence.pageEnd(),
                    evidence.charStart(), evidence.charEnd());
        }
        System.out.println("=".repeat(100));
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
