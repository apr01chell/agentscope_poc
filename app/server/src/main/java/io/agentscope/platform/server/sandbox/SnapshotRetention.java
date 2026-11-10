package io.agentscope.platform.server.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 快照保留策略。
 *
 * <p>沙箱每轮结束都会落一份快照（框架托管模式），不清理的话
 * `agentscope_snapshots` 会无限增长。这里按时间淘汰：超过保留期的直接删。
 *
 * <p>代价说清楚：删掉的快照对应的会话如果再次发问，会**从零重建工作区**
 * （之前会话里写的文件不在了）。所以保留期要给得比"用户可能回来的时间"长。
 */
@Component
public class SnapshotRetention {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetention.class);

    private final JdbcClient jdbc;
    private final long retentionHours;

    public SnapshotRetention(
            JdbcClient jdbc,
            @Value("${app.sandbox.snapshot-retention-hours:24}") long retentionHours) {
        this.jdbc = jdbc;
        this.retentionHours = retentionHours;
    }

    /** 每小时清一次；用 SQL 直接按 created_at 淘汰，不读 data 列。 */
    @Scheduled(fixedDelayString = "${app.sandbox.snapshot-cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        try {
            int deleted = jdbc.sql("""
                            DELETE FROM agentscope_snapshots
                            WHERE created_at < DATE_SUB(NOW(), INTERVAL :hours HOUR)
                            """)
                    .param("hours", retentionHours)
                    .update();
            if (deleted > 0) {
                log.info("快照清理：删除 {} 份超过 {} 小时的快照", deleted, retentionHours);
            }
        }
        catch (RuntimeException error) {
            // 清理失败不影响主流程；下一轮再试
            log.warn("快照清理失败", error);
        }
    }
}
