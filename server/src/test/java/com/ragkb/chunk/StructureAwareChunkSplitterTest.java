package com.ragkb.chunk;

import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.RawBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructureAwareChunkSplitterTest {

    private final TokenCounter counter = new TokenCounter();

    private static ParsedDocument doc(RawBlock... blocks) {
        return ParsedDocument.of(Arrays.asList(blocks));
    }

    /** 造一段足够长的正文,用来把缓冲区顶过 target。 */
    private static String body(int sentences) {
        return "测试正文句子。".repeat(sentences);
    }

    /** 每个切片都必须满足的三条硬约束:下标可回溯源文、token 由 TokenCounter 算出、不超上限。 */
    private void assertInvariants(ParsedDocument document, List<ChunkDraft> chunks, int maxTokens) {
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            assertThat(chunk.index())
                    .as("index 必须从 0 连续递增")
                    .isEqualTo(i);
            assertThat(document.slice(chunk.charStart(), chunk.charEnd()))
                    .as("第 %d 片的内容必须能从原文按下标取回", i)
                    .isEqualTo(chunk.content());
            assertThat(chunk.tokenCount())
                    .as("第 %d 片的 token 数必须来自 TokenCounter", i)
                    .isEqualTo(counter.count(chunk.content()));
            assertThat(chunk.tokenCount())
                    .as("第 %d 片不得超过硬上限 %d", i, maxTokens)
                    .isLessThanOrEqualTo(maxTokens);
            assertThat(chunk.content().isBlank())
                    .as("第 %d 片不得为空内容", i)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("空文档产出空列表,不返回 null")
    void emptyDocumentYieldsEmptyList() {
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter();

        assertThat(splitter.split(doc())).isEmpty();
    }

    @Test
    @DisplayName("单段落成一片,下标覆盖全文")
    void singleParagraphBecomesOneChunk() {
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter();
        ParsedDocument document = doc(RawBlock.paragraph("这一段是唯一的内容。"));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).charStart()).isZero();
        assertThat(chunks.get(0).charEnd()).isEqualTo(document.charLength());
        assertThat(chunks.get(0).sectionPath()).isNull();
        assertInvariants(document, chunks, ChunkingProperties.defaults().maxTokens());
    }

    @Test
    @DisplayName("target 足够大时,多个块合并成一片")
    void largeTargetMergesEverything() {
        ChunkingProperties wide = new ChunkingProperties(5000, 6000, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(wide);
        ParsedDocument document = doc(
                RawBlock.paragraph("第一段。"),
                RawBlock.paragraph("第二段。"),
                RawBlock.paragraph("第三段。"));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("第一段", "第二段", "第三段");
        assertInvariants(document, chunks, wide.maxTokens());
    }

    @Test
    @DisplayName("target 撑满后封口,多块产出多片")
    void targetSplitsIntoMultipleChunks() {
        ChunkingProperties small = new ChunkingProperties(20, 200, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(small);
        ParsedDocument document = doc(
                RawBlock.paragraph(body(30)),
                RawBlock.paragraph(body(30)),
                RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertInvariants(document, chunks, small.maxTokens());
    }

    @Test
    @DisplayName("章节路径按标题栈拼接,深级标题在前一级之下")
    void sectionPathIsBuiltFromHeadingStack() {
        ChunkingProperties small = new ChunkingProperties(20, 400, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(small);
        ParsedDocument document = doc(
                RawBlock.heading(1, "第一章"),
                RawBlock.paragraph(body(30)),
                RawBlock.heading(2, "1.1 小节"),
                RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks.get(0).sectionPath()).isEqualTo("第一章");
        assertThat(chunks.get(chunks.size() - 1).sectionPath()).isEqualTo("第一章 > 1.1 小节");
        assertInvariants(document, chunks, small.maxTokens());
    }

    @Test
    @DisplayName("同级或更高级标题必须弹出栈,路径不含已结束的章节")
    void headingStackPopsOnSameOrShallowerLevel() {
        ChunkingProperties small = new ChunkingProperties(20, 400, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(small);
        ParsedDocument document = doc(
                RawBlock.heading(1, "第一章"),
                RawBlock.paragraph(body(30)),
                RawBlock.heading(2, "1.1 小节"),
                RawBlock.paragraph(body(30)),
                RawBlock.heading(1, "第二章"),
                RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        ChunkDraft last = chunks.get(chunks.size() - 1);
        assertThat(last.sectionPath())
                .as("进入第二章后,路径里不该还留着第一章或它的子节")
                .isEqualTo("第二章");
        assertThat(last.content()).contains("第二章");
    }

    @Test
    @DisplayName("标题不单独成片,总是领起后面的正文")
    void headingNeverBecomesStandaloneChunk() {
        ChunkingProperties small = new ChunkingProperties(5, 400, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(small);
        ParsedDocument document = doc(
                RawBlock.heading(1, "只有标题"),
                RawBlock.paragraph(body(10)),
                RawBlock.heading(2, "第二部分"),
                RawBlock.paragraph(body(10)));

        List<ChunkDraft> chunks = splitter.split(document);

        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.content().strip())
                    .as("不应出现只含标题的碎片")
                    .isNotEqualTo("只有标题")
                    .isNotEqualTo("第二部分");
        }
    }

    @Test
    @DisplayName("单个块超过硬上限时在块内切开,每片都不超上限")
    void oversizedBlockIsSplitWithinItself() {
        ChunkingProperties tight = new ChunkingProperties(10, 20, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(tight);
        ParsedDocument document = doc(RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertInvariants(document, chunks, tight.maxTokens());
    }

    @Test
    @DisplayName("单个句子本身超过上限时按窗口硬切,仍不越界且不丢内容")
    void singleOversizedSentenceIsCutByWindow() {
        ChunkingProperties tight = new ChunkingProperties(10, 20, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(tight);
        // 没有任何句末标点 => 整块只有一句,走 shrinkToFit 分支
        String unbroken = "测试".repeat(150);
        ParsedDocument document = doc(RawBlock.paragraph(unbroken));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertInvariants(document, chunks, tight.maxTokens());
        StringBuilder reassembled = new StringBuilder();
        chunks.forEach(chunk -> reassembled.append(chunk.content()));
        assertThat(reassembled.toString())
                .as("硬切之后拼回来必须与原文一致,不能丢字符")
                .isEqualTo(unbroken);
    }

    @Test
    @DisplayName("overlap 让后一片的起点前移,且下标与内容仍然一致")
    void overlapShiftsCharStartBackwards() {
        ChunkingProperties withOverlap = new ChunkingProperties(20, 400, 12);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(withOverlap);
        ParsedDocument document = doc(
                RawBlock.paragraph(body(20)),
                RawBlock.paragraph(body(20)));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        ChunkDraft second = chunks.get(1);
        assertThat(second.charStart())
                .as("补了重叠之后,第二片的起点必须前移进第一片")
                .isLessThan(chunks.get(0).charEnd());
        assertInvariants(document, chunks, withOverlap.maxTokens());
    }

    @Test
    @DisplayName("overlap 为 0 时相邻片不重叠")
    void zeroOverlapKeepsChunksDisjoint() {
        ChunkingProperties noOverlap = new ChunkingProperties(20, 400, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(noOverlap);
        ParsedDocument document = doc(
                RawBlock.paragraph(body(20)),
                RawBlock.paragraph(body(20)));

        List<ChunkDraft> chunks = splitter.split(document);

        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).charStart())
                    .isGreaterThanOrEqualTo(chunks.get(i - 1).charEnd());
        }
    }

    @Test
    @DisplayName("连续两级标题必须整串带到下一片,不能把上一个标题留在片尾")
    void consecutiveHeadingsAreCarriedTogether() {
        ChunkingProperties small = new ChunkingProperties(20, 400, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(small);
        ParsedDocument document = doc(
                RawBlock.heading(1, "第一章"),
                RawBlock.paragraph(body(30)),
                RawBlock.heading(2, "小节二"),
                RawBlock.heading(3, "2.1 细目"),
                RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        // 只搬最后一个标题是错的:H2 会被留在上一片结尾,和它的正文被边界分开
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.content().strip())
                    .as("任何一片都不该以标题结尾")
                    .doesNotEndWith("第一章")
                    .doesNotEndWith("小节二")
                    .doesNotEndWith("2.1 细目");
        }
        assertThat(chunks)
                .as("相邻两级标题与它们的正文应落在同一片里")
                .anySatisfy(chunk -> assertThat(chunk.content())
                        .contains("小节二")
                        .contains("2.1 细目"));
        assertInvariants(document, chunks, small.maxTokens());
    }

    @Test
    @DisplayName("标题后面紧跟超长块时,标题并入第一片开头而不是单独成片")
    void headingIsPrependedToFirstPieceOfOversizedBlock() {
        ChunkingProperties tight = new ChunkingProperties(10, 20, 0);
        StructureAwareChunkSplitter splitter = new StructureAwareChunkSplitter(tight);
        ParsedDocument document = doc(
                RawBlock.heading(2, "超长章节"),
                RawBlock.paragraph(body(30)));

        List<ChunkDraft> chunks = splitter.split(document);

        assertThat(chunks.get(0).content())
                .as("第一片必须以标题开头,标题不能单独成片")
                .startsWith("超长章节");
        assertInvariants(document, chunks, tight.maxTokens());
    }

    @Test
    @DisplayName("默认参数下,真实 PDF 样本的每个切片都能按下标取回原文")
    void realSampleSatisfiesInvariants() throws Exception {
        java.nio.file.Path sample = java.nio.file.Path.of("..", "samples", "rag-notes.pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(sample),
                "样本文件不存在时跳过");

        com.ragkb.parse.ParserRegistry registry = new com.ragkb.parse.ParserRegistry(List.of(
                new com.ragkb.parse.PdfParser(),
                new com.ragkb.parse.DocxParser(),
                new com.ragkb.parse.MarkdownParser(),
                new com.ragkb.parse.PlainTextParser()));
        ParsedDocument document;
        try (var in = java.nio.file.Files.newInputStream(sample)) {
            document = ParsedDocument.of(registry.require("pdf").parse(in));
        }

        ChunkingProperties properties = ChunkingProperties.defaults();
        List<ChunkDraft> chunks = new StructureAwareChunkSplitter(properties).split(document);

        assertThat(chunks).isNotEmpty();
        assertInvariants(document, chunks, properties.maxTokens());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.sectionPath()).isNotNull());
    }
}
