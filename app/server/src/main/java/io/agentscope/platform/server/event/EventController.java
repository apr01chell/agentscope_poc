package io.agentscope.platform.server.event;

import io.agentscope.platform.server.conversation.ConversationRepository;
import io.agentscope.platform.server.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 事件流（SSE）。
 *
 * <p>cursor 是 {@code turn_event.id}：
 * <ul>
 *   <li>不带 cursor / cursor=0 —— 从头重放整段历史（刷新页面就是这条路径）</li>
 *   <li>带 cursor —— 从上次位置续传</li>
 * </ul>
 *
 * <p>读取是轮询 MySQL，每 400ms 一次；空闲时发心跳注释帧。轮询放在独立线程池，
 * 不能占请求线程。
 */
@RestController
@RequestMapping("/api/conversations")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private static final long MAX_LIFETIME_MS = 10 * 60 * 1000L;
    private static final long POLL_MS = 400;
    private static final int BATCH = 200;

    private final EventStore events;
    private final ConversationRepository repository;
    private final CurrentUser currentUser;
    private final ExecutorService readers = Executors.newCachedThreadPool();

    public EventController(EventStore events, ConversationRepository repository, CurrentUser currentUser) {
        this.events = events;
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long id,
            @RequestParam(required = false) Long after,
            // 浏览器自动重连时带的是标准头，不是我们的查询参数；不认它就会重复重放整段历史
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        boolean owned = repository.find(id)
                .map(row -> currentUser.sub().equals(row.get("ownerSub")))
                .orElse(false);
        if (!owned) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }

        SseEmitter emitter = new SseEmitter(0L);
        long cursor = resolveCursor(after, lastEventId);
        readers.submit(() -> pump(id, cursor, emitter));
        return emitter;
    }

    /** after 优先；否则用标准头 Last-Event-ID；都没有才从头重放。 */
    private static long resolveCursor(Long after, String lastEventId) {
        if (after != null && after > 0) {
            return after;
        }
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Long.parseLong(lastEventId.trim());
            }
            catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void pump(long conversationId, long startCursor, SseEmitter emitter) {
        long cursor = startCursor;
        long deadline = System.currentTimeMillis() + MAX_LIFETIME_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                List<EventRecord> batch = events.readAfter(conversationId, cursor, BATCH);

                if (batch.isEmpty()) {
                    emitter.send(SseEmitter.event().comment("ping"));
                    Thread.sleep(POLL_MS);
                    continue;
                }

                for (EventRecord record : batch) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(record.id()))
                            .name(record.type())
                            .data(record.payload(), MediaType.APPLICATION_JSON));
                    cursor = record.id();
                }
                // 注意：done/error 之后**不能**断开。EventSource 会自动重连，
                // 一旦断开就会重放历史、把气泡渲染两遍。连接保留到生命周期上限，
                // 下一轮的事件继续在这条流上推。
            }
            emitter.complete();
        }
        catch (IOException clientGone) {
            // 关页面是常态
            emitter.completeWithError(clientGone);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.complete();
        }
        catch (RuntimeException error) {
            log.warn("事件流中断 conversation={}", conversationId, error);
            emitter.completeWithError(error);
        }
    }
}
