package com.ragkb.document;

import com.ragkb.document.mapper.DocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步入库的边界行为。
 *
 * <h2>这组用例测的是"出问题时会发生什么"</h2>
 * 队列满、进程被强杀、启动时发现半截任务 —— 这三件事在正常路径上永远看不到,
 * 但它们是"上传成功却永远停在已上传"的全部来源。
 */
class IngestTriggerTest {

    private final IngestService ingestService = mock(IngestService.class);
    private final DocumentMapper documentMapper = mock(DocumentMapper.class);
    private IngestTrigger trigger;

    @AfterEach
    void tearDown() {
        if (trigger != null) {
            trigger.shutdown();
        }
    }

    @Test
    @DisplayName("正常触发:任务被交给入库服务")
    void triggerRunsIngest() {
        trigger = new IngestTrigger(ingestService, documentMapper);

        trigger.trigger(42L);

        verify(ingestService, timeout(2000)).ingestQuietly(42L);
    }

    @Test
    @DisplayName("队列满:拒绝入队并把文档标成 FAILED,而不是无限堆在内存里")
    void rejectsWhenQueueFull() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        doAnswer(invocation -> {
            block.await(5, TimeUnit.SECONDS);
            return null;
        }).when(ingestService).ingestQuietly(anyLong());
        trigger = new IngestTrigger(ingestService, documentMapper);

        // 2 个线程被占住 + 队列 50 = 第 53 个开始被拒绝
        for (int i = 0; i < 60; i++) {
            trigger.trigger(i);
        }

        org.mockito.ArgumentCaptor<DocumentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentMapper, timeout(3000).atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues())
                .as("被拒绝的文档必须标成 FAILED 并写明原因,否则用户看到的是'上传成功但永远不动'")
                .anyMatch(patch -> "FAILED".equals(patch.getStatus())
                        && patch.getFailReason() != null
                        && patch.getFailReason().contains("队列已满"));
        block.countDown();
    }

    @Test
    @DisplayName("启动恢复:停在中间态的文档复位并重新入队")
    void recoversStuckDocuments() {
        DocumentEntity stuck = new DocumentEntity();
        stuck.setId(7L);
        stuck.setStatus("PARSING");
        when(documentMapper.findStuck(any())).thenReturn(List.of(stuck));
        trigger = new IngestTrigger(ingestService, documentMapper);

        trigger.recoverStuckDocuments();

        verify(documentMapper).updateStatusClearingReason(eq(7L), eq("UPLOADED"), any());
        verify(ingestService, timeout(2000)).ingestQuietly(7L);
    }

    @Test
    @DisplayName("没有卡住的文档时启动恢复什么都不做 —— 不能每次启动都把全部文档重跑一遍")
    void recoveryIsNoOpWhenNothingStuck() {
        when(documentMapper.findStuck(any())).thenReturn(List.of());
        trigger = new IngestTrigger(ingestService, documentMapper);

        trigger.recoverStuckDocuments();

        // findStuck 本身当然会被调用;这里要证明的是"没有任何文档被复位或重新入队"
        verify(documentMapper, org.mockito.Mockito.never())
                .updateStatusClearingReason(anyLong(), org.mockito.ArgumentMatchers.anyString(), any());
        verify(ingestService, org.mockito.Mockito.never()).ingestQuietly(anyLong());
    }
}
