package com.ragkb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 排除 UserDetailsServiceAutoConfiguration:它会创建一个内存默认用户并把随机密码打进启动日志。
// 这套应用只有 JWT 一个身份来源,那个默认用户既用不上(HTTP Basic 没开),又是一条
// "看起来像凭据"的日志,将来谁加了 httpBasic() 还会变成后门
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.ragkb.**.mapper")
public class RagKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKbApplication.class, args);
    }
}
