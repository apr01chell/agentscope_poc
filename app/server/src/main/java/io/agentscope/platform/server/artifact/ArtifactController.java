package io.agentscope.platform.server.artifact;

import io.agentscope.platform.common.ApiResult;
import io.agentscope.platform.server.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;

/** 产物下载。地址 5 分钟有效，且签发前必须校验归属。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;
    private final CurrentUser currentUser;

    public ArtifactController(ArtifactService artifacts, CurrentUser currentUser) {
        this.artifacts = artifacts;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable long id) {
        var content = artifacts.open(id, currentUser.sub());
        if (content.isEmpty()) {
            // 别人的产物一律 404：不泄露"存在但不是你的"
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.failure("产物不存在", "NOT_FOUND"));
        }
        var body = content.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(body.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(body.fileName(),
                                StandardCharsets.UTF_8).build().toString())
                .contentLength(body.size())
                .body(new InputStreamResource(body.stream()));
    }
}
