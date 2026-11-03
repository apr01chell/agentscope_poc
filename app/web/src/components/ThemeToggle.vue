<script setup lang="ts">
import { useDark, useToggle } from '@vueuse/core'

/**
 * 主题切换。
 *
 * `useDark` 负责把 `<html class="dark">` 与 localStorage 双向绑定，并在默认的 `auto` 模式下
 * 跟随系统偏好；具体颜色由 `src/styles/theme.css` 里的 CSS 变量与 `.dark` 覆盖决定。
 *
 * 按钮直接用 `el-button`：圆角、尺寸、hover、focus-visible、禁用态、暗色适配都由 Element Plus
 * 与 `src/styles/element.css` 的主题映射统一提供。手写 `<button>` 会把这些逐条重做一遍，
 * 一旦组件库调整交互规范，模板里的按钮就成了唯一的例外。
 */
const isDark = useDark({
  storageKey: 'vue3-web-template.theme',
  valueDark: 'dark',
  valueLight: '',
})
const toggleDark = useToggle(isDark)
</script>

<template>
  <el-tooltip :content="isDark ? '切换到亮色' : '切换到暗色'" placement="bottom">
    <el-button
      circle
      aria-label="切换主题"
      :aria-pressed="isDark"
      data-testid="theme-toggle"
      @click="toggleDark()"
    >
      <!-- 表情只是图标占位：引入 @element-plus/icons-vue 后换成 <Moon /> / <Sunny /> 即可。 -->
      <span aria-hidden="true">{{ isDark ? '🌙' : '☀️' }}</span>
    </el-button>
  </el-tooltip>
</template>
