package com.ragkb.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式事件缓冲。
 *
 * 这层的价值全在"断线之后还能补到 final"这一个场景上,所以用例围绕
 * id 递增、增量补齐、关键事件不被上限挤掉三件事。
 */
class QaStreamRegistryTest {

    private final QaStreamRegistry registry = new QaStreamRegistry();

    @Test
    @DisplayName("事件 id 从 1 开始递增,客户端靠它做 Last-Event-ID")
    void idsAreMonotonic() {
        String streamId = registry.open();

        assertThat(registry.append(streamId, "open", "{}").id()).isEqualTo(1);
        assertThat(registry.append(streamId, "delta", "{}").id()).isEqualTo(2);
        assertThat(registry.append(streamId, "final", "{}").id()).isEqualTo(3);
    }

    @Test
    @DisplayName("重连只补 lastEventId 之后的事件")
    void sinceReturnsOnlyMissedEvents() {
        String streamId = registry.open();
        registry.append(streamId, "open", "o");
        registry.append(streamId, "delta", "d1");
        registry.append(streamId, "delta", "d2");
        registry.append(streamId, "final", "f");

        List<QaStreamRegistry.Event> missed = registry.since(streamId, 2);

        assertThat(missed).extracting(QaStreamRegistry.Event::name)
                .containsExactly("delta", "final");
        assertThat(missed).extracting(QaStreamRegistry.Event::id).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("流结束后缓冲仍在,重连能拿到 final")
    void finishedStreamStillReplayable() {
        String streamId = registry.open();
        registry.append(streamId, "delta", "d");
        registry.append(streamId, "final", "f");
        registry.finish(streamId);

        assertThat(registry.isFinished(streamId)).isTrue();
        assertThat(registry.since(streamId, 0)).hasSize(2);
    }

    @Test
    @DisplayName("未知流 ID 不会抛异常,返回空表(过期后重连就是这个情况)")
    void unknownStreamYieldsEmpty() {
        assertThat(registry.since("not-exists", 0)).isEmpty();
        assertThat(registry.append("not-exists", "delta", "{}")).isNull();
        assertThat(registry.isFinished("not-exists")).isTrue();
    }

    @Test
    @DisplayName("增量超过上限时,final 依然保留 —— 用户真正要的是它")
    void keyEventSurvivesCap() {
        String streamId = registry.open();
        for (int i = 0; i < 2100; i++) {
            registry.append(streamId, "delta", "x");
        }
        registry.append(streamId, "final", "定稿");

        List<QaStreamRegistry.Event> all = registry.since(streamId, 0);

        assertThat(all.size()).isLessThanOrEqualTo(2001);
        assertThat(all.get(all.size() - 1).name()).isEqualTo("final");
        assertThat(all.get(all.size() - 1).data()).isEqualTo("定稿");
    }
}
