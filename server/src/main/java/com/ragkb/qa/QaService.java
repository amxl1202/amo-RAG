package com.ragkb.qa;

import com.ragkb.generation.AnswerGenerator;
import com.ragkb.generation.ChatException;
import com.ragkb.generation.ChatModel;
import com.ragkb.generation.Citation;
import com.ragkb.generation.CitationParser;
import com.ragkb.generation.Evidence;
import com.ragkb.generation.EvidenceEvaluator;
import com.ragkb.generation.GeneratedAnswer;
import com.ragkb.generation.ParsedAnswer;
import com.ragkb.generation.Prompt;
import com.ragkb.generation.PromptBuilder;
import com.ragkb.retrieval.HybridRetriever;
import com.ragkb.retrieval.RrfFuser;
import com.ragkb.vector.ScoredChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次问答的编排:检索 → 门控 → 拼提示词 → 生成 → 解析引用 → 自检。
 *
 * <h2>这一层只做顺序与短路</h2>
 * 每一步的实现都在各自包里,这里不重复它们的判断逻辑。唯一的职责是决定
 * <b>哪一步可以跳过</b>:两路全空时跳过生成与自检,门控拒答时跳过生成,
 * 模型自述材料不足时跳过自检。每一次跳过都是省下的一次外部调用。
 *
 * <h2>为什么门控要用"两路原始结果"而不是融合结果</h2>
 * 融合后的分数是名次倒数,已经失去"像不像"的信息;判断证据够不够,
 * 需要的是向量通道的余弦相似度与关键词通道的覆盖率。所以先把
 * {@link HybridRetriever.HybridResult} 原样交给门控,再融合取前 N 条喂模型。
 *
 * <h2>降级怎么处理</h2>
 * 单通道降级时另一路仍然可用,照常作答,但把 {@code degraded} 标出来 ——
 * 跑批时"这一题答得差"和"这一题那一路挂了"必须能分开。
 */
public class QaService {

    /** RRF 的平滑常数。取论文默认值:三组实验(k=0/20/60)在本语料上结果一致,没有数据支持别的选择。 */
    public static final int RRF_K = 60;

    private final HybridRetriever retriever;
    private final RrfFuser fuser;
    private final PromptBuilder promptBuilder;
    private final AnswerGenerator generator;
    private final EvidenceEvaluator evaluator;
    private final ChatModel chatModel;
    private final DocumentTitles titles;
    private final int channelLimit;

    public QaService(HybridRetriever retriever, RrfFuser fuser, PromptBuilder promptBuilder,
                     AnswerGenerator generator, EvidenceEvaluator evaluator, ChatModel chatModel,
                     DocumentTitles titles, int channelLimit) {
        if (channelLimit <= 0) {
            throw new IllegalArgumentException("channelLimit 必须为正: " + channelLimit);
        }
        this.retriever = retriever;
        this.fuser = fuser;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.evaluator = evaluator;
        this.chatModel = chatModel;
        this.titles = titles;
        this.channelLimit = channelLimit;
    }

    /**
     * 回答一个问题。
     *
     * @param question 问题原文
     * @return 答案与全部中间量;模型故障时返回 {@link QaAnswer.Status#FAILED},不抛异常
     */
    public QaAnswer ask(String question, long tenantId) {
        return ask(question, tenantId, delta -> {
        });
    }

    /**
     * 流式版:生成过程中的增量通过 {@code onDelta} 流出,返回值与 {@link #ask(String)} 完全一致。
     *
     * <h2>增量流的是"未定稿文本"</h2>
     * 生成结束后还有自检,被判定无支撑的句子会被删掉。所以这里推出去的增量只能算
     * "候选答案",调用方(SSE 层)必须把它标成待校验,并在拿到返回值后用定稿文本覆盖。
     * 这一点是这个项目与"纯流式"的根本差别:承诺可溯源,就不能在自检之前假装定稿。
     *
     * @param onDelta 增量回调,按模型产出顺序调用
     */
    public QaAnswer ask(String question, long tenantId, java.util.function.Consumer<String> onDelta) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        long retrievalStarted = System.nanoTime();
        HybridRetriever.HybridResult hits = retriever.search(question, channelLimit, tenantId);
        long retrievalMillis = (System.nanoTime() - retrievalStarted) / 1_000_000;

        EvidenceEvaluator.RetrievalSignals signals = new EvidenceEvaluator.RetrievalSignals(
                hits.vectorHits(), hits.keywordHits(), hits.vectorDegraded(), hits.keywordDegraded(),
                EvidenceEvaluator.maxKeywordScore(question));
        EvidenceEvaluator.Gate gate = evaluator.gate(signals);
        if (gate.refuse()) {
            // 证据明显不够:不拼提示词、不调模型、不做自检。
            // 但"两路都挂了"与"材料里没有"必须分成两种状态 —— 前者是服务故障,
            // 伪装成"材料中未提及"会让用户以为文档里真的没有,也会让跑批把故障记成正确拒答
            QaAnswer.Status status = gate.reason() == EvidenceEvaluator.Reason.CHANNELS_DOWN
                    ? QaAnswer.Status.FAILED
                    : QaAnswer.Status.REFUSED;
            String text = status == QaAnswer.Status.FAILED ? "" : PromptBuilder.INSUFFICIENT;
            return new QaAnswer(question, status, text, List.of(), gate, null, List.of(),
                    0, 0, hits.anyDegraded(), 0, 0, 0, 0, retrievalMillis, 0, null);
        }

        List<ScoredChunk> fused = fuser.fuse(hits.vectorHits(), hits.keywordHits(), RRF_K, channelLimit)
                .stream().map(RrfFuser.Hit::chunk).toList();
        List<Evidence> evidences = new ArrayList<>(fused.size());
        for (ScoredChunk chunk : fused) {
            evidences.add(Evidence.from(chunk, titles.titleOf(chunk.documentId(), tenantId)));
        }
        Prompt prompt = promptBuilder.build(question, evidences);

        long answerStarted = System.nanoTime();
        GeneratedAnswer generated;
        try {
            generated = generator.generate(prompt, onDelta);
        } catch (ChatException e) {
            // 服务故障与拒答是两件事:前者该重试/报警,后者是正确行为
            return new QaAnswer(question, QaAnswer.Status.FAILED, "",
                    List.of(), gate, null, List.of(), fused.size(), prompt.citations().size(),
                    hits.anyDegraded(), 0, 0, 0, 0, retrievalMillis,
                    (System.nanoTime() - answerStarted) / 1_000_000, null);
        }

        ParsedAnswer parsed = new CitationParser().parse(generated.text(), prompt);
        EvidenceEvaluator.Verdict verdict = evaluator.verify(prompt, parsed, chatModel);
        long answerMillis = (System.nanoTime() - answerStarted) / 1_000_000;

        // 引用列表必须跟着"留下来的句子"走:展示文本是 verdict.text(),
        // 若这里仍返回原答案的全部编号,前端就会出现"面板里挂着正文中找不到的编号" ——
        // 直接违反"每句话都能追溯"这条对用户的承诺
        List<Citation> cited = verdict.refuse() ? List.of()
                : parsed.citations().stream()
                        .filter(citation -> verdict.keptLabels().contains(citation.label()))
                        .toList();

        return new QaAnswer(question,
                verdict.refuse() ? QaAnswer.Status.REFUSED : QaAnswer.Status.ANSWERED,
                verdict.text(), cited,
                gate, verdict, parsed.unknownLabels(), fused.size(), prompt.citations().size(),
                hits.anyDegraded(), generated.promptTokens(), generated.completionTokens(),
                verdict.checkPromptTokens(), verdict.checkCompletionTokens(),
                retrievalMillis, answerMillis, generated.model());
    }
}
