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

    /** 令牌有效期(分钟)。 */
    private int ttlMinutes = 120;

    /** 签发者。校验时会比对,避免拿别的系统签的令牌来用。 */
    private String issuer = "amo-rag";
}
