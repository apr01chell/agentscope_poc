package io.agentscope.platform.server.agent;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 的 {@link AgentRuntime} 实现。
 *
 * <p><b>框架类型只能出现在这个类里</b>（以及同包的依赖声明中）。上层不得 import 任何
 * `io.agentscope.*` —— 框架在 2.0 已经有过一次 breaking change，把它的类型散到业务代码里
 * 意味着每次升级都要全仓改。
 *
 * <p>用 OpenAI 兼容协议接 DeepSeek：换别的供应商（Kimi/GLM/私有 vLLM）只需要改 baseUrl 与模型名。
 */
@Component
@ConditionalOnProperty(name = "app.model.provider", havingValue = "deepseek")
public class HarnessAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentRuntime.class);

    private final AgentProperties props;
    private final Path workspace;

    public HarnessAgentRuntime(
            AgentProperties props,
            @org.springframework.beans.factory.annotation.Value("${app.agent.workspace:/tmp/agentscope}")
            String workspace) {
        this.props = props;
        this.workspace = Path.of(workspace);
    }

    @Override
    public void stream(String input, TokenSink sink) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            // 明确告诉用户缺什么，而不是抛一个看不懂的 401
            sink.error("未配置模型密钥：请设置环境变量 DEEPSEEK_API_KEY（或 app.model.api-key）");
            return;
        }

        HarnessAgent agent = null;
        try {
            Files.createDirectories(workspace);
            agent = HarnessAgent.builder()
                    .name("workbench")
                    .agentId("workbench")
                    .workspace(workspace)
                    .model(model())
                    .build();

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Flux<Object> events = agent.streamEvents(input).cast(Object.class);
            events.doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            sink.token(delta.getDelta());
                        }
                    })
                    .doOnError(failure::set)
                    .doFinally(signal -> finished.countDown())
                    // 放到弹性线程池：Flux 订阅本身是阻塞的，不能占用调用方的工作线程
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();

            if (!finished.await(props.timeoutSeconds(), TimeUnit.SECONDS)) {
                sink.error("模型响应超时（" + props.timeoutSeconds() + " 秒）");
                return;
            }
            if (failure.get() != null) {
                log.warn("模型调用失败", failure.get());
                sink.error(readable(failure.get()));
                return;
            }
            sink.done();
        }
        catch (java.io.IOException ioFailure) {
            log.warn("agent 工作区不可写: {}", workspace, ioFailure);
            sink.error("agent 工作区不可写：" + workspace + "（检查 app.agent.workspace 配置）");
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
