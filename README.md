# 多用户云端 Agent 平台

浏览器里的多用户 agent 工作台：用户对话，agent 调用**公共 skill** 与**已授权的 MCP**，代码执行下沉到按会话隔离的沙箱，产物进对象存储。

- 规格说明：[#1](https://github.com/apr01chell/agentscope_poc/issues/1)（`docs/specs/v1-spec.md`）
- 架构设计：[docs/design/v1-architecture.md](docs/design/v1-architecture.md)
- 用户中心方案：[docs/design/14-auth-oidc.md](docs/design/14-auth-oidc.md)
- 工单：[#2–#25](https://github.com/apr01chell/agentscope_poc/issues)

## 结构

```
app/
  pom.xml        Maven 父 POM（Java 17 + Spring Boot 4.1.2）
  common/        两个服务共享的线上契约（响应信封）
  server/        工作台后端（8080）
  auth/          用户中心（8081）
  auth-console/  用户中心控制台（Vite 5174）
  web/           Vue 3 + TS 前端（Vite 5173 / 容器 18088）
deploy/
  docker-compose.yml   中间件默认；应用服务在 full profile
  .env.example         端口与凭证示例
```

## 本地启动

默认**只起中间件**，后端在 IDE 里跑、前端用 vite 跑——改代码不用重建镜像，也不用和容器争端口。

### 中间件（日常开发）

```bash
cd deploy
cp .env.example .env     # 可选，改端口或凭证
docker compose up -d     # mysql + redis + rustfs
```

### 后端（IDE 或命令行）

```bash
cd app
mvn verify                                    # 编译 + 单测
mvn -pl server spring-boot:run                # 工作台后端 :8080
mvn -pl auth   spring-boot:run                # 用户中心   :8081
```

两个服务默认连 `localhost:13306` 的 MySQL，与 compose 起的中间件一致。

### 前端

```bash
cd app/web
pnpm install && pnpm dev          # http://localhost:5173，/api 代理到 :8080

cd app/auth-console
pnpm install && pnpm dev          # http://localhost:5174，/auth 代理到 :8081
```

### 容器化全栈（验证用）

```bash
cd deploy
docker compose --profile full up -d
```

应用服务放在 `full` profile 里，宿主端口是 **18080 / 18081 / 18088**，
所以它能和本地 IDEA（8080/8081）、vite（5173/5174）同时跑，互不干扰。

## 端口

| 服务 | 本地调试 | 容器（full profile） | 说明 |
|---|---|---|---|
| web（工作台前端） | 5173 | 18088 | vite dev / nginx |
| server | 8080 | 18080 | 工作台后端 |
| auth | 8081 | 18081 | 用户中心 |
| auth-console | 5174 | 未容器化（#28） | 控制台 |
| MySQL | — | **13306** → 3306 | 避开本机已有 MySQL |
| Redis | — | **16379** → 6379 | 避开本机已有 Redis |
| RustFS | — | 9000 / 9001 | S3 API / 控制台，凭证默认 `rustfsadmin` |

容器端口故意与本地端口错开：**full profile 与本地调试可以同时运行**。
MySQL/Redis 不用默认端口，是因为本机常已有其他项目的实例在跑。

## 验证

```bash
# 后端：编译 + 单测
cd app && mvn verify

# 前端：lint + 类型 + 单测
cd app/web && pnpm lint && pnpm typecheck && pnpm test

# 控制台：类型 + 单测
cd app/auth-console && pnpm typecheck && pnpm test

# 中间件健康（加 --profile full 则连应用服务一起）
cd deploy && docker compose ps
```

CI 跑同样的三件事，见 `.github/workflows/ci.yml`。

## 用户中心控制台

`app/auth-console` 是用户中心自带的控制台（Vue 3 + Element Plus），与 auth **同源**：开发时由 Vite 代理 `/auth`、`/oauth2`、`/userinfo` 到 `:8081`，生产由 nginx 反代（见工单 #28）。

```bash
cd app/auth-console
pnpm install
pnpm dev          # http://localhost:5174
```

用 `admin` / `admin123` 登录后可管理用户、角色（含权限勾选）与 OAuth2 客户端。

两个容易踩的点：

- **不要代理 `/login`**：`GET /login` 是 SPA 的路由，只有 `POST /auth/login` 才该到 auth。代理 `/login` 会让浏览器拿到 auth 的服务端登录页，SPA 根本不渲染。
- **代理不要 `changeOrigin: true`**：否则 Spring 会用 `:8081` 生成绝对跳转，session cookie 与重定向都会跑到错误的 origin。

控制台与 auth 同源，所以写请求必须带 CSRF 令牌：登录前先取 `/auth/csrf`（该端点匿名可访问），之后放在 `X-CSRF-TOKEN` 头里。

## 模型配置

工作台的 agent 走 AgentScope Java（`2.1.0-BETA1`），模型用 OpenAI 兼容协议接 DeepSeek：

```bash
cd deploy
cp .env.example .env
# 编辑 .env，填入你的密钥
DEEPSEEK_API_KEY=sk-...
```

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `MODEL_PROVIDER` | `deepseek` | `stub` 时用离线桩，不发任何网络请求（测试用） |
| `DEEPSEEK_API_KEY` | 空 | 为空时前端会看到"未配置模型密钥"的可读提示，不会白屏 |
| `MODEL_NAME` | `deepseek-chat` | 换成 `deepseek-reasoner` 或其他 OpenAI 兼容模型只需改这里 |
| `MODEL_BASE_URL` | `https://api.deepseek.com` | 私有化 vLLM 改这一行即可 |
| `MODEL_TIMEOUT_SECONDS` | `120` | 单轮超时 |

**框架类型只出现在 `agent/` 包内**：上层只依赖 `AgentRuntime` 接口，升级 AgentScope 时改动面被限制在这个包里。

## 用户中心（OIDC）

`auth` 是独立部署的授权服务器。启动后 Flyway 自动建表并幂等创建：

- 管理员账号：`admin` / `admin123`（可用 `AUTH_BOOTSTRAP_ADMIN_PASSWORD` 覆盖）
- 工作台客户端：`workbench` / `workbench-secret`（可用 `AUTH_WORKBENCH_CLIENT_SECRET` 覆盖）
- 14 个权限点，`ADMIN` 全部拥有；`USER` 不进入控制台

关键端点：

```bash
curl http://localhost:8081/.well-known/openid-configuration
curl http://localhost:8081/oauth2/jwks
curl -u workbench:workbench-secret -X POST http://localhost:8081/oauth2/token ...
```

签名密钥在 `app/auth/src/main/resources/keys/dev-rsa.pem`，**仅开发用**。生产必须设 `AUTH_JWK_PATH` 指向挂载的 Secret；`prod` profile 下仍用 classpath 密钥会直接启动失败。

集成测试跑在 Testcontainers MySQL 8.4 上，覆盖：PKCE 全流程与不匹配、授权码重放、JWKS 独立验签、userinfo claims、refresh 轮换、过期 token、未登记客户端、动态注册端点不存在。

```bash
cd app && mvn -pl auth test
```

## 约定

- 后端响应统一走 `{ success, message, data }` 信封（`app/common` 的 `ApiResult`），前端用 `unwrap` 取 `data`。
- 前端只允许用 `src/api/http.ts` 的统一实例发请求；Mock 适配器对未登记的路径**透传**给真实网络，所以健康检查拿到的一定是真实后端。
- 前端编码规范见 `app/web/AGENTS.md`。
