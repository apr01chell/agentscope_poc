package io.agentscope.platform.server.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.platform.server.artifact.ArtifactService;
import io.agentscope.platform.server.artifact.ArtifactTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把框架的产物交付接到我们的对象存储 + artifact 表。
 *
 * <p>框架类型只出现在这个类里；真正的落地规则在 {@link ArtifactService}。
 */
@Component
public class PlatformArtifactDeliveryTarget implements ArtifactDeliveryTarget {

    private static final Logger log = LoggerFactory.getLogger(PlatformArtifactDeliveryTarget.class);

    private final ArtifactService artifacts;

    public PlatformArtifactDeliveryTarget(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext context, ArtifactDeliveryRequest request) {
        try {
            long conversationId = Long.parseLong(context.getSessionId());
            String ownerSub = context.getUserId();
            long id = artifacts.save(
                    conversationId, ownerSub, request.fileName(), request.content(),
                    guessContentType(request.fileName()));
            log.info("产物已保存 conversation={} artifact={} name={} size={}",
                    conversationId, id, request.fileName(), request.content().length);
            return ArtifactDeliveryResult.success("artifact:" + id);
        }
        catch (ArtifactTooLargeException tooLarge) {
            // 不是错误：告诉模型改走直传路径（#10）
            return ArtifactDeliveryResult.fail(tooLarge.getMessage() + "，请改用直传上传");
        }
        catch (RuntimeException error) {
            log.warn("产物保存失败 name={}", request.fileName(), error);
            return ArtifactDeliveryResult.fail("产物保存失败：" + error.getMessage());
        }
    }

    /** 只做最基本的分辨率判断，够前端决定用哪种预览。 */
    private static String guessContentType(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".csv")) {
            return "text/csv; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".md") || name.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (name.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (name.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }
}
