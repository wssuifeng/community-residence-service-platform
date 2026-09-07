import { createApp } from 'vue'
import App from './App.vue'
import { pinia } from './store'
import router from './router'
import { setupDirectives } from './directives'
import './styles/variables.css'
import './styles/breakpoints.css'
import './styles/index.css'

const app = createApp(App)

app.use(pinia)
app.use(router)
setupDirectives(app)

app.mount('#app')
