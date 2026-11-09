package io.agentscope.platform.server.event;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 事件存储：MySQL 是唯一来源。
 *
 * <p>不叠一层 Redis Stream，是因为重放要用"同一个游标语义"。双存储下实时流读 Redis、
 * 归档后读 MySQL，游标要在两种 ID 之间转换，归档边界极易丢事件。单表自增 id 让
 * "实时推送"与"断线重放"变成同一条查询，代价是每 400ms 一次的轮询
 * ——在这个量级完全可接受，且不依赖粘性路由，多副本天然可用。
 */
@Component
public class EventStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public EventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条事件，返回它的 id（即给客户端的新 cursor）。 */
    public long append(long conversationId, long turnId, int seq, String type, Map<String, Object> payload) {
        String json = JSON.writeValueAsString(payload);
        jdbc.sql("""
                        INSERT INTO turn_event (conversation_id, turn_id, seq, type, payload_json)
                        VALUES (:cid, :tid, :seq, :type, :payload)
                        """)
                .param("cid", conversationId)
                .param("tid", turnId)
                .param("seq", seq)
                .param("type", type)
                .param("payload", json)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    /** 从 cursor 之后读，按 id 升序。cursor <= 0 表示从头读（首次打开会话 / 刷新页面）。 */
    public List<EventRecord> readAfter(long conversationId, long cursor, int limit) {
        return jdbc.sql("""
                        SELECT id, conversation_id, turn_id, seq, type, payload_json
                        FROM turn_event
                        WHERE conversation_id = :cid AND id > :cursor
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("cid", conversationId)
                .param("cursor", Math.max(cursor, 0))
                .param("limit", limit)
                .query((rs, n) -> new EventRecord(
                        rs.getLong("id"),
                        rs.getLong("conversation_id"),
                        rs.getLong("turn_id"),
                        rs.getInt("seq"),
                        rs.getString("type"),
                        JSON.readValue(rs.getString("payload_json"), Map.class)))
                .list();
    }

    /** 该会话已落库的事件数（测试与运维用）。 */
    public long count(long conversationId) {
        return jdbc.sql("SELECT COUNT(*) FROM turn_event WHERE conversation_id = :cid")
                .param("cid", conversationId).query(Long.class).single();
    }
}
