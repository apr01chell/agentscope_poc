# 多用户云端 Agent 平台

浏览器里的多用户 agent 工作台：用户对话，agent 调用**公共 skill** 与**已授权的 MCP**，代码执行下沉到按会话隔离的沙箱，产物进对象存储。

- 规格说明：[#1](https://github.com/apr01chell/agentscope_poc/issues/1)（`docs/specs/v1-spec.md`）
- 架构设计：[docs/design/v1-architecture.md](docs/design/v1-architecture.md)
- 工单：[#2–#25](https://github.com/apr01chell/agentscope_poc/issues)

## 结构

```
app/
  pom.xml        Maven 父 POM（Java 17 + Spring Boot 4.1.2）
  common/        两个服务共享的线上契约（响应信封）
  server/        工作台后端（8080）
  auth/          用户中心（8081）
  web/           Vue 3 + TS 前端（Vite 5173 / 容器 8088）
deploy/
  docker-compose.yml   全栈编排
  .env.example         端口与凭证示例
```

## 本地启动

### 方式一：全栈容器（验证用）

```bash
cd deploy
cp .env.example .env     # 可选，改端口或凭证
docker compose up -d
docker compose ps        # 六个服务都应为 healthy
```

打开 <http://localhost:8088>，登录页底部应显示「后端已连接 · server v0.1.0」。

### 方式二：中间件容器 + 本地进程（日常开发）

```bash
cd deploy && docker compose up -d mysql redis rustfs
```

后端（需要 JDK 17）：

```bash
cd app
mvn verify                                    # 编译 + 单测
mvn -pl server spring-boot:run                # 或直接在 IDE 里跑 ServerApplication
```

前端：

```bash
cd app/web
pnpm install
pnpm dev          # http://localhost:5173，/api 代理到 localhost:8080
```

## 端口

| 服务 | 端口 | 说明 |
|---|---|---|
| web | 8088（容器）/ 5173（dev） | 前端 |
| server | 8080 | 工作台后端 |
| auth | 8081 | 用户中心 |
| MySQL | **13306** → 3306 | 避开本机已有 MySQL |
| Redis | **16379** → 6379 | 避开本机已有 Redis |
| RustFS | 9000 / 9001 | S3 API / 控制台，凭证默认 `rustfsadmin` |

MySQL 与 Redis 故意不用默认端口：本机常已有其他项目的实例在跑，端口冲突会让 `compose up` 直接失败。

## 验证

```bash
# 后端：编译 + 单测
cd app && mvn verify

# 前端：lint + 类型 + 单测
cd app/web && pnpm lint && pnpm typecheck && pnpm test

# 全栈健康
cd deploy && docker compose ps
```

CI 跑同样的三件事，见 `.github/workflows/ci.yml`。

## 约定

- 后端响应统一走 `{ success, message, data }` 信封（`app/common` 的 `ApiResult`），前端用 `unwrap` 取 `data`。
- 前端只允许用 `src/api/http.ts` 的统一实例发请求；Mock 适配器对未登记的路径**透传**给真实网络，所以健康检查拿到的一定是真实后端。
- 前端编码规范见 `app/web/AGENTS.md`。
