package io.agentscope.platform.server.conversation;

import io.agentscope.platform.server.event.EventLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/**
 * 轮次编排。
 *
 * <p>当前 agent 是桩实现（{@link StubAgent}）：先把"提交即返回 202 + 事件流"这条链路跑通，
 * 接真实 HarnessAgent 是 #4 的事。桩的好处是事件序列完全可预测，测试能断言到逐字。
 */
@Service
public class TurnService {

    private final ConversationRepository repository;
    private final EventLog events;
    private final StubAgent agent;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public TurnService(ConversationRepository repository, EventLog events, StubAgent agent) {
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

            agent.reply(input, chunk -> events.append(conversationId, "token", Map.of(
                    "turnId", String.valueOf(turnId),
                    "delta", chunk)));

            repository.markTurn(turnId, "DONE");
            events.append(conversationId, "done", Map.of(
                    "turnId", String.valueOf(turnId),
                    "usage", "prompt=0 completion=0"));
        }
        catch (RuntimeException error) {
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
