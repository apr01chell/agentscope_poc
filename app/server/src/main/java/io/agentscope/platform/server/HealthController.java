package io.agentscope.platform.server;

import io.agentscope.platform.common.ApiResult;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点。
 *
 * <p>前端用它验证「浏览器 → 后端」这条链路是通的，与 actuator 的 /actuator/health 并存：
 * 前者给产品用（稳定的、给前端消费的契约），后者给运维用（探针与细节）。
 *
 * <p>响应走统一信封 {@link ApiResult}，与后续所有接口保持同一形状。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String version;

    public HealthController(@Value("${app.version:0.1.0}") String version) {
        this.version = version;
    }

    @GetMapping
    public ApiResult<HealthPayload> health() {
        return ApiResult.ok(new HealthPayload("UP", "server", version, Instant.now().toString()));
    }

    /** 健康载荷：字段稳定，前端可直接依赖。 */
    public record HealthPayload(String status, String service, String version, String timestamp) {}
}
