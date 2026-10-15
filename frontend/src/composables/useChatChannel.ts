import { onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { subscribe, isWsConnected } from '@/utils/websocket'
interface Message {id:number;content:string;createdAt:string}
interface Adapter<T extends Message>{id:()=>number;closed:()=>boolean;load:(id:number)=>Promise<T[]>;send:(id:number,content:string)=>Promise<T>;image:(id:number,file:File)=>Promise<T>;topic:(id:number)=>string;eventType:string;changed?:()=>void}
/** 公共消息引擎：去重、切会话防串、发送、图片、轮询/推送与关闭态；不承载业务鉴权。 */
export function useChatChannel<T extends Message>(adapter:Adapter<T>){
 const messages=shallowRef<T[]>([]),text=ref(''),sending=ref(false),readOnly=ref(false)
 let timer:ReturnType<typeof setTimeout>|undefined,unsubscribe:(()=>void)|undefined,seq=0,disposed=false
 const stop=()=>{if(timer)clearTimeout(timer);timer=undefined;unsubscribe?.();unsubscribe=undefined}
 const add=(item:T)=>{if(!messages.value.some(m=>m.id===item.id))messages.value=[...messages.value,item]}
 async function load(){const id=adapter.id(),version=seq;try{const rows=await adapter.load(id);if(!disposed&&version===seq&&id===adapter.id())messages.value=rows}catch(error){const e=error as {httpStatus?:number;code?:number};if(version===seq&&(e.httpStatus===403||e.httpStatus===404||e.code===403)){readOnly.value=true;stop()}}}
 const poll=()=>{if(disposed||adapter.closed()||readOnly.value)return;const version=seq;timer=setTimeout(()=>{void load().finally(()=>{if(version===seq)poll()})},isWsConnected()?15000:5000)}
 function connect(){stop();if(disposed||adapter.closed()||readOnly.value)return
  unsubscribe=subscribe(adapter.topic(adapter.id()),raw=>{const e=raw as {type?:string;data?:T;id?:number;content?:string};const candidate=e.type===adapter.eventType?e.data:e.type===undefined?e as T:undefined;if(candidate&&typeof candidate.id==='number'&&typeof candidate.content==='string')add(candidate);adapter.changed?.()});poll()
 }
 async function start(){seq++;messages.value=[];text.value='';readOnly.value=false;stop();await load();connect()}
 async function send(image?:File){if(sending.value||adapter.closed()||readOnly.value)return;const content=text.value.trim();if(!image&&!content)return
  const id=adapter.id(),version=seq;sending.value=true
  try{const item=image?await adapter.image(id,image):await adapter.send(id,content);if(version!==seq||disposed)return;add(item);if(!image)text.value='';adapter.changed?.()}
  catch(error){ElMessage.error(error instanceof Error?error.message:'发送失败，请重试')}
  finally{sending.value=false}
 }
 onMounted(()=>{void start()});watch(adapter.id,()=>{void start()});watch(adapter.closed,()=>{connect();void load()})
 onUnmounted(()=>{disposed=true;seq++;stop()})
 return {messages,text,sending,readOnly,sendText:()=>send(),sendImage:(file:File)=>send(file),reload:load}
}
