package com.ragkb.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册/登录请求体。
 *
 * 校验规则写在类型上而不是散在 service 里:两个接口用同一套规则,
 * 写在类型上就不会出现"注册时限制 32 位、登录时忘了限制"这种不对称。
 *
 * 用户名的字符集刻意收紧到 {@code [A-Za-z0-9_]}:允许中文或空格会带来
 * "看着一样但不是同一个"的问题(全角/半角、首尾空白、同形字),而用户名要参与唯一约束。
 */
public record AuthRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名只能是 3~32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8~64 位之间")
        String password) {
}
