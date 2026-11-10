-- 产物：agent 产出的文件落到对象存储，这里只存元数据。
-- object_key 一律由服务端生成，绝不采信沙箱/模型给的名字（安全铁律见设计文档 §10）。
CREATE TABLE artifact (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT       NOT NULL,
    owner_sub       VARCHAR(64)  NOT NULL,
    tenant          VARCHAR(64)  NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    object_key      VARCHAR(512) NOT NULL,
    content_type    VARCHAR(128) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_artifact_conversation (conversation_id, id),
    KEY idx_artifact_owner (owner_sub, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
