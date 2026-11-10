package io.agentscope.platform.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真实模型适配层的失败路径：没有密钥时必须给出可读原因，而不是白屏或 500。 */
class HarnessAgentRuntimeTest {

    @Test
    void missingApiKeyProducesReadableError() {
        HarnessAgentRuntime runtime = new HarnessAgentRuntime(
                new AgentProperties("deepseek", "", "https://api.deepseek.com", "deepseek-chat", 5),
                null,
                "/tmp/agentscope-test",
                false,
                "ubuntu:24.04",
                null);

        List<String> errors = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        runtime.stream(new AgentRuntime.AgentRequest("1", 1L, "你好"), new AgentRuntime.TokenSink() {
            @Override
            public void token(String delta) {
                tokens.add(delta);
            }

            @Override
            public void done() {
                // 不应该走到这里
            }

            @Override
            public void error(String message) {
                errors.add(message);
            }
        });

        assertEquals(1, errors.size(), "应恰好报一次错");
        assertTrue(errors.get(0).contains("DEEPSEEK_API_KEY"), "错误信息应指出该配哪个变量：" + errors.get(0));
        assertTrue(tokens.isEmpty(), "没有密钥时不应产生任何 token");
    }
}
