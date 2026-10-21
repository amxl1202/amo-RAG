package com.ragkb.generation;

import com.ragkb.retrieval.QueryTerms;
import com.ragkb.vector.ScoredChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 证据评估与两阶段拒答。
 *
 * <h2>为什么两阶段</h2>
 * 拒答发生在两个不同的时刻,代价也完全不同:
 * <ul>
 *   <li><b>阶段一(检索后)</b>:证据明显不够,直接返回"查不到",<b>省掉一次模型调用</b>。
 *       这一阶段只能看检索分数,判错了会误拒 —— 误拒比编造更让人恼火,所以阈值要保守。</li>
 *   <li><b>阶段二(生成后)</b>:模型已经答了,逐句检查每句话有没有材料支撑,
 *       把无支撑的句子剔掉;整段都被剔掉就整体拒答。这一阶段能看到答案,
 *       能抓住"检索看起来像、但材料其实不支持"的情况。</li>
 * </ul>
 * 只做阶段一会漏掉"检索命中但答非所问",只做阶段二则每次提问都白花一次模型调用。
 *
 * <h2>为什么阶段一不能直接拿融合分比阈值</h2>
 * 融合结果是 RRF 分,数量级约 {@code 1/(k+rank)},对 k=60 而言满分也就 0.0327×2。
 * 拿它跟 0.5 这样的相似度阈值比,阈值永远不触发,拒答看起来"完全失效"。
 * 能比阈值的只有<b>向量通道的原始余弦相似度</b>,所以这里只取向量通道的 top-1。
 *
 * <h2>为什么还要看关键词通道</h2>
 * 本项目的语料是中文技术笔记,实测纯向量通道的 MRR 反而最差(0.8167 对 0.9167):
 * 专有名词靠字面就能命中,而语义向量会把"意思相近"的片段挤上来。
 * 只看相似度会把"关键词精准命中、但语义相似度不高"的问题误拒掉,
 * 所以再加一条"问题词被覆盖的比例"作为第二判据 —— 两条都不达标才拒。
 *
 * <h2>阈值从哪来</h2>
 * 两个阈值都不能拍。做法是:对评估集里每道题记录向量 top-1 与关键词覆盖率,
 * 看文档内问题与文档外问题两组数是否分得开,取两组之间留有余量的位置。
 * 校准工具输出的表必须随阈值一起留档,否则半年后没人说得清 0.35 是怎么来的。
 * 当前值见 {@link Thresholds#calibrated()} 的注释。
 */
public class EvidenceEvaluator {

    /**
     * 阶段一的两个阈值。
     *
     * @param minVectorSimilarity 向量通道 top-1 余弦相似度下限
     * @param minKeywordCoverage  关键词通道 top-1 对问题词的覆盖率下限(0~1)
     */
    public record Thresholds(double minVectorSimilarity, double minKeywordCoverage) {

        public Thresholds {
            if (minVectorSimilarity < 0 || minVectorSimilarity > 1) {
                throw new IllegalArgumentException("相似度阈值应在 0~1: " + minVectorSimilarity);
            }
            if (minKeywordCoverage < 0 || minKeywordCoverage > 1) {
                throw new IllegalArgumentException("关键词覆盖率阈值应在 0~1: " + minKeywordCoverage);
            }
        }

        /**
         * 校准前的初始值,<b>尚未由数据决定</b>。
         *
         * 之所以留一个能跑的初值而不是 0:阈值取 0 等于阶段一永不触发,
         * 那样"两阶段拒答"就只剩一阶段,而这件事在代码里看不出来。
         * 校准之后必须把这里与 {@code docs/eval-baseline.md} 的数字对齐。
         */
        public static Thresholds calibrated() {
            return new Thresholds(0.45, 0.5);
        }
    }

    /**
     * 检索阶段能拿到的全部信号。
     *
     * @param vectorHits      向量通道命中(降级时为空)
     * @param keywordHits     关键词通道命中(降级时为空)
     * @param vectorDegraded  向量通道是否超时或异常
     * @param keywordDegraded 关键词通道是否超时或异常
     * @param keywordMaxScore 关键词通道的理论满分,用于把原始分归一到 0~1
     */
    public record RetrievalSignals(
            List<ScoredChunk> vectorHits,
            List<ScoredChunk> keywordHits,
            boolean vectorDegraded,
            boolean keywordDegraded,
            double keywordMaxScore) {

        public RetrievalSignals {
            vectorHits = List.copyOf(vectorHits);
            keywordHits = List.copyOf(keywordHits);
        }

        /** 向量通道 top-1 相似度;无命中记 0。 */
        public double vectorTop1() {
            return vectorHits.isEmpty() ? 0 : vectorHits.get(0).score();
        }

        /** 关键词通道 top-1 覆盖率;无命中或满分未知时记 0。 */
        public double keywordCoverage() {
            if (keywordHits.isEmpty() || keywordMaxScore <= 0) {
                return 0;
            }
            return Math.min(1.0, keywordHits.get(0).score() / keywordMaxScore);
        }

        public boolean anyDegraded() {
            return vectorDegraded || keywordDegraded;
        }
    }

    /** 阶段一的判定原因。带上原因才能统计"拒答是被谁触发的"。 */
    public enum Reason {
        /** 不该拒答。 */
        NONE,
        /** 两路都没命中。 */
        NO_HITS,
        /** 两条判据都没达标。 */
        WEAK_EVIDENCE,
        /** 模型自己说材料不足。 */
        MODEL_INSUFFICIENT,
        /** 逐句自检把每一句都剔光了。 */
        UNSUPPORTED,
        /** 自检本身失败,无法判断 —— 这时不拒答,见 {@link Verdict#unavailable()}。 */
        CHECK_UNAVAILABLE
    }

    /**
     * 阶段一的结论。
     *
     * @param refuse         是否应当直接拒答
     * @param reason         原因
     * @param vectorTop1     本次向量 top-1,记进日志与报告
     * @param keywordCoverage 本次关键词覆盖率
     */
    public record Gate(boolean refuse, Reason reason, double vectorTop1, double keywordCoverage) {
    }

    /**
     * 阶段二的结论。
     *
     * @param refuse      最终是否拒答
     * @param reason      原因
     * @param text        可以展示的答案文本;拒答时是拒答固定串
     * @param dropped     被剔除的句子数
     * @param kept        保留的句子数
     * @param unavailable 自检调用失败。此时保留原答案、不拒答 ——
     *                    自检是"更严格"的附加检查,它坏了不该让系统开始乱拒答
     */
    public record Verdict(
            boolean refuse,
            Reason reason,
            String text,
            int dropped,
            int kept,
            boolean unavailable) {
    }

    private final Thresholds thresholds;

    public EvidenceEvaluator(Thresholds thresholds) {
        this.thresholds = thresholds == null ? Thresholds.calibrated() : thresholds;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    /**
     * 阶段一:检索后判断证据是否明显不够。
     *
     * @param signals 两路命中与降级标记
     */
    public Gate gate(RetrievalSignals signals) {
        if (signals.vectorHits().isEmpty() && signals.keywordHits().isEmpty()) {
            // 两路全空:要么语料里真的没有,要么两个通道都挂了。两种情况都不该调模型
            return new Gate(true, Reason.NO_HITS, 0, 0);
        }
        double similarity = signals.vectorTop1();
        double coverage = signals.keywordCoverage();
        if (similarity >= thresholds.minVectorSimilarity()
                || coverage >= thresholds.minKeywordCoverage()) {
            return new Gate(false, Reason.NONE, similarity, coverage);
        }
        return new Gate(true, Reason.WEAK_EVIDENCE, similarity, coverage);
    }

    /**
     * 关键词通道的理论满分:问题抽出词的词长之和。
     *
     * 与 {@code JdbcKeywordRetriever} 的打分口径一致(命中词按词长加权求和),
     * 所以两者相除就是"问题里的概念被这条切片覆盖了多少"。口径必须对齐 ——
     * 用词数而不是词长去除,覆盖率会随词的构成漂移。
     */
    public static double maxKeywordScore(String question) {
        return QueryTerms.extract(question).stream().mapToInt(String::length).sum();
    }

    /**
     * 阶段二:逐句检查答案有无材料支撑。
     *
     * 模型已经拒答时不再自检:它说的是"材料中没有",而自检要判的对象是句子,
     * 没有句子可判,再调一次只是白花钱。
     *
     * @param prompt   本次提示词(编号表与材料都在里面)
     * @param answer   已解析的答案
     * @param checker  自检用的模型调用
     */
    public Verdict verify(Prompt prompt, ParsedAnswer answer, ChatModel checker) {
        if (answer == null || prompt == null) {
            throw new IllegalArgumentException("自检需要提示词与已解析的答案");
        }
        if (answer.sentences().isEmpty()) {
            return new Verdict(true, Reason.MODEL_INSUFFICIENT, PromptBuilder.INSUFFICIENT, 0, 0, false);
        }
        if (AnswerGenerator.looksLikeRefusal(answer.raw())) {
            return new Verdict(true, Reason.MODEL_INSUFFICIENT, PromptBuilder.INSUFFICIENT, 0, 0, false);
        }

        List<ParsedSentence> judgeable = answer.sentences().stream().filter(s -> !s.blank()).toList();
        TreeSet<Integer> unsupported;
        try {
            unsupported = askUnsupported(prompt, judgeable, checker);
        } catch (ChatException e) {
            // 自检坏了不能变成"系统开始拒答":保留原答案,把 unavailable 标出来
            return new Verdict(false, Reason.CHECK_UNAVAILABLE, answer.sanitized(),
                    0, judgeable.size(), true);
        }

        List<ParsedSentence> kept = new ArrayList<>();
        for (int i = 0; i < judgeable.size(); i++) {
            if (!unsupported.contains(i + 1)) {
                kept.add(judgeable.get(i));
            }
        }
        if (kept.isEmpty()) {
            return new Verdict(true, Reason.UNSUPPORTED, PromptBuilder.INSUFFICIENT,
                    judgeable.size(), 0, false);
        }
        StringBuilder text = new StringBuilder();
        for (ParsedSentence sentence : kept) {
            // 用 text 而不是 plain:编号要留给前端,非法编号在解析阶段已经删掉
            text.append(sentence.text());
        }
        return new Verdict(false, Reason.NONE, text.toString().strip(),
                judgeable.size() - kept.size(), kept.size(), false);
    }

    /**
     * 问模型"哪些句子没有材料支撑",返回句子序号(1 基)。
     *
     * <h2>为什么只让它输出"不支持"的名单</h2>
     * 若要求逐句给出"支持/不支持",模型漏答一句就等于没判 —— 而漏答是常态。
     * 只让列举不支持的句子,漏答的语义变成"支持",这与"宁可少删"的取舍一致:
     * 误删一句真答案比漏删一句无支撑的话更糟,前者用户直接看不到答案。
     *
     * @throws ChatException 调用失败或返回不可解析 —— 由调用方决定降级方式
     */
    private TreeSet<Integer> askUnsupported(Prompt prompt, List<ParsedSentence> sentences, ChatModel checker) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            numbered.append(i + 1).append(". ").append(sentences.get(i).plain().strip()).append('\n');
        }
        String systemPrompt = """
                你是严格的审稿人。你只做一件事:判断给定句子能否由「材料」直接支撑。
                判断标准:句子的内容必须能在材料里找到对应表述;需要额外常识、推测、概括才能成立的,视为没有支撑。
                只输出 JSON,不要输出任何解释、前后缀或代码块标记。格式:{"unsupported":[2,5]}
                全部都有支撑时输出 {"unsupported":[]}。
                """;
        String userPrompt = "材料:\n" + prompt.material()
                + "\n\n待判定句子:\n" + numbered
                + "请输出没有材料支撑的句子序号。";

        ChatResponse response = checker.complete(systemPrompt, userPrompt);
        return parseUnsupported(response.content(), sentences.size());
    }

    /**
     * 从模型输出里抽出 unsupported 数组。
     *
     * 容忍代码块围栏与前后废话 —— 只取第一个 '{' 到最后一个 '}'。
     * 越界序号直接丢弃:模型有时会数到不存在的句子,照着删会把真答案删掉。
     */
    private static TreeSet<Integer> parseUnsupported(String content, int sentenceCount) {
        TreeSet<Integer> result = new TreeSet<>();
        if (content == null || content.isBlank()) {
            throw new ChatException("自检返回空内容", false);
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ChatException("自检输出不是 JSON: " + content, false);
        }
        String json = content.substring(start, end + 1);
        Optional<int[]> indexes = extractArray(json);
        if (indexes.isEmpty()) {
            throw new ChatException("自检输出缺少 unsupported 字段: " + json, false);
        }
        for (int index : indexes.get()) {
            if (index >= 1 && index <= sentenceCount) {
                result.add(index);
            }
        }
        return result;
    }

    /** 只解析一个整数数组,不引入完整 JSON 依赖的取舍:结构固定,解析器越小越难出错。 */
    private static Optional<int[]> extractArray(String json) {
        int key = json.toLowerCase(Locale.ROOT).indexOf("unsupported");
        if (key < 0) {
            return Optional.empty();
        }
        int open = json.indexOf('[', key);
        int close = json.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return Optional.empty();
        }
        String body = json.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return Optional.of(new int[0]);
        }
        List<Integer> values = new ArrayList<>();
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                // 出现非数字说明不是我们要求的格式,按"无法解析"处理,不猜
                return Optional.empty();
            }
        }
        int[] array = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return Optional.of(array);
    }
}
