package io.agentscope.platform.server.conversation;

import io.agentscope.platform.server.agent.AgentRuntime;
import io.agentscope.platform.server.event.EventStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 轮次编排。
 *
 * <p>不关心 agent 是桩还是真模型：只依赖平台的 {@link AgentRuntime}，
 * 把三类回调翻译成事件并落库（落库是 #5 的事，重放靠它）。
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final ConversationRepository repository;
    private final EventStore events;
    private final AgentRuntime agent;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public TurnService(ConversationRepository repository, EventStore events, AgentRuntime agent) {
        this.repository = repository;
        this.events = events;
        this.agent = agent;
    }

    /** 提交一轮：立即返回 turnId 与 cursor，实际生成在后台线程里跑。 */
    public SubmittedTurn submit(long conversationId, String input, String ownerSub) {
        int turnSeq = repository.nextSeq(conversationId);
        long turnId = repository.createTurn(conversationId, turnSeq, input);
        repository.touchConversation(conversationId);

        // queued 事件带上 input，重放时前端才能还原用户气泡（事件流是渲染的唯一来源）
        long cursor = events.append(conversationId, turnId, 1, "turn/queued", Map.of(
                "turnId", turnId,
                "turnSeq", turnSeq,
                "input", input));

        executor.submit(() -> run(conversationId, turnId, input, ownerSub));
        return new SubmittedTurn(turnId, cursor);
    }

    private void run(long conversationId, long turnId, String input, String ownerSub) {
        AtomicInteger seq = new AtomicInteger(1);
        try {
            repository.markTurn(turnId, "RUNNING");
            events.append(conversationId, turnId, seq.incrementAndGet(), "turn/started", Map.of("turnId", turnId));

            AgentRuntime.AgentRequest request =
                    new AgentRuntime.AgentRequest(ownerSub, conversationId, input);
            agent.stream(request, new AgentRuntime.TokenSink() {
                @Override
                public void token(String delta) {
                    events.append(conversationId, turnId, seq.incrementAndGet(), "token",
                            Map.of("turnId", turnId, "delta", delta));
                }

                @Override
                public void done() {
                    repository.markTurn(turnId, "DONE");
                    events.append(conversationId, turnId, seq.incrementAndGet(), "done", Map.of("turnId", turnId));
                }

                @Override
                public void error(String message) {
                    repository.markTurn(turnId, "FAILED");
                    events.append(conversationId, turnId, seq.incrementAndGet(), "error",
                            Map.of("turnId", turnId, "message", message == null ? "生成失败" : message));
                }
            });
        }
        catch (RuntimeException error) {
            // 兜底：adapter 自己抛异常（而不是走 sink.error）时也要给前端一个可读结果
            log.warn("轮次执行失败 turn={}", turnId, error);
            repository.markTurn(turnId, "FAILED");
            events.append(conversationId, turnId, seq.incrementAndGet(), "error",
                    Map.of("turnId", turnId, "message",
                            error.getMessage() == null ? "生成失败" : error.getMessage()));
        }
    }

    public List<Map<String, Object>> turns(long conversationId) {
        return repository.listTurns(conversationId);
    }

    public record SubmittedTurn(long turnId, long cursor) {}
}
