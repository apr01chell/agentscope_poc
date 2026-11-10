package io.agentscope.platform.server.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import io.agentscope.harness.agent.session.StoreSessionStorage;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 的 {@link AgentRuntime} 实现。
 *
 * <p><b>框架类型只能出现在这个类里</b>（以及同包的依赖声明中）。上层不得 import 任何
 * `io.agentscope.*` —— 框架在 2.0 已经有过一次 breaking change，把它的类型散到业务代码里
 * 意味着每次升级都要全仓改。
 *
 * <p>持久化全部交给 {@link DistributedStore}（MySQL）：
 * 工作区用 {@link RemoteFilesystemSpec}，所以 `sessions/`（会话日志）、`MEMORY.md`、
 * `skills/` 都不落本地磁盘。容器非 root、多副本不共享磁盘，落文件等于换实例就失忆。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "deepseek")
public class HarnessAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentRuntime.class);

    private final AgentProperties props;
    private final DistributedStore store;
    private final Path workspace;
    private final boolean sandboxEnabled;
    private final String sandboxImage;
    private final io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget artifactDeliveryTarget;

    /** 正在进行的订阅，按会话 id 索引，供中断使用。 */
    private final Map<Long, Disposable> running = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public HarnessAgentRuntime(
            AgentProperties props,
            @Autowired(required = false) DistributedStore store,
            @Value("${app.agent.workspace:/tmp/agentscope}") String workspace,
            @Value("${app.sandbox.enabled:false}") boolean sandboxEnabled,
            @Value("${app.sandbox.image:ubuntu:24.04}") String sandboxImage,
            io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget artifactDeliveryTarget) {
        this.props = props;
        this.store = store;
        this.workspace = Path.of(workspace);
        this.sandboxEnabled = sandboxEnabled;
        this.sandboxImage = sandboxImage;
        this.artifactDeliveryTarget = artifactDeliveryTarget;
    }

    @Override
    public void stream(AgentRequest request, TokenSink sink) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            // 明确告诉用户缺什么，而不是抛一个看不懂的 401
            sink.error("未配置模型密钥：请设置环境变量 DEEPSEEK_API_KEY（或 app.model.api-key）");
            return;
        }

        HarnessAgent agent = null;
        try {
            agent = buildAgent();

            long startedAt = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicLong firstTokenAt =
                    new java.util.concurrent.atomic.AtomicLong(0);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // userId + sessionId 是 agent 状态的寻址键。缺了 sessionId，所有会话会共用
            // 一份上下文（串味），这是之前真实存在的 bug。
            RuntimeContext context = RuntimeContext.builder()
                    .userId(request.userId())
                    .sessionId(String.valueOf(request.conversationId()))
                    .build();

            Flux<Object> events = agent.streamEvents(request.input(), context).cast(Object.class);
            Disposable subscription = events
                    .doOnNext(event -> {
                        if (cancelled.contains(request.conversationId())) {
                            return;
                        }
                        if (event instanceof TextBlockDeltaEvent delta) {
                            // 首 token 延迟包含沙箱冷启动（框架每轮重建容器），
                            // 这是判断"要不要为保活付架构代价"的关键数字
                            firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                            sink.token(delta.getDelta());
                        }
                    })
                    .doOnError(failure::set)
                    .doFinally(signal -> finished.countDown())
                    // 放到弹性线程池：Flux 订阅本身是阻塞的，不能占用调用方的工作线程
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            running.put(request.conversationId(), subscription);

            if (!finished.await(props.timeoutSeconds(), TimeUnit.SECONDS)) {
                sink.error("模型响应超时（" + props.timeoutSeconds() + " 秒）");
                return;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            long firstToken = firstTokenAt.get();
            log.info("轮次耗时 conversation={} 首token={}ms 总计={}ms",
                    request.conversationId(),
                    firstToken == 0 ? -1 : firstToken - startedAt,
                    elapsed);

            if (cancelled.remove(request.conversationId())) {
                // 被中断：终止事件由上层发，这里保持沉默
                return;
            }
            if (failure.get() != null) {
                log.warn("模型调用失败", failure.get());
                sink.error(readable(failure.get()));
                return;
            }
            sink.done();
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sink.error("被中断");
        }
        catch (RuntimeException error) {
            log.warn("agent 运行失败", error);
            sink.error(readable(error));
        }
        finally {
            running.remove(request.conversationId());
            if (agent != null) {
                // 框架建议每次请求构建后关闭，避免会话状态在实例间串味
                try {
                    agent.close();
                }
                catch (RuntimeException ignored) {
                    // 关闭失败不该盖住真正的错误
                }
            }
        }
    }

    @Override
    public void cancel(long conversationId) {
        cancelled.add(conversationId);
        Disposable subscription = running.remove(conversationId);
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private HarnessAgent buildAgent() {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("workbench")
                .agentId("workbench")
                .model(model())
                // 产物交付：落到 RustFS + artifact 表（#9）
                .artifactDeliveryTarget(artifactDeliveryTarget);

        // 会话状态与会话日志始终落 MySQL：沙箱是可丢弃的，不能让它承载记忆
        if (store != null) {
            builder.distributedStore(store);
        }

        if (sandboxEnabled && store != null) {
            // 沙箱文件系统不提供持久化会话存储（容器随时会被销毁），
            // 所以显式把 journal 指到 MySQL 的 BaseStore 上：用官方 JournalSessionLog +
            // StoreSessionStorage，不自己实现 SessionLogStore 的并发语义。
            SessionLogStore logStore = (key, context) -> {
                String path = key.storagePath();
                return new JournalSessionLog(
                        new StoreSessionStorage(store.baseStore(), java.util.List.of("agents/")),
                        path);
            };
            builder.sessionLogStore(logStore);
        }

        if (sandboxEnabled) {
            // 代码执行下沉到容器：shell / 读写文件都发生在沙箱里。
            // 沙箱文件系统是分层的——AGENTS.md / skills / knowledge 由外层投影进沙箱，
            // 会话日志仍走上面的 distributedStore。
            builder.filesystem(new DockerFilesystemSpec()
                    .image(sandboxImage)
                    .workspaceRoot("/workspace"));
        }
        else if (store != null) {
            // 不用沙箱时，工作区整体走远程存储；agents/ 是会话日志所在，必须显式路由
            builder.filesystem(new RemoteFilesystemSpec()
                            .isolationScope(IsolationScope.SESSION)
                            .addSharedPrefix("agents/"))
                    .workspace(workspace);
        }
        return builder.build();
    }

    private Model model() {
        return OpenAIChatModel.builder()
                .apiKey(props.apiKey())
                .baseUrl(props.baseUrl())
                .modelName(props.name())
                .stream(true)
                .build();
    }

    /** 把底层异常转成人能读的一句话，前端直接展示。 */
    private static String readable(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "模型调用失败：" + error.getClass().getSimpleName();
        }
        return "模型调用失败：" + message;
    }
}
