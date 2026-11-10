package io.agentscope.platform.server.artifact;

import io.agentscope.platform.common.ApiResult;
import io.agentscope.platform.server.security.CurrentUser;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 产物下载。地址 5 分钟有效，且签发前必须校验归属。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private static final Duration LINK_TTL = Duration.ofMinutes(5);

    private final ArtifactService artifacts;
    private final CurrentUser currentUser;

    public ArtifactController(ArtifactService artifacts, CurrentUser currentUser) {
        this.artifacts = artifacts;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<ApiResult<Map<String, Object>>> download(@PathVariable long id) {
        return artifacts.find(id, currentUser.sub())
                .flatMap(meta -> artifacts.downloadUrl(id, currentUser.sub(), LINK_TTL)
                        .map(url -> ResponseEntity.ok(ApiResult.ok(Map.of(
                                "url", url,
                                "fileName", meta.get("fileName"),
                                "size", meta.get("size"),
                                "expiresInSeconds", LINK_TTL.toSeconds())))))
                // 别人的产物一律 404：不泄露"存在但不是你的"
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.failure("产物不存在", "NOT_FOUND")));
    }
}
