-- 事件落库：SSE 的 cursor 就是这张表的自增 id。
-- 单来源（不叠 Redis Stream）的理由见 docs/design/v1-architecture.md 的 §5 说明：
-- 双存储会让"实时"和"重放"用两套游标，归档边界极易丢事件。

CREATE TABLE turn_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    turn_id         BIGINT       NOT NULL,
    seq             INT          NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    payload_json    TEXT         NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一轮内顺序稳定且可复现；重试写同一条会直接失败而不是产生重复
    UNIQUE KEY uk_turn_event_turn_seq (turn_id, seq),
    KEY idx_turn_event_conversation (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
