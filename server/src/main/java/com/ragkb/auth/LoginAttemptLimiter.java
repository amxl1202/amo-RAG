package com.ragkb.auth;

import com.ragkb.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数限制(固定窗口)。
 *
 * <h2>为什么是"按用户名"而不是"按 IP"</h2>
 * 这一层的目标是<b>抬高撞库成本</b>,不是防 DDoS。按 IP 限流在反向代理后面拿到的往往是同一个
 * 代理地址,一刀切会把所有用户一起锁死;按用户名锁只影响被攻击的那个账号,误伤面小得多。
 * 真要按 IP 做,应该在网关层(能拿到真实来源地址的地方)做。
 *
 * <h2>为什么是固定窗口而不是滑动窗口</h2>
 * 窗口边界会有"最后一秒打满、下一窗口立刻重置"的突刺,但这套系统的登录量级不值得上滑动窗口
 * (要实现按时间分桶的计数或令牌桶)。**这个取舍写在这里,是为了让它成为有意的选择**,
 * 而不是被当成"没想过"。
 *
 * <h2>为什么放在内存里</h2>
 * 单实例部署时内存计数够用;多实例时每个实例各算各的,真实上限变成 N×阈值 ——
 * 那一步要么换 Redis,要么在网关做。当前是单实例演示形态,先不上分布式。
 *
 * <h2>已知缺口</h2>
 * 只按用户名计数意味着攻击者可以轮换用户名扫描(每个用户名都不触发限制),
 * 但那种扫描的收益远低于盯住一个已知账号撞库。要防扫描需要按来源地址计数,
 * 见上面第一条。
 */
@Slf4j
@Component
public class LoginAttemptLimiter {

    /** 同一用户名允许的连续失败次数。 */
    private static final int MAX_FAILURES = 5;

    /** 计数窗口。超过这个时间未再失败,计数自动作废。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** 键的规模上限:超过就清一次过期项,避免被随机用户名刷爆内存。 */
    private static final int PRUNE_THRESHOLD = 1024;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 检查是否允许这次登录尝试。
     *
     * @throws ApiException 429,并给出还需等待的分钟数
     */
    public void checkAllowed(String username, Instant now) {
        Attempt attempt = attempts.get(username);
        if (attempt == null || isExpired(attempt, now)) {
            return;
        }
        if (attempt.count() >= MAX_FAILURES) {
            long waitSeconds = WINDOW.minus(Duration.between(attempt.windowStart(), now)).toSeconds();
            // 向上取整到 5 分钟档:精确到分钟等于把"什么时候可以再试"告诉撞库者,
            // 而那正是他需要的节奏参数。粗化之后仍可执行,但不再可规划
            long rawMinutes = Math.max(1, (waitSeconds + 59) / 60);
            long waitMinutes = ((rawMinutes + 4) / 5) * 5;
            // 不告诉调用方"这个用户名存在与否",只说尝试过密 —— 否则这里成了另一个枚举接口
            throw new ApiException(429, "登录尝试过于频繁,请 " + waitMinutes + " 分钟后再试");
        }
    }

    /** 记一次失败。窗口内累加,跨窗口重新开始。 */
    public void recordFailure(String username, Instant now) {
        pruneIfNeeded(now);
        attempts.compute(username, (key, current) -> {
            if (current == null || isExpired(current, now)) {
                return new Attempt(1, now);
            }
            return new Attempt(current.count() + 1, current.windowStart());
        });
    }

    /** 登录成功后清掉计数:成功的人不该继续背着之前的失败。 */
    public void clear(String username) {
        attempts.remove(username);
    }

    private boolean isExpired(Attempt attempt, Instant now) {
        return Duration.between(attempt.windowStart(), now).compareTo(WINDOW) >= 0;
    }

    private void pruneIfNeeded(Instant now) {
        if (attempts.size() < PRUNE_THRESHOLD) {
            return;
        }
        int before = attempts.size();
        attempts.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        log.debug("login attempt table pruned: {} -> {}", before, attempts.size());
    }

    /**
     * @param count       窗口内的失败次数
     * @param windowStart 本窗口的起点(第一次失败的时间)
     */
    private record Attempt(int count, Instant windowStart) {
    }
}
