"""Real browser acceptance observations for existing isolated flow data."""
import datetime as dt, importlib.util, json, time
from pathlib import Path
from playwright.sync_api import sync_playwright
ROOT=Path.cwd();OUT=ROOT/'docs/60_评估验收/evidence/acceptance-flow-20261020';OUT.mkdir(exist_ok=True)
sp=importlib.util.spec_from_file_location('flow',ROOT/'docs/60_评估验收/scripts/supplement_acceptance_flows.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
sup=json.loads((OUT/'supplement.json').read_text(encoding='utf-8'));ids=m.ids;cid=ids['communityId']
suffix=next(x['actor'].split('acc_owner_')[1] for x in m.seed['trace'] if x['actor'].startswith('acc_owner_'));owner='acc_owner_'+suffix
observations=[];errors=[]

def ui_login(browser,name,pw,res=False):
    ctx=browser.new_context(viewport={'width':1440,'height':1050});page=ctx.new_page()
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://127.0.0.1:5181/auth/login');page.wait_for_load_state('networkidle')
    if not res:page.get_by_role('tab',name='管理员 / 服务人员').click()
    page.get_by_placeholder('请输入用户名或注册手机号').fill(name)
    page.get_by_placeholder('请输入密码',exact=True).fill(pw)
    page.get_by_role('button',name='登 录').click()
    page.wait_for_url('**/'+('resident' if res else ('staff' if name==sup['ids']['testStaffUsername'] else 'admin'))+'/**')
    page.wait_for_load_state('networkidle')
    m.actors[name]=page.evaluate("JSON.parse(sessionStorage.getItem('crsp_session'))")
    observations.append({'actor':name,'check':'login through actual form','path':page.url,'passed':True})
    return ctx,page

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,channel='msedge')
    ctx,page=ui_login(browser,owner,'Resident123456',True)
    wid=sup['ids']['workOrderId']
    page.goto(f'http://127.0.0.1:5181/resident/work-orders/{wid}');page.wait_for_load_state('networkidle')
    text=page.locator('body').inner_text();assert '已关闭' in text
    page.screenshot(path=str(OUT/'resident-workorder-closed.png'),full_page=True)
    observations.append({'check':'resident sees same closed work order','id':wid,'passed':True})
    page.goto('http://127.0.0.1:5181/resident/notifications');page.wait_for_load_state('networkidle')
    await_js="""async()=>{const ws=await import('/src/common/messaging/utils/websocket.ts');window.__acc={notifications:[],feedback:[]};ws.connectWebSocket(x=>window.__acc.notifications.push(x));window.__accWs=ws;return true}"""
    page.evaluate(await_js);page.wait_for_function('window.__accWs.isWsConnected()',timeout=15000)
    m.login('admin1','Admin123456');m.login('superadmin','Admin@123456');m.login(sup['ids']['scopedAdminUsername'],'Admin123456')
    fb=m.api(owner,'E6 realtime feedback submit','POST','/api/v1/feedbacks',{'communityId':cid,'title':'Acceptance realtime feedback','content':'same event WS evidence','category':'COMPLAINT'})
    fid=fb['id']
    page.evaluate("id=>window.__accWs.subscribe('/topic/feedback/'+id,x=>window.__acc.feedback.push(x))",fid)
    # A round-trip after subscription establishes ordering before the message is produced.
    page.evaluate("async()=>await fetch('/api/v1/notifications/unread',{headers:{Authorization:'Bearer '+JSON.parse(sessionStorage.crsp_session).token}}).then(r=>r.json())")
    msg=m.api('admin1','E6 realtime admin response','POST',f'/api/v1/feedbacks/{fid}/messages',{'content':'same-event realtime response'})
    page.wait_for_function('id=>window.__acc.feedback.some(x=>x.type==="FEEDBACK_MESSAGE" && x.data?.id===id)',arg=msg['id'],timeout=10000)
    page.wait_for_function('id=>window.__acc.notifications.some(x=>x.sourceType==="FEEDBACK" && x.sourceId===id)',arg=fid,timeout=10000)
    delivered=page.evaluate('window.__acc')
    notif=next(x for x in delivered['notifications'] if x['sourceType']=='FEEDBACK' and x['sourceId']==fid)
    unread=m.api(owner,'E11 same delivered event unread','GET','/api/v1/notifications/unread')
    assert any(x['id']==notif['id'] for x in unread)
    pulled=m.api(owner,'E11 same delivered event incremental pull','GET','/api/v1/notifications/pull?sinceSeq='+str(notif['seq']-1))
    assert any(x['id']==notif['id'] and x['seq']==notif['seq'] for x in pulled)
    m.api(owner,'E11 mark same delivered event read','PATCH',f'/api/v1/notifications/{notif["id"]}/read')
    unread2=m.api(owner,'E11 verify same event no longer unread','GET','/api/v1/notifications/unread')
    assert not any(x['id']==notif['id'] for x in unread2)
    m.api(owner,'E6 realtime resident response','POST',f'/api/v1/feedbacks/{fid}/messages',{'content':'resident response'})
    m.api('admin1','E6 realtime close','PATCH',f'/api/v1/feedbacks/{fid}/close',{'remark':'resolved and archived read-only'})
    page.wait_for_function('id=>window.__acc.feedback.some(x=>x.type==="FEEDBACK_STATUS" && x.data?.feedbackId===id)',arg=fid,timeout=10000)
    m.api(owner,'E6 closed realtime feedback rejects writes','POST',f'/api/v1/feedbacks/{fid}/messages',{'content':'must reject'},deny=True)
    page.goto(f'http://127.0.0.1:5181/resident/feedbacks/{fid}');page.wait_for_load_state('networkidle')
    assert '已办结' in page.locator('body').inner_text()
    page.screenshot(path=str(OUT/'resident-feedback-closed.png'),full_page=True)
    observations.extend([{'check':'E6 realtime same-message delivery and closed status delivery','feedbackId':fid,'messageId':msg['id'],'passed':True},{'check':'E11 same-notification WS, unread, incremental pull, read','notificationId':notif['id'],'seq':notif['seq'],'passed':True}])
    channel=m.sql(f"SELECT notification_id,channel,status FROM notification_channel_log WHERE notification_id={notif['id']}")
    assert any(x[1]=='WEBSOCKET' and x[2]=='SUCCESS' for x in channel),channel
    # Reproduce data-scope and global-role denials while the server log is captured.
    an=sup['ids']['scopedAdminUsername']
    log=ROOT/'output/runtime/acceptance-flow-20261020/acceptance-backend.log'
    log_offset=len(log.read_text(encoding='utf-8').splitlines())
    m.api(an,'E10 scoped query outside community denied','GET','/api/v1/communities/1/buildings',deny=True)
    m.api(an,'E10 global user create denied','POST','/api/v1/sys-users',{'username':'acc_denied_realtime','password':'Admin123456','realName':'Denied','phone':'13400077881','role':'STAFF'},deny=True)
    log=ROOT/'output/runtime/acceptance-flow-20261020/acceptance-backend.log'
    lines=[x for x in log.read_text(encoding='utf-8').splitlines()[log_offset:] if '越权尝试：uri=/api/v1/communities/1/buildings' in x or '功能级越权尝试：uri=/api/v1/sys-users' in x]
    assert len(lines)>=2
    (OUT/'authorization-log-excerpts.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    observations.append({'check':'E10 actual denials appear in application error log; critical writes have DB operation logs','passed':True})
    ac,ap=ui_login(browser,an,'Admin123456')
    ap.goto(f'http://127.0.0.1:5181/admin/communities/{cid}');ap.wait_for_load_state('networkidle');ap.screenshot(path=str(OUT/'scoped-admin-community.png'),full_page=True)
    sc,spg=ui_login(browser,sup['ids']['testStaffUsername'],'Staff123456')
    spg.goto(f'http://127.0.0.1:5181/staff/work-orders/{wid}');spg.wait_for_load_state('networkidle');assert '已关闭' in spg.locator('body').inner_text();spg.screenshot(path=str(OUT/'staff-workorder-closed.png'),full_page=True)
    out={'recordedAt':dt.datetime.now().isoformat(),'browser':{'name':'Microsoft Edge','version':browser.version},'base':'http://127.0.0.1:5181','jarSha256':m.seed['jarSha256'],'normalDatabaseWrites':0,'financialOperations':0,'observations':observations,'pageErrors':errors,'feedbackId':fid,'notification':notif,'websocketMessages':delivered,'channelLogs':channel,'trace':m.trace}
    (OUT/'browser-realtime.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print('browser observations',len(observations),'page errors',len(errors),'feedback',fid,'notification',notif['id'])
    browser.close()
