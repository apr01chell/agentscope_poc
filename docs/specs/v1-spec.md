> 已发布：GitHub issue [#1](https://github.com/apr01chell/agentscope_poc/issues/1)（标签 `ready-for-agent`）
> 本文件是同一份 spec 的仓库副本；以 issue 为准，修改请回写两边。

## Problem Statement

内部同事想把 agent 当成日常生产力工具用起来，但现在每个人要么自己在本地装一堆脚本和模型客户端，要么把文件、凭证、对话历史散落在各自的机器上。具体痛点：

- **能力无法共享**。某个人调好的分析/写作/报表流程，别人复制不走；想统一维护一套公共能力（skill）也没有地方发布。
- **数据源接不进来**。内部系统陆续开放了 MCP 接口，但每个系统都要单独发 token、单独教用户配置，用户不知道从哪授权，系统方也不知道该信任谁。
- **跑东西没地方跑**。让 agent 处理文件、跑脚本，要么污染本机，要么担心它读到不该读的东西；跑完的产物散在临时目录里，换个会话就找不回来。
- **长任务体验差**。生成一份报表要几十秒到几分钟，用户刷新一下页面就丢失进度，不知道跑到哪一步、产物出没出。
- **没有边界感**。谁能配什么能力、普通用户能不能自己加工具，全靠口头约定，没有角色约束，也没有审计。

## Solution

一个浏览器里的多用户 agent 工作台，后端接 AgentScope Java 的 `HarnessAgent`，代码执行下沉到按会话隔离的沙箱。

- **用户在浏览器登录后直接对话**，agent 能调用「已启用的公共 skill」和「已授权的公共 MCP 工具」，在沙箱里读写文件、跑脚本。
- **公共能力由管理员统一发布**：管理员上传 skill 包（zip）、配置公共 MCP server，并对所有人可见。
- **用户也能自建私有资源**：普通用户可以自己上传 skill zip 包、自己添加 MCP 地址，这些私有资源只有本人可见、可启用、可删除；公共资源仍然只能启用不能修改。
- **MCP 怎么鉴权由被连的服务决定**：公司内部系统信任我们的用户中心（跳用户中心授权）；第三方 MCP 用**你自己的第三方账号**登录（平台作为 OAuth 客户端走 MCP 规范流程）；只发 API key 的服务则手工填 token。
- **对话体验面向长任务设计**：提交后立即返回，事件走 SSE 长连接；断线或刷新后从上次位置补齐事件，能看到"跑到哪、产物出没出"；同一会话并发请求排队并显示位置，可取消。
- **产物进对象存储**，对话里出现下载卡片；工作区文件在沙箱销毁后仍可通过快照恢复。
- **MCP 走统一授权**：用户点启用 → 跳转用户中心授权 → 回到工作台，之后 agent 调用该 MCP 时携带该用户的 token，MCP 提供方据此做行级权限。
- **输入框两个触发器**：`/` 弹已启用 skill 与内置指令；`@` 弹本地文件选择器（上传后成为 chip）。

## User Stories

1. 作为普通用户，我想要用账号密码登录工作台，以便我的对话、文件、产物只属于我自己。
2. 作为普通用户，我想要登录后直接进入工作台，以便不用先做一堆配置就能开始用。
3. 作为普通用户，我想要在工作台新建会话并给它起标题，以便把不同任务的上下文分开。
4. 作为普通用户，我想要看到自己的会话列表并继续之前任一对话，以便中断的工作能接着做。
5. 作为普通用户，我想要输入一句话就能让 agent 干活，以便不必学命令行或脚本。
6. 作为普通用户，我想要回答以流式逐字出现，以便感知 agent 正在工作而不是卡死。
7. 作为普通用户，我想要看到 agent 每次调用工具的名称、参数摘要和耗时，以便判断它是否在按我的意思做。
8. 作为普通用户，我想要看到工具调用的结果摘要并可展开，以便核对 agent 拿到了什么数据。
9. 作为普通用户，我想要在生成过程中随时中断，以便发现方向错了能立刻止损。
10. 作为普通用户，我想要在刷新页面或断网重连后仍能看到完整的历史事件，以便长任务不因一次刷新而丢失进度。
11. 作为普通用户，我想要在同一会话重复提交时看到"排队中·第 N 位"，以便知道我的请求没被丢掉。
12. 作为普通用户，我想要取消还在排队的请求，以便误发时不必等它执行。
13. 作为普通用户，我想要在等待超过一定时间后收到明确的失败提示，以便不会无限等待。
14. 作为普通用户，我想要在输入框打 `/` 时看到我已启用的 skill 列表和内置指令，以便不用记名字就能调用。
15. 作为普通用户，我想要 `/` 只在行首或空格后触发，以便我正常描述包含斜杠的路径时不被误拦。
16. 作为普通用户，我想要选中一个 skill 后它变成输入框里的标签，以便我知道这轮会用到哪个能力。
17. 作为普通用户，我想要 `/模型` 选中后在输入框内直接出现二级模型选择，以便不用来回切换焦点和弹窗。
18. 作为普通用户，我想要 `/压缩` 立即压缩当前上下文，以便长对话不会因为上下文超限而质量下降。
19. 作为普通用户，我想要 `/下载日志` 导出本次会话的事件日志，以便把问题现场发给管理员排查。
20. 作为普通用户，我想要在输入框打 `@` 时弹出本地文件选择器，以便对话时直接把文件交给 agent。
21. 作为普通用户，我想要 `@` 只在行首或空格后触发，以便我输入邮箱地址时不会被误拦。
22. 作为普通用户，我想要选中的文件在输入框里显示为一个标签，并按一次 Backspace 就能整块删掉，以便快速修正。
23. 作为普通用户，我想要在发送前看到附件数量与大小限制提示，以便知道为什么某个文件传不上去。
24. 作为普通用户，我想要上传被类型白名单拒绝时看到明确原因，以便换成支持的格式。
25. 作为普通用户，我想要在对话中拿到产物时看到下载卡片，以便直接把结果拿走。
26. 作为普通用户，我想要产物下载链接有时效，以便链接被转发出去后不会长期可访问。
27. 作为普通用户，我想要在 skill 管理页看到所有公共 skill 及其说明，以便决定启用哪些。
28. 作为普通用户，我想要一键启用/停用一个 skill，以便按任务切换能力集合。
29. 作为普通用户，我想要在对话中启用一个新 skill 后，下一轮就能用上，以便不必重开会话。
30. 作为普通用户，我想要启用 skill 后看到"下一轮生效"的提示，以便不会误以为本轮就会用上。
31. 作为普通用户，我想要在 MCP 管理页看到可用的公共 MCP server 及其说明，以便知道公司接了哪些数据源。
32. 作为普通用户，我想要点启用 MCP 时被跳到用户中心完成授权，以便不用手工申请和粘贴 token。
33. 作为普通用户，我想要授权后自动回到工作台且该 MCP 立即可用，以便不中断流程。
34. 作为普通用户，我想要 MCP 授权过期时被弹窗要求重新授权，以便不会看到 agent 莫名其妙报错。
35. 作为普通用户，我想要单独撤销某个 MCP 的授权，以便收回对某一个系统的访问而不影响其他。
36. 作为普通用户，我想要在个人设置里看到自己的用量与配额，以便知道还能用多少。
37. 作为管理员，我想要上传 skill 包（zip）并填写说明，以便把团队能力沉淀成可复用资产。
38. 作为管理员，我想要上传时立刻得到校验结果（缺 `SKILL.md`、名字不合规、含符号链接等），以便在发布前修正而不是让用户踩坑。
39. 作为管理员，我想要看到 skill 的启用人数，以便判断哪些能力值得继续维护。
40. 作为管理员，我想要把 skill 软删除下架，以便已启用的用户不会突然失去能力，而新用户看不到它。
41. 作为管理员，我想要配置公共 MCP server 的地址、传输方式与协议版本，以便接入不同实现的数据源。
42. 作为管理员，我想要为每个 MCP server 指定是共享连接还是每用户授权，以便在"只读公共数据"和"按人过滤"两种场景下都选对模式。
43. 作为管理员，我想要创建/禁用用户并分配管理员或普通用户角色，以便控制谁能发布能力。
44. 作为管理员，我想要配置模型供应商与可切换的模型白名单，以便不改代码就能增删模型。
45. 作为管理员，我想要设置每个用户的日 token 配额，以便成本可控。
46. 作为管理员，我想要查询审计日志（谁发布了什么、谁启用了什么），以便追溯变更。
47. 作为管理员，我想要在用户反馈问题时拿到该会话的事件日志，以便还原现场。
48. 作为 MCP 提供方（外部系统），我想要通过标准 OAuth2.1 授权码 + PKCE 对接用户中心，以便不用为这个平台定制一套鉴权。
49. 作为 MCP 提供方，我想要在收到的 token 里拿到 `tenant` 与 `role`，以便按角色做行级数据过滤。
50. 作为 MCP 提供方，我想要 token 短时效且可被用户单方撤销，以便安全边界清晰。
51. 作为运维人员，我想要沙箱在空闲后自动快照并销毁、下次对话自动恢复，以便不必为闲置会话付资源。
52. 作为运维人员，我想要沙箱镜像同时满足本地 Docker 与生产 K8s，以便上线时不因环境差异翻车。
53. 作为运维人员，我想要同一会话的请求串行执行，以便不会因为并发写坏会话状态。
54. 作为运维人员，我想要队列深度与等待超时有明确上限，以便突发流量不会无限堆积。
55. 作为运维人员，我想要关键路径（首 token、沙箱获取、产物落地）有可观测指标，以便定位慢在哪。
56. 作为普通用户，我想要在沙箱正在冷启动时看到"沙箱启动中"而不是干等，以便知道系统在做什么。
57. 作为普通用户，我想要在 agent 因权限或配额被拦截时看到可理解的原因，以便知道该找谁。
58. 作为普通用户，我想要上传的附件在会话内被 agent 看到（落在它的工作区里），以便它能真正读取内容而不是只知道文件名。

**私有资源（用户自建）**

59. 作为普通用户，我想要自己上传一个 skill zip 包，以便把个人的工作方法沉淀成可复用能力，而不必求管理员发布。
60. 作为普通用户，我想要上传私有 skill 时得到和公共 skill 一样的校验反馈（缺 `SKILL.md`、名字不合规、含符号链接等），以便自己就能改对。
61. 作为普通用户，我想要我的私有 skill 只有我自己能看到和启用，以便不会把半成品暴露给同事。
62. 作为普通用户，我想要把私有 skill 和公共 skill 一起在 `/` 菜单里选择，以便用起来没有区别。
63. 作为普通用户，我想要删除自己的私有 skill，以便清理不再用的能力。
64. 作为普通用户，我想要上传私有 skill 时如果名字与某个公共 skill 撞了，直接被告知改名，以便不会出现"到底用了哪个"的困惑。
65. 作为普通用户，我想要自己添加一个 MCP server 地址，以便接入公司没统一接入的数据源。
66. 作为普通用户，我想要在保存私有 MCP 前先测一下连通性，以便当场知道地址或协议版本填错了。
67. 作为普通用户，我想要连接第三方 MCP 时用我自己的第三方账号登录，以便不需要把第三方密码交给平台，也不必手工申请 token。
68. 作为普通用户，我想要在第三方登录完成后自动回到工作台且该 MCP 立即可用，以便不中断流程。
69. 作为普通用户，我想要为不支持 OAuth 的服务手工填写 token，以便只发 API key 的服务也能接。
70. 作为普通用户，我想要在私有 MCP 地址指向内网或本机时被明确拒绝，以便知道这是平台的安全策略而不是我填错了。
71. 作为管理员，我想要看到所有用户创建的私有 skill 与私有 MCP 清单，并能禁用其中任何一个，以便在出现滥用时能处置。
72. 作为管理员，我想要为用户自建资源设置数量与大小配额，以便不会被单个用户把存储或连接数占满。

## Implementation Decisions

### 模块划分

三个可独立部署的单元，一个 monorepo 承载：

- **web**：Vue 3 + TypeScript + Element Plus + Pinia + UnoCSS，以现有 `vue3-web-template` 为脚手架基线（其登录页、auth store、axios 封装、vitest/Playwright 配置直接继承）。页面：登录/回调、工作台、skill 管理、MCP 管理、产物、管理后台（用户/模型/配额/审计）。
- **server**：Spring Boot，承载认证过滤器、turn 编排与排队、SSE 事件网关、skill/MCP/产物 API、`HarnessAgent` 生命周期管理。
- **auth**：独立部署的用户中心，Spring Authorization Server 实现 OAuth2.1 授权码 + PKCE，签发 JWT 并暴露 JWKS 与 `/userinfo`。只做最小 OIDC，不做动态客户端注册、不做个性化同意页。
- **deploy**：本地 compose（MySQL/Redis/MinIO）与生产 K8s 清单。

### 五个必须第一天就位的接口

「生产形态」的本质是接口边界而非副本数。这五个接口全部定义好，本地实现允许简陋：

| 接口 | 职责 | v1 实现 |
|---|---|---|
| `ObjectStore` | put / get / head / presign / rangeGet / createMultipartUpload | 本地 MySQL BLOB，生产 OSS |
| `SandboxBackend` | create / exec / upload / download / snapshot / resume / destroy | 本地 Docker，生产 agent-sandbox（K8s CRD） |
| `StateStore` | Agent 状态按 `(tenant:user, sessionId)` 存取 | Redis |
| `EventLog` | 事件追加、按游标读取、归档 | Redis Stream |
| `ArtifactDeliveryTarget` | 框架 SPI，把产物落到对象存储 | 写 `ObjectStore` + `artifact` 表 |

`ObjectStore` 从一开始声明 `createMultipartUpload` 与 `rangeGet`；v1 实现可抛"不支持"，v2 补实现时调用方不改。

### 数据模型

所有业务表带 `tenant_id`（v1 恒为 `default`），为将来多租户留位。核心表：

- 身份：`tenant`、`user`、`role`、`user_role`、`user_session`
- 用户中心：`oauth_client`、`oauth_authorization`（授权码，短 TTL，单次消费）、`oauth_token`（access/refresh，加密存储，按 `resource` 维度）
- skill：`skill`（zip 元数据、版本、状态 `draft|published|archived`、对象键、大小、校验和、**`visibility = public|private`**、**`owner_user_id`**（公共为 null））、`user_skill_enable`
- MCP：`mcp_server`（传输方式、URL、**协议版本列表**、**`visibility = public|private`**、**`owner_user_id`**、`auth_mode = none|shared|user_center|third_party_oauth|manual_token`、第三方授权服务器元数据缓存）、`user_mcp_enable`、`user_mcp_token`（按 server 维度加密存储）
- 会话：`conversation`、`turn`（状态 `queued|running|done|failed|cancelled`、token 用量）、`turn_event`（**原始事件 JSON 落 MySQL**，按 `turn_id + seq` 唯一）、`artifact`、`sandbox_binding`（会话 → 快照/状态）
- 配额与审计：`model_provider`、`model_whitelist`、`quota_daily`（Redis 为主、日结落库）、`audit_log`

三处刻意不建表：内置指令（代码定义）、排队状态（纯 Redis）、skill 包内文件（zip 存对象存储，物化时才解压）。

### 私有资源的权限与安全边界

**可见性与可操作性矩阵**

| 资源 | 管理员 | 普通用户 |
|---|---|---|
| 公共 skill | 创建 / 修改 / 软删 / 启用 | 只能启用 / 停用 |
| 私有 skill | 可查看全部、可禁用 | 只能操作自己创建的 |
| 公共 MCP | 创建 / 修改 / 停用 | 只能启用 / 停用 |
| 私有 MCP | 可查看全部、可禁用 | 只能操作自己创建的 |

所有资源查询默认按 `(tenant_id, visibility = 'public' OR owner_user_id = 当前用户)` 过滤，且**在 SQL 层过滤，不在应用层筛**——应用层漏一处就是越权。

**skill 命名规则**：私有 skill 的 `name` 在 `(tenant, owner)` 内唯一，且**不得与任何公共 skill 同名**。撞名时拒绝上传并提示改名，不允许"私有覆盖公共"，否则用户永远搞不清当前用的是哪一个。

**私有 MCP 的 SSRF 防护**（用户填的 URL 由服务端 JVM 去连，这是一块新的攻击面）：

- 只允许 `https`（`http` 仅在显式开启的开发模式下允许）
- 解析后校验 IP，拒绝：回环（`127.0.0.0/8`、`::1`）、私网（`10/8`、`172.16/12`、`192.168/16`、`fc00::/7`）、链路本地（`169.254/16`，含云元数据端点 `169.254.169.254`）、`0.0.0.0/8`
- **先解析再固定 IP 连接**，防 DNS rebinding；禁止跟随重定向
- 连接与读取超时各 10 秒；只允许 `sse` 与 `streamable_http` 两种传输
- 违规时返回明确原因（"该地址指向内网，平台不允许"），不要笼统的 400

**MCP 的鉴权由「被连的服务」决定，不是由平台决定**。四种模式，按 server 逐个配置：

| `auth_mode` | 适用 | 用户看到什么 |
|---|---|---|
| `none` | 开放或内网无鉴权服务 | 直接启用 |
| `shared` | 平台统一凭证、无用户身份 | 直接启用 |
| `user_center` | 公司内部系统，信任我们的用户中心 | 跳用户中心授权（授权码 + PKCE，`resource={serverId}`） |
| `third_party_oauth` | 第三方 MCP，有自己的账号体系 | **跳第三方登录页**，用你自己的第三方账号登录 |
| `manual_token` | 只发 API key / 不支持 OAuth 的服务 | 粘贴 token |

`third_party_oauth` 走 MCP 规范自身的授权流程，平台扮演**第三方授权服务器的 OAuth 客户端**：

1. 从 MCP server 的 `/.well-known/oauth-protected-resource` 发现授权服务器
2. 未预注册客户端时，向第三方做**动态客户端注册**（RFC 7591）
3. 浏览器跳第三方授权页（授权码 + PKCE + `state`），用户在**第三方页面**登录自己的账号
4. 回调到平台的统一回调端点，换取并加密存储第三方 token
5. 校验返回的 `iss` 与发现阶段的授权服务器一致，防混淆攻击

要点：
- **用户登的是第三方账号，不是我们的账号**——平台只保存第三方签发的 token，不保存第三方密码
- 平台需要**动态客户端注册**能力（对第三方），这与"用户中心不做动态客户端注册"不冲突：前者是对外的客户端，后者是对内的授权服务器
- 第三方 token 过期用 refresh 续；续不上则弹"重新登录第三方账号"，不静默失效
- 每个 `(user, server)` 独立存 token，可单独撤销
- 公共 MCP 默认 `user_center`，私有 MCP 由用户添加时选择 `third_party_oauth` 或 `manual_token`

**配额**（可配置）：私有 skill 每人 20 个、单包 20MB；私有 MCP 每人 10 个。超限返回明确错误并提示清理。

**审计**：私有资源的创建、修改、删除、禁用全部写 `audit_log`；管理员禁用他人私有资源的操作同样留痕。

### API 契约

**Turn 与事件**

- `POST /api/conversations/{id}/turns` → `202 { turnId, cursor }`，**立即返回**，避免网关超时
- `GET /api/conversations/{id}/events?after={cursor}` → SSE 长连接，支持 `Last-Event-ID` 头
- `POST /api/conversations/{id}/turns/{turnId}/cancel` → 排队中则摘除，执行中则走框架中断
- 队列深度同会话 **1 执行 + 1 排队**，第 3 个返回 `429`；等待超时 **10 分钟**

**SSE 事件类型**：`turn/queued`（含 `position`、`estimatedWaitMs`）、`turn/started`、`token`（`delta`）、`tool_call`（`toolName`、`argsPreview`、`callId`）、`tool_result`（`callId`、`ok`、`summary`、`durationMs`）、`artifact`（`artifactId`、`fileName`、`sizeBytes`、`downloadUrl`）、`pending`（HITL，v2 启用）、`done`（`usage`）、`error`（`code`、`message`）、`turn/cancelled`。每 15 秒发一行 SSE 注释帧作心跳，客户端 60 秒无帧则重连。

**游标语义**：`cursor` 即 Redis Stream 消息 ID；重连从该点往后补齐，因此刷新页面能拿回全部历史事件。会话结束后整条 Stream 归档进 `turn_event` 再删除。

**输入候选**

- `GET /api/workbench/slash-items?conversationId=...` → `{ skills: [...], commands: [...] }`，**不含文件**（文件走 `@`）
- `POST /api/uploads` → 返回 `fileId`；文件在**发送时**上传到对象存储，写 `artifact(source=upload)`，chip 只带 `fileId`；开轮时由 server 从对象存储拉进沙箱 `uploads/`

**产物**

- 阈值 **3MB**（可配置）：`≤3MB` 走框架 SPI（JVM 中转），`>3MB` 走沙箱直传
- 沙箱直传时 **object key 由 server 生成并签进 presigned URL**，沙箱只负责 PUT；收到回执后 server 必须 `HEAD` 校验存在与大小再写库
- 单产物硬上限 32MB，超出返回明确错误，不静默截断
- 下载走短时效预签名 URL（5 分钟）+ 二次鉴权

**MCP**

- `POST /api/admin/mcp/servers` → 公共 MCP server
- `POST /api/mcp/servers` → **私有 MCP server**，保存前强制跑 SSRF 校验 + 配额检查
- `POST /api/mcp/servers/{id}/test` → 连通性与握手测试（用配置的协议版本列表逐个协商，失败时回报实际支持版本）
- `PUT /api/mcp/servers/{id}/token` → 私有 MCP 手工 token（加密落库）
- `DELETE /api/mcp/servers/{id}` → 仅本人私有 MCP 可删；公共 MCP 由管理员停用
- `POST /api/mcp/{serverId}/enable` → 按 `auth_mode` 分流：`none`/`shared` 直接启用；`user_center` 走 302 到用户中心 `/authorize`（带 `resource={serverId}`、PKCE challenge）；`third_party_oauth` 走下方第三方流程；`manual_token` 校验已配置 token 后启用
- `GET /api/mcp/callback?code=...&state=...` → 用户中心回调，换 token、加密落库
- `POST /api/mcp/servers/{id}/oauth/start` → 第三方授权：发现 `/.well-known/oauth-protected-resource`、必要时动态注册客户端、返回第三方授权 URL
- `GET /api/mcp/oauth/callback?code=...&state=...` → 第三方回调，校验 `iss` 与 `state` 后换 token 落库
- `POST /api/mcp/servers/{id}/oauth/revoke` → 撤销该 server 的第三方授权
- 按 **server 粒度**授权，撤销粒度可控；refresh 失败时**不静默失效**，前端弹"重新授权"（用户中心）或"重新登录第三方账号"（第三方）
- JWT claims 携带 `tenant` 与 `role`，行级过滤由 MCP 提供方实施

**skill**

- `POST /api/admin/skills`（multipart zip）→ 公共 skill，校验后发布
- `POST /api/skills`（multipart zip）→ **私有 skill**，同样的校验规则 + 撞名检查 + 配额检查
- `DELETE /api/skills/{id}` → 仅本人私有 skill 可删；公共 skill 由管理员软删
- `POST /api/skills/{id}/enable` / `DELETE /api/skills/{id}/enable` → 公共与私有统一入口，私有资源额外校验归属
- 管理员改 skill → 已启用用户**下次对话**读到新版；删除走软删（`archived`），已启用用户仍可用但列表标灰
- 私有 skill 被本人删除时**立即**从启用集移除（下一轮生效）

### 交互规则

- **`/` 与 `@` 都要求前一个字符是空格或行首**。任意位置触发会误拦 `a@b.com`、`/usr/bin`、`mailto:` 等输入。
- `/` 只弹 skill 与内置指令；`@` 才是文件选择器。选中 skill 变成 chip；选中指令后**在输入框内联出二级选择**（如 `/模型` 的模型列表），不弹独立对话框。
- 文件 chip 内按一次 Backspace **整块删除**。
- 内置指令三条，代码定义、不入库：`/压缩`（调框架 `ConversationCompactionStrategy.compact`，执行前先刷盘未落盘记忆）、`/模型`（切换**本会话后续轮次**模型，不改用户偏好）、`/下载日志`（导出会话事件 JSONL，含每轮 token 与工具耗时）。

### 架构决策

- **框架版本锁 `2.1.0-BETA1`**：产物 SPI 依赖 `SandboxFileTransfer`（`downloadFiles`），稳定版 `2.0.4` 没有该能力。必须固定版本号，不使用自建 SNAPSHOT（自建时 `agentscope-harness` 依赖 `agentscope-core` 的 test-jar，跳过测试编译会导致依赖解析失败）。
- **框架 API 只出现在一层 adapter 内**：2.0 相比 1.x 已有 breaking change，业务代码不得直接引用框架类型。
- **流式自研 SSE，不采用 AG-UI**：AG-UI 有中断恢复但没有事件日志重放语义，而本产品的任务动辄 30–60 秒、用户会刷新。
- **多副本无粘性路由**：事件走 Redis Stream，任意实例都能从游标续读；同会话互斥复用框架的 `SandboxExecutionGuard`（Redis `SET NX PX`），不自研锁。
- **沙箱绑定 `IsolationScope.SESSION`**：一会话一沙箱，空闲 30 分钟快照 + 销毁，下轮 resume（已实测：容器销毁后快照恢复，产物字节一致）。
- **skill 生效机制**：`RuntimeContextSkillRepository` 按请求上下文返回可见 skill，`SkillFilter` overlay 决定进入 prompt 索引的子集；`DynamicSkillMiddleware` 每次 `call()` 重新解析，因此"下一轮生效"是框架原生行为，无需改造。skill 正文按需加载（渐进披露），prompt 里只放索引。
- **沙箱镜像**：单一最小镜像（Ubuntu 24.04 + python3 + `sh`/GNU `stat -c`/`tar`/`base64`/`curl`）。框架要求 POSIX 契约，**alpine 与 distroless 不可用**。本地 Docker 与生产 K8s 必须使用同一镜像 tag。已知限制：镜像内无 LibreOffice，v1 无法做 xlsx/docx → PDF 的视觉校验。
- **上传能力**：单文件上限 5MB（可配置）、单轮 5 个（可配置）、类型白名单（xlsx/docx/pdf/csv/txt/md/json/图片）。**v1 不做**分片、秒传、断点续传/下载；只做普通上传 + `Range` 下载。
- **模型接入**：OpenAI 兼容端点，v1 先接 DeepSeek；供应商与白名单配置入库，管理端可增删，私有化 vLLM 只需改 baseUrl。
- **MCP 协议版本**：框架默认只协商 `2024-11-05`，接新版 server 会握手失败且报错不明显，因此 `protocol_versions` 必须是管理端可配字段。

## Testing Decisions

**什么算好测试**：只测外部可观察行为——HTTP 响应、SSE 事件序列、数据库最终状态、组件渲染结果。不测私有方法、不测内部调用次数、不对实现细节做断言（否则重构即红）。测试名描述行为而非方法名。

**接缝 1 — server 的 HTTP 边界（主接缝，业务规则只在这里测）**
`@SpringBootTest` + MockMvc/WebTestClient，Testcontainers 起 MySQL 与 Redis，`SandboxBackend` 与 `ObjectStore` 注入内存实现。覆盖 turn 生命周期、排队与取消、超时、SSE 事件序列与游标重放、skill zip 校验与启用生效时机、MCP 回调与续期失败、产物下载鉴权、配额拦截、越权访问（跨用户读会话/产物）。
私有资源另加一组：用户 A 看不到也启用不了用户 B 的私有 skill 与私有 MCP；私有 skill 撞公共名被拒；私有资源超配额被拒；`manual_token` 落库后不出现明文；管理员能列出并禁用他人私有资源。SSRF 拦截按地址分类逐条测（回环、私网、链路本地、元数据端点、`http://`、重定向到内网），断言返回的是可读原因而非 500。

**接缝 2 — 适配器契约测试（只跑真实后端）**
`SandboxBackend` 与 `ObjectStore` 的契约测试**直接跑在开发环境的真实 Docker 与 MinIO 上**，不做 fake 双实现。要验证的是 fake 永远对、真实后端不一定对的部分：快照恢复后文件字节一致、presigned URL 过期后拒绝、`Range` 请求边界（首字节、末字节、越界）、大文件直传后 `HEAD` 校验、容器销毁后 `resume` 拿到新容器。这些用例需要标记为可选执行（本机无 Docker 时跳过），CI 里单独跑。

**接缝 3 — auth 服务的 HTTP 边界**
真实 HTTP 客户端打完整 OAuth2.1 流程：`/authorize` → 授权码 → `/token` → `/userinfo`。覆盖 PKCE challenge 不匹配被拒、授权码单次消费、过期 token 被拒、refresh 轮换、JWKS 可被外部验证。理由是它是独立部署单元且第三方要对接，契约必须用真实协议验证。

**接缝 3b — 第三方 MCP 授权（用桩上游）**
平台在这里扮演第三方授权服务器的 OAuth 客户端，上游是外部服务、不能真连，所以用桩（本地假的 `.well-known/oauth-protected-resource`、授权端点、token 端点）。要验证的是：能发现授权服务器；无预注册时走动态客户端注册；`state` 不匹配被拒；返回的 `iss` 与发现阶段不一致被拒（防混淆）；第三方 token 加密落库；refresh 失败时返回可识别错误码，让前端弹「重新登录第三方账号」而不是 500。

**接缝 4 — 前端两条**
其一，`/` 与 `@` 的解析测在 **WorkbenchComposer 组件层**：输入 `@` 后选择器出现、选中后 chip 出现、Backspace 整块删除、`a@b.com` 不触发、`/模型` 选中后出现内联二级选择。其二，三条 Playwright 关键旅程：登录→工作台→一轮对话→产物下载卡片出现；skill 启用后下轮可用；MCP 启用跳转授权并回到工作台。

**受测模块**：server（全部业务规则 + 第三方授权流程）、auth（OAuth 流程）、web（composer 组件 + 三条 E2E）、两个适配器（契约）。**不测**：框架内部行为、`HarnessAgent` 的推理质量、模型输出内容。

**先例**：模板里已有 `tests/unit/` 的 vitest + `@vue/test-utils` 用例（auth store、http 封装、LoginView），以及 `e2e/` 的 Playwright + Page Object 结构（`pages/app.page.ts`）；新测试沿用同样的目录与写法，E2E 继续用 Page Object 而非裸选择器。后端无先例，本 spec 确立第一套。

## Out of Scope

- 多副本部署与分布式会话（接口已分层，扩副本是配置改动）
- K8s 预热池（`SandboxBackend` 换实现即可）
- 用户自定义指令模板（与 skill 能力重叠，v1 统一用 skill 承载，避免两套能力模型）
- 私有资源的审核流程（用户自建即用，不做管理员前置审批，只做事后禁用）
- 动态客户端注册**在我们自己的用户中心**上（授权服务器只接受管理员登记的客户端）
- 用户中心授权页的个性化同意（固定文案即可）
- 私有资源分享给他人或团队（私有即私有，转公共需管理员重新上传）
- BYOK（用户自带模型 Key）——表结构留位，不实现
- 团队/工作空间协作（隔离边界是 `(tenant, user, conversation)`）
- skill 包内文件级管理
- 分片上传 / 秒传 / 断点续传 / 分片下载
- RAG / 知识库
- 对外暴露 MCP server（框架只提供 MCP 客户端）
- HITL 待答表单的完整交互（事件类型预留，v1 不启用）
- xlsx/docx → PDF 的视觉校验（镜像无 LibreOffice）

## Further Notes

- **权限模型变更（相对早期设计）**：早期设计里普通用户只能启用公共资源，v1 改为同时支持用户自建私有 skill 与私有 MCP。这带来三块新增风险，必须一起实现而不是后补：① 私有 MCP 的 URL 由服务端 JVM 去连，构成 SSRF 攻击面，防护规则见「私有资源的权限与安全边界」；② 私有 skill 里的脚本会在该用户自己的沙箱内执行，沙箱无长期凭证（只有短时效预签名 URL），因此爆炸半径限于本人会话；③ 第三方授权把**别人的 token** 存进了我们的库，加密存储、按 `(user, server)` 隔离、可单方撤销这三条缺一不可。
- **第三方账号登录的边界**：平台只保存第三方签发的 token，**从不接触第三方密码**；用户在第三方页面上登录，我们只接收授权码。这也是不把第三方 OAuth 做成"代填账号密码"的原因。
- 完整架构设计、时序图与风险清单见仓库内 `docs/design/v1-architecture.md`；本 spec 是它的可执行版本，两者冲突时以 spec 为准并回改设计文档。
- 版本风险是最大不确定项：`2.1.0-BETA1` 是 BETA，且框架有近千个 open issue。缓解手段只有 adapter 隔离层——**框架 API 不得出现在 adapter 之外的代码里**。
- 可直接复用的参考实现（同为 AgentScope Java 仓库内的示例）：`agentscope-chat` 的 `snapshot + cursor + Last-Event-ID` 断线续传与 SSE 控制器写法；`agentscope-dataagent` 的多用户 BFF 形态（JWT、每用户 workspace、`UserSandboxRegistry`、Redis 多副本）。二者都是 React 前端，**UI 不复用**，只借后端与协议思路。
- 上一轮 POC 已实测：Docker 沙箱 `stop()`（快照）+ `shutdown()`（销毁）后，`resume(state)` 会新建容器并从快照 hydrate，产物字节一致。这条是"会话可恢复"承诺的事实基础。
- 建议的实施顺序：先 server 的接口定义与数据表迁移，再 auth 的最小 OIDC，最后 web 接工作台——前端依赖 SSE 协议定稿，而协议已在本 spec 中写死。
