package io.agentscope.platform.server.sandbox;

import java.nio.file.Path;

/**
 * 沙箱后端。业务代码只依赖这个接口，Docker / Kubernetes 各自实现。
 *
 * <p>为什么不用框架的 `SandboxClient` 直接当业务接口：那是框架类型，散进业务代码后
 * 每次升级都要全仓改（见 `agent/` 包的同名约定）。这里只暴露"起容器、执行命令、
 * 传文件、销毁"四件事。
 *
 * <p><b>进程模型</b>：一个会话一个沙箱（`IsolationScope.SESSION`）。销毁必须幂等——
 * 沙箱可能已经被 TTL 回收或被运维手工删掉。
 */
public interface SandboxBackend {

    /**
     * 为某个会话创建沙箱；已存在则直接返回（幂等）。
     *
     * @param sessionKey 会话标识（用它做容器名，便于排查与回收）
     */
    Sandbox ensure(String sessionKey);

    /** 销毁；不存在也算成功。 */
    void destroy(String sessionKey);
}
