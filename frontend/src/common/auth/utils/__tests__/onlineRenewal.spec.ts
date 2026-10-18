import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { saveSession,loadSession,clearSession } from '@/common/auth/utils/auth'
import { renewVisibleSession,stopOnlineRenewal } from '@/common/auth/utils/onlineRenewal'
import axios from 'axios'
const api={post:vi.spyOn(axios,'post')}
const seed=(token='old',hours=1)=>saveSession({token,user:{id:1,username:'测试',realName:'测试',role:'RESIDENT'},expiresAt:Date.now()+hours*3600000})
const result={data:{code:200,data:{token:'new',expiresIn:129600,user:{id:1,username:'测试',realName:'测试',role:'RESIDENT'}}}}
beforeEach(()=>{sessionStorage.clear();Object.defineProperty(document,'visibilityState',{value:'visible',configurable:true});api.post.mockReset().mockResolvedValue(result)})
afterEach(()=>{stopOnlineRenewal();sessionStorage.clear()})
describe('有效在线会话续期',()=>{
 it('接近到期更新令牌和36h到期时间',async()=>{seed();await renewVisibleSession();expect(loadSession()?.token).toBe('new');expect(loadSession()!.expiresAt-Date.now()).toBeGreaterThan(129599000)})
 it('隐藏页面和新36h会话不续期',async()=>{seed('old',36);await renewVisibleSession();expect(api.post).not.toHaveBeenCalled();seed();Object.defineProperty(document,'visibilityState',{value:'hidden'});await renewVisibleSession();expect(api.post).not.toHaveBeenCalled()})
 it('多请求只发一次续期',async()=>{seed();await Promise.all([renewVisibleSession(),renewVisibleSession(),renewVisibleSession()]);expect(api.post).toHaveBeenCalledTimes(1)})
 it('续期在途退出后不能复活登录',async()=>{seed();let finish!:(v:unknown)=>void;api.post.mockImplementation(()=>new Promise(r=>{finish=r}));const pending=renewVisibleSession();clearSession();finish(result);await pending;expect(loadSession()).toBeNull()})
 it('旧续期401不能踢掉新登录',async()=>{seed();let fail!:(e:unknown)=>void;api.post.mockImplementation(()=>new Promise((_,r)=>{fail=r}));const pending=renewVisibleSession();seed('fresh-login',36);fail({isAxiosError:true,response:{status:401}});await expect(pending).rejects.toBeTruthy();expect(loadSession()?.token).toBe('fresh-login')})
 it('网络失败保留原会话，真正401清理当前会话',async()=>{seed();api.post.mockRejectedValue(new Error('offline'));await expect(renewVisibleSession()).rejects.toThrow('offline');expect(loadSession()?.token).toBe('old');api.post.mockRejectedValue({isAxiosError:true,response:{status:401}});await expect(renewVisibleSession()).rejects.toBeTruthy();expect(loadSession()).toBeNull()})
 it('过期会话不可续期或恢复',async()=>{seed('expired',-1);await renewVisibleSession();expect(api.post).not.toHaveBeenCalled();expect(loadSession()).toBeNull()})
})
