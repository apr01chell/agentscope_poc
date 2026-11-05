# 用户中心技术方案（RBAC + 客户端管理 + OIDC + 独立控制台）

> 状态：**待评审**（未开始编码）
> 对应工单：#14 拆分为 A / B / C 三个（见 §12）
> 上位设计：[v1-architecture.md](./v1-architecture.md) §4.1、§4.2、§8

---

## 1. 目标

一个**独立部署、自带控制台**的用户中心：

- **RBAC**：用户 / 角色 / 用户角色 / 权限 / 角色权限 —— 五张表，角色是权限的集合
- **客户端管理**：管理员在控制台增删改 OAuth2 客户端（工作台、外部系统）
- **OIDC**：授权码 + PKCE、JWT、JWKS、`/userinfo`，供工作台与第三方系统对接

它是 #15（工作台切 OIDC）、#17（公共 MCP 授权）的前置依赖。**用户与角色管理只在这里做**，Agent 平台侧只保留审计日志查询（原 #23 收缩）。

## 2. 范围

**做**：

| 层 | 内容 |
|---|---|
| 数据 | `tenant` / `user` / `role` / `user_role` / `permission` / `role_permission` + SAS 标准 `oauth2_*` |
| 后端 | 登录、OIDC 协议端点、RBAC 管理 API、客户端管理 API |
| 前端 | **独立控制台**（Vue 3）：登录后进入，含用户 / 角色 / 权限 / 客户端四个页面；构建产物由独立 nginx 提供，与 auth 同源（§4） |

**不做**：

| 不做 | 原因 |
|---|---|
| 找回密码、邮箱验证、MFA | 不在 v1 |
| 多租户切换 | v1 恒为 `default` |
| 同意页个性化 | 关闭同意页 |
| 工作台校验 JWT（resource server） | #15 |
| 第三方 MCP 的 OAuth 客户端能力 | #18、#19 |
| 审计日志的**查询界面** | 留在 #23（Agent 平台侧），本工单只写审计事件 |

## 3. 技术选型

| 项 | 选择 | 依据 |
|---|---|---|
| 授权服务器 | **`spring-security-oauth2-authorization-server`（直接用库）** | 本模块显式定义全部 bean，starter 的属性/自动配置用不上；详见 §15 |
| SAS 版本 | **7.1.2**（Spring Security BOM 管理） | 已核实 `spring-security-bom-7.1.2.pom` |
| Spring Security | 7.1.2 | Boot 4.1.2 的 `spring.security.version` |
| 配置 API | `OAuth2AuthorizationServerConfigurer.authorizationServer()` | SAS 7 写法 |
| 持久化 | MySQL 8.4 + **Flyway** | 授权状态必须跨重启存活 |
| 密码哈希 | BCrypt | — |
| 控制台前端 | Vue 3 + Element Plus（沿用 `app/web` 的技术栈与约定） | 团队已有约定，不引入第二套 |
| 控制台部署 | 独立 nginx（`console-nginx`），静态资源 + 反代 `/auth`、`/oauth2`、`/userinfo` | 同源由代理保证，见 §4 |
| 测试库 | Testcontainers `mysql:8.4` | SAS schema 是 MySQL 方言 |

**已否决 Sa-Token**：`sa-token-oauth2 1.46.0` 有 `IdTokenModel` / `SaOAuth2OidcConfig`，但 jar 内**没有任何 JWKS 或 discovery 相关类**。验收要求"JWKS 可被外部独立验证"，用它就得自己补这两个端点。

## 4. 架构与「同源」问题

控制台要带**登录 session** 调 `/auth/admin/**`。如果控制台和 auth 是不同 origin，浏览器不会带上 `JSESSIONID`，就得开 `SameSite=None` + CORS 带凭证 + CSRF 跨域传递——三个坑叠加还降低安全性。

**结论：控制台构建产物与 auth 的 API 走同一个 nginx，因此同源。**

```
浏览器 ──▶ console-nginx :8082          ← 唯一 origin
             ├─ /           控制台 SPA（Vue 构建产物，try_files 回落 index.html）
             ├─ /auth/**    ──proxy──▶ auth :8081   管理 API + 登录处理
             ├─ /oauth2/**  ──proxy──▶ auth :8081   协议端点
             ├─ /userinfo   ──proxy──▶ auth :8081
             └─ /.well-known/** ─proxy▶ auth :8081

auth :8081  纯后端：/auth/admin/**、/oauth2/**、/userinfo、/auth/login 处理
```

登录页在 SPA 里（`/login` 路由），Spring Security 配成 `loginPage("/login")` + `loginProcessingUrl("/auth/login")`：
- `GET /login` → nginx 的 SPA 回落，渲染登录表单
- `POST /auth/login` → 代理到 auth，由 Spring Security 处理

这样**不需要按 HTTP 方法做 nginx 分流**（那是最容易出错的一类 nginx 配置）。

**过渡期**：工单 A 阶段控制台还不存在，auth 自己先提供一张最简登录页（纯 HTML，仅两个输入框）供集成测试与手工验证；B/C 上线后由 SPA 的 `/login` 路由取代，auth 那份不再被访问，可以删掉。

Agent 平台侧（`app/web` + `server`）与控制台**完全分开**：两套 nginx、两套 session，互不影响。

## 5. 数据模型

### 5.1 RBAC 五张表

```sql
tenant (
  id BIGINT PK, code VARCHAR(64) UNIQUE, name VARCHAR(128),
  status VARCHAR(16), created_at DATETIME
)

user (
  id BIGINT PK, tenant_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_tenant_username (tenant_id, username),
  KEY idx_user_tenant (tenant_id)
)

role (
  id BIGINT PK, tenant_id BIGINT NOT NULL,
  code VARCHAR(32) NOT NULL,            -- ADMIN | USER | 自定义
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  built_in BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE KEY uk_role_tenant_code (tenant_id, code)
)

permission (
  id BIGINT PK,
  code VARCHAR(64) NOT NULL UNIQUE,     -- user:read / user:write / client:rotate-secret
  name VARCHAR(64) NOT NULL,
  resource VARCHAR(32) NOT NULL,        -- user | role | permission | client
  action VARCHAR(32) NOT NULL,          -- read | write | delete | manage
  description VARCHAR(255)
)

user_role (
  user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY idx_user_role_role (role_id)          -- 反查「这个角色有哪些人」
)

role_permission (
  role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  KEY idx_role_permission_perm (permission_id)
)
```

**全库不使用数据库外键，关联完整性由应用层维护。** 这是刻意的取舍：

| 代价 | 我们的对策 |
|---|---|
| 可能出现孤儿行（角色删了，`user_role` 还留着） | 删除一律走**同一事务**里的显式清理：删角色先删 `role_permission`、`user_role`；删用户先删 `user_role` |
| 失去 `ON DELETE CASCADE` | 上面这条就是替代品；不给"删了主表忘清子表"留口子 |
| 唯一性无法靠外键保证 | 保留 `UNIQUE KEY`（唯一索引不是外键，不受此决定影响） |
| 没人拦着插一个不存在的 `role_id` | 写路径只有管理 API 一处；集成测试里加"引用不存在的 role_id 应被拒"的用例 |

收益：表结构可独立演进（加字段、换引擎、将来分库分表都不被外键约束绑住），且导入导出、批量重建数据不会被外键顺序卡住。

**`permission` 是全局表（不带 `tenant_id`）**：权限点由代码定义，随版本发布，不属于某个租户。租户只能组合角色，不能造权限点——否则"某租户自定义了一个平台不认识的权限码"会让鉴权逻辑失效。

`role.built_in` 让 `ADMIN` / `USER` 不可删——否则误删 ADMIN 后无人能进后台。

### 5.2 权限点种子（v1 全集，共 14 个）

```
user:read      user:write      user:delete
role:read      role:write      role:delete      role:assign
permission:read
client:read    client:write    client:delete   client:rotate-secret
audit:read
tenant:read
```

`ADMIN` 拥有全部；`USER` 一个都没有（普通用户不进控制台）。

### 5.3 协议表：复用 SAS 标准 schema

```sql
oauth2_registered_client / oauth2_authorization / oauth2_authorization_consent
```

**修正设计文档 §4.2**：原文的 `oauth_client` / `oauth_authorization` / `oauth_token` 三张自建表，前两张结构由 SAS 状态机定义，自建要么兼容其 JSON 序列化、要么重写 `OAuth2AuthorizationService`；官方已有 `JdbcRegisteredClientRepository` / `JdbcOAuth2AuthorizationService`。原文的 `oauth_token` 实际是**第三方 MCP** 用的（`user_mcp_token`），与用户中心签发的 token 不是一回事。

### 5.4 迁移

```
V1__rbac.sql          -- 五表 + audit_log + 种子（default 租户、ADMIN/USER 角色、14 个权限点）
V2__oauth2_schema.sql -- SAS 官方 MySQL schema
```

**管理员用户与工作台客户端不写进 SQL，由启动时幂等的 `PlatformSeeder` 创建。** 两个理由：
1. 密码要在运行时用配置的编码器哈希，不能把哈希写进迁移脚本；
2. `RegisteredClient` 的 `client_settings` / `token_settings` 是 SAS 的 JSON 格式，手写 SQL 极易和框架序列化漂移。

这仍是"管理员预先登记"：种子等价于一次离线登记，且动态注册端点始终不暴露。

## 6. 接口

### 6.1 OIDC（SAS 提供，只做配置）

`/oauth2/authorize`、`/oauth2/token`、`/oauth2/jwks`、`/oauth2/revoke`、`/oauth2/introspect`、`/userinfo`、`/.well-known/openid-configuration`。

**不暴露** `/connect/register`——"客户端只能由管理员登记"靠端点不存在实现。

### 6.2 管理 API（`/auth/admin/**`，session 认证 + 权限校验）

| 方法 | 路径 | 所需权限 |
|---|---|---|
| GET | `/users` | `user:read` |
| POST | `/users` | `user:write` |
| PATCH | `/users/{id}` | `user:write` |
| PUT | `/users/{id}/roles` | `role:assign` |
| DELETE | `/users/{id}` | `user:delete` |
| GET | `/roles` | `role:read` |
| POST | `/roles` | `role:write` |
| PATCH | `/roles/{id}` | `role:write` |
| DELETE | `/roles/{id}` | `role:delete` |
| PUT | `/roles/{id}/permissions` | `role:write` |
| GET | `/permissions` | `permission:read` |
| GET | `/clients` | `client:read` |
| POST | `/clients` | `client:write` |
| PATCH | `/clients/{id}` | `client:write` |
| POST | `/clients/{id}/rotate-secret` | `client:rotate-secret` |
| DELETE | `/clients/{id}` | `client:delete` |

三条硬规则：

1. **secret 永不可读回**：创建与轮换时明文只返回一次，之后只存 BCrypt 哈希。
2. **不能删除自己所属的最后一个 ADMIN**，也不能把最后一个 ADMIN 降级。
3. **`built_in` 角色不可删**，其 `code` 不可改。

### 6.3 控制台路由（独立 nginx，根路径）

控制台是独立应用，路由不挂在 `/admin` 前缀下——它自己就是那个 origin 的根。

| 路径 | 页面 | 权限 |
|---|---|---|
| `/login` | 登录表单，POST 到 `/auth/login` | 匿名 |
| `/` | 概览（用户/角色/客户端计数） | 登录即可 |
| `/users` | 用户列表、建用户、分配角色 | `user:read` / `role:assign` |
| `/roles` | 角色列表、建角色、勾选权限 | `role:read` / `role:write` |
| `/clients` | 客户端列表、建客户端、轮换 secret | `client:read` |
| `/profile` | 改自己的密码 | 登录即可 |

无权限的菜单项不渲染；直接访问受限路由时拦回 `/`。会话失效（401）时跳 `/login?redirect=…`。

## 7. 关键流程

### 7.1 授权码 + PKCE

```text
浏览器            auth                          client
  │ /oauth2/authorize ───▶ 未登录 → 302 /login
  │ /login 提交 ────────▶ BCrypt 校验 → 建 session
  │                    校验 client / redirect_uri / code_challenge
  │ ◀── 302 redirect_uri?code=…&state=…
  │                    ◀── POST /oauth2/token (code + verifier)
  │                    SHA256(verifier) == challenge ? 否则 invalid_grant
  │                    code 单次消费；重放则撤销该 code 签发的 token
  │                    ──▶ access_token / id_token / refresh_token
  │                    ◀── GET /userinfo (Bearer)
  │                    ──▶ { sub, name, tenant, role[], perms[] }
```

### 7.2 刷新与轮换

`reuseRefreshTokens(false)`：每次刷新换新 refresh token，旧的立即失效；旧 token 被再次使用则判定泄漏，撤销整条授权链。

### 7.3 权限变更的生效

权限与角色都进了 token，**改完在 token 过期前仍生效**（最长 15 分钟）。v1 采取：

- 管理员改用户角色 / 改角色权限时，**主动撤销受影响用户的全部授权**（调 `OAuth2AuthorizationService` 删除其授权记录）
- 成本约 30 行，换来"降权立即生效"

控制台自己的 session 权限**每次请求从库里算**（不缓存），因此控制台内是即时生效的。

## 8. Token 与 claims

| 项 | 值 |
|---|---|
| access token | JWT，RS256，15 分钟 |
| refresh token | 不透明串，7 天，轮换 |
| id token | JWT，标准 claims + 自定义 |

```json
{
  "sub": "42",
  "tenant": "default",
  "role": ["ADMIN"],
  "perms": ["user:read", "user:write", "client:rotate-secret"],
  "scope": ["openid", "profile"]
}
```

- `sub` 用 `user.id`（用户名可改，改完历史 token 对不上）
- `role` 与 `perms` 都是**数组**
- **权限进 token** 的理由：消费方（#15 的 server、将来的 MCP 资源服务器）拿到 token 就能判权限，不必回调 auth；代价是 token 体积与最多 15 分钟的滞后，后者由 §7.3 的主动撤销兜住
- v1 权限点 14 个，`perms` 最多 14 项，对 token 体积无实质影响

`/userinfo` 返回 `{ sub, name, tenant, role: [], perms: [] }`。

## 9. 安全决策

### 9.1 PKCE 全客户端强制
`requireProofKey(true)`，含机密客户端。

### 9.2 授权码重放
SAS 内置（重放即撤销该 code 签发的 token），我们不关掉它，并用测试锁住。

### 9.3 签名密钥
**不能启动时随机生成**（重启即全部 token 失效、多副本验不过签）。

| 环境 | 来源 |
|---|---|
| 开发 | 仓库内 `auth/src/main/resources/keys/dev-rsa.pem`（文件头标注仅开发） |
| 生产 | 挂载 Secret，`AUTH_JWK_PATH` 指定；**未配置则启动失败** |

### 9.4 两套 filter chain

| 范围 | 认证方式 | 授权 |
|---|---|---|
| `/oauth2/**`、`/userinfo` | Bearer 或 session | 协议要求 |
| `/auth/admin/**` | **仅 session**（不接受 Bearer） | 按 §6.2 的权限点 |
| `/auth/login`（登录处理） | 匿名 | — |
| `/.well-known/**`、`/oauth2/jwks` | 匿名 | — |

控制台静态资源与 SPA 路由由 nginx 提供，不经过 auth。

管理 API 拒绝 Bearer 是刻意的：避免"拿用户 token 调管理接口"这个越权面。

### 9.5 其他
- client secret 存 BCrypt，明文只返回一次
- token 不进日志
- 登录失败限流归 #23 之后的安全加固
- CSRF 默认开启；控制台的写请求带 CSRF token

## 10. 与其他工单的边界

| 工单 | 关系 |
|---|---|
| #15 工作台切 OIDC | 消费 JWKS 与 `role[]` / `perms[]` |
| #17 公共 MCP 启用 | 复用本服务作为授权服务器 |
| **#23** | **收缩为"审计日志查询"**：用户/角色管理已在本工单。本工单负责**写**审计事件，不负责查询界面 |
| #24/#25 | 不变 |

## 11. 测试策略

接缝 3（真实 HTTP）+ Testcontainers MySQL 8.4。

| 用例 | 断言 |
|---|---|
| PKCE 不匹配 | 400 `invalid_grant` |
| 授权码重放 | 第二次 400，且第一次的 access token 被撤销 |
| 未登记客户端 | 400 `invalid_client` |
| JWKS 独立验签 | nimbus 从 `/oauth2/jwks` 取公钥独立验 id token |
| `/userinfo` claims | 含 `sub` / `name` / `tenant` / `role[]` / `perms[]` |
| refresh 轮换 | 旧 refresh 失效；再用旧的 → 拒绝并撤销链路 |
| 过期 token | `/userinfo` 401 |
| 动态注册端点不存在 | `POST /connect/register` → 404 |
| 无权限调管理 API | 403 |
| 删除最后一个 ADMIN | 400 且用户仍存在 |
| 改角色后旧 token 失效 | 撤销后旧 token 401 |
| 客户端 secret 不可读回 | GET 列表响应不含 secret |
| 轮换后旧 secret 失效 | 用旧 secret 换 token → 401 |
| 控制台：无权限菜单不渲染 | 组件测试 |

## 12. 拆分为 3 个工单

| 工单 | 内容 | 阻塞于 |
|---|---|---|
| **A. 用户中心后端：RBAC + 登录 + OIDC** | 五张 RBAC 表、Flyway、登录页、SAS 配置、`role[]`/`perms[]` claims、`/userinfo`、密钥外部化 | #2（已完） |
| **B. 用户中心管理 API + 控制台** | 用户/角色/权限/客户端 CRUD、secret 轮换、审计事件写入、控制台四个页面 | A |
| **C. 控制台部署形态** | 独立 nginx 容器：静态资源 + 反代 auth、`loginPage`/`loginProcessingUrl` 对齐、compose 编排、同源与 CSRF 实测 | B |

**A 完成即可解锁 #15**（工作台只需要 JWKS 与 claims，不依赖控制台）。

> 说明：B 把"管理 API + 控制台"放在一起，而不是像上一版那样拆成 API / UI 两个工单——因为控制台是这套 API 的第一个消费者，同一个人在同一上下文里做，接口会自然贴合；拆开反而要来回对齐契约。

## 13. 实施步骤（工单 A 内部）

1. `auth` 接入 MySQL + Flyway（**必须**用 `spring-boot-starter-flyway`），五张 RBAC 表 + `audit_log` + 种子权限点
2. `UserDetailsService` 查库 + BCrypt + 过渡期最简登录页（`loginPage("/login")` + `loginProcessingUrl("/auth/login")`）
3. SAS 配置：两个 filter chain、JDBC `RegisteredClientRepository`、`JWKSource` 读 PEM
4. `OAuth2TokenCustomizer` 注入 `tenant` / `role[]` / `perms[]`（权限按 `user → user_role → role_permission → permission` 聚合，一次查询）
5. `/userinfo` 映射
6. `PlatformSeeder` 幂等创建管理员账号与 `workbench` 客户端
7. 集成测试（§11 前 8 条 + refresh 轮换 + 过期 token + 未登记客户端）
8. compose 接上 auth 的数据库依赖，README 补流程

## 14. 已确认的决定

评审时逐条拍板，全部按建议落地：

| # | 问题 | 结论 |
|---|---|---|
| 1 | 权限点粒度 | **`resource:action`，14 个够用** |
| 2 | `perms` 是否进 token | **进**（§8） |
| 3 | 审计事件写到哪 | **auth 自己的 `audit_log` 表**；查询界面留在 #23 |
| 4 | 签名密钥 | **开发入库（标注仅开发）+ 生产挂载 + 未配置即启动失败** |

另有两项在评审中被修正：

| 修正 | 内容 |
|---|---|
| 同源 | 控制台由**独立 nginx** 提供并与 auth 反代同源，而不是打进 auth 镜像（§4） |
| 外键 | **全库不使用数据库外键**，关联完整性由应用层在同一事务里显式维护（§5.1） |

## 15. 落地时的事实修正（2026-11-04）

评审时的假设与 Boot 4 / Spring Security 7 的实际情况有出入，实现时按实际 API 调整。这些不是需求变更，但值得留痕，避免下次照旧文档抄错。

| 项 | 评审时假设 | 实际情况 |
|---|---|---|
| SAS configurer | `OAuth2AuthorizationServerConfigurer.authorizationServer()` | 7.1.2 里**没有这个静态工厂方法**；类也移到了 `spring-security-config` 包的 `org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization`，用无参构造 |
| 授权服务器依赖 | `spring-boot-starter-oauth2-authorization-server` | 改用 `spring-security-oauth2-authorization-server` 库本身；所有 bean 显式定义，starter 无价值 |
| Flyway | 加 `flyway-core` + `flyway-mysql` 即可 | Boot 4 把自动配置拆进了 `spring-boot-flyway` 模块，**必须用 `spring-boot-starter-flyway`**，否则迁移根本不跑（表现为表不存在） |
| JSON | Jackson 2（`com.fasterxml.jackson`） | Boot 4 用 **Jackson 3（`tools.jackson.*`）** |
| 日志桥 | Spring 自带 commons-logging | Spring Framework 7 不再内嵌，且依赖仲裁可能把传递依赖挤掉；`auth` 显式声明了 `commons-logging` |
| Testcontainers | `org.testcontainers:mysql` | 2.x 改名为 `testcontainers-mysql` / `testcontainers-junit-jupiter` |
| `OAuth2TokenType` | `oauth2.core` | 移到 `oauth2.server.authorization`；`OidcParameterNames` 移到 `oauth2.core.oidc.endpoint` |
| SAS 连接串 | 普通 MySQL URL | 官方 schema 注明必须带 `preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，否则 token 时间戳错位 |

另外，SAS 的 `ClientSettings.requireProofKey(true)` 与 `TokenSettings.reuseRefreshTokens(false)` 已用集成测试锁住，不是口头约定。
