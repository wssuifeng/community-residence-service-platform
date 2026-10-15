import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { afterEach,beforeEach,describe,expect,it } from 'vitest'
import instance from '../request'
import { loadSession,saveSession } from '../auth'
const seed=(token:string)=>saveSession({token,user:{id:1,username:'测试',realName:'测试',role:'RESIDENT'},expiresAt:Date.now()+36*3600000})
const original=instance.defaults.adapter
const reject=(config:InternalAxiosRequestConfig)=>new AxiosError('Unauthorized','ERR_BAD_REQUEST',config,undefined,{data:{code:401,message:'失效'},status:401,statusText:'Unauthorized',headers:{},config})
beforeEach(()=>{sessionStorage.clear()});afterEach(()=>{instance.defaults.adapter=original;sessionStorage.clear()})
describe('401仅影响产生该请求的会话',()=>{
 it('旧请求失败时新登录已存在，安全重试新令牌而不登出',async()=>{seed('old');const seen:string[]=[];instance.defaults.adapter=async config=>{seen.push(String(config.headers.Authorization));if(seen.length===1){seed('new');throw reject(config)}return {data:{code:200,data:'ok'},status:200,statusText:'OK',headers:{},config}};await expect(instance.get('/test-read')).resolves.toBeTruthy();expect(seen).toEqual(['Bearer old','Bearer new']);expect(loadSession()?.token).toBe('new')})
 it('真正当前令牌401会清理会话，不自动复活',async()=>{seed('current');instance.defaults.adapter=async config=>{throw reject(config)};await expect(instance.get('/test-read')).rejects.toMatchObject({code:401});expect(loadSession()).toBeNull()})
 it('旧未登录请求的迟到401不能清理同时成功的新登录',async()=>{instance.defaults.adapter=async config=>{seed('fresh');throw reject(config)};await expect(instance.post('/auth/resident/login')).rejects.toMatchObject({code:401});expect(loadSession()?.token).toBe('fresh')})
})
