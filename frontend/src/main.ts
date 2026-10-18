import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { pinia } from '@/common/auth/store/index'
import router from '@/router'
import { setupDirectives } from '@/common/web/directives/index'
import { applyElementPlusDefaults } from '@/common/web/utils/elementPlusDefaults'
import '@/common/styles/variables.css'
import '@/common/styles/breakpoints.css'
import '@/common/styles/index.css'
import '@/common/styles/sidebar-scrollbar.css'

const app = createApp(App)

/* 组件库默认值收口须在组件被解析前完成（组件库设计规范 §6.3.2：蒙层点击不关闭弹窗） */
applyElementPlusDefaults()
app.use(pinia)
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.use(router)
setupDirectives(app)

app.mount('#app')
