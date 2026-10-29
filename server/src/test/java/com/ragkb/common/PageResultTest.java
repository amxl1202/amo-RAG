package com.ragkb.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数收敛。
 *
 * <p>分页最容易出的事故是"调用方传了 size=100000 把整表读出来"。
 * 收敛必须发生在进入查询之前，而不是指望调用方自觉。
 */
class PageResultTest {

    @Test
    @DisplayName("页码小于 1 时按第 1 页")
    void pageIsAtLeastOne() {
        assertThat(PageResult.normalizePage(0)).isEqualTo(1);
        assertThat(PageResult.normalizePage(-5)).isEqualTo(1);
        assertThat(PageResult.normalizePage(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("页大小有上限，避免一次把整表读出来")
    void sizeIsCapped() {
        assertThat(PageResult.normalizeSize(100_000)).isEqualTo(PageResult.MAX_SIZE);
        assertThat(PageResult.normalizeSize(20)).isEqualTo(20);
    }

    @Test
    @DisplayName("页大小非正数时回落到默认 50，而不是返回 0 条")
    void nonPositiveSizeFallsBackToDefault() {
        assertThat(PageResult.normalizeSize(0)).isEqualTo(50);
        assertThat(PageResult.normalizeSize(-1)).isEqualTo(50);
    }

    @Test
    @DisplayName("信封结构原样保留 items/total/page/size")
    void carriesEnvelope() {
        PageResult<String> result = new PageResult<>(List.of("a", "b"), 42, 2, 10);

        assertThat(result.items()).containsExactly("a", "b");
        assertThat(result.total()).isEqualTo(42);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
    }
}
