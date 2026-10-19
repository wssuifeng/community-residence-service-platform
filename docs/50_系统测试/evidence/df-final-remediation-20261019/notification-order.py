from support import *
from playwright.sync_api import sync_playwright
s=load_sessions();auth=s['resident1'];base=int(sql('SELECT MAX(seq) FROM notification'));a=base+1000;b=a+1;marker='df提交乱序'+str(int(time.time()));checks=[]
query="START TRANSACTION;INSERT INTO notification(seq,user_id,owner_type,community_id,title,content,type,channels,is_read) VALUES(%d,1,'RESIDENT',1,'%s-A','较早分配、稍晚提交','SYSTEM','WEBSOCKET',0);SELECT 'READY';DO SLEEP(8);COMMIT;"%(a,marker)
proc=subprocess.Popen([MYSQL,'-h127.0.0.1','-u'+ENV.get('DB_USERNAME','root'),'-D'+DB,'-N','-B','--unbuffered','-e',query],env={**ENV,'MYSQL_PWD':ENV['DB_PASSWORD']},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
assert proc.stdout.readline().strip()=='READY'
sql("INSERT INTO notification(seq,user_id,owner_type,community_id,title,content,type,channels,is_read) VALUES(%d,1,'RESIDENT',1,'%s-B','较晚分配、先提交','SYSTEM','WEBSOCKET',0)"%(b,marker))
try:
 with sync_playwright() as pw:
  browser=pw.chromium.launch(channel='msedge',headless=True);context=browser.new_context();session={'token':auth['token'],'user':auth['user'],'expiresAt':int(time.time()*1000)+auth['expiresIn']*1000};context.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')')
  page=context.new_page();page.goto(WEB+'/resident/home');page.wait_for_load_state('networkidle')
  first=page.evaluate("async ({a,b}) => { const {useNotificationStore}=await import('/src/common/messaging/store/notification.ts'); window.dfStore=useNotificationStore(); window.dfStore.cleanup(); await window.dfStore.refreshBaseline(); return {cursor:window.dfStore.lastSeq,hasA:window.dfStore.unread.some(n=>Number(n.seq)===a),hasB:window.dfStore.unread.some(n=>Number(n.seq)===b)} }",{'a':a,'b':b})
  proc.communicate(timeout=12);assert proc.returncode==0
  second=page.evaluate("async ({a,b}) => { const api=await import('/src/common/messaging/api/notification.ts'); const delta=await api.pullNotifications(b); await window.dfStore.refreshBaseline(); return {strictDelta:delta.notifications.length,cursor:window.dfStore.lastSeq,hasA:window.dfStore.unread.some(n=>Number(n.seq)===a),hasB:window.dfStore.unread.some(n=>Number(n.seq)===b)} }",{'a':a,'b':b})
  checks=[{'name':'真实数据库先提交大序号','passed':first['cursor']==b and first['hasB'] and not first['hasA'],'actual':first},{'name':'游标前移后较小序号由完整未读快照恢复','passed':second['strictDelta']==0 and second['cursor']==b and second['hasA'] and second['hasB'],'actual':second}]
  context.close();browser.close()
finally:
 proc.communicate(timeout=12);sql("DELETE FROM notification WHERE title IN ('"+marker+"-A','"+marker+"-B')")
save('notification-order.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'financialRequests':0,'scope':'真实MySQL事务交错＋当前前端store/API；严格seq增量仍可能越过晚提交小序号，完整未读快照可恢复，未声称仅靠增量可保证提交顺序。'})
print('notification-order',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps(checks,ensure_ascii=False));assert all(x['passed'] for x in checks)
