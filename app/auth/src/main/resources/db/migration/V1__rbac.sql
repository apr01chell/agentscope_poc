-- RBAC 基础表。
-- 全库不使用数据库外键：关联完整性由应用层在同一事务里显式维护（见 docs/design/14-auth-oidc.md §5.1）。

CREATE TABLE tenant (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `user` (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    -- 内置角色不可删：否则误删 ADMIN 之后无人能进控制台。
    built_in    TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_code (tenant_id, code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 权限点是全局的（不带 tenant_id）：由代码定义、随版本发布。
-- 租户只能组合角色，不能造权限码，否则会出现平台不认识的权限。
CREATE TABLE permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    resource    VARCHAR(32)  NOT NULL,
    action      VARCHAR(32)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_perm (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 审计事件：本工单只写，查询界面在 #23。
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    actor_id    BIGINT       DEFAULT NULL,
    actor_name  VARCHAR(64)  DEFAULT NULL,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(32)  DEFAULT NULL,
    target_id   VARCHAR(64)  DEFAULT NULL,
    detail      VARCHAR(1000) DEFAULT NULL,
    result      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_tenant_time (tenant_id, created_at),
    KEY idx_audit_actor (actor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------- 种子 ----------

INSERT INTO tenant (id, code, name, status) VALUES (1, 'default', '默认租户', 'ACTIVE');

INSERT INTO role (id, tenant_id, code, name, description, built_in) VALUES
    (1, 1, 'ADMIN', '管理员', '拥有全部权限，可管理用户、角色与客户端', 1),
    (2, 1, 'USER', '普通用户', '仅可使用 Agent 平台，不进入控制台', 1);

INSERT INTO permission (code, name, resource, action, description) VALUES
    ('user:read',             '查看用户',   'user',       'read',          '查看用户列表与详情'),
    ('user:write',            '创建/修改用户', 'user',    'write',         '创建用户、修改显示名/状态/密码'),
    ('user:delete',           '禁用用户',   'user',       'delete',        '软删除用户（status=DISABLED）'),
    ('role:read',             '查看角色',   'role',       'read',          '查看角色列表'),
    ('role:write',            '创建/修改角色', 'role',    'write',         '创建角色、改名称描述、设置权限'),
    ('role:delete',           '删除角色',   'role',       'delete',        '删除非内置角色'),
    ('role:assign',           '分配角色',   'role',       'assign',        '给用户设置角色集合'),
    ('permission:read',       '查看权限点', 'permission', 'read',          '查看权限点全集'),
    ('client:read',           '查看客户端', 'client',     'read',          '查看 OAuth2 客户端列表（不含 secret）'),
    ('client:write',          '创建/修改客户端', 'client', 'write',        '创建客户端、改 redirect_uri 与 scopes'),
    ('client:delete',         '删除客户端', 'client',     'delete',        '删除客户端'),
    ('client:rotate-secret',  '轮换客户端密钥', 'client', 'rotate-secret', '轮换 client secret'),
    ('audit:read',            '查看审计日志', 'audit',    'read',          '查询审计事件'),
    ('tenant:read',           '查看租户',   'tenant',     'read',          '查看租户信息');

-- ADMIN 拥有全部权限点
INSERT INTO role_permission (role_id, permission_id)
SELECT 1, p.id FROM permission p;
