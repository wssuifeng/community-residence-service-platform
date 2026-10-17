import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { pinia } from './store'
import router from './router'
import { setupDirectives } from './directives'
import { applyElementPlusDefaults } from './utils/elementPlusDefaults'
import './styles/variables.css'
import './styles/breakpoints.css'
import './styles/index.css'
import './styles/sidebar-scrollbar.css'

const app = createApp(App)

/* 组件库默认值收口须在组件被解析前完成（组件库设计规范 §6.3.2：蒙层点击不关闭弹窗） */
applyElementPlusDefaults()
app.use(pinia)
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.use(router)
setupDirectives(app)

app.mount('#app')
