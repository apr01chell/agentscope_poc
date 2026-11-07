package io.agentscope.platform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户中心的可配置项。
 *
 * <p>所有敏感值都允许用环境变量覆盖，开发默认值只用于本地。
 */
@ConfigurationProperties(prefix = "app.security")
public record AuthProperties(
        Jwk jwk,
        BootstrapAdmin bootstrapAdmin,
        WorkbenchClient workbenchClient) {

    public record Jwk(
            /** JWK 私钥 PEM 的位置；生产必须指向挂载的 Secret。 */
            String path) {}

    public record BootstrapAdmin(
            String username,
            String password,
            String displayName) {}

    public record WorkbenchClient(
            String clientId,
            String clientSecret,
            /**
             * 逗号分隔的多个回调地址：dev（vite 5173）与容器（8080）各一个。
             * 回调地址是按请求 Host 推导的，两处调试需要各自登记。
             */
            String redirectUris) {}
}
