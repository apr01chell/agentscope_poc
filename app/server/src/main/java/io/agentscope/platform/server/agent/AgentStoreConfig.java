package io.agentscope.platform.server.agent;

import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.harness.agent.DistributedStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 AgentScope 的分布式存储接到我们自己的 MySQL 上。
 *
 * <p>一次注入覆盖四类数据：agent 状态（对话上下文/压缩摘要）、工作区 KV
 * （`MEMORY.md`、`skills/`、`sessions/` 会话日志）、沙箱快照、沙箱并发锁。
 * 方言由驱动自动识别（这里就是 MySQL），表由框架启动时创建（`agentscope_` 前缀）。
 *
 * <p>为什么不用文件：容器以非 root 运行、且多副本之间不共享磁盘，
 * 落到本地文件等于"换个实例就失忆"。
 */
@Configuration
@ConditionalOnProperty(name = "app.model.provider", havingValue = "deepseek")
public class AgentStoreConfig {

    @Bean
    public DistributedStore distributedStore(DataSource dataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
                .tablePrefix("agentscope_")            // 默认 agentscope_
                .build();
        return JdbcDistributedStore.create(dataSource, dialect);
    }
}
