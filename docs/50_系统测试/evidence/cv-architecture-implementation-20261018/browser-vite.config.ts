import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath,URL} from 'node:url'
export default defineConfig({plugins:[vue()],resolve:{alias:[
 {find:'@/common/messaging/utils/websocket',replacement:fileURLToPath(new URL('./cv-ws.ts',import.meta.url))},
 {find:'@',replacement:fileURLToPath(new URL('./src',import.meta.url))}
]},server:{host:'127.0.0.1',port:5188,strictPort:true,proxy:{}}})
