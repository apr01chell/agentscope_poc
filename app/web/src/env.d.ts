/**
 * 项目环境变量声明。
 *
 * 注意：这个文件**不能**出现顶层 `import` / `export`。一旦成为 ES 模块，`interface ImportMetaEnv`
 * 就只作用于模块内部，`import.meta.env.XXX` 在全项目都会退化成 `any` 或直接报错。
 * 需要给 `ImportMeta` 补类型时用 `declare global` 包裹。
 *
 * 在这里声明而不是在代码里写 `import.meta.env.XXX as string`：拼错变量名时 `vue-tsc` 会直接
 * 报错，而不是在运行时拿到 `undefined` 却看起来一切正常。值的来源见 `.env.example`。
 */
interface ImportMetaEnv {
  /** 应用标题，写入 document.title 与顶栏、登录页。 */
  readonly VITE_APP_TITLE?: string
  /** 接口基础地址；留空表示走同源相对路径（开发期由 Vite 代理 `/api`）。 */
  readonly VITE_API_BASE_URL?: string
  /** 请求超时（毫秒），缺省 15000。 */
  readonly VITE_API_TIMEOUT?: string
  /** 是否使用内置认证 Mock（`'false'` 关闭）；缺省开启。 */
  readonly VITE_USE_MOCK?: string
  /** 开发代理目标地址，仅 vite.config.ts 使用。 */
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
