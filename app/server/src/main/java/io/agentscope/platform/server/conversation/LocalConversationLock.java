package io.agentscope.platform.server.conversation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内互斥，**只用于离线桩模式**（测试与无 key 调试）。
 *
 * <p>生产环境走 {@code agent/GuardConversationLock}（框架的 JDBC 租约，多副本有效）。
 * 这个实现能锁住单进程就够了——桩模式下没有真实并发场景，注释写清楚是为了
 * 避免以后有人把它当成正式实现。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "stub", matchIfMissing = true)
public class LocalConversationLock implements ConversationLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Lease acquire(String userId, String sessionId) throws InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, key -> new ReentrantLock());
        lock.lockInterruptibly();
        return lock::unlock;
    }
}
