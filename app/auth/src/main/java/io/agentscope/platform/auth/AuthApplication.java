package io.agentscope.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 用户中心入口。
 *
 * <p>当前只有健康检查；OAuth2.1 授权码 + PKCE、JWT 与 JWKS 在工单 #14 接入。
 */
@SpringBootApplication
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
