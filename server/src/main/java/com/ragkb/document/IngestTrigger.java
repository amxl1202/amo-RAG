package com.ragkb.document;

import com.ragkb.document.mapper.DocumentMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步触发入库。
 *
 * <p>上传接口不该等着解析跑完:20 页 PDF 的解析 + 切片是几百毫秒到几秒的事,
 * 让上传请求等着体验很差,而且超时重试会让同一份文件被解析多遍。
 *
 * <h2>为什么是有界队列</h2>
 * 第一版用 {@code Executors.newFixedThreadPool},队列无界 —— 突发上传会把任务无限堆在内存里,
 * 既没有背压也没有"队列有多深"的概念,直到 OOM 才暴露。现在队列满就<b>拒绝并标成 FAILED</b>:
 * 用户看到"稍后重试",而不是"上传成功但永远停在已上传"。
 *
 * <h2>为什么拒绝时不选 CallerRunsPolicy</h2>
 * 那个策略会让上传线程去解析 PDF —— 那不是背压,是把延迟转移给用户。
 * 上传接口本该快速返回,重活应当排队或被明确拒绝。
 *
 * <h2>为什么关闭用 shutdown 而不是 shutdownNow</h2>
 * {@code shutdownNow} 直接中断正在解析的文档,状态会永远停在 PARSING,
 * 下次启动时没人知道它做完没有。现在给 30 秒把手上的活干完,超时才强制中断。
 *
 * <h2>为什么还要启动恢复</h2>
 * 进程被强杀、机器断电、容器被驱逐 —— 这些情况没机会走关闭钩子。
 * 启动时扫一遍"停在中间态且超过 N 分钟没更新"的文档重新入队,
 * 这是任务只存在内存里、没有任何持久化记录时唯一能做的补偿。
 */
@Slf4j
@Component
public class IngestTrigger {

    /** 队列深度:入库是重活,堆得越多,失败时用户等得越久。 */
    private static final int QUEUE_CAPACITY = 50;

    private final IngestService ingestService;
    private final DocumentMapper documentMapper;
    private final ExecutorService pool;

    @Value("${app.ingest.recover-after-minutes:5}")
    private int recoverAfterMinutes = 5;

    public IngestTrigger(IngestService ingestService, DocumentMapper documentMapper) {
        this.ingestService = ingestService;
        this.documentMapper = documentMapper;
        this.pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "ingest-worker");
                    // 守护线程:应用关闭时不因为还有文档在解析而卡住 JVM 退出
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> {
                    long documentId = ((IngestTask) task).documentId;
                    log.warn("ingest queue full, rejecting documentId={}", documentId);
                    markRejected(documentId);
                    throw new RejectedExecutionException("入库队列已满,请稍后重试");
                });
    }

    /** 带文档号的任务 —— 拒绝策略需要知道"被拒绝的是哪一份"。 */
    private final class IngestTask implements Runnable {

        private final long documentId;

        private IngestTask(long documentId) {
            this.documentId = documentId;
        }

        @Override
        public void run() {
            ingestService.ingestQuietly(documentId);
        }
    }

    public void trigger(long documentId) {
        try {
            pool.execute(new IngestTask(documentId));
            log.debug("ingest triggered: documentId={}", documentId);
        } catch (RejectedExecutionException e) {
            // 拒绝策略里已经把文档标成 FAILED,这里只记日志,不让异常冒到上传接口之外
            log.warn("ingest rejected: documentId={}, reason={}", documentId, e.getMessage());
        }
    }

    /**
     * 启动恢复:把停在中间态的文档重新入队。
     *
     * <p>用"超过 N 分钟没更新"而不是"所有中间态"来过滤,是为了避开刚启动时
     * 正在处理的文档 —— 那些由当前进程负责,不该被重复入队。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckDocuments() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(recoverAfterMinutes);
        List<DocumentEntity> stuck = documentMapper.findStuck(deadline);
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("recovering {} documents stuck in intermediate status", stuck.size());
        for (DocumentEntity document : stuck) {
            documentMapper.updateStatusClearingReason(document.getId(), "UPLOADED", LocalDateTime.now());
            trigger(document.getId());
        }
    }

    private void markRejected(long documentId) {
        try {
            DocumentEntity patch = new DocumentEntity();
            patch.setId(documentId);
            patch.setStatus("FAILED");
            patch.setFailReason("入库队列已满,请稍后重新入库");
            patch.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(patch);
        } catch (RuntimeException e) {
            // 标记失败也不能让上传接口炸掉:已经在拒绝路径上,信息留日志
            log.error("failed to mark rejected document: id={}", documentId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("ingest pool did not finish within 30s, forcing shutdown");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
