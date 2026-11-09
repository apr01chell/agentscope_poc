package io.agentscope.platform.server.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.platform.server.sandbox.docker.DockerSandboxBackend;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 沙箱契约：跑在**真实 Docker** 上，没有 Docker 就跳过。
 *
 * <p>用随机会话键互不干扰；每个用例结束都销毁，避免留下垃圾容器。
 */
class DockerSandboxContractTest {

    private static boolean dockerAvailable;

    private final String sessionKey = "test-" + UUID.randomUUID();
    private final DockerSandboxBackend backend =
            new DockerSandboxBackend("ubuntu:24.04", "/workspace", "none", 60);

    @BeforeAll
    static void detectDocker() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            dockerAvailable = process.waitFor() == 0;
        }
        catch (IOException | InterruptedException probe) {
            if (probe instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            dockerAvailable = false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (dockerAvailable) {
            backend.destroy(sessionKey);
        }
    }

    @Test
    void createsSandboxAndExecutesShell() throws Exception {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        Sandbox sandbox = backend.ensure(sessionKey);
        Sandbox.ExecResult result = sandbox.exec("echo hello && pwd");

        assertEquals(0, result.exitCode(), "stderr=" + result.stderr());
        assertTrue(result.stdout().contains("hello"));
        assertTrue(result.stdout().contains("/workspace"), "工作目录应为 /workspace：" + result.stdout());
    }

    @Test
    void sandboxIsIdempotentPerSession() {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        String first = backend.ensure(sessionKey).id();
        String second = backend.ensure(sessionKey).id();

        assertEquals(first, second, "同一会话重复 ensure 不应起第二个容器");
    }

    @Test
    void posixContractHolds() throws Exception {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        Sandbox sandbox = backend.ensure(sessionKey);
        // agent 的工具链依赖这四个：sh、GNU stat -c、tar、base64
        sandbox.execOrThrow("sh -c 'echo ok'");
        sandbox.execOrThrow("stat -c %s /etc/hostname");
        sandbox.execOrThrow("tar --version");
        sandbox.execOrThrow("base64 /etc/hostname");
    }

    @Test
    void writesAndReadsFiles() throws Exception {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        Sandbox sandbox = backend.ensure(sessionKey);
        String content = "第一行\nsecond line\n";
        sandbox.write("/workspace/nested/dir/note.txt",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        String readBack = new String(
                sandbox.read("/workspace/nested/dir/note.txt").readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(content, readBack, "写入后应能原样读回（含中文）");

        assertEquals(0, sandbox.exec("test -f /workspace/nested/dir/note.txt").exitCode());
    }

    @Test
    void nonZeroExitIsAResultNotAnInfrastructureFailure() throws Exception {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        Sandbox sandbox = backend.ensure(sessionKey);
        Sandbox.ExecResult result = sandbox.exec("ls /definitely-not-here");

        assertFalse(result.exitCode() == 0);
        assertFalse(result.stderr().isBlank(), "错误输出应保留，便于 agent 判断");
    }

    @Test
    void destroyIsIdempotent() {
        assumeTrue(dockerAvailable, "本机没有 Docker，跳过契约测试");

        backend.ensure(sessionKey);
        backend.destroy(sessionKey);
        backend.destroy(sessionKey); // 第二次不应抛异常
    }
}
