package com.ragkb.chunk;

import com.ragkb.parse.ParsedDocument;
import com.ragkb.parse.StructuralBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 结构感知切片器:按文档自带的标题层级与段落边界切片,并把标题"粘"给它所领起的正文。
 *
 * 分四步,每一步单独看都朴素,难点全在它们的相互作用:
 * <ol>
 *   <li><b>标注章节</b>:维护一个标题栈,给每个结构块算出它所属的章节路径。
 *       层级会跳变(H1 直接到 H3),所以弹出条件是"栈顶层级不低于当前层级",
 *       不能假设层级连续递减。</li>
 *   <li><b>合并到目标大小</b>:把相邻块累积到接近 target 再封口。两个例外:
 *       缓冲里全是标题时不封口(否则会产出只有标题的碎片);
 *       封口时若最后一块是标题,把它留给下一片(标题必须领起下文)。</li>
 *   <li><b>硬切超长块</b>:单个块超过 max 时,在块内按句末标点切,每片都不超过 max。
 *       这一条是"最长段落不会被切坏"的保证,但它依赖 max 本身不小于语料最长段落。</li>
 *   <li><b>补重叠</b>:从上一片尾部取回若干完整句子,接到这一片开头。</li>
 * </ol>
 *
 * <b>第四步的关键坑</b>:补了重叠之后,这一片的内容比它原本 charStart 指向的位置更长,
 * 所以 charStart 必须连同内容一起往前移。只改内容不改下标,引用就会指到别处 ——
 * 这是"下标可回溯"最容易破的地方。
 *
 * 为什么不跳过空白块:{@link ChunkDraft} 会对空白内容抛异常。静默丢掉一段内容比当场报错
 * 危险得多,而且 {@link ParsedDocument} 已保证不会产出空白块;真出现了说明上游有 bug,应该炸出来。
 *
 * 一处已知的例外:文档末尾若只剩标题(最后一块就是标题),尾片会是"只有标题"的一片。
 * 它不违反接口那四条硬约束(非空、下标合法、不超上限)。
 * 处理上有三个选项,不是两个:丢掉尾标题(直接丢内容)、让它独占一片(当前选择)、
 * 并入前一片(不丢内容,但会让前一片的内容跨进新章节、sectionPath 语义失真,并且可能超上限)。
 * 选中间那个的理由是对下游最不意外:片边界仍然落在结构边界上,不会出现"一片横跨两章"。
 */
public class StructureAwareChunkSplitter implements ChunkSplitter {

    private static final String SECTION_SEPARATOR = " > ";

    private final TokenCounter tokenCounter = new TokenCounter();
    private final ChunkingProperties properties;

    /** 预览工具通过反射调用无参构造来实例化,这个构造器不能删。 */
    public StructureAwareChunkSplitter() {
        this(ChunkingProperties.defaults());
    }

    public StructureAwareChunkSplitter(ChunkingProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<ChunkDraft> split(ParsedDocument document) {
        if (document.isEmpty()) {
            return List.of();
        }
        List<SectionedBlock> units = annotateSections(document.blocks());
        List<ChunkDraft> merged = merge(units, document);
        return applyOverlap(merged, document);
    }

    //第一步:章节标注
    private List<SectionedBlock> annotateSections(List<StructuralBlock> blocks) {
        List<SectionedBlock> units = new ArrayList<>(blocks.size());
        // 栈顶永远是当前最深一级的标题
        Deque<StructuralBlock> headingStack = new ArrayDeque<>();
        for (StructuralBlock block : blocks) {
            if (block.isHeading()) {
                int level = clampLevel(block.headingLevel());
                // 用 >= 而不是 == :层级会跳变,不能假设逐级递减
                while (!headingStack.isEmpty() && clampLevel(headingStack.peek().headingLevel()) >= level) {
                    headingStack.pop();
                }
                headingStack.push(block);
            }
            units.add(new SectionedBlock(block, sectionPathOf(headingStack), sectionLevelOf(headingStack)));
        }
        return units;
    }

    private String sectionPathOf(Deque<StructuralBlock> headingStack) {
        if (headingStack.isEmpty()) {
            return null;
        }
        // 迭代顺序是栈顶→栈底(深→浅),路径要浅在前,所以反过来
        List<String> parts = new ArrayList<>(headingStack.size());
        for (StructuralBlock heading : headingStack) {
            parts.add(heading.text());
        }
        Collections.reverse(parts);
        return String.join(SECTION_SEPARATOR, parts);
    }

    private int sectionLevelOf(Deque<StructuralBlock> headingStack) {
        return headingStack.isEmpty() ? 0 : clampLevel(headingStack.peek().headingLevel());
    }

    private static int clampLevel(int level) {
        return Math.min(6, Math.max(1, level));
    }

    //第二、三步:合并与硬切

    private List<ChunkDraft> merge(List<SectionedBlock> units, ParsedDocument document) {
        List<ChunkDraft> drafts = new ArrayList<>();
        List<SectionedBlock> buffer = new ArrayList<>();
        int bufferTokens = 0;

        for (SectionedBlock unit : units) {
            int unitTokens = tokenCounter.count(unit.block().text());

            // 两条路径都要求"把前面的标题串并进第一片":
            // ① 单个块本身超过硬上限;
            // ② 缓冲里全是标题,且标题串 + 这一块也超过硬上限 ——
            //    这时既不能封口(会留下只有标题的碎片),也不能直接拼(会超上限),
            //    只能让第一片少装一些,把额度让给标题。
            //
            // 判定②不能用"标题 token + 块 token"的朴素加法:两段拼接后的计数可能比各自之和多
            // 1~2 个(边界处的分词合并方式变了),于是"朴素和恰好等于 max"这条窄带会被漏判。
            // 实测:head 48 + body 352,朴素和 400 看似没超,拼接后实测 401,产出了一片 401 token。
            // 所以触发条件也按拼接后的真实区间实测 —— 与修复内部用的是同一个口径。
            boolean headingsPlusUnitTooLarge = false;
            if (!buffer.isEmpty() && !containsBody(buffer)) {
                String combined = document.slice(buffer.get(0).block().charStart(), unit.block().charEnd());
                headingsPlusUnitTooLarge = tokenCounter.count(combined) > properties.maxTokens();
            }
            if (unitTokens > properties.maxTokens() || headingsPlusUnitTooLarge) {
                emitHeadingPrefixedUnit(drafts, buffer, unit, document);
                bufferTokens = 0;
                continue;
            }

            boolean overTarget = !buffer.isEmpty() && bufferTokens + unitTokens > properties.targetTokens();
            // 缓冲里全是标题时不封口,避免产出"只有标题"的碎片
            if (overTarget && !containsBody(buffer)) {
                overTarget = false;
            }
            if (overTarget) {
                // 末尾可能压着一串标题(H2 紧跟 H3),要整串带去下一片。
                // 只搬最后一个的话,上一个标题会留在这一片结尾,和它的正文生生分开。
                List<SectionedBlock> carriedHeadings = takeTrailingHeadings(buffer);
                flush(drafts, buffer, document);
                buffer.clear();
                bufferTokens = 0;
                for (SectionedBlock heading : carriedHeadings) {
                    buffer.add(heading);
                    bufferTokens += tokenCounter.count(heading.block().text());
                }
            }
            buffer.add(unit);
            bufferTokens += unitTokens;
        }
        flush(drafts, buffer, document);
        return drafts;
    }

    /**
     * 把"一串标题 + 一个块"输出成一片或若干片:标题并进第一片,第一片的可用额度相应减少,
     * 从而保证任何一片都不超过 max。
     *
     * 调用方负责保证 unit 前面的缓冲里没有正文 —— 有正文的情况应先封口,不能并进来。
     */
    private void emitHeadingPrefixedUnit(List<ChunkDraft> drafts, List<SectionedBlock> buffer,
                                         SectionedBlock unit, ParsedDocument document) {
        List<SectionedBlock> carriedHeadings = takeTrailingHeadings(buffer);
        flush(drafts, buffer, document);
        buffer.clear();

        int reserved = 0;
        for (SectionedBlock heading : carriedHeadings) {
            reserved += tokenCounter.count(heading.block().text());
        }
        List<ChunkDraft> pieces = hardSplit(unit, reserved);

        if (!carriedHeadings.isEmpty()) {
            int prefixStart = carriedHeadings.get(0).block().charStart();
            // token 数<b>不可相加</b>:标题与正文各自计数之和,不等于拼起来之后的计数
            // (实测会多 1 个,因为边界处的合并方式变了)。所以不能只按 reserved 扣额度,
            // 要用"并上前缀之后的实测总量"回调预算,直到真的不超上限。
            for (int guard = 0; guard < 8; guard++) {
                int actual = tokenCounter.count(document.slice(prefixStart, pieces.get(0).charEnd()));
                if (actual <= properties.maxTokens()) {
                    break;
                }
                reserved += actual - properties.maxTokens();
                if (reserved >= properties.maxTokens()) {
                    break;
                }
                pieces = hardSplit(unit, reserved);
            }
        }

        for (int k = 0; k < pieces.size(); k++) {
            ChunkDraft piece = pieces.get(k);
            if (k == 0 && !carriedHeadings.isEmpty()) {
                // 标题必须领起下文:它的字符区间紧挨着这一块,把起点往前挪就能一并取回
                piece = pieceStartingAt(document, piece, carriedHeadings.get(0).block().charStart());
            }
            drafts.add(piece.withIndex(drafts.size()));
        }
    }

    /**
     * 把缓冲区末尾的标题整串取出来(连同"缓冲动里全是标题"的情况),留给下一片。
     * 取出来的标题按原顺序返回,调用方负责把它们接到下一片开头。
     */
    private List<SectionedBlock> takeTrailingHeadings(List<SectionedBlock> buffer) {
        List<SectionedBlock> carried = new ArrayList<>();
        while (buffer.size() > 1 && lastIsHeading(buffer)) {
            carried.add(0, buffer.remove(buffer.size() - 1));
        }
        if (!buffer.isEmpty() && !containsBody(buffer)) {
            carried.addAll(0, buffer);
            buffer.clear();
        }
        return carried;
    }

    /** 把缓冲区的块封成一片。内容直接从原文切,保证 {@code slice(charStart, charEnd)} 能取回它。 */
    private void flush(List<ChunkDraft> drafts, List<SectionedBlock> buffer, ParsedDocument document) {
        if (buffer.isEmpty()) {
            return;
        }
        SectionedBlock head = buffer.get(0);
        StructuralBlock first = head.block();
        StructuralBlock last = buffer.get(buffer.size() - 1).block();
        String content = document.slice(first.charStart(), last.charEnd());
        drafts.add(new ChunkDraft(
                drafts.size(),
                head.sectionPath(),
                head.sectionLevel(),
                content,
                tokenCounter.count(content),
                first.charStart(),
                last.charEnd(),
                first.page(),
                last.page()));
    }

    /**
     * 把一个超长块切成若干不超过 max 的片,切点优先落在句末标点上。
     *
     * @param reservedTokens 要并到第一片开头的标题占用,第一片的可用额度要相应减少
     */
    private List<ChunkDraft> hardSplit(SectionedBlock unit, int reservedTokens) {
        StructuralBlock block = unit.block();
        String text = block.text();
        int base = block.charStart();
        List<int[]> sentences = sentenceBounds(text);
        List<ChunkDraft> pieces = new ArrayList<>();

        int i = 0;
        while (i < sentences.size()) {
            // 第一片要给前面那串标题留出额度,否则并上标题后就超上限了
            int budget = pieces.isEmpty()
                    ? Math.max(1, properties.maxTokens() - reservedTokens)
                    : properties.maxTokens();
            int start = sentences.get(i)[0];
            int end = sentences.get(i)[1];
            int next = i + 1;
            // 尽量把后面的句子并进来,一旦超额度就停
            while (next < sentences.size()) {
                int candidateEnd = sentences.get(next)[1];
                if (tokenCounter.count(text.substring(start, candidateEnd)) > budget) {
                    break;
                }
                end = candidateEnd;
                next++;
            }
            if (next == i + 1 && tokenCounter.count(text.substring(start, end)) > budget) {
                // 单句自己就超上限:只能按窗口硬切,接受语句被截断
                int from = start;
                while (from < end) {
                    int to = shrinkToFit(text, from, end, budget);
                    pieces.add(pieceOf(unit, text.substring(from, to), base + from, base + to));
                    from = to;
                }
            } else {
                pieces.add(pieceOf(unit, text.substring(start, end), base + start, base + end));
            }
            i = next;
        }
        return pieces;
    }

    /** 从 from 开始取一段不超过 budget 的最长前缀,[from, limit) 内保证有进展。 */
    private int shrinkToFit(String text, int from, int limit, int budget) {
        int length = Math.min(limit - from, budget);
        while (length > 1 && tokenCounter.count(text.substring(from, from + length)) > budget) {
            length = length * 9 / 10;
        }
        return from + Math.max(1, length);
    }

    private ChunkDraft pieceOf(SectionedBlock unit, String content, int charStart, int charEnd) {
        return new ChunkDraft(
                0, // 占位,由调用方 withIndex 统一编号
                unit.sectionPath(),
                unit.sectionLevel(),
                content,
                tokenCounter.count(content),
                charStart,
                charEnd,
                unit.block().page(),
                unit.block().page());
    }

    /** 把一片的起点往前挪,内容按新区间重切一次 —— 用来把前面那串标题并到片首。 */
    private ChunkDraft pieceStartingAt(ParsedDocument document, ChunkDraft piece, int newStart) {
        if (newStart >= piece.charStart()) {
            return piece;
        }
        String content = document.slice(newStart, piece.charEnd());
        return new ChunkDraft(
                piece.index(),
                piece.sectionPath(),
                piece.headingLevel(),
                content,
                tokenCounter.count(content),
                newStart,
                piece.charEnd(),
                piece.pageStart(),
                piece.pageEnd());
    }

    //第四步:重叠

    /**
     * 给第二片之后的每一片补上"上一片尾部的若干完整句子"。
     *
     * 补完必须同步前移 charStart —— 否则内容变长了下标没变,引用会指错位置。
     * 若补完超过 max,就放弃这一片的重叠,而不是让单片超上限。
     */
    private List<ChunkDraft> applyOverlap(List<ChunkDraft> drafts, ParsedDocument document) {
        if (drafts.size() < 2 || properties.overlapTokens() == 0) {
            return drafts;
        }
        List<ChunkDraft> out = new ArrayList<>(drafts.size());
        out.add(drafts.get(0));
        for (int i = 1; i < drafts.size(); i++) {
            // 用未经重叠的上一片算重叠区,否则重叠会一层层累积下去
            ChunkDraft previous = drafts.get(i - 1);
            ChunkDraft current = drafts.get(i);

            int newStart = overlapStart(previous, current);
            if (newStart >= current.charStart()) {
                out.add(current);
                continue;
            }
            String content = document.slice(newStart, current.charEnd());
            if (tokenCounter.count(content) > properties.maxTokens()) {
                // 宁可这一片少一点重叠,也不让单片超上限
                out.add(current);
                continue;
            }
            out.add(new ChunkDraft(
                    i,
                    current.sectionPath(),
                    current.headingLevel(),
                    content,
                    tokenCounter.count(content),
                    newStart,
                    current.charEnd(),
                    current.pageStart(),
                    current.pageEnd()));
        }
        return out;
    }

    /** 从上一片尾部按完整句子往前取,直到够 overlapTokens;不越过上一片自己的起点。 */
    private int overlapStart(ChunkDraft previous, ChunkDraft current) {
        String previousText = previous.content();
        List<int[]> sentences = sentenceBounds(previousText);
        int offset = previousText.length();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            int sentenceStart = sentences.get(i)[0];
            offset = sentenceStart;
            if (tokenCounter.count(previousText.substring(sentenceStart)) >= properties.overlapTokens()) {
                break;
            }
        }
        int candidate = previous.charStart() + offset;
        return Math.max(previous.charStart(), Math.min(candidate, current.charStart()));
    }

    // ---------------------------------------------------------------- 工具

    /** 按句末标点切句,返回相对 text 的 [start, end) 偏移。 */
    private static List<int[]> sentenceBounds(String text) {
        List<int[]> bounds = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean sentenceEnd = c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '!' || c == '?' || c == ';' || c == '\n'
                    // 英文句点只在后面是空白或结尾时才算句末,否则会把 3.14 / example.com 切断
                    || (c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))));
            if (sentenceEnd) {
                bounds.add(new int[]{start, i + 1});
                start = i + 1;
            }
        }
        if (start < text.length()) {
            bounds.add(new int[]{start, text.length()});
        }
        return bounds;
    }

    private static boolean containsBody(List<SectionedBlock> buffer) {
        for (SectionedBlock unit : buffer) {
            if (!unit.block().isHeading()) {
                return true;
            }
        }
        return false;
    }

    private static boolean lastIsHeading(List<SectionedBlock> buffer) {
        return buffer.get(buffer.size() - 1).block().isHeading();
    }

    /** 结构块 + 它所属的章节信息。 */
    private record SectionedBlock(StructuralBlock block, String sectionPath, int sectionLevel) {
    }
}
