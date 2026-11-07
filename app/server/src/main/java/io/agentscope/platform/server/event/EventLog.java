package io.agentscope.platform.server.event;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 事件流：一条会话一条 Redis Stream。
 *
 * <p>用 Stream 而不是 pub/sub，是因为要支持"断线后从 cursor 补发"（#5 的重放依赖它）。
 * cursor 就是 Stream 的消息 ID，客户端拿到的 `after` 直接喂回来即可。
 */
@Component
public class EventLog {

    /** 事件保留 24 小时；归档进 MySQL 是 #5 的事。 */
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public EventLog(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String key(long conversationId) {
        return "conversation:%d:events".formatted(conversationId);
    }

    /** 追加一条事件，返回它的 Stream ID（即给客户端的新 cursor）。 */
    public String append(long conversationId, String type, Map<String, String> payload) {
        var fields = new java.util.LinkedHashMap<>(payload);
        fields.put("type", type);
        fields.put("at", java.time.Instant.now().toString());
        String id = redis.opsForStream().add(key(conversationId), fields).getValue();
        redis.expire(key(conversationId), TTL);
        return id;
    }

    /**
     * 从 cursor 之后读事件；blockMillis 内没有新事件就返回空列表。
     *
     * <p>cursor 为空表示从最早一条开始（首次进入会话）。
     */
    public List<MapRecord<String, Object, Object>> readAfter(long conversationId, String cursor, long blockMillis) {
        StreamOffset<String> offset = cursor == null || cursor.isBlank()
                ? StreamOffset.fromStart(key(conversationId))
                : StreamOffset.create(key(conversationId), ReadOffset.from(cursor));

        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                StreamReadOptions.empty().count(200).block(Duration.ofMillis(blockMillis)), offset);
        return records == null ? List.of() : records;
    }
}
