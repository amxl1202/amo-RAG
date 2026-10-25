package com.ragkb.metrics;

import com.ragkb.metrics.mapper.QaRecordMapper;
import com.ragkb.qa.QaAnswer;
import com.ragkb.qa.QaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * 把每次问答记成一行,供成本与延迟画像使用。
 *
 * <h2>为什么是"异步且失败不影响回答"</h2>
 * 埋点是旁路:它挂了不该让用户看不到答案。所以写入扔到独立执行器里,
 * 异常只记日志;反问一句"那数据丢了呢"——丢了就丢了,一行统计比一次失败的问答便宜得多。
 * 这是"可观测性不能反过来影响可用性"的具体落点。
 *
 * <h2>为什么只有接口层调用它</h2>
 * 评估跑批直接调 {@code QaService},不该把 59 条固定用例混进线上流量 ——
 * 否则"线上拒答率"与"评估拒答率"会变成同一张表里的两个来源,再也分不开。
 */
@Slf4j
@Service
public class QaRecordService {

    private final QaRecordMapper mapper;
    private final ExecutorService executor;

    public QaRecordService(QaRecordMapper mapper,
                           @Qualifier("qaRecordExecutor") ExecutorService executor) {
        this.mapper = mapper;
        this.executor = executor;
    }

    /**
     * 异步记录一次问答。
     *
     * @param question 问题原文(用于事后看"哪些问题答得差")
     * @param answer   编排层的完整结果,含生成与自检两侧的 token
     */
    public void recordAsync(String question, QaAnswer answer) {
        QaRecordEntity entity = toEntity(question, answer);
        try {
            executor.submit(() -> {
                try {
                    mapper.insert(entity);
                } catch (RuntimeException e) {
                    log.warn("qa record insert failed, ignored: {}", e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // 执行器已关闭(应用停机中)时只记一笔,不影响正在返回的响应
            log.warn("qa record submit rejected: {}", e.getMessage());
        }
    }

    private static QaRecordEntity toEntity(String question, QaAnswer answer) {
        QaRecordEntity entity = new QaRecordEntity();
        entity.setQuestion(question);
        entity.setStatus(answer.status().name());
        entity.setReason(answer.reason() == null ? null : answer.reason().name());
        entity.setPromptTokens(answer.promptTokens());
        entity.setCompletionTokens(answer.completionTokens());
        entity.setCheckPromptTokens(answer.checkPromptTokens());
        entity.setCheckCompletionTokens(answer.checkCompletionTokens());
        entity.setRetrievalMs((int) Math.min(Integer.MAX_VALUE, answer.retrievalMillis()));
        entity.setAnswerMs((int) Math.min(Integer.MAX_VALUE, answer.answerMillis()));
        entity.setCitationCount(answer.citations().size());
        entity.setModel(null);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
