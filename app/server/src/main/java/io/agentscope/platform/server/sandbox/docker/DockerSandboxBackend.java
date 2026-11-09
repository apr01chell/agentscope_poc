package io.agentscope.platform.server.sandbox.docker;

import io.agentscope.platform.server.sandbox.Sandbox;
import io.agentscope.platform.server.sandbox.SandboxBackend;
import io.agentscope.platform.server.sandbox.SandboxException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用本机 Docker 实现的沙箱后端（开发环境；生产换 Kubernetes + agent-sandbox CRD）。
 *
 * <p>为什么走 CLI 而不是 docker-java：这里只需要 create/exec/cp/rm 四个动作，
 * CLI 没有额外的依赖与 Docker Engine API 版本耦合；错误信息也直接可读。
 * 代价是解析文本，所以只看退出码与标准输出，不解析复杂格式。
 *
 * <p><b>镜像契约</b>：必须满足 POSIX 基础工具（`sh`、GNU `stat -c`、`tar`、`base64`）。
 * alpine 的 busybox `stat` 不支持 `-c`，distroless 连 `sh` 都没有，都会让 agent 的工具
 * 直接失效。默认用 Ubuntu 系的 JRE 镜像，契约测试会在真实 Docker 上验证。
 */
@Component
public class DockerSandboxBackend implements SandboxBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxBackend.class);

    /** 容器名前缀：便于一眼认出是哪个系统的容器，回收时也好匹配。 */
    static final String NAME_PREFIX = "agentscope-sbx-";

    private final String image;
    private final String workspaceRoot;
    private final String network;
    private final long execTimeoutSeconds;

    public DockerSandboxBackend(
            @Value("${app.sandbox.image:ubuntu:24.04}") String image,
            @Value("${app.sandbox.workspace-root:/workspace}") String workspaceRoot,
            @Value("${app.sandbox.network:none}") String network,
            @Value("${app.sandbox.exec-timeout-seconds:60}") long execTimeoutSeconds) {
        this.image = image;
        this.workspaceRoot = workspaceRoot;
        this.network = network;
        this.execTimeoutSeconds = execTimeoutSeconds;
    }

    @Override
    public Sandbox ensure(String sessionKey) {
        String name = containerName(sessionKey);
        if (!isRunning(name)) {
            runQuietly(List.of(
                    "docker", "run", "-d", "--name", name,
                    "-w", workspaceRoot,
                    // 沙箱里跑的是模型生成的脚本：默认断开外网，需要联网的会话由上层显式放开
                    "--network", network,
                    image, "sleep", "infinity"));
        }
        return new DockerSandbox(name, execTimeoutSeconds);
    }

    @Override
    public void destroy(String sessionKey) {
        String name = containerName(sessionKey);
        // 幂等：容器可能已经被 TTL 回收或人工删掉了
        runQuietly(List.of("docker", "rm", "-f", name));
    }

    /** 容器名只允许 [a-zA-Z0-9_.-]，其余字符替换掉，避免非法名字与注入。 */
    static String containerName(String sessionKey) {
        String safe = sessionKey.replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (safe.length() > 40) {
            safe = safe.substring(0, 40);
        }
        return NAME_PREFIX + safe;
    }

    private boolean isRunning(String name) {
        try {
            Process process = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(10, TimeUnit.SECONDS);
            return "true".equals(output);
        }
        catch (IOException | InterruptedException probe) {
            if (probe instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private void runQuietly(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SandboxException("docker 命令超时：" + String.join(" ", command));
            }
            if (process.exitValue() != 0 && !output.contains("No such container")) {
                throw new SandboxException("docker 命令失败（" + process.exitValue() + "）：" + output.trim());
            }
        }
        catch (IOException error) {
            throw new SandboxException("无法调用 docker CLI，确认本机已安装并启动 Docker", error);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SandboxException("docker 命令被中断", interrupted);
        }
    }

    /** 单引号包裹，内部的单引号按 POSIX 规则转义。 */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** 一个容器对应一个 {@link Sandbox}。 */
    static final class DockerSandbox implements Sandbox {

        private final String name;
        private final long execTimeoutSeconds;

        DockerSandbox(String name, long execTimeoutSeconds) {
            this.name = name;
            this.execTimeoutSeconds = execTimeoutSeconds;
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public ExecResult exec(String command) throws IOException, InterruptedException {
            Path out = Files.createTempFile("sbx-out", ".txt");
            Path err = Files.createTempFile("sbx-err", ".txt");
            try {
                Process process = new ProcessBuilder("docker", "exec", name, "sh", "-lc", command)
                        // 重定向到文件而不是管道：两条流都可能有大量输出，
                        // 顺序读取会造成管道写满而死锁
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile())
                        .start();
                if (!process.waitFor(execTimeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return new ExecResult(124, "", "命令执行超时（" + execTimeoutSeconds + " 秒）");
                }
                return new ExecResult(
                        process.exitValue(),
                        Files.readString(out, StandardCharsets.UTF_8),
                        Files.readString(err, StandardCharsets.UTF_8));
            }
            finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
            }
        }

        @Override
        public void write(String path, InputStream content) throws IOException {
            String script = "mkdir -p \"$(dirname " + quote(path) + ")\" && cat > " + quote(path);
            Process process = new ProcessBuilder("docker", "exec", "-i", name, "sh", "-c", script).start();
            try (OutputStream stdin = process.getOutputStream()) {
                content.transferTo(stdin);
            }
            try {
                if (!process.waitFor(execTimeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new SandboxException("写入超时：" + path);
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SandboxException("写入被中断：" + path, interrupted);
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new SandboxException("写入失败（" + process.exitValue() + "）：" + err.trim());
            }
        }

        @Override
        public InputStream read(String path) throws IOException {
            Process process = new ProcessBuilder("docker", "exec", name, "cat", path).start();
            // 调用方负责关闭；未消费完时由 docker exec 进程结束兜底
            return process.getInputStream();
        }
    }
}
