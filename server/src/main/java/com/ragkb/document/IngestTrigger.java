package com.ragkb.document;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 异步触发入库。
 *
 * 上传接口不该等着解析跑完:20 页 PDF 的解析 + 切片是几百毫秒到几秒的事,
 * 让上传请求等着,用户体验会很差,而且超时重试会让同一份文件被解析多遍。
 *
 * 为什么不用 {@code @Async}:那条路要开 {@code @EnableAsync}、要处理代理失效(同类自调用),
 * 还得为它单独配线程池。这里只需要"丢进池子,失败已经记在状态里",一个普通线程池就够,
 * 依赖关系也更直白 —— 读代码的人不用去猜哪个注解在起作用。
 *
 * 池子刻意开得很小:入库是 IO + CPU 混合的重活,并发太高只会互相拖慢,
 * 而且同一份文档被并发入库本身也没有意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestTrigger {

    private final IngestService ingestService;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ingest-worker");
            // 守护线程:应用关闭时不因为还有文档在解析而卡住
            thread.setDaemon(true);
            return thread;
        }
    });

    public void trigger(long documentId) {
        pool.execute(() -> ingestService.ingestQuietly(documentId));
        log.debug("ingest triggered: documentId={}", documentId);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
