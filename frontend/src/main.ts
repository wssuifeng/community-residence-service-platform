import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { pinia } from './store'
import router from './router'
import { setupDirectives } from './directives'
import './styles/variables.css'
import './styles/breakpoints.css'
import './styles/index.css'

const app = createApp(App)

app.use(pinia)
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.use(router)
setupDirectives(app)

app.mount('#app')
