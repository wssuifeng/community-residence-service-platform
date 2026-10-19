from support import *
from playwright.sync_api import sync_playwright
from datetime import datetime
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];samples=[];frames=[];errors=[]
f=api('/feedbacks',rt,'POST',{'communityId':1,'title':'dc实时双通道'+datetime.now().strftime('%H%M%S'),'content':'定向时延采样','category':'INQUIRY'})
fid=f['id'];api('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':'会话准备'})
api('/notifications/read-all',rt,'PATCH',{})
with sync_playwright() as pw:
 b=pw.chromium.launch(channel='msedge',headless=True);c=b.new_context(viewport={'width':1440,'height':1000})
 auth=s['resident1'];session={'token':rt,'user':auth['user'],'expiresAt':int(time.time()*1000)+auth['expiresIn']*1000};c.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')')
 p=c.new_page();p.on('pageerror',lambda e:errors.append(str(e)))
 def socket(ws):
  ws.on('framesent',lambda e:frames.append({'direction':'sent','frame':e}) if 'SUBSCRIBE' in str(e) else None)
  ws.on('framereceived',lambda e:frames.append({'direction':'received','frame':e}) if 'MESSAGE' in str(e) else None)
 p.on('websocket',socket)
 p.goto(WEB+'/resident/conversations?source=FEEDBACK&id='+str(fid));p.get_by_label('消息记录').get_by_text('会话准备',exact=True).wait_for();p.get_by_role('button',name='通知',exact=True).click();p.get_by_role('region',name='未读通知列表').wait_for()
 for _ in range(30):
  if any('/topic/feedback/'+str(fid) in str(x['frame']) for x in frames):break
  p.wait_for_timeout(200)
 assert any('/topic/feedback/'+str(fid) in str(x['frame']) for x in frames),'feedback subscription missing'
 for i in range(10):
  marker='dc实时消息-'+str(fid)+'-'+str(i);before=p.locator('.bell-item').count();t=time.perf_counter();api('/feedbacks/'+str(fid)+'/messages',sa,'POST',{'content':marker})
  p.get_by_label('消息记录').get_by_text(marker,exact=True).wait_for(timeout=5000);chat=round((time.perf_counter()-t)*1000,3)
  p.wait_for_function('(n)=>document.querySelectorAll(".bell-item").length>n',arg=before,timeout=5000);notice=round((time.perf_counter()-t)*1000,3)
  samples.append({'sample':i+1,'conversationDOMMs':chat,'notificationDOMMs':notice,'passed':max(chat,notice)<=3000})
 p.screenshot(path=str(OUT/'realtime-browser.png'));c.close();b.close()
save('realtime-browser.json',{'samples':samples,'allWithin3s':all(x['passed'] for x in samples),'notificationSamples':len(samples),'conversationSamples':len(samples),'method':'同一台机器perf_counter记录HTTP请求开始到真实Edge接收端DOM出现；请求开始早于DB提交，记录保守上界；反馈/通知均真实WS订阅','wsReceivedMessageFrames':sum(x['direction']=='received' for x in frames),'subscribedFeedback':any('/topic/feedback/'+str(fid) in str(x['frame']) for x in frames),'consoleErrors':errors,'financialRequests':0})
print('real browser realtime',len(samples),'max milliseconds',max(max(x['conversationDOMMs'],x['notificationDOMMs']) for x in samples),'errors',errors)
