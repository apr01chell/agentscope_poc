import { defineConfig, presetWind4, transformerDirectives, transformerVariantGroup } from 'unocss'

/**
 * 设计 token 的颜色映射。
 *
 * 为什么必须登记到 `theme.colors`：`text-text`、`border-border` 这类「token 名与属性名相同」
 * 的工具类，presetWind4 只有在 theme 里查得到颜色时才产出 `color: ...`；不登记的话类名匹配
 * 不到规则，样式会静默不生效（类名在 DOM 上，屏幕上却没变化）。
 *
 * 变量本身定义在 `src/styles/theme.css`（前缀 `--app-`，暗色由 `.dark` 覆盖），这里只做映射，
 * 换色仍然只改一处。
 */
const themeColors = {
  'primary': 'var(--app-primary)',
  'primary-strong': 'var(--app-primary-strong)',
  'page': 'var(--app-page)',
  'surface': 'var(--app-surface)',
  'border': 'var(--app-border)',
  'text': 'var(--app-text)',
  'muted': 'var(--app-muted)',
  'on-primary': 'var(--app-on-primary)',
}

export default defineConfig({
  presets: [
    /**
     * presetWind4：Tailwind CSS v4 语义。
     *
     * 关掉 reset：全局基准样式统一在 `src/styles`，避免与 Element Plus 的基线互相覆盖。
     */
    presetWind4({
      preflights: {
        reset: false,
      },
    }),
  ],
  theme: {
    colors: themeColors,
  },
  transformers: [
    // 支持 `hover:(bg-primary text-on-primary)` 这样的变体分组写法。
    transformerVariantGroup(),
    // 允许在 `<style>` 里使用 `@apply`。
    transformerDirectives(),
  ],
  shortcuts: {
    'surface-card': 'bg-surface border border-border rounded-xl',
  },
})
