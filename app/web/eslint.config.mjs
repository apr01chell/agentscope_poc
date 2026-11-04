import antfu from '@antfu/eslint-config'

/**
 * 公司内部统一 lint 基线（@antfu/eslint-config）。
 *
 * 只定风格，不改语义：缩进 2 空格、单引号、无分号，其余走 antfu 默认的 Vue / TS 推荐规则。
 * `ignores` 让构建产物与测试产物不参与 lint，否则 `pnpm build` 之后再 lint 会突然变红。
 */
export default antfu(
  {
    type: 'app',
    vue: true,
    typescript: true,
    stylistic: {
      indent: 2,
      quotes: 'single',
      semi: false,
    },
    ignores: [
      'dist/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  {
    // Vue 模板里带 a11y 属性的元素动辄 5~6 个属性，强制逐行写反而更难读；
    // 只放宽单行上限，多行仍然一个属性一行。
    name: 'vue3-web-template/vue',
    files: ['**/*.vue'],
    rules: {
      'vue/max-attributes-per-line': ['error', {
        singleline: { max: 6 },
        multiline: { max: 1 },
      }],
    },
  },
)
