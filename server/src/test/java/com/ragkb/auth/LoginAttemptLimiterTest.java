package com.ragkb.auth;

import com.ragkb.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录失败限制。
 *
 * 固定窗口的唯一难点是边界:窗口结束时必须放行、窗口内第 6 次必须拦。时间由调用方传入,
 * 所以这里不需要 sleep —— 用 sleep 测窗口的用例要么慢、要么不稳定。
 */
class LoginAttemptLimiterTest {

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter();
    private final Instant t0 = Instant.parse("2026-10-22T10:00:00Z");

    private void failTimes(String username, int times, Instant base) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username, base.plusSeconds(i));
        }
    }

    @Test
    @DisplayName("失败 5 次后第 6 次被拦,给出 429")
    void locksAfterFiveFailures() {
        failTimes("alice", 5, t0);

        assertThatThrownBy(() -> limiter.checkAllowed("alice", t0.plusSeconds(10)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(429));
    }

    @Test
    @DisplayName("5 次以内不拦 —— 阈值不能卡在正常用户会踩到的地方")
    void allowsUpToThreshold() {
        failTimes("alice", 4, t0);

        assertThatCode(() -> limiter.checkAllowed("alice", t0.plusSeconds(10))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("窗口过去后重新放行")
    void windowExpires() {
        failTimes("alice", 5, t0);

        // 窗口是 5 分钟:刚好 5 分钟时算过期(>=),此时应当放行
        assertThatCode(() -> limiter.checkAllowed("alice", t0.plusSeconds(300))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("登录成功后计数清零:成功的人不该继续背着之前的失败")
    void successResetsCounter() {
        failTimes("alice", 5, t0);
        limiter.clear("alice");

        assertThatCode(() -> limiter.checkAllowed("alice", t0.plusSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不同用户名互不影响")
    void countersArePerUser() {
        failTimes("alice", 5, t0);

        assertThatCode(() -> limiter.checkAllowed("bob", t0.plusSeconds(10))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("跨窗口的失败从 1 重新计数,不会把上一窗口的账带过来")
    void failureAfterExpiryStartsNewWindow() {
        failTimes("alice", 5, t0);
        // 窗口结束后再失败一次:新窗口只记 1 次,所以仍然放行
        limiter.recordFailure("alice", t0.plusSeconds(400));

        assertThatCode(() -> limiter.checkAllowed("alice", t0.plusSeconds(401))).doesNotThrowAnyException();
        failTimes("alice", 4, t0.plusSeconds(402));
        assertThatThrownBy(() -> limiter.checkAllowed("alice", t0.plusSeconds(410)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("429 的提示不透露用户名是否存在")
    void messageDoesNotLeakAccountExistence() {
        failTimes("nobody", 5, t0);

        assertThatThrownBy(() -> limiter.checkAllowed("nobody", t0.plusSeconds(1)))
                .hasMessageContaining("频繁")
                .hasMessageNotContaining("不存在")
                .hasMessageNotContaining("用户");
    }
}
