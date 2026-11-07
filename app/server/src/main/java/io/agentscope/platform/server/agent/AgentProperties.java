package io.agentscope.platform.server.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置。
 *
 * <p>凭证只从环境变量注入，绝不硬编码；`provider=stub` 时不发起任何网络请求（测试用）。
 */
@ConfigurationProperties(prefix = "app.model")
public record AgentProperties(
        /** stub | deepseek */
        String provider,
        String apiKey,
        String baseUrl,
        String name,
        /** 单轮最长等待时间（秒）。 */
        int timeoutSeconds) {}
