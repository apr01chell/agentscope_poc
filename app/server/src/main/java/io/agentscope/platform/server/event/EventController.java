package io.agentscope.platform.server.event;

import io.agentscope.platform.server.conversation.ConversationRepository;
import io.agentscope.platform.server.security.CurrentUser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 事件流（SSE）。
 *
 * <p>客户端先 POST /turns 拿到 cursor，再连这里从 cursor 往后读；断线重连带同一个 cursor 即可补齐
 * —— 这正是用 Redis Stream 而不是 pub/sub 的原因。
 *
 * <p>注意 XREAD BLOCK 会占住一个线程，所以读取放在独立线程池里，不能让请求线程阻塞。
 */
@RestController
@RequestMapping("/api/conversations")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    /** 单条连接最长存活 10 分钟；前端到点会重连。 */
    private static final long MAX_LIFETIME_MS = 10 * 60 * 1000L;
    private static final long BLOCK_MS = 5_000;

    private final EventLog events;
    private final ConversationRepository repository;
    private final CurrentUser currentUser;
    private final ExecutorService readers = Executors.newCachedThreadPool();

    public EventController(
            EventLog events, ConversationRepository repository, CurrentUser currentUser) {
        this.events = events;
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long id, @RequestParam(required = false) String after) {
        boolean owned = repository.find(id)
                .map(row -> currentUser.sub().equals(row.get("ownerSub")))
                .orElse(false);
        if (!owned) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }

        SseEmitter emitter = new SseEmitter(0L);
        readers.submit(() -> pump(id, after, emitter));
        return emitter;
    }

    private void pump(long conversationId, String cursor, SseEmitter emitter) {
        String current = cursor;
        long deadline = System.currentTimeMillis() + MAX_LIFETIME_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                List<MapRecord<String, Object, Object>> records =
                        events.readAfter(conversationId, current, BLOCK_MS);

                if (records.isEmpty()) {
                    // 心跳：让中间代理与浏览器知道连接还活着
                    emitter.send(SseEmitter.event().comment("ping"));
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    Map<String, String> payload = toPayload(record);
                    String type = payload.getOrDefault("type", "message");
                    emitter.send(SseEmitter.event()
                            .id(record.getId().getValue())
                            .name(type)
                            .data(payload, MediaType.APPLICATION_JSON));
                    current = record.getId().getValue();
                    if ("done".equals(type) || "error".equals(type)) {
                        emitter.complete();
                        return;
                    }
                }
            }
            emitter.complete();
        }
        catch (IOException clientGone) {
            // 浏览器关页面是常态，不记为错误
            emitter.completeWithError(clientGone);
        }
        catch (RuntimeException error) {
            log.warn("事件流中断 conversation={}", conversationId, error);
            emitter.completeWithError(error);
        }
    }

    private static Map<String, String> toPayload(MapRecord<String, Object, Object> record) {
        Map<String, String> payload = new LinkedHashMap<>();
        record.getValue().forEach((k, v) -> payload.put(String.valueOf(k), String.valueOf(v)));
        return payload;
    }
}
