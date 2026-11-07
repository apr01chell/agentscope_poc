package io.agentscope.platform.server.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话与轮次的读写。全库无外键，归属校验在服务层做。 */
@Repository
public class ConversationRepository {

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String tenant, String ownerSub, String title) {
        jdbc.sql("""
                        INSERT INTO conversation (tenant_code, owner_sub, title)
                        VALUES (:tenant, :owner, :title)
                        """)
                .param("tenant", tenant).param("owner", ownerSub).param("title", title)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    public List<Map<String, Object>> listOwned(String tenant, String ownerSub) {
        return jdbc.sql("""
                        SELECT c.id, c.title, c.created_at, c.last_turn_at,
                               (SELECT COUNT(*) FROM turn t WHERE t.conversation_id = c.id) AS turn_count
                        FROM conversation c
                        WHERE c.tenant_code = :tenant AND c.owner_sub = :owner
                        ORDER BY c.id DESC
                        """)
                .param("tenant", tenant).param("owner", ownerSub)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("title", rs.getString("title"));
                    row.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
                    row.put("lastTurnAt", String.valueOf(rs.getTimestamp("last_turn_at")));
                    row.put("turnCount", rs.getInt("turn_count"));
                    return row;
                })
                .list();
    }

    public Optional<Map<String, Object>> find(long id) {
        return jdbc.sql("SELECT id, tenant_code, owner_sub, title FROM conversation WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("tenant", rs.getString("tenant_code"));
                    row.put("ownerSub", rs.getString("owner_sub"));
                    row.put("title", rs.getString("title"));
                    return row;
                })
                .optional();
    }

    /** 下一轮的序号；并发下由唯一索引 `uk_turn_conversation_seq` 兜底。 */
    public int nextSeq(long conversationId) {
        Integer max = jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM turn WHERE conversation_id = :id")
                .param("id", conversationId).query(Integer.class).single();
        return (max == null ? 0 : max) + 1;
    }

    public long createTurn(long conversationId, int seq, String inputText) {
        jdbc.sql("""
                        INSERT INTO turn (conversation_id, seq, status, input_text)
                        VALUES (:cid, :seq, 'QUEUED', :input)
                        """)
                .param("cid", conversationId).param("seq", seq).param("input", inputText)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    public void markTurn(long turnId, String status) {
        String column = switch (status) {
            case "RUNNING" -> "started_at";
            case "DONE", "FAILED" -> "ended_at";
            default -> null;
        };
        if (column == null) {
            jdbc.sql("UPDATE turn SET status = :s WHERE id = :id")
                    .param("s", status).param("id", turnId).update();
            return;
        }
        jdbc.sql("UPDATE turn SET status = :s, " + column + " = CURRENT_TIMESTAMP WHERE id = :id")
                .param("s", status).param("id", turnId).update();
    }

    public void touchConversation(long conversationId) {
        jdbc.sql("UPDATE conversation SET last_turn_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", conversationId).update();
    }

    public List<Map<String, Object>> listTurns(long conversationId) {
        return jdbc.sql("""
                        SELECT id, seq, status, input_text, created_at
                        FROM turn WHERE conversation_id = :id ORDER BY seq
                        """)
                .param("id", conversationId)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("seq", rs.getInt("seq"));
                    row.put("status", rs.getString("status"));
                    row.put("input", rs.getString("input_text"));
                    row.put("createdAt", String.valueOf(rs.getTimestamp("created_at")));
                    return row;
                })
                .list();
    }
}
