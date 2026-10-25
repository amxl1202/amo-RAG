package com.ragkb.auth;

import com.ragkb.common.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 生产环境下的 cookie 配置检查。
 *
 * <p>本地是 http，{@code Secure} 开着浏览器就不会回传，所以默认关。但生产切 https 后
 * <b>忘了打开</b>是一个静默降级：功能照样能跑，只是刷新令牌会明文走在网络上。
 * 这种"忘了也没报错"的配置，只能靠启动时喊一声。
 *
 * <p>选择告警而不是直接启动失败：一个配错的开关不该让整个服务起不来，
 * 但必须在日志里显眼到不会被忽略。
 */
@Slf4j
@Component
public class AuthCookieGuard {

    public AuthCookieGuard(AuthProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod")) && !properties.isCookieSecure()) {
            log.warn("!!! app.auth.cookie-secure=false 且当前是 prod profile:"
                    + "刷新令牌会以明文经过网络。生产必须设为 true(或设置 JWT_COOKIE_SECURE=true)");
        }
    }
}
