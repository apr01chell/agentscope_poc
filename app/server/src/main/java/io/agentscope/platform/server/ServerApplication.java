package io.agentscope.platform.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 工作台后端入口。
 *
 * <p>这个类只负责启动；编排、SSE、skill/MCP/产物等能力按工单逐步接入。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
