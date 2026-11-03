package io.agentscope.platform.auth;

import io.agentscope.platform.common.ApiResult;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户中心的健康检查端点，契约与工作台后端保持一致。 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String version;

    public HealthController(@Value("${app.version:0.1.0}") String version) {
        this.version = version;
    }

    @GetMapping
    public ApiResult<HealthPayload> health() {
        return ApiResult.ok(new HealthPayload("UP", "auth", version, Instant.now().toString()));
    }

    public record HealthPayload(String status, String service, String version, String timestamp) {}
}
