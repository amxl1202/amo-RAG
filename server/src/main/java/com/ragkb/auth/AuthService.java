package com.ragkb.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragkb.common.ApiException;
import com.ragkb.user.UserEntity;
import com.ragkb.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 注册与登录。
 *
 * <h2>三条不变式</h2>
 * <ol>
 *   <li><b>密码只以 BCrypt 哈希入库</b>,原文在任何地方都不留:不入库、不进日志、不进响应。</li>
 *   <li><b>登录失败不区分"用户不存在"与"密码错"</b>,统一返回 401「用户名或密码错误」。
 *       区分开等于给了一个用户名枚举接口 —— 攻击者能先确认哪些账号存在,再针对性撞库。</li>
 *   <li><b>注册的并发冲突交给库的唯一索引</b>。先查再插的写法在两个请求同时通过检查时
 *       会插进两条同名记录,而唯一索引会把其中一个变成 409,这正是我们要的。</li>
 * </ol>
 *
 * <h2>为什么要吞掉 DuplicateKeyException 的堆栈</h2>
 * 它不是 bug,是预期的并发结果。若让它落到全局异常处理,会被记成 500 并打一整页堆栈,
 * 掩盖真正的 500。这里显式转成 409,日志只留一行 warn。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    /**
     * 注册并直接登录(返回令牌,省掉注册后再调一次登录)。
     *
     * @throws ApiException 409 用户名已存在
     */
    public AuthResponse register(String username, String password) {
        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setRole("USER");
        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("register rejected, username exists: {}", username);
            throw ApiException.conflict("用户名已被占用");
        }
        log.info("user registered: id={}, username={}", entity.getId(), username);
        return issue(entity);
    }

    /**
     * 登录。
     *
     * @throws ApiException 401 凭据不对
     */
    public AuthResponse login(String username, String password) {
        UserEntity entity = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        // 用户不存在时仍然做一次哈希比对,让两条失败路径的耗时接近 ——
        // 否则"用户存在与否"能从响应时间上读出来,前面那条不变式就白写了
        String hash = entity == null ? DUMMY_HASH : entity.getPasswordHash();
        boolean matched = passwordEncoder.matches(password, hash);
        if (entity == null || !matched) {
            throw new ApiException(401, "用户名或密码错误");
        }
        log.info("user logged in: id={}, username={}", entity.getId(), username);
        return issue(entity);
    }

    /**
     * 一个真实的 BCrypt 哈希(密码是一个随机串),用于"用户不存在"时的等时比对。
     *
     * 写死常量而不是每次 encode:encode 一次约几十毫秒,会给登录接口平白加一倍延迟。
     */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private AuthResponse issue(UserEntity entity) {
        AuthPrincipal principal = new AuthPrincipal(entity.getId(), entity.getUsername(), entity.getRole());
        return AuthResponse.of(tokenProvider.issue(principal), tokenProvider.ttlSeconds(), principal);
    }
}
