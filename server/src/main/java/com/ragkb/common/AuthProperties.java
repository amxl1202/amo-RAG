package com.ragkb.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置。
 *
 * secret 从环境变量来({@code JWT_SECRET}),默认值为空 —— 空的密钥应当在启动时就失败,
 * 而不是等到第一次登录才抛异常:那样一来服务看着是"起来了",实际所有登录都不可用。
 */
@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 签名密钥。HS256 要求至少 256 位(32 字节),太短会在启动时直接报错。 */
    private String secret = "";

    /**
     * 访问令牌有效期(分钟)。
     *
     * <p>30 分钟不是教科书值(那通常是 15),因为本地演示与截图往往一次跑很久,
     * 15 分钟会把"刷新"这件事变成干扰。生产部署改一行配置即可,不需要改代码。
     */
    private int ttlMinutes = 30;

    /** 刷新令牌有效期(天)。7 天 = "一周内不用重新输密码"。 */
    private int refreshTtlDays = 7;

    /**
     * 刷新令牌的 cookie 是否加 Secure 标志。
     *
     * <p>本地是 http,加了 Secure 浏览器就不会回传;生产(https)必须打开。
     */
    private boolean cookieSecure = false;

    /** 签发者。校验时会比对,避免拿别的系统签的令牌来用。 */
    private String issuer = "amo-rag";
}
