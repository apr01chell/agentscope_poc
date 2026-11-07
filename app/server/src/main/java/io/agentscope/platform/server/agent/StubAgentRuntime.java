package io.agentscope.platform.server.agent;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 离线桩：输出完全可预测，测试与本地无 key 调试用。
 *
 * <p>与真实模型的接口完全一致（{@link AgentRuntime}），所以上层的流式链路
 * 在两种实现下走的是同一条代码路径。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "stub", matchIfMissing = true)
public class StubAgentRuntime implements AgentRuntime {

    static final List<String> REPLY_CHUNKS =
            List.of("收到：", "这是", "桩 agent", "的回复。", "（接入真实模型见 #4）");

    @Override
    public void stream(String input, TokenSink sink) {
        try {
            for (String chunk : REPLY_CHUNKS) {
                sink.token(chunk);
                Thread.sleep(20);
            }
            sink.done();
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sink.error("被中断");
        }
    }
}
