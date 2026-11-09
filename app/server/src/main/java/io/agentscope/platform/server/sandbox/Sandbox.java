package io.agentscope.platform.server.sandbox;

import java.io.IOException;
import java.io.InputStream;

/** 一个运行中的沙箱。 */
public interface Sandbox {

    /** 容器/实例标识，用于日志与排查。 */
    String id();

    /**
     * 在沙箱里执行一条 shell 命令。
     *
     * @return 退出码与输出；非 0 退出码**不抛异常**——那是命令的正常结果，不是基础设施故障
     */
    ExecResult exec(String command) throws IOException, InterruptedException;

    /** 写文件（覆盖）。父目录不存在时自动创建。 */
    void write(String path, InputStream content) throws IOException;

    /** 读文件。 */
    InputStream read(String path) throws IOException;

    /** 同 #exec，但失败（非 0 退出码）时抛异常，适合"必须成功"的场景。 */
    default ExecResult execOrThrow(String command) throws IOException, InterruptedException {
        ExecResult result = exec(command);
        if (result.exitCode() != 0) {
            throw new SandboxException("命令执行失败（exit=" + result.exitCode() + "）：" + result.stderr());
        }
        return result;
    }

    record ExecResult(int exitCode, String stdout, String stderr) {}
}
