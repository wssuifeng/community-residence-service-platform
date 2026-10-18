import axios from 'axios'
import { loadSession, saveSession, clearSession, getToken } from '@/common/auth/utils/auth'
import type { LoginResult } from '@/common/auth/types/user'
const RENEW_WINDOW=12*60*60*1000
let inflight:Promise<void>|null=null
let timer:ReturnType<typeof setInterval>|null=null
/** 只对前台可见、仍有效的会话续期；接口不参与通用请求拦截，避免递归和并发风暴。 */
export async function renewVisibleSession():Promise<void> {
  const session=loadSession()
  if(!session || document.visibilityState!=='visible' || session.expiresAt-Date.now()>RENEW_WINDOW)return
  if(inflight)return inflight
  const source=session.token
  inflight=(async()=>{
    try{
      const response=await axios.post<{code:number;data:LoginResult;message:string}>(`${import.meta.env.VITE_API_BASE_URL}/auth/renew`,null,{headers:{Authorization:`Bearer ${source}`},timeout:15000})
      if(response.data.code!==200)throw new Error(response.data.message)
      if(getToken()!==source)return
      const result=response.data.data
      saveSession({token:result.token,user:result.user,expiresAt:Date.now()+result.expiresIn*1000})
    }catch(error){
      // 旧会话的迟到失败不能踢掉已重新登录的新会话；网络错误保留原会话。
      if(axios.isAxiosError(error) && error.response?.status===401 && getToken()===source)clearSession()
      throw error
    }
  })().finally(()=>{inflight=null})
  return inflight
}
let checkVisible:(()=>void)|undefined
export function stopOnlineRenewal():void {
 if(timer)clearInterval(timer);timer=null
 if(checkVisible){document.removeEventListener('visibilitychange',checkVisible);window.removeEventListener('focus',checkVisible);checkVisible=undefined}
}
export function startOnlineRenewal():void {
  if(timer)return
  const check=()=>{void renewVisibleSession().catch(()=>{/* 请求链负责显示业务错误；离线不清会话。 */})}
  checkVisible=check
  timer=setInterval(check,60000)
  document.addEventListener('visibilitychange',check)
  window.addEventListener('focus',check)
  check()
}
