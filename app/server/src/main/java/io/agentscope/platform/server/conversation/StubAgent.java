package io.agentscope.platform.server.conversation;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 桩 agent：输出完全可预测，便于测试逐字断言。
 *
 * <p>为什么先放桩而不是直接接 HarnessAgent：#3 要验证的是"提交即返回 + 事件流 + 断线游标"
 * 这条链路，模型输出会引入不确定性，让失败定位变得困难。接真模型是 #4。
 */
@Component
public class StubAgent {

    /** 固定回复，测试与前端都能依赖。 */
    static final List<String> REPLY_CHUNKS = List.of(
            "收到：", "这是", "桩 agent", "的回复。", "（接入真实模型见 #4）");

    public void reply(String input, Consumer<String> onChunk) {
        for (String chunk : REPLY_CHUNKS) {
            onChunk.accept(chunk);
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
