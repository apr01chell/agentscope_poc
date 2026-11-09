package io.agentscope.platform.server.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
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

    /** 正在进行的订阅，按会话 id 索引，供中断使用。 */
    private final Map<Long, Disposable> running = new ConcurrentHashMap<>();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public HarnessAgentRuntime(
            AgentProperties props,
            @Autowired(required = false) DistributedStore store,
            @Value("${app.agent.workspace:/tmp/agentscope}") String workspace) {
        this.props = props;
        this.store = store;
        this.workspace = Path.of(workspace);
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
                .model(model());

        if (store != null) {
            builder.distributedStore(store)
                    // SESSION 而不是 USER：每个会话独立工作区，
                    // 否则同一用户的多个会话会共享 MEMORY.md 和文件，互相污染
                    .filesystem(new RemoteFilesystemSpec()
                            .isolationScope(IsolationScope.SESSION)
                            // 关键：会话日志（journal）落在工作区的 agents/ 前缀下。
                            // 不显式路由它，CompositeFilesystem 会把它交给本地文件系统，
                            // 于是 journal 又回到磁盘（容器里还会 AccessDenied）。
                            .addSharedPrefix("agents/"))
                    // 本地只做暂存；会话状态与日志走上面的分布式存储
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
