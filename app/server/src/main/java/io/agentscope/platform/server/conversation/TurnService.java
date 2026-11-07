package io.agentscope.platform.server.conversation;

import io.agentscope.platform.server.agent.AgentRuntime;
import io.agentscope.platform.server.event.EventLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 轮次编排。
 *
 * <p>不关心 agent 是桩还是真模型：只依赖平台的 {@link AgentRuntime}，
 * 把它的三类回调翻译成事件流。接真实模型是 #4，事件归档是 #5。
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final ConversationRepository repository;
    private final EventLog events;
    private final AgentRuntime agent;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public TurnService(ConversationRepository repository, EventLog events, AgentRuntime agent) {
        this.repository = repository;
        this.events = events;
        this.agent = agent;
    }

    /** 提交一轮：立即返回 turnId 与 cursor，实际生成在后台线程里跑。 */
    public SubmittedTurn submit(long conversationId, String input) {
        int seq = repository.nextSeq(conversationId);
        long turnId = repository.createTurn(conversationId, seq, input);
        repository.touchConversation(conversationId);

        String cursor = events.append(conversationId, "turn/queued", Map.of(
                "turnId", String.valueOf(turnId),
                "seq", String.valueOf(seq)));

        executor.submit(() -> run(conversationId, turnId, input));
        return new SubmittedTurn(turnId, cursor);
    }

    private void run(long conversationId, long turnId, String input) {
        try {
            repository.markTurn(turnId, "RUNNING");
            events.append(conversationId, "turn/started", Map.of("turnId", String.valueOf(turnId)));

            agent.stream(input, new AgentRuntime.TokenSink() {
                @Override
                public void token(String delta) {
                    events.append(conversationId, "token", Map.of(
                            "turnId", String.valueOf(turnId),
                            "delta", delta));
                }

                @Override
                public void done() {
                    repository.markTurn(turnId, "DONE");
                    events.append(conversationId, "done", Map.of(
                            "turnId", String.valueOf(turnId),
                            "usage", "prompt=0 completion=0"));
                }

                @Override
                public void error(String message) {
                    repository.markTurn(turnId, "FAILED");
                    events.append(conversationId, "error", Map.of(
                            "turnId", String.valueOf(turnId),
                            "message", message == null ? "生成失败" : message));
                }
            });
        }
        catch (RuntimeException error) {
            // 兜底：adapter 自身抛异常（而不是走 sink.error）时也要给前端一个可读结果
            log.warn("轮次执行失败 turn={}", turnId, error);
            repository.markTurn(turnId, "FAILED");
            events.append(conversationId, "error", Map.of(
                    "turnId", String.valueOf(turnId),
                    "message", error.getMessage() == null ? "生成失败" : error.getMessage()));
        }
    }

    public List<Map<String, Object>> turns(long conversationId) {
        return repository.listTurns(conversationId);
    }

    public record SubmittedTurn(long turnId, String cursor) {}
}
