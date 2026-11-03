# 前端模板 Agent 约定

这是一个**公司内部通用前端模板**：Vite + Vue 3 + TypeScript + Pinia + VueUse + Vue Router + Axios + Element Plus + UnoCSS（presetWind4）+ `@antfu/eslint-config` + Playwright。它只包含登录与首页两屏，新项目从这里「一键起架」，把业务代码加进来。

> 本文件是本仓库的**通用约定**（不含任何具体业务）。改写业务时请保留结构，只替换示例页 `src/views/HomeView.vue`。

## 技术基线

- Vue 3 Composition API，统一使用 `<script setup lang="ts">` 和 TypeScript；不允许 Options API 与 `any`。
- Pinia 管理跨页面 / 会话状态，组件内只保留局部 UI 状态（`setup store` 写法，见 `src/stores/auth.ts`）。
- Vue Router：路由按页面惰性加载，登录态与标题通过 `meta.requiresAuth` / `meta.title` 声明。
- Axios：**只允许**使用 `src/api/http.ts` 里的 `http` / `request` / `unwrap`，禁止组件内 `axios.create`。
- Element Plus 提供交互组件；布局与样式一律 UnoCSS 原子类，启用 `presetWind4`（Tailwind CSS v4 语义）。
- VueUse 提供通用组合式能力（持久化、暗色主题、时间、监听等），能用就不手写等效逻辑。
- Playwright 是**给 AI 用的验收通道**，页面对象在 `e2e/pages/`。

## 目录结构

```
src/            # 只放会被打进产物的代码，不含任何测试文件
  api/          # Axios 实例、鉴权拦截器、错误归一化；按领域拆 auth.ts 等
  components/   # 无业务耦合的可复用组件
  layouts/      # 页面骨架（顶栏 + 内容区 + 页脚）
  mock/         # 内置认证 Mock，VITE_USE_MOCK=false 关闭
  modules/      # 业务模块（按领域划分，当前为空，新增业务放这里）
  router/       # 路由表、守卫、safeRedirect
  stores/       # Pinia store
  styles/       # 全局样式：设计 token（theme.css）、Element 主题适配
  types/        # 跨模块类型：ApiResult、分页信封、领域类型
  views/        # 路由页面
tests/          # 单元测试（Vitest + jsdom）
  setup.ts      # 全局准备（清存储、屏蔽 jsdom 未实现的 API）
  unit/         # 目录结构与 src/ 镜像：unit/api、unit/stores、unit/views …
e2e/            # 端到端测试（Playwright）
  pages/        # 页面对象，AI 自动化测试的唯一入口
  *.spec.ts     # 用例
```

### 为什么测试不放在源码旁边

「colocate（测试与源码同目录）」在小型库里很流行，但作为**公司通用模板**，分离更划算：

- `src/` 的语义变成「会被打包进产物的代码」，代码审查与体积排查时不用逐个人肉区分；
- 与 `e2e/` 对称：`tests/unit`（jsdom 单测）、`e2e`（真浏览器），「这条断言该写哪」没有歧义；
- 新增功能时不会出现「一半文件把测试放旁边、一半放进目录」的混搭——那是模板最容易传下去的技术债。

代价是引用路径变长，所以测试统一用 `@/` 别名（不要写 `../../src/...`）。新增测试时保持
`tests/unit/<与 src 同名的目录>/<被测文件>.test.ts`。

## 请求约定

- 后端响应体统一为 `{ success, message, data }`（`ApiResult<T>`），用 `unwrap` / `request<T>` 解包；业务代码不再出现 `response.data.data`。
- 错误统一经 `toApiError` 归一化为 `ApiError { message, status, code }`，页面只展示 `error.message`，不要各自判断 Axios 错误形状。
- 401 的语义：受保护接口 → 清空会话并跳登录页；`/auth/login` 自身 → 账号密码错误，不跳转。
- 新增接口时：先在 `src/api/<domain>.ts` 定义路径与函数，再在 store / composable 里使用，组件不直接拼 URL。

## 状态与页面

- `AuthStatus` 必须区分 `unknown | authenticated | unauthenticated | unavailable`：`unknown` 是「还没问过服务端」，不能当成「未登录」；`unavailable` 是后端不可达，保留本地会话让用户重试。
- 路由守卫用返回值写法（返回路由或 `true`）；跳转参数一律过 `safeRedirect`，禁止直接把 query 拼进 `router.replace`。
- 页面标题写进 `meta.title`，由 `App.vue` 的 `useTitle` 统一拼接，不要在页面里手写 `document.title`。

## 组件选型：先 Element Plus，再 UnoCSS

优先级是**强制**的，按顺序判断，不要跳步：

1. **Element Plus 有现成组件 → 必须用**。按钮、输入、下拉、弹窗、表格、分页、提示、日期、树、
   上传、骨架屏、空状态等一律用 `el-*`。理由不是省事，而是这些组件自带键盘交互、焦点管理、
   无障碍属性、暗色适配与统一设计语言；手写一遍必然缺几项，且组件库升级时成为唯一例外。
2. **Element Plus 有组件但布局不同 → 用它 + UnoCSS 补差异**。例如胶囊形按钮用 `round` 属性，
   需要微调间距时用 `class="!py-1 !pr-3"`，而不是退回原生标签重写。
3. **Element Plus 确实没有 → 才用 UnoCSS 写新组件**，并放进 `src/components/`，同时补单元测试。

禁止的做法：

- 用原生 `<button>` / `<input>` / `<select>` 代替已有的 `el-button` / `el-input` / `el-select`；
- 复制一份 Element Plus 已有能力（自己写 tooltip、下拉、弹层、表单校验）；
- 同时引入第二套 UI 库或图标库（例如再装 antd、naive、tailwind 组件库）。

`el-tooltip`、`el-dropdown` 等需要包裹元素的组件，其触发器也应是 `el-button`，不要塞原生标签。

## 样式与可访问性

- 颜色只来自 `src/styles/theme.css` 的 `--app-*` 变量（由 `uno.config.ts` 的 `theme.colors` 映射成 `bg-page` / `text-muted` 等工具类）；业务代码不写色值。暗色适配通过 `.dark` 覆盖变量完成，不需要写两套类名。
- Element Plus 主题通过 `src/styles/element.css` 的 CSS 变量映射，禁止覆盖组件内部 DOM 结构。
- 交互元素必须有可读的 aria 标签或可见文本；焦点可见；颜色不能是唯一的状态表达方式。

## 测试与验收

- `pnpm typecheck`：`vue-tsc -b`。
- `pnpm lint` / `pnpm lint:fix`：`@antfu/eslint-config`。
- `pnpm test`：Vitest 单元测试（`tests/unit/**/*.test.ts`，jsdom）。
- `pnpm test:e2e`：Playwright，先 build 再 preview，测真实产物；首次运行需 `pnpm test:e2e:install`。
  若环境不便下载浏览器，用 `pnpm test:e2e:chrome` 复用系统 Chrome。
- `pnpm ai:check`：类型 + lint + 单测 + E2E 全链路，AI 改完代码后先跑它。
- 新增页面时：同时补 `e2e/pages/` 的页面对象与至少一条 spec；选择器优先 `data-testid`，不要用结构选择器。
- Element Plus 组件会把未识别属性透传到内部原生元素（例如 `el-input` 的 `data-testid` 落在
  `<input>` 上），所以页面对象里**不要**再 `.locator('input')` 往下钻一层。
- 不要默认开 `video`：视频需要额外的 ffmpeg 二进制，缺失时所有用例都会在 `newPage` 阶段失败，
  报错信息却像「浏览器没装」。trace + 截图已足够定位。
- 提交前 `pnpm typecheck`、`pnpm lint`、`pnpm test` 必须全绿。
