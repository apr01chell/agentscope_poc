package io.agentscope.platform.server.conversation;

/**
 * 会话级互斥锁。
 *
 * <p>存在的理由是边界：真正的实现在 `agent/` 包里（复用框架的 `SandboxExecutionGuard`），
 * 但业务层不该 import 框架类型。接口只有"拿锁 / 放锁"两件事。
 *
 * <p>语义：{@link #acquire} **阻塞**直到拿到锁；被中断时抛 {@link InterruptedException}，
 * 调用方据此区分"排队被取消"和"排队超时"。
 */
public interface ConversationLock {

    Lease acquire(String userId, String sessionId) throws InterruptedException;

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
