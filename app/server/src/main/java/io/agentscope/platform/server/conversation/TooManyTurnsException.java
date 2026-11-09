package io.agentscope.platform.server.conversation;

/** 同会话并发超限（1 执行中 + 1 排队），映射为 429。 */
public class TooManyTurnsException extends RuntimeException {

    public TooManyTurnsException(String message) {
        super(message);
    }
}
