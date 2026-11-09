package io.agentscope.platform.server.conversation;

import io.agentscope.platform.server.agent.AgentRuntime;
import io.agentscope.platform.server.event.EventStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 轮次编排：串行、排队、取消、排队超时。
 *
 * <p>并发模型：同一会话最多 1 个 RUNNING + 1 个 QUEUED，第 3 个直接 429。
 * 互斥用 {@link ConversationLock}（生产实现是框架的 JDBC 租约，多副本有效）。
 *
 * <p>排队是"阻塞等锁"而不是自建队列：`acquire` 会一直等到前一个跑完，
 * 期间线程被中断就区分成"用户取消"或"排队超时"。
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    /** 排队等待的原因。 */
    private enum Interrupt {
        CANCELLED,
        TIMEOUT
    }

    private static final class Handle {
        final AtomicReference<Interrupt> reason = new AtomicReference<>();
        /** 是否已经拿到锁（= 开始执行）。定时器据此判断"还要不要超时"。 */
        final java.util.concurrent.atomic.AtomicBoolean acquired =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile Future<?> future;
        volatile java.util.concurrent.ScheduledFuture<?> timeout;
    }

    private final ConversationRepository repository;
    private final EventStore events;
    private final AgentRuntime agent;
    private final ConversationLock lock;
    private final int maxActive;
    private final Duration queueTimeout;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    public TurnService(
            ConversationRepository repository,
            EventStore events,
            AgentRuntime agent,
            ConversationLock lock,
            @Value("${app.turn.max-active:2}") int maxActive,
            @Value("${app.turn.queue-timeout-seconds:600}") long queueTimeoutSeconds) {
        this.repository = repository;
        this.events = events;
        this.agent = agent;
        this.lock = lock;
        this.maxActive = maxActive;
        this.queueTimeout = Duration.ofSeconds(queueTimeoutSeconds);
    }

    /** 提交一轮：立即返回状态与 cursor，实际生成在后台线程里跑。 */
    public SubmittedTurn submit(long conversationId, String input, String ownerSub) {
        int active = repository.countActive(conversationId);
        if (active >= maxActive) {
            throw new TooManyTurnsException(
                    "该会话已有 1 个执行中、1 个排队中的请求，请等待或取消后再试");
        }

        int turnSeq = repository.nextSeq(conversationId);
        long turnId = repository.createTurn(conversationId, turnSeq, input);
        repository.touchConversation(conversationId);

        // position: 1 = 立即执行，2 = 排队
        int position = active + 1;
        long cursor = events.append(conversationId, turnId, 1, "turn/queued", Map.of(
                "turnId", turnId,
                "turnSeq", turnSeq,
                "input", input,
                "position", position));

        Handle handle = new Handle();
        handles.put(turnId, handle);
        handle.future = workers.submit(() -> run(conversationId, turnId, input, ownerSub, handle));

        // 排队超时：到点还在等锁就中断它，由 run() 里区分是超时还是取消
        handle.timeout = timers.schedule(() -> {
            // 只靠取消定时器不够：拿锁与取消之间有个窗口，机器一卡就会把
            // 正在跑的轮次误判成"排队超时"。这里再确认一次是否已经开始执行。
            if (handle.acquired.get()) {
                return;
            }
            if (handle.reason.compareAndSet(null, Interrupt.TIMEOUT)) {
                Future<?> future = handle.future;
                if (future != null) {
                    future.cancel(true);
                }
                // 终止事件由这里发：如果任务还没开始跑，worker 的 catch 不会执行，
                // 只靠 worker 发就会"排队超时了但前端什么都收不到"
                repository.markTurnEnded(turnId, "FAILED", "QUEUE_TIMEOUT");
                events.appendNext(conversationId, turnId, "turn/error", Map.of(
                        "turnId", turnId,
                        "message", "排队超时（超过 " + queueTimeout.toSeconds() + " 秒未开始）"));
            }
        }, queueTimeout.toSeconds(), TimeUnit.SECONDS);

        return new SubmittedTurn(turnId, cursor, position);
    }

    private void run(long conversationId, long turnId, String input, String ownerSub, Handle handle) {
        AtomicInteger seq = new AtomicInteger(1);
        try (ConversationLock.Lease lease = lock.acquire(ownerSub, String.valueOf(conversationId))) {
            handle.acquired.set(true);
            // 已经拿到锁（= 开始执行），排队超时不再适用；不取消的话，
            // 跑得久的轮次会被自己的排队定时器杀掉
            java.util.concurrent.ScheduledFuture<?> timeout = handle.timeout;
            if (timeout != null) {
                timeout.cancel(false);
            }
            if (handle.reason.get() != null) {
                return; // 排队时就被取消了：状态与事件由 cancel() 负责
            }
            repository.markTurnStarted(turnId);
            events.append(conversationId, turnId, seq.incrementAndGet(), "turn/started", Map.of("turnId", turnId));

            AgentRuntime.AgentRequest request =
                    new AgentRuntime.AgentRequest(ownerSub, conversationId, input);
            agent.stream(request, new AgentRuntime.TokenSink() {
                @Override
                public void token(String delta) {
                    if (handle.reason.get() != null) {
                        return;
                    }
                    events.append(conversationId, turnId, seq.incrementAndGet(), "token",
                            Map.of("turnId", turnId, "delta", delta));
                }

                @Override
                public void done() {
                    if (handle.reason.get() != null) {
                        return;
                    }
                    repository.markTurnEnded(turnId, "DONE", null);
                    events.append(conversationId, turnId, seq.incrementAndGet(), "done", Map.of("turnId", turnId));
                }

                @Override
                public void error(String message) {
                    if (handle.reason.get() != null) {
                        return;
                    }
                    repository.markTurnEnded(turnId, "FAILED", "AGENT_ERROR");
                    events.append(conversationId, turnId, seq.incrementAndGet(), "error",
                            Map.of("turnId", turnId, "message",
                                    message == null ? "生成失败" : message));
                }
            });
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // 超时与取消的状态、事件都由定时器/控制器负责，这里不再重复发
            log.debug("轮次排队中断 turn={} reason={}", turnId, handle.reason.get());
        }
        catch (RuntimeException error) {
            log.warn("轮次执行失败 turn={}", turnId, error);
            if (handle.reason.get() == null) {
                repository.markTurnEnded(turnId, "FAILED", "INTERNAL");
                events.appendNext(conversationId, turnId, "error",
                        Map.of("turnId", turnId, "message",
                                error.getMessage() == null ? "生成失败" : error.getMessage()));
            }
        }
        finally {
            handles.remove(turnId);
        }
    }

    /**
     * 取消一轮。
     *
     * @return 取消时该轮的状态（QUEUED / RUNNING）；不处于这两种状态返回 empty
     */
    public java.util.Optional<String> cancel(long conversationId, long turnId) {
        return repository.findTurn(turnId)
                .filter(turn -> ((Number) turn.get("conversationId")).longValue() == conversationId)
                .map(turn -> {
                    String status = String.valueOf(turn.get("status"));
                    Handle handle = handles.get(turnId);

                    if ("QUEUED".equals(status)) {
                        // 排队中取消：不打断正在跑的那一轮
                        if (handle != null) {
                            handle.reason.compareAndSet(null, Interrupt.CANCELLED);
                            Future<?> future = handle.future;
                            if (future != null) {
                                future.cancel(true);
                            }
                        }
                        repository.markTurnEnded(turnId, "CANCELLED", "QUEUED_CANCELLED");
                        events.appendNext(conversationId, turnId, "turn/cancelled",
                                Map.of("turnId", turnId, "stage", "QUEUED",
                                        "message", "已取消排队的请求"));
                        return status;
                    }

                    if ("RUNNING".equals(status)) {
                        // 执行中中断：让它停下来，再由这里的调用方发事件
                        if (handle != null) {
                            handle.reason.compareAndSet(null, Interrupt.CANCELLED);
                        }
                        agent.cancel(conversationId);
                        repository.markTurnEnded(turnId, "CANCELLED", "RUNNING_CANCELLED");
                        events.appendNext(conversationId, turnId, "turn/interrupted",
                                Map.of("turnId", turnId, "stage", "RUNNING",
                                        "message", "已中断正在生成的回答"));
                        return status;
                    }

                    return status; // 终态，调用方据此返回 409
                });
    }

    public List<Map<String, Object>> turns(long conversationId) {
        return repository.listTurns(conversationId);
    }

    /** @param position 1 = 正在执行，2 = 排队中 */
    public record SubmittedTurn(long turnId, long cursor, int position) {}
}
