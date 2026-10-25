package com.ragkb.auth;

import com.ragkb.auth.mapper.RefreshTokenMapper;
import com.ragkb.common.ApiException;
import com.ragkb.common.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌的轮换与重放检测。
 *
 * <h2>这组用例的核心</h2>
 * "轮换"本身不难写,难的是分清三种失败:过期、已吊销、以及<b>已用过的令牌再次出现</b>。
 * 第三种必须整族吊销 —— 只作废那一枚,攻击者手里还有刚换到的新令牌。
 *
 * <h2>这组用例覆盖不到什么(被 P0 打中过一次)</h2>
 * mapper 是 mock 的,所以 {@code mapper.insert} 的参数绑定、SQL 与列类型这一整层<b>从未被走过</b>。
 * 曾经有一个 UUID 类型绑定缺陷,让注册与登录 100% 返回 500,而这 8 条全绿。
 * 那一层只能靠真库用例({@code SecurityChainTest})兜住 —— 它 skip 时,这类缺陷就是隐形的。
 */
class RefreshTokenServiceTest {

    private RefreshTokenMapper mapper;
    private RefreshTokenService service;
    private final UUID family = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = mock(RefreshTokenMapper.class);
        AuthProperties properties = new AuthProperties();
        properties.setRefreshTtlDays(7);
        service = new RefreshTokenService(mapper, properties);
    }

    private RefreshTokenEntity row(LocalDateTime usedAt) {
        RefreshTokenEntity row = new RefreshTokenEntity();
        row.setId(1L);
        row.setUserId(9L);
        row.setFamilyId(family);
        row.setIssuedAt(LocalDateTime.now().minusMinutes(5));
        row.setExpiresAt(LocalDateTime.now().plusDays(1));
        row.setUsedAt(usedAt);
        return row;
    }

    @Test
    @DisplayName("正常轮换:旧令牌作废,新令牌入同一族")
    void rotateIssuesNewTokenInSameFamily() {
        RefreshTokenEntity existing = row(null);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.claim(eq(1L), any())).thenReturn(1);
        when(mapper.insert(any(RefreshTokenEntity.class))).thenAnswer(invocation -> {
            RefreshTokenEntity saved = invocation.getArgument(0);
            assertThat(saved.getFamilyId()).as("新令牌必须留在同一族里,否则重放检测就断了").isEqualTo(family);
            assertThat(saved.getUserId()).isEqualTo(9L);
            return 1;
        });

        RefreshTokenService.Rotated rotated = service.rotate("some-raw-token");

        assertThat(rotated.userId()).isEqualTo(9L);
        assertThat(rotated.raw()).isNotBlank().isNotEqualTo("some-raw-token");
        verify(mapper).claim(eq(1L), any());
    }

    @Test
    @DisplayName("已用过的令牌再次出现 = 重放:吊销整族,而不是只拒这一次")
    void replayRevokesWholeFamily() {
        when(mapper.selectOne(any())).thenReturn(row(LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> service.rotate("stolen-raw-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重放");

        verify(mapper).revokeFamily(eq(family), any());
        verify(mapper, never()).insert(any(RefreshTokenEntity.class));
    }

    @Test
    @DisplayName("并发抢同一枚令牌:抢不到的按重放处理")
    void concurrentClaimLosesTreatedAsReplay() {
        when(mapper.selectOne(any())).thenReturn(row(null));
        // 条件更新返回 0 行:说明另一个请求刚刚用掉了它
        when(mapper.claim(anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate("raw-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已被使用");

        verify(mapper).revokeFamily(eq(family), any());
    }

    @Test
    @DisplayName("过期令牌不再接受,且不需要吊销整族")
    void expiredTokenIsRejected() {
        RefreshTokenEntity expired = row(null);
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(expired);

        assertThatThrownBy(() -> service.rotate("raw-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("过期");

        verify(mapper, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("已吊销的会话不再接受")
    void revokedFamilyIsRejected() {
        RefreshTokenEntity revoked = row(null);
        revoked.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectOne(any())).thenReturn(revoked);

        assertThatThrownBy(() -> service.rotate("raw-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已注销");
    }

    @Test
    @DisplayName("库里查不到的令牌一律 401,不区分'从未存在'与'已被清理'")
    void unknownTokenIsRejected() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.rotate("never-existed"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("空令牌直接拒,不查库")
    void blankTokenRejectedWithoutQuery() {
        assertThatThrownBy(() -> service.rotate(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rotate("")).isInstanceOf(ApiException.class);

        verify(mapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("库里存的是哈希,不是令牌原文")
    void storesHashNotRawToken() {
        when(mapper.insert(any(RefreshTokenEntity.class))).thenAnswer(invocation -> {
            RefreshTokenEntity saved = invocation.getArgument(0);
            assertThat(saved.getTokenHash()).hasSize(64).doesNotContain("-").doesNotContain("_");
            return 1;
        });

        RefreshTokenService.Issued issued = service.issueNewFamily(9L);

        // 原文是 43 字符的 base64url(32 字节),与 64 位十六进制哈希明显不同
        assertThat(issued.raw()).hasSize(43);
    }
}
