package com.ragkb.auth;

import com.ragkb.common.ApiException;
import com.ragkb.common.AuthProperties;
import com.ragkb.user.UserEntity;
import com.ragkb.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册与登录。
 *
 * 三条不变式各有一个用例守着:密码不以明文入库、登录失败不区分原因、重复用户名是 409 而不是 500。
 * 这三条都有"实现起来很自然、错了也不报错"的写法。
 */
class AuthServiceTest {

    private UserMapper userMapper;
    private AuthService authService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        AuthProperties properties = new AuthProperties();
        properties.setSecret("test-secret-0123456789abcdef0123456789abcdef");
        properties.setTtlMinutes(60);
        properties.setIssuer("amo-rag");
        authService = new AuthService(userMapper, encoder, new JwtTokenProvider(properties),
                new LoginAttemptLimiter());
    }

    @Test
    @DisplayName("注册把 BCrypt 哈希写库,明文一个字节都不进实体")
    void registerStoresOnlyHash() {
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity saved = invocation.getArgument(0);
            saved.setId(1L);
            return 1;
        });

        AuthResponse response = authService.register("alice", "s3cret-pass");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        UserEntity saved = captor.getValue();
        assertThat(saved.getPasswordHash()).isNotEqualTo("s3cret-pass").startsWith("$2");
        assertThat(encoder.matches("s3cret-pass", saved.getPasswordHash())).isTrue();
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(response.token()).isNotBlank();
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("用户名重复是 409:并发时靠库的唯一索引兜底,不能让它冒成 500")
    void duplicateUsernameIsConflict() {
        when(userMapper.insert(any(UserEntity.class))).thenThrow(new DuplicateKeyException("uk_users_username"));

        assertThatThrownBy(() -> authService.register("alice", "s3cret-pass"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(409));
    }

    @Test
    @DisplayName("登录成功返回可用令牌,且令牌能解回同一个人")
    void loginIssuesUsableToken() {
        UserEntity existing = new UserEntity();
        existing.setId(9L);
        existing.setUsername("bob");
        existing.setPasswordHash(encoder.encode("correct-horse"));
        existing.setRole("USER");
        when(userMapper.selectOne(any())).thenReturn(existing);

        AuthResponse response = authService.login("bob", "correct-horse");

        assertThat(response.token()).isNotBlank();
        AuthProperties properties = new AuthProperties();
        properties.setSecret("test-secret-0123456789abcdef0123456789abcdef");
        properties.setIssuer("amo-rag");
        AuthPrincipal parsed = new JwtTokenProvider(properties).parse(response.token());
        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("密码错与用户不存在返回同一句 401 —— 区分开等于提供了用户名枚举")
    void wrongPasswordAndUnknownUserLookIdentical() {
        when(userMapper.selectOne(any())).thenReturn(null);
        Throwable unknownUser = catchThrowable(() -> authService.login("nobody", "whatever-1"));

        UserEntity existing = new UserEntity();
        existing.setId(9L);
        existing.setUsername("bob");
        existing.setPasswordHash(encoder.encode("correct-horse"));
        existing.setRole("USER");
        when(userMapper.selectOne(any())).thenReturn(existing);
        Throwable wrongPassword = catchThrowable(() -> authService.login("bob", "wrong-pass"));

        assertThat(unknownUser).isInstanceOf(ApiException.class);
        assertThat(wrongPassword).isInstanceOf(ApiException.class);
        assertThat(((ApiException) unknownUser).getStatus()).isEqualTo(401);
        assertThat(((ApiException) wrongPassword).getStatus()).isEqualTo(401);
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
