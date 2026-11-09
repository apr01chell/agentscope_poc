package io.agentscope.platform.server.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.platform.server.conversation.ConversationLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 用框架的 {@link SandboxExecutionGuard} 实现会话互斥。
 *
 * <p>不自己写锁：这个 guard 在 JDBC 后端下是数据库层面的租约（含超时与抢占），
 * 多副本之间天然互斥。自己用 `synchronized`/`ReentrantLock` 只能锁住单进程，
 * 部署两个实例就失效——而这正是"一个在跑、一个排队、第三个拒绝"必须防住的情况。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "deepseek")
public class GuardConversationLock implements ConversationLock {

    private final SandboxExecutionGuard guard;

    public GuardConversationLock(@Autowired(required = false) DistributedStore store) {
        this.guard = store != null ? store.sandboxExecutionGuard() : SandboxExecutionGuard.noop();
    }

    @Override
    public Lease acquire(String userId, String sessionId) throws InterruptedException {
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
        SandboxIsolationKey key = SandboxIsolationKey
                .resolve(IsolationScope.SESSION, context, "workbench")
                .orElseThrow(() -> new IllegalStateException("无法解析会话隔离键"));
        SandboxLease lease = guard.tryEnter(key);
        return lease::close;
    }
}
