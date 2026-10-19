from support import *
from playwright.sync_api import sync_playwright
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from ws_client import Stomp
import ctypes,statistics
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];state=json.loads((PRIVATE/'state.json').read_text('utf-8'));checks=[]
def check(name,ok,actual=None):
 checks.append({'name':name,'passed':bool(ok),'actual':actual});save('redis-progress.json',{'checks':checks})
def command(pid):return subprocess.check_output(['powershell','-NoProfile','-Command',f'(Get-CimInstance Win32_Process -Filter "ProcessId={pid}").CommandLine'],text=True).strip()
def redis_start():
 with (PRIVATE/'redis-restored-out.log').open('ab') as out,(PRIVATE/'redis-restored-err.log').open('ab') as err:
  proc=subprocess.Popen(['D:/Tools/Memurai_Redis/memurai.exe','--port','6387','--bind','127.0.0.1','--save','','--appendonly','no','--dir',str(PRIVATE),'--logfile',str(PRIVATE/'redis-restored.log')],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
 state['redisPid']=proc.pid;(PRIVATE/'state.json').write_text(json.dumps(state,indent=2),'utf-8');time.sleep(2.5);return proc.pid
fid=api('/feedbacks',rt,'POST',{'communityId':1,'title':'df故障等待复验','content':'仅隔离数据','category':'INQUIRY'})['id']
api('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':'故障前预热'})
logout=login('df_pool_049','Resident123456','resident')['token']
with sync_playwright() as pw:
 browser=pw.chromium.launch(channel='msedge',headless=True);context=browser.new_context()
 auth=s['superadmin'];session={'token':sa,'user':auth['user'],'expiresAt':int(time.time()*1000)+auth['expiresIn']*1000}
 context.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')');context.route('**/ws/**',lambda route:route.abort())
 page=context.new_page();page.goto(WEB+'/admin/home');page.wait_for_load_state('networkidle')
 page.evaluate("async () => { window.dfApi = await import('/src/common/feedback/api/feedback.ts') }")
 def browser_send(marker):
  return page.evaluate("""async ({id,marker}) => { const t=performance.now(); try { const result=await window.dfApi.sendFeedbackMessage(id,{content:marker}); return {ok:true,id:result.id,ms:performance.now()-t} } catch(e) {return {ok:false,ms:performance.now()-t,error:e.message}} }""",{'id':fid,'marker':marker})
 pid=state['redisPid'];assert '--port 6387' in command(pid)
 subprocess.run(['powershell','-NoProfile','-Command',f'Stop-Process -Id {pid} -ErrorAction Stop'],check=True,capture_output=True)
 started=time.perf_counter()
 try:
  first=browser_send('df断开首个真实Axios请求');check('断开后真实前端请求小于15秒且业务成功',first['ok'] and first['ms']<15000,first)
  durations=[];responses=[]
  for round in range(3):
   gate=Barrier(50)
   def one(i):
    gate.wait();t=time.perf_counter();r=request('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':'df故障并发-%d-%d'%(round,i)},timeout=15);data=r.json();return {'http':r.status_code,'code':data.get('code'),'ms':round_value((time.perf_counter()-t)*1000)}
   def round_value(v):return float('%.3f'%v)
   with ThreadPoolExecutor(max_workers=50) as ex:result=list(ex.map(one,range(50)))
   responses.extend(result);durations.extend(x['ms'] for x in result)
   check('故障50路业务写入第'+str(round+1)+'轮',all(x['http']==200 and x['code']==200 and x['ms']<15000 for x in result),{'requests':len(result),'maxMs':max(x['ms'] for x in result)})
   page.wait_for_timeout(2500)
  r=request('/auth/resident/logout',logout,'POST',{},timeout=15);check('Redis故障退出仍写DB黑名单',r.status_code==200 and r.json().get('code')==200)
  r=request('/notifications',logout,timeout=15);check('Redis故障退出令牌仍被拒绝',r.status_code==401)
  check('150条反馈及通知全部落库',int(sql("SELECT COUNT(*) FROM feedback_message WHERE feedback_id="+str(fid)+" AND content LIKE 'df故障并发-%'"))==150)
  fault_elapsed=time.perf_counter()-started
 finally:pid=redis_start()
 check('断开后恢复',True,{'faultSeconds':float('%.3f'%fault_elapsed)})
 # Simulate a live TCP peer that stops answering commands, rather than connection refusal.
 assert '--port 6387' in command(pid)
 kernel=ctypes.windll.kernel32;kernel.OpenProcess.restype=ctypes.c_void_p
 handle=kernel.OpenProcess(0x0800,False,pid);assert handle
 suspended=False
 try:
  assert ctypes.windll.ntdll.NtSuspendProcess(ctypes.c_void_p(handle))==0;suspended=True
  hung=browser_send('df无响应Redis真实Axios请求');check('Redis进程无响应时前端15秒内成功',hung['ok'] and hung['ms']<15000,hung)
 finally:
  if suspended:assert ctypes.windll.ntdll.NtResumeProcess(ctypes.c_void_p(handle))==0
  kernel.CloseHandle(ctypes.c_void_p(handle))
 page.wait_for_timeout(3000)
 recovery=browser_send('df恢复后通知');check('恢复后实际前端继续成功',recovery['ok'] and recovery['ms']<15000,recovery)
 # Verify the real frontend wrapper sends a nonzero sinceSeq.
 cursor=int(sql("SELECT MIN(seq) FROM notification WHERE owner_type='RESIDENT' AND user_id=1"))
 rctx=browser.new_context();ra=s['resident1'];rctx.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps({'token':rt,'user':ra['user'],'expiresAt':int(time.time()*1000)+ra['expiresIn']*1000}))+')')
 rp=rctx.new_page();urls=[];rp.on('request',lambda req:urls.append(req.url) if '/notifications/pull' in req.url else None);rp.goto(WEB+'/resident/home');rp.wait_for_load_state('networkidle')
 data=rp.evaluate("async cursor => { const api=await import('/src/common/messaging/api/notification.ts'); const r=await api.pullNotifications(cursor); return {count:r.notifications.length,latestSeq:r.latestSeq,allAfter:r.notifications.every(n=>Number(n.seq)>cursor)} }",cursor)
 check('真实前端非零sinceSeq契约',data['count']>0 and data['allAfter'] and any('sinceSeq='+str(cursor) in u for u in urls) and not any('lastSeq=' in u for u in urls),{'cursor':cursor,**data,'pullURLs':urls})
 rp.screenshot(path=str(OUT/'redis-recovered.png'));rctx.close();context.close();browser.close()
ws=Stomp(rt);before=len(ws.messages);api('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':'df恢复后WS重新送达'});time.sleep(1)
check('Redis恢复后WS投递继续',len(ws.messages)>before,{'messages':len(ws.messages),'errors':ws.errors});ws.close()
save('redis-fault.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'firstBrowser':first,'unresponsiveBrowser':hung,'faultWriteRequests':len(responses),'latencyMs':{'max':max(durations),'p95':sorted(durations)[int(len(durations)*.95)-1]},'normalRedisTouched':False,'financialRequests':0,'scope':'本轮故障时延/50路负载与恢复定向复验；原dc五分钟窗口证据保留，未重复宣称本轮五分钟'})
print('redis',sum(x['passed'] for x in checks),'/',len(checks),'first',first,'hung',hung,'maxMs',max(durations))
assert all(x['passed'] for x in checks)
