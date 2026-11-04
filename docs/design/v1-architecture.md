# 多用户云端 Agent 平台 — v1 架构设计

> 状态：**已确认**（设计树已收敛，无待拍板项；暂不开工）
> 决策来源：8 轮拷问，共 33 条决策，见文末「决策记录」
> 技术基线：AgentScope Java `2.1.0-BETA1` + Spring Boot + Vue 3 + Docker/K8s 沙箱

---

## 0. 一句话定义

一个内部多用户 Agent 工作台：用户在浏览器里和 agent 对话，agent 能调用**管理员发布的公共 skill**、**用户授权的公共 MCP**，在**按会话隔离的沙箱**里读写文件、跑脚本，产物直传 OSS 并可下载。

## 1. v1 明确不做

把边界钉死，避免范围蔓延：

| 不做 | 原因 |
|---|---|
| 多副本部署与分布式会话 | 接口分层到位即可，扩副本是配置改动（见 §3） |
| K8s 预热池 | 本地 Docker 跑通，`SandboxBackend` 换实现即可 |
| 用户自定义指令模板 | 与 skill 能力重叠，统一用 skill 承载，避免两套能力模型 |
| 私有资源审核 / 分享 | 用户自建即用，不做前置审批；私有即私有，转公共需管理员重传 |
| BYOK（用户自带模型 Key） | 表结构留位，不实现 |
| 团队/工作空间协作 | 隔离边界是 `(tenant, user, conversation)` 三段 |
| skill 包内文件级管理 | zip 存 OSS，只在物化时解压 |
| 分片上传 / 秒传 / 断点续传 | 见 §12 待确认项 |
| 动态客户端注册、个性化同意页 | 用户中心只做最小 OIDC |
| RAG / 知识库 | 框架自身该能力仍在完善 |

## 2. 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  app/web  (Vue 3.5 + Element Plus + Pinia + UnoCSS)         │
│  ├─ 登录/回调    ├─ 工作台(SSE)   ├─ skill 管理              │
│  ├─ MCP 管理     ├─ 产物下载      └─ 管理后台(admin)          │
└───────────────┬──────────────────────────┬──────────────────┘
                │ REST + SSE               │ OAuth2.1 授权码
┌───────────────▼──────────────┐  ┌────────▼─────────────────┐
│  app/server  工作台后端       │  │  app/auth  用户中心       │
│  (Spring Boot MVC)           │──▶│  (Spring Authorization    │
│  ├─ 认证过滤器(JWT 校验)      │  │   Server)                 │
│  ├─ Turn 编排 + 排队          │  │  ├─ /authorize + PKCE     │
│  ├─ SSE 事件网关              │  │  ├─ /token /userinfo      │
│  ├─ skill / MCP / 产物 API    │  │  └─ /.well-known/jwks.json│
│  └─ HarnessAgent 生命周期     │  └───────────────────────────┘
└───┬────────┬────────┬─────────┘
    │        │        │
    ▼        ▼        ▼
┌────────┐┌──────┐┌──────────────────────────┐
│ MySQL  ││Redis ││ OSS (产物/skill 包/事件归档)│
│ 业务表  ││Stream│└──────────────────────────┘
│        ││锁/配额│
└────────┘└──────┘
    │
    ▼  沙箱后端（本地 Docker / 生产 agent-sandbox）
┌──────────────────────────────────────────────┐
│  per-conversation sandbox (ubuntu:24.04)     │
│  /workspace/{uploads,outputs,skills,scripts} │
└──────────────────────────────────────────────┘
```

三个**可独立部署**的单元：`web`（静态资源）、`server`、`auth`。`auth` 单独部署是因为其他系统开放 MCP 时要对接它。

## 3. 五个必须第一天就位的接口

「生产形态」的本质是接口边界，不是副本数。这五个接口在 v1 全部定义好，本地实现可以简陋：

| 接口 | 职责 | 本地实现 | 生产实现 |
|---|---|---|---|
| `ObjectStore` | put/get/head/presign/rangeGet | MySQL BLOB | OSS |
| `SandboxBackend` | create/exec/upload/download/snapshot/resume/destroy | Docker | agent-sandbox (K8s CRD) |
| `StateStore` | Agent 状态 `(tenant:user, sessionId)` | Redis | Redis |
| `EventLog` | 追加/读取/归档事件 | Redis Stream | Redis Stream |
| `ArtifactDeliveryTarget` | 框架 SPI，产物落地 | 自实现写 ObjectStore | 同左 |

`ObjectStore` 必须从一开始就声明 `createMultipartUpload` 与 `rangeGet`（v1 可抛 `UnsupportedOperationException`），这样 v2 加分片不改调用方。

## 4. 数据表骨架

全表带 `tenant_id`（v1 恒为 `default`）。时间戳统一 `created_at`/`updated_at`。软删用 `status`。

### 4.1 身份 / 租户

```sql
tenant        (id, code, name, status)
user          (id, tenant_id, username, password_hash, display_name, status)
role          (id, tenant_id, code, name)            -- admin / user
user_role     (user_id, role_id)
user_session  (id, tenant_id, user_id, refresh_token_hash, expires_at, revoked_at)
```

### 4.2 用户中心（OAuth2.1）

```sql
oauth_client        (id, tenant_id, client_id, client_secret_hash,
                     redirect_uris, scopes, status)
oauth_authorization (id, code_hash, client_id, user_id, tenant_id,
                     resource, scopes, code_challenge, expires_at, consumed_at)
oauth_token         (id, tenant_id, user_id, client_id, resource,
                     access_token_enc, refresh_token_enc,
                     access_expires_at, refresh_expires_at, revoked_at)
```

`resource` 字段是 MCP server 的唯一标识 —— 一个用户启用 N 个 MCP 就有 N 条授权，撤销粒度按 server（见 §8）。

### 4.3 skill

```sql
skill              (id, tenant_id, name, description, version, status,
                    visibility,                            -- public | private
                    owner_user_id,                         -- 公共为 null
                    object_key, size_bytes, checksum, created_by, published_at)
                   -- status: draft | published | archived
user_skill_enable  (id, tenant_id, user_id, skill_id, enabled_at)
```

`name` 必须匹配 `^[a-z0-9][a-z0-9-]*$`（框架硬约束）。公共 skill 全局唯一；私有 skill 在 `(tenant, owner)` 内唯一，且**不得与公共 skill 同名**（撞名拒绝上传，不允许私有覆盖公共）。

### 4.4 MCP

```sql
mcp_server        (id, tenant_id, name, transport,        -- sse | streamable_http
                   url, protocol_versions,                 -- 默认 "2024-11-05,2025-03-26"
                   visibility,                             -- public | private
                   owner_user_id,                          -- 公共为 null
                   auth_mode,                              -- none|shared|user_center|third_party_oauth|manual_token
                   status)
user_mcp_enable   (id, tenant_id, user_id, mcp_server_id, enabled_at)
user_mcp_token    (id, tenant_id, user_id, mcp_server_id,
                   access_token_enc, refresh_token_enc,
                   access_expires_at, refresh_expires_at, scopes)
```

`protocol_versions` 必须可配 —— 框架默认只协商 `2024-11-05`，接新版 MCP server 会握手失败且报错不明显。

**鉴权由被连的服务决定，不是由平台决定**：`user_center`（公司内部系统，跳我们的用户中心）、`third_party_oauth`（第三方 MCP，用户登**自己的第三方账号**，平台作为该授权服务器的 OAuth 客户端）、`manual_token`（只发 API key 的服务）、`shared`（平台凭证）、`none`。

`third_party_oauth` 走 MCP 规范自身流程：发现 `/.well-known/oauth-protected-resource` → 必要时动态客户端注册（RFC 7591）→ 第三方授权码 + PKCE → 回调校验 `iss` → 加密存第三方 token。**平台从不接触第三方密码。**

用户填的 URL 由服务端 JVM 去连，必须做 SSRF 防护：仅 https、拒绝回环/私网/链路本地/元数据端点、先解析后固定 IP（防 DNS rebinding）、禁跟随重定向、超时 10s。

### 4.5 会话 / 轮次 / 产物

```sql
conversation      (id, tenant_id, user_id, title, model_code, status, last_turn_at)
turn              (id, conversation_id, seq, status,               -- queued|running|done|failed|cancelled
                   model_code, input_text, input_files_json,
                   prompt_tokens, completion_tokens, error_code, started_at, ended_at)
turn_event        (id, turn_id, seq, type, payload_json, created_at)
                   -- 原始事件落 MySQL（用户决定），按 turn_id+seq 唯一
artifact          (id, tenant_id, user_id, conversation_id, turn_id,
                   file_name, object_key, size_bytes, mime, source,   -- tool | upload
                   status, created_at)
sandbox_binding   (conversation_id PK, tenant_id, sandbox_ref,
                   snapshot_ref, state_json, status, last_active_at)
```

`turn_event` 保留完整 `payload_json`：排障必需（"为什么答错"只能靠它）。用户量上来后再迁 OSS，表里加 `payload_object_key` 即可切换。

### 4.6 配额 / 审计

```sql
model_provider  (id, tenant_id, code, base_url, api_key_enc,
                 kind,                        -- openai_compatible
                 status)
model_whitelist (id, tenant_id, provider_id, model_code, display_name,
                 enabled, sort_order)
quota_daily     (id, tenant_id, user_id, day, prompt_tokens,
                 completion_tokens, turns)   -- Redis 为主，日结落库
audit_log       (id, tenant_id, user_id, action, target_type, target_id,
                 detail_json, ip, created_at)
```

## 5. Turn 生命周期与 SSE 协议

### 5.1 两个请求，不是"后端主动推"

```
POST /api/conversations/{cid}/turns
  → 202 { turnId, cursor }            # 立即返回，避免网关超时

GET  /api/conversations/{cid}/events?after={cursor}   # SSE 长连接
  → event: token         data: {...}
  → event: tool_call     data: {...}
  → event: tool_result   data: {...}
  → event: artifact      data: {...}
  → event: done          data: {...}
```

**SSE 必须由客户端发起长连接**，不能在后端"另开线程推给已关闭的连接"。`XREAD BLOCK` 会占住一个请求线程，所以 SSE 端点跑在**异步 Servlet（`SseEmitter`）或 WebFlux** 上，配**有界线程池**，禁止每请求开线程。

### 5.2 事件协议

| type | 关键字段 | 前端表现 |
|---|---|---|
| `turn/queued` | `position`, `estimatedWaitMs` | "排队中 · 第 2 位"，可取消 |
| `turn/started` | `turnId` | 转"执行中" |
| `token` | `delta` | 流式追加 |
| `tool_call` | `toolName`, `argsPreview`, `callId` | 工具卡片 |
| `tool_result` | `callId`, `ok`, `summary`, `durationMs` | 卡片折叠结果 |
| `artifact` | `artifactId`, `fileName`, `sizeBytes`, `downloadUrl` | 下载卡片 |
| `pending` | `kind`, `payload` | HITL 待答表单（v2 启用） |
| `done` | `usage` | 收尾 |
| `error` | `code`, `message` | 错误条 |
| `turn/cancelled` | `reason` | 取消态 |

心跳：每 15s 发一行 SSE 注释帧 `: ping`，前端 60s 无任何帧则重连。

### 5.3 断线重放

`cursor` 是 Redis Stream 的消息 ID。重连时带 `after=cursor` 或 HTTP 头 `Last-Event-ID`，`XREAD` 从该点往后补齐 —— 用户刷新页面后能看到"刚才跑到哪、产物出没出"。

会话结束后整条 Stream 归档进 `turn_event` 再删 Stream。

### 5.4 排队与取消

- 队列深度：同会话 **1 执行 + 1 排队**，第 3 个请求返回 `429`
- 等待超时：**10 分钟**未开始执行 → 失败并提示
- 取消：排队中 → 写 `turn/cancel` 事件并从队列摘除；执行中 → 走框架 `interrupt`（两个不同语义，前端要区分"可取消"与"可中断"）
- 互斥：复用框架 `SandboxExecutionGuard`（Redis `SET NX PX`），**不自己写锁**

## 6. 沙箱生命周期

绑定粒度：**一会话一沙箱**（`IsolationScope.SESSION`）。

```
首轮:
  acquire(conversationId) ── 无 state ──▶ create sandbox (ubuntu:24.04)
     ├─ 物化已启用 skill → /workspace/skills/
     ├─ 拉取本轮上传文件 OSS → /workspace/uploads/
     └─ 执行 turn

空闲超时（默认 30 分钟）:
  stop()        ──▶ snapshot
  shutdown()    ──▶ 容器销毁，写 sandbox_binding.state_json

下一轮（同一会话）:
  acquire(conversationId) ── 有 state ──▶ 新容器 + snapshot hydrate
     └─ /workspace/outputs 内文件字节一致（POC 已验证）
```

镜像约束（框架硬要求，违反即启动失败）：
- 必须有 `sh`、GNU `stat -c`、`tar`、`base64`、`curl`
- **alpine 与 distroless 不可用**
- v1 单一镜像 `ubuntu:24.04 + python3 + 上述工具`（约 300MB）
- **本地 Docker 与生产 K8s 必须用同一镜像 tag**，否则 POSIX 契约差异会在上线当天暴露
- 已知限制：镜像内**无 LibreOffice**，所以 v1 无法做 xlsx/docx → PDF 的视觉校验

## 7. Skill 链路

### 7.1 上传与校验（admin）

`POST /api/admin/skills`（multipart zip）→ 校验：
1. 包内必须含 `SKILL.md`，YAML frontmatter 有 `name`、`description`
2. `name` 匹配 `^[a-z0-9][a-z0-9-]*$`，且与 zip 顶层目录名一致
3. 拒绝符号链接、绝对路径、`..`、可执行位
4. 解压后总大小上限（默认 20MB），文件数上限（默认 500）
5. 通过 → zip 存 OSS，`skill.status = published`

### 7.2 启用与生效

- 用户在 skill 管理页点「启用」→ 写 `user_skill_enable`
- **不在当前轮生效**。下一轮对话开始时，agent 构建阶段读取启用集
- 框架侧有两层机制：
  - `RuntimeContextSkillRepository.getAllSkills(ctx)` —— 按请求上下文返回可见 skill
  - `SkillFilter` overlay（`enable`/`disable` 与 builder 级 base filter 合并）—— 决定哪些进 prompt 索引
  - `DynamicSkillMiddleware.onSystemPrompt` **每次 `call()` 重新解析**，所以"下一轮生效"是原生行为，零改造
- 正文按需加载（`load_skill_through_path` 渐进披露），prompt 里只放索引
- 沙箱物化：会话首次创建沙箱时，把启用集同步到 `/workspace/skills/`

### 7.3 管理员改 / 删的生效规则

| 动作 | 已启用用户 |
|---|---|
| 改 skill | **下次对话**立即读到新版（会话内不换，沙箱已物化） |
| 删 skill | 软删 `status=archived`；已启用用户**仍可用**，列表标灰，不能再新启用 |

### 7.4 私有 skill（用户自建）

用户可自行上传 skill zip，校验规则与公共 skill 完全相同，另加撞名检查与配额（默认 20 个/人、单包 20MB）。可见性在 SQL 层按 `visibility='public' OR owner_user_id=当前用户` 过滤，不在应用层筛。私有 skill 被本人删除时立即从启用集移除（下一轮生效）。

## 8. MCP 授权链路

```
用户在 MCP 管理页点「启用」
   │
   ▼
浏览器 302 → 用户中心 /authorize?client_id=workbench
                          &response_type=code
                          &resource={mcp_server_id}
                          &code_challenge=...&code_challenge_method=S256
   │  用户授权
   ▼
回调 302 → 工作台 /api/mcp/callback?code=...&state=...
   │
   ▼
server 用 code + code_verifier 换 token（用户中心 /token）
   │
   ▼
写 user_mcp_token（AES-GCM 加密，密钥在 application 配置）
   │
   ▼
agent 调用 MCP 时注入 Authorization: Bearer <access_token>
```

- **按 server 授权**，不是一次授权覆盖全部 —— 撤销粒度可控
- `access_token` 短时效，过期前用 `refresh_token` 续
- **续期失败 → 前端弹「重新授权」**，不静默失效（静默失效会让 agent 莫名报错）
- JWT claims 带 `tenant` 与 `role`，**MCP server 侧据此做行级过滤**；不在 JVM 侧拼过滤条件（既不可靠也漏审计）
- **私有 MCP 不走这条链路**（除非它的 `auth_mode = user_center`）：第三方 MCP 用 `third_party_oauth` 登录第三方账号，或 `manual_token` 手工填 token；两条都过 SSRF 校验
- 框架只做 MCP **客户端**（`McpClientBuilder`），仓库无 server 实现 —— 对外暴露 MCP 不在 v1 范围

## 9. 产物链路

阈值 **3MB**（可配置）：

```
artifact ≤ 3MB  ──▶ 框架 SPI（JVM 中转）
                    filesystem.downloadFiles() → byte[]
                    → ArtifactDeliveryTarget.deliver(ctx, req)
                    → ObjectStore.put → 写 artifact 表

artifact > 3MB  ──▶ 沙箱直传
                    server 生成 objectKey（服务端定名，非沙箱定名）
                    → 签 presigned PUT（绑 tenant/user/conversation，5 分钟）
                    → 沙箱 curl -X PUT
                    → 返回 objectKey
                    → server HEAD 校验存在与大小 → 写 artifact 表
```

**安全铁律**：object key 必须由服务端生成并签进 URL。绝不能让沙箱返回"它自己起的名字"当唯一 id —— 沙箱里跑着模型生成的脚本，否则可伪造任意路径。收到 key 后必须 `HEAD` 校验再入库。

框架 SPI 签名（已核实）：

```java
public interface ArtifactDeliveryTarget {
    ArtifactDeliveryResult deliver(RuntimeContext ctx, ArtifactDeliveryRequest req);
}
public record ArtifactDeliveryRequest(
        String filePath, byte[] content, String fileName,
        String description, boolean force) {}
```

`force` 映射为"覆盖同名产物"。单产物硬上限 32MB（超过返回明确错误，不静默截断）。

## 10. 前端交互规范

### 10.1 两个触发器，互不相干

| 触发 | 弹出内容 | 选中后 |
|---|---|---|
| `/` | **已启用 skill + 内置指令**（不含文件） | skill → chip；指令 → 内联二级选择 |
| `@` | **本地文件选择器** | 上传 → 文件 chip |

共同规则：**必须前一个字符是空格或行首**。否则用户输入 `a@b.com`、`/usr/bin`、`mailto:` 时会被误拦 —— 这是聊天输入框的经典 bug。

### 10.2 内置指令（代码定义，不入库）

| 指令 | 行为 |
|---|---|
| `/压缩` | 调 `ConversationCompactionStrategy.compact(request)` 立即压缩上下文（先 `flushBeforeCompact` 刷盘，否则丢事实） |
| `/模型` | 切换**本会话后续轮次**的模型（不动用户偏好；内联 chip 二级选择） |
| `/下载日志` | 导出该会话事件日志 JSONL（含每轮 token/tool 耗时） |

### 10.3 `@` 文件上传

- **发送时才上传**到 OSS → 写 `artifact(source=upload)` → chip 只带 `fileId`
- 开轮时沙箱已就绪，再从 OSS 拉进 `/workspace/uploads/`
- chip 内**按一次 Backspace 删除整个文件名**
- 限制：单文件 5MB（可配置）、单轮 5 个（可配置）、类型白名单 `xlsx/docx/pdf/csv/txt/md/json/图片`

### 10.4 状态呈现

| 状态 | 呈现 |
|---|---|
| 排队中 | "排队中 · 第 N 位"，带取消按钮 |
| 执行中 | 流式 token + 工具卡片 + 中断按钮 |
| 沙箱冷启动 | "沙箱启动中"，不阻塞输入 |
| 产物就绪 | 下载卡片（预签名 URL，5 分钟有效） |
| 重新授权 | MCP token 续期失败时弹窗 |

## 11. 依赖与版本锁定

| 项 | 选择 | 理由 |
|---|---|---|
| AgentScope | **2.1.0-BETA1**（Maven Central） | 3MB 以上产物走直传、以下走 SPI，SPI 的 `downloadFiles` 依赖 `SandboxFileTransfer`，**该能力 2.0.4 没有** |
| 构建 | 不要自建 SNAPSHOT | 自建时 `agentscope-harness` 依赖 `agentscope-core` 的 test-jar，`-Dmaven.test.skip=true` 会导致依赖解析失败 |
| JDK | 17+ | 框架要求 |
| 隔离 | 包一层 adapter | 2.0 相比 1.x 已有 breaking change，968 个 open issue；框架 API 只在 adapter 内出现 |
| 沙箱后端 | 本地 Docker / 生产 agent-sandbox | `KubernetesSandboxClient` 走 agent-sandbox CRD |
| 流式 | 自研 SSE | AG-UI 有 interrupt 的 resume 但**无事件日志重放**，而你的任务 30–60 秒、用户会刷新 |

## 12. 上传能力（已定）

**结论：方案 A。** 单文件上限 5MB（可配置），v1 **不做**分片上传、秒传、断点续传、分片下载；只做普通上传 + `Range` 下载。

- `ObjectStore` 接口从一开始声明 `createMultipartUpload` / `rangeGet`，v1 实现可抛 `UnsupportedOperationException`
- 触发条件：单文件超过 5MB 或需要断点续传时，再补实现，调用方不改
- 代价：大文件（>5MB）在 v1 无法上传，需用户自行拆分或走管理员后台上传


## 13. 风险清单

| 风险 | 缓解 |
|---|---|
| 锁在 BETA 版本 | adapter 隔离框架 API；`2.1.0-BETA1` 固定版本号，不跟 SNAPSHOT |
| 大产物 OOM（JVM 中转路径） | 3MB 阈值 + 32MB 硬上限 + 直传兜底 |
| 沙箱镜像漂移（本地/生产） | 同一镜像 tag，CI 里做 POSIX 契约自检 |
| MCP 协议版本握手失败 | `protocol_versions` 做成管理端可配字段 |
| 跨用户产物泄漏 | object key 服务端生成 + presigned 绑 `tenant/user/conversation` + 下载二次鉴权 |
| 同会话并发写坏状态 | `SandboxExecutionGuard` + 队列深度 2 |
| 无 LibreOffice | v1 不做 xlsx→PDF 视觉校验，明确告知 skill 作者 |
| 968 个 open issue | 只依赖已核实的 API 面（本文档引用的均为源码核实过的） |

## 14. 决策记录

| # | 决策 |
|---|---|
| Q1 | 端到端最小闭环，架构按生产形态；本地 Docker 沙箱 + OSS 走接口 |
| Q2 | 数据结构多租户（全表 `tenant_id`），实现单组织多用户 |
| Q3 | skill 上传 = zip 包 |
| Q4 | prompt 索引 + 沙箱物化都要；支持对话中动态启用 |
| Q5 | MCP 每用户独立 Token，调用区分用户角色 |
| Q6 | 一会话一沙箱，空闲快照+销毁，resume 恢复；同会话排队串行 |
| Q7 | 自研 SSE（否决 AG-UI，因无事件重放） |
| Q8 | "指令" = 自研产品命令（压缩/模型/下载日志） |
| Q9/Q11 | 产物：≤3MB 走框架 SPI（JVM 中转），>3MB 沙箱直传 OSS |
| Q10 | 五个接口第一天就位 + Redis 从第一天接入 |
| Q12 | 自研 SSE + Redis Stream + `Last-Event-ID` 重放 |
| Q13 | skill 在管理页启用，下次对话生效 |
| Q14 | 独立用户中心（OAuth2.1 授权码 + PKCE + JWT），MCP 走它授权 |
| Q15 | `/压缩`=压缩上下文历史；`/下载日志`=会话事件 JSONL；`/模型`=会话级切换 |
| Q16 | 平台统一凭证 + 白名单切模型 + Redis 配额 |
| Q17 | 框架 SPI + 阈值降级 |
| Q18 | Redis Stream(TTL 24h) + MySQL 归档 + 无粘性路由 |
| Q19 | 用户中心 v1 就做，独立部署，最小 OIDC |
| Q20 | 按 server 授权；续期失败弹重新授权 |
| Q21 | 直接调 `ConversationCompactionStrategy.compact` |
| Q22 | 改 skill 下次对话生效；删走软删 |
| Q23 | 生产 agent-sandbox（K8s CRD），本地 Docker |
| Q24 | 单一最小镜像 `ubuntu:24.04 + python3 + sh/stat/tar/base64/curl` |
| Q25 | monorepo：`app/web` + `app/server` + `app/auth` + `deploy` |
| Q26 | 原始事件落 MySQL；密钥放 application 配置；无 workspace 层 |
| Q27 | `/` 只弹 skill+指令；`@` 才是文件上传 |
| Q28 | 队列深度 2、等待 10 分钟、支持取消 |
| Q29 | OpenAI 兼容端点，先接 DeepSeek，模型配置入库 |
| Q30 | 发送时上传 OSS，开轮时拉进沙箱 |
| Q31 | 5MB / 5 个 / 类型白名单（可配置） |
| Q32 | `/` 与 `@` 均需空格或行首触发；`/模型` 内联二级 chip |
| Q33 | 上传只做普通上传 + Range 下载，上限 5MB，分片/秒传/断点留 v2 |
| Q34 | 用户可自建私有 skill 与私有 MCP（原「只能启用公共资源」作废）；MCP 鉴权由被连服务决定：`user_center`/`third_party_oauth`/`manual_token`，统一加 SSRF 防护 |


