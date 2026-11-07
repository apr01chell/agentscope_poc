package io.agentscope.platform.server.agent;

/**
 * 平台的 agent 运行时抽象。
 *
 * <p>这层存在的唯一目的：**框架类型不越过这条线**。上层（TurnService、Controller）只知道这个接口，
 * 换成别的 agent 框架时只需要重写实现。接 AgentScope 的实现见 {@link HarnessAgentRuntime}。
 */
public interface AgentRuntime {

    /**
     * 流式回答。
     *
     * <p>实现必须在**当前线程**上把事件推给 sink（同步或阻塞等待都行），
     * 因为调用方（TurnService 的工作线程）要做顺序保证：先 token 后 done。
     */
    void stream(String input, TokenSink sink);

    /** 事件出口。实现方保证 done 与 error 至多发生一个。 */
    interface TokenSink {
        void token(String delta);

        void done();

        void error(String message);
    }
}
