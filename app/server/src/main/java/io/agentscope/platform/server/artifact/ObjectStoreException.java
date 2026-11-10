package io.agentscope.platform.server.artifact;

/** 对象存储故障（不是业务校验失败）。 */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(String message) {
        super(message);
    }

    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
