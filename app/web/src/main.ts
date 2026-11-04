import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { installAuthInterceptors } from './api/interceptors'
import App from './App.vue'
import { router } from './router'
// 顺序有意义：先 Element Plus 的基础样式，再本项目的样式与工具类。
// `src/styles/element.css` 与 Element 自身的变量都定义在 `:root`，后加载者生效。
import 'element-plus/dist/index.css'
import './styles/index.css'
import 'virtual:uno.css'

const app = createApp(App)
const pinia = createPinia()

// 拦截器依赖 router 与 pinia 实例，必须在首次导航前装好，否则首屏 401 不会跳转到登录页。
installAuthInterceptors(router, pinia)

app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.mount('#app')
