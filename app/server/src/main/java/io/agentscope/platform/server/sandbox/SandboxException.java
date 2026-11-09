package io.agentscope.platform.server.sandbox;

/** 沙箱基础设施故障（不是"命令返回非 0"）。 */
public class SandboxException extends RuntimeException {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
