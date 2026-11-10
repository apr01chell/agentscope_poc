package io.agentscope.platform.server.artifact;

import io.agentscope.platform.server.conversation.ConversationRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 产物：对象存储 + 元数据表。
 *
 * <p>三条不能破的规则：
 * <ol>
 *   <li>object key 由服务端生成，<b>绝不采信沙箱或模型给的名字</b>——沙箱里跑的是模型生成的脚本</li>
 *   <li>写完必须 HEAD 校验（见 {@link ObjectStore#put}）</li>
 *   <li>下载必须先校验归属，再签发有时效的地址</li>
 * </ol>
 */
@Service
public class ArtifactService {

    /** 超过这个体积转交直传路径（#10）。 */
    private static final long INLINE_MAX_BYTES_DEFAULT = 3L * 1024 * 1024;

    private final ObjectStore store;
    private final JdbcClient jdbc;
    private final ConversationRepository conversations;
    private final long inlineMaxBytes;

    public ArtifactService(
            ObjectStore store,
            JdbcClient jdbc,
            ConversationRepository conversations,
            @Value("${app.artifact.inline-max-bytes:3145728}") long inlineMaxBytes) {
        this.store = store;
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.inlineMaxBytes = inlineMaxBytes > 0 ? inlineMaxBytes : INLINE_MAX_BYTES_DEFAULT;
    }

    public long save(long conversationId, String ownerSub, String fileName, byte[] content, String contentType) {
        if (content.length > inlineMaxBytes) {
            throw new ArtifactTooLargeException(
                    "产物 " + content.length + " 字节，超过内联上限 " + inlineMaxBytes + " 字节");
        }

        Map<String, Object> conversation = conversations.find(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        String tenant = String.valueOf(conversation.get("tenant"));

        // 服务端定名：会话 + 随机目录 + 清洗过的文件名
        String key = "artifacts/" + conversationId + "/" + UUID.randomUUID() + "/" + safeName(fileName);
        store.put(key, content, contentType == null ? "application/octet-stream" : contentType);

        jdbc.sql("""
                        INSERT INTO artifact
                            (conversation_id, owner_sub, tenant, file_name, object_key, content_type, size_bytes)
                        VALUES (:cid, :owner, :tenant, :name, :key, :type, :size)
                        """)
                .param("cid", conversationId)
                .param("owner", ownerSub)
                .param("tenant", tenant)
                .param("name", fileName == null || fileName.isBlank() ? "artifact" : fileName)
                .param("key", key)
                .param("type", contentType == null ? "application/octet-stream" : contentType)
                .param("size", content.length)
                .update();

        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /** 只有归属者能拿到下载地址；别人的产物一律当作不存在。 */
    public Optional<String> downloadUrl(long artifactId, String ownerSub, Duration ttl) {
        return jdbc.sql("SELECT object_key FROM artifact WHERE id = :id AND owner_sub = :owner")
                .param("id", artifactId)
                .param("owner", ownerSub)
                .query(String.class)
                .optional()
                .map(key -> store.presignedGetUrl(key, ttl));
    }

    public Optional<Map<String, Object>> find(long artifactId, String ownerSub) {
        return jdbc.sql("""
                        SELECT id, conversation_id, file_name, size_bytes, content_type, created_at
                        FROM artifact WHERE id = :id AND owner_sub = :owner
                        """)
                .param("id", artifactId)
                .param("owner", ownerSub)
                .query((rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "conversationId", rs.getLong("conversation_id"),
                        "fileName", rs.getString("file_name"),
                        "size", rs.getLong("size_bytes"),
                        "contentType", rs.getString("content_type")))
                .optional();
    }

    /** 文件名只保留安全字符，防止路径穿越与服务端路径伪造。 */
    static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "artifact";
        }
        String base = fileName.substring(fileName.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return cleaned.isBlank() ? "artifact" : cleaned;
    }
}
