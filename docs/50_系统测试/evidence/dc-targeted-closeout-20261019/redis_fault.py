from support import *
from ws_client import Stomp
from playwright.sync_api import sync_playwright
from datetime import datetime
import socket
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];checks=[];polls=[];state=json.loads((PRIVATE/'state.json').read_text('utf-8'));started=None
cli='D:/Tools/Memurai_Redis/memurai-cli.exe'
def check(name,ok,actual=None):
 checks.append({'name':name,'passed':bool(ok),'actual':actual});save('redis-fault-progress.json',{'checks':checks,'outageStarted':started,'polls':polls})
def redis(*args):
 p=subprocess.run([cli,'-h','127.0.0.1','-p','6387',*args],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW);assert p.returncode==0;return p.stdout.strip()
unique_title='dcRedis降级-'+datetime.now().strftime('%H%M%S');fid=api('/feedbacks',rt,'POST',{'communityId':1,'title':unique_title,'content':'仅隔离降级验证','category':'INQUIRY'})['id']
log_offset=len((PRIVATE/'backend-final-out.log').read_text('utf-8',errors='replace'))
logout=login('dc_pool_199','Resident123456','resident')['token']
check('故障前专用Redis正常',redis('PING')=='PONG')
ws=Stomp(rt);check('故障前WS认证订阅正常',ws.connected);ws.close()
redis('SAVE')
pid=state['redisPid'];cmd=subprocess.check_output(['powershell','-NoProfile','-Command',f'(Get-CimInstance Win32_Process -Filter "ProcessId={pid}").CommandLine'],text=True).strip();assert '--port 6387' in cmd,'拒绝停止非本批Redis'
try:
 with sync_playwright() as pw:
  browser=pw.chromium.launch(channel='msedge',headless=True);context=browser.new_context(viewport={'width':1440,'height':900})
  auth=s['resident1'];session={'token':rt,'user':auth['user'],'expiresAt':int(time.time()*1000)+auth['expiresIn']*1000};context.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')')
  context.route('**/ws/**',lambda route:route.abort())
  page=context.new_page();page.on('request',lambda req:polls.append(time.time()) if '/notifications/unread' in req.url else None)
  page.goto(WEB+'/resident/home');page.get_by_role('button',name='通知',exact=True).wait_for()
  subprocess.run(['powershell','-NoProfile','-Command',f'Stop-Process -Id {pid} -ErrorAction Stop'],check=True,capture_output=True)
  started=time.time();check('仅专用6387Redis故障注入',True,{'stoppedPid':pid,'normal6379Untouched':True})
  for path in ['/notices','/housings']:
   response=request(path,rt,timeout=40);check('Redis不可用核心浏览'+path,response.status_code==200 and response.json().get('code')==200,{'http':response.status_code,'code':response.json().get('code')})
  marker='dc-Redis故障轮询-'+datetime.now().strftime('%H%M%S');t=time.time();response=request('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':marker},timeout=60);check('Redis故障通知业务仍提交',response.status_code==200 and response.json().get('code')==200,{'http':response.status_code,'seconds':round(time.time()-t,3)})
  nid=int(sql("SELECT MAX(id) FROM notification WHERE owner_type='RESIDENT' AND user_id=1"));outage_seq=int(sql('SELECT seq FROM notification WHERE id='+str(nid)))
  response=request('/auth/resident/logout',logout,'POST',{},timeout=60);check('Redis故障登出仍成功',response.status_code==200 and response.json().get('code')==200,{'http':response.status_code,'code':response.json().get('code')})
  response=request('/notifications',logout,timeout=40);check('Redis故障已登出令牌DB拒绝',response.status_code==401,{'http':response.status_code,'code':response.json().get('code')})
  while time.time()-started<305:page.wait_for_timeout(5000);save('redis-fault-progress.json',{'checks':checks,'observedSeconds':round(time.time()-started),'polls':polls})
  intervals=[round(b-a,2) for a,b in zip(polls,polls[1:]) if a>=started];check('故障窗口至少5分钟',time.time()-started>=300,{'seconds':round(time.time()-started,2)})
  check('真实前端约30秒周期轮询',sum(25<=x<=40 for x in intervals)>=7 and max(intervals,default=999)<=40,{'intervalSeconds':intervals,'count':len(polls)})
  data=api('/notifications/unread',rt,timeout=40);check('轮询可读故障期间新通知',any(x['id']==nid for x in data),{'notificationId':nid,'seq':outage_seq})
  page.get_by_role('button',name='通知',exact=True).click();panel=page.get_by_role('region',name='未读通知列表');panel.wait_for();check('真实界面显示故障期间通知',panel.get_by_text('您的反馈「'+unique_title+'」有新回复',exact=True).count()>0,{'visibleItems':panel.locator('.bell-item').count()});page.screenshot(path=str(OUT/'redis-polling.png'))
  context.close();browser.close()
finally:
 # Restore only the dedicated process, from its own SAVE snapshot.
 with (PRIVATE/'redis-restored-out.log').open('ab') as out,(PRIVATE/'redis-restored-err.log').open('ab') as err:
  proc=subprocess.Popen(['D:/Tools/Memurai_Redis/memurai.exe','--port','6387','--bind','127.0.0.1','--save','','--appendonly','no','--dir',str(PRIVATE),'--logfile',str(PRIVATE/'redis-restored.log')],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
 state['redisPid']=proc.pid;(PRIVATE/'state.json').write_text(json.dumps(state,indent=2),'utf-8')
 for _ in range(30):
  try:
   if redis('PING')=='PONG':break
  except Exception:pass
  time.sleep(.5)
check('专用Redis恢复正常',redis('PING')=='PONG')
for path in ['/notices','/housings']:check('恢复后浏览'+path,isinstance(api(path,rt),dict))
ws=Stomp(rt);before=len(ws.messages);t=time.time();api('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':'dc-Redis恢复后的新通知'});time.sleep(1)
newseq=int(sql("SELECT seq FROM notification WHERE owner_type='RESIDENT' AND user_id=1 ORDER BY id DESC LIMIT 1"))
check('恢复后WS重新注册且通知送达',len(ws.messages)>before,{'messages':len(ws.messages),'errors':ws.errors})
if 'outage_seq' in globals():
 pulled=api('/notifications/pull?sinceSeq='+str(outage_seq),rt);check('恢复后通知游标继续递增且补拉可达',bool(pulled),{'outageSeq':outage_seq,'maxSeq':newseq,'pulledCount':len(pulled)})
ws.close()
log=(PRIVATE/'backend-final-out.log').read_text('utf-8',errors='replace')[log_offset:];check('Redis故障有明确降级告警','降级查 DB 黑名单' in log and 'Redis' in log,{'fallbackWarnings':log.count('Redis 不可用')})
check('故障窗口无认证过滤器异常雪崩','Servlet.service()' not in log,{'unhandledServletErrors':log.count('Servlet.service()')})
save('redis-fault.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'pollTimestamps':polls,'normalRedisTouched':False,'faultSeconds':round(time.time()-started,2) if started else None,'originalUserStopMethodAdapted':'用户本轮授权AI收尾；在独立6387实例实施同等停机/恢复，正常6379保持'})
print('redis',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
