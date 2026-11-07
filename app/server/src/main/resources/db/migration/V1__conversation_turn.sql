-- 会话与轮次。事件流在 Redis Stream，落库归档留给 #5。
-- 全库无外键：关联完整性由应用层维护（与 auth 模块一致）。

CREATE TABLE conversation (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_code  VARCHAR(64)  NOT NULL,
    owner_sub    VARCHAR(64)  NOT NULL,   -- OIDC 的 sub，即 user.id
    title        VARCHAR(200) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_turn_at DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_conversation_owner (tenant_code, owner_sub, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE turn (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    seq             INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,   -- QUEUED | RUNNING | DONE | FAILED
    input_text      TEXT         NOT NULL,
    error_code      VARCHAR(64)  DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at      DATETIME     DEFAULT NULL,
    ended_at        DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_turn_conversation_seq (conversation_id, seq),
    KEY idx_turn_conversation (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
