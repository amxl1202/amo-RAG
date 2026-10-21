package com.ragkb.generation;

import com.ragkb.chunk.ChunkDraft;
import com.ragkb.chunk.StructureAwareChunkSplitter;
import com.ragkb.chunk.TokenCounter;
import com.ragkb.common.DotEnv;
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
import java.util.Map;

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
        boolean answerMode = false;
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--question=")) {
                question = arg.substring("--question=".length());
            } else if (arg.equals("--answer")) {
                answerMode = true;
            } else {
                files.add(arg);
            }
        }
        // 约定:最后一个位置参数是问题,其余是文件 —— 命令行里写"文件… 问题"最自然
        if (question == null && files.size() >= 2) {
            question = files.remove(files.size() - 1);
        }
        if (question == null || files.isEmpty()) {
            System.err.println("用法: PromptPreview [--answer] <文件...> \"问题\"");
            System.err.println("  或: PromptPreview [--answer] --question=\"问题\" <文件...>");
            System.exit(2);
        }

        TokenCounter tokenCounter = new TokenCounter();
        List<Evidence> ranked = rank(question, files);
        Prompt prompt = new PromptBuilder(tokenCounter).build(question, ranked);

        print(prompt, ranked.size());
        if (answerMode) {
            printAnswer(prompt, tokenCounter);
        }
    }

    /**
     * 真的调一次模型。
     *
     * 配置从 {@code .env} 读(进程环境优先),与 Spring 容器里走的键同名 ——
     * 用两套键名会出现"容器里能跑、命令行跑不通"这种最难查的差异。
     */
    private static void printAnswer(Prompt prompt, TokenCounter counter) {
        Map<String, String> dotEnv = DotEnv.load(Path.of(".env"));
        String apiKey = DotEnv.get(dotEnv, "DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            System.err.println("跳过生成:.env 里没有 DASHSCOPE_API_KEY");
            return;
        }
        ChatProperties defaults = ChatProperties.defaults();
        ChatProperties properties = new ChatProperties(
                apiKey,
                DotEnv.get(dotEnv, "CHAT_MODEL", defaults.model()),
                DotEnv.get(dotEnv, "CHAT_ENDPOINT", defaults.endpoint()),
                defaults.temperature(),
                defaults.maxTokens(),
                defaults.timeoutSeconds(),
                defaults.maxAttempts());

        System.out.println("---- 模型回答 ----");
        try {
            GeneratedAnswer answer = new AnswerGenerator(new DashScopeChatModel(properties)).generate(prompt);
            System.out.println(answer.text());
            System.out.printf("%n[用量] 输入 %d / 输出 %d token,耗时 %d ms,模型 %s,模型自述拒答=%s%n",
                    answer.promptTokens(), answer.completionTokens(), answer.latencyMillis(),
                    answer.model(), answer.refused());
            int local = counter.count(prompt.systemPrompt()) + counter.count(prompt.userPrompt());
            if (local > 0 && answer.promptTokens() > 0) {
                // 两个数并排打出来:提示词预算用的是本地口径,而账单按实际口径算,
                // 不知道倍率就没法判断"预算还剩多少"与"钱花在哪"
                System.out.printf("[口径] 本地 cl100k 估计 %d,实际 %d,倍率 %.3f(实际 / 本地)%n",
                        local, answer.promptTokens(), (double) answer.promptTokens() / local);
            }
            printCitations(answer, prompt);
        } catch (ChatException e) {
            System.err.println("生成失败: " + e.getMessage() + "(可重试=" + e.retryable() + ")");
        }
        System.out.println("=".repeat(100));
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

    /** 打印引用解析结果:哪一句引了哪条、有没有编造的编号。 */
    private static void printCitations(GeneratedAnswer answer, Prompt prompt) {
        ParsedAnswer parsed = new CitationParser().parse(answer.text(), prompt);
        System.out.println("---- 引用解析 ----");
        System.out.printf("句子 %d / 有内容的句子 %d / 无引用句 %d / 引用覆盖率 %.2f%n",
                parsed.sentences().size(), parsed.contentSentences(),
                parsed.uncitedSentences(), parsed.citationCoverage());
        for (ParsedSentence sentence : parsed.sentences()) {
            String labels = sentence.citations().isEmpty() ? "-"
                    : String.join(",", sentence.citations().stream().map(Citation::label).toList());
            System.out.printf("  [%s] %s%n", labels, abbreviate(sentence.plain(), 40));
        }
        if (parsed.hasUnknownLabels()) {
            System.out.println("  剔除的编造编号: " + String.join(",", parsed.unknownLabels()));
            System.out.println("  剔除后的展示文本: " + parsed.sanitized());
        }
    }

    private static String abbreviate(String text, int limit) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
