"""仅针对本轮专用隔离库/8087/5181；不访问真实支付，不接管用户浏览器。"""
import json, os, subprocess, time, urllib.request, urllib.error
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from playwright.sync_api import sync_playwright
ROOT=Path.cwd(); OUT=ROOT/'docs/50_系统测试/evidence/cf-notification-home-workorder-20261016'
BASE='http://127.0.0.1:8087/api/v1'; WEB='http://127.0.0.1:5181'; DB='community_residence_cf_20261016'; checks=[]
def check(name,value):
    assert value,name
    checks.append({'name':name,'passed':True})
def call(path,token=None,method='GET',body=None,deny=False):
    assert path.startswith('/') and not any(x in path for x in ['payments','refund','/payment/']), 'No trading endpoints'
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path,headers=headers,method=method,data=None if body is None else json.dumps(body,ensure_ascii=False).encode())
    try:
        with urllib.request.urlopen(req,timeout=15) as res: data=json.load(res)
    except urllib.error.HTTPError as e:
        if deny and e.code in [400,403]:return True
        raise RuntimeError(path+' HTTP '+str(e.code)) from None
    if deny:return data['code']!=200
    assert data['code']==200,(path,data.get('code'),data.get('message'))
    return data['data']
for _ in range(90):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8087/v3/api-docs',timeout=3) as response: spec=json.load(response)
        break
    except Exception:time.sleep(1)
else: raise RuntimeError('isolated backend not ready')
check('工单补群端点已发布','/api/v1/work-orders/{id}/conversation' in spec['paths'])
sessions={}
for name,password,kind in [('superadmin','Admin@123456','admin'),('admin1','Admin123456','admin'),('staff1','Staff123456','admin'),('resident1','Resident123456','resident')]:
    sessions[name]=call('/auth/'+kind+'/login',method='POST',body={'username':name,'password':password})
sa=sessions['superadmin']['token'];res=sessions['resident1']['token'];staff=sessions['staff1']['token'];adm=sessions['admin1']['token']
category=call('/communities/1/service-categories',res)[0]['id']
def order():return call('/work-orders',res,'POST',{'categoryId':category,'title':'cf-隔离回归','content':'验证直接派单建群，不含真实业务','contactPhone':'13800000001','address':'隔离测试地址'})
created=order();wid=created['id']
check('未受理不允许提前补群',call(f'/work-orders/{wid}/conversation',sa,'POST',deny=True))
call(f'/work-orders/{wid}/assign',sa,'PATCH',{'assigneeId':sessions['staff1']['user']['id']})
detail=call(f'/work-orders/{wid}',sa);cid=detail['conversationId']
check('直接派单即有群，不再仅join不存在的群',bool(cid) and detail['status']=='ASSIGNED')
for name,token in [('居民',res),('派单管理员',sa),('服务人员',staff)]:check(name+'可读同一个工单群',call(f'/conversations/{cid}',token)['id']==cid)
check('未参与管理员不能直接读他人群',call(f'/conversations/{cid}',adm,deny=True))
for name,token in [('居民',res),('服务人员',staff),('管理方',sa)]:
    call(f'/conversations/{cid}/messages',token,'POST',{'content':name+'-cf回归消息'})
check('三端共用消息历史',len(call(f'/conversations/{cid}/messages',res))>=3)
check('会话中心工单筛选可见',any(x['id']==cid for x in call('/conversations?type=WORK_ORDER_GROUP&page=1&size=50',sa)['records']))
check('居民不可调用管理端恢复入口',call(f'/work-orders/{wid}/conversation',res,'POST',deny=True))
check('重复进入复用同一群',call(f'/work-orders/{wid}/conversation',sa,'POST')['conversationId']==cid and call(f'/work-orders/{wid}/conversation',sa,'POST')['conversationId']==cid)
# Only the isolated database may be used for reconstructing the historical missing-group shape.
env=os.environ.copy()
for line in (ROOT/'.env').read_text('utf-8-sig').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        k,v=line.split('=',1)
        if k.strip() in ['DB_PASSWORD','DB_USERNAME']:env[k.strip()]=v.strip().strip('"').strip("'")
def sql(q):
    assert DB=='community_residence_cf_20261016'
    p=subprocess.run(['C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe','-h127.0.0.1','-u'+env.get('DB_USERNAME','root'),'-D'+DB,'-N','-B','--default-character-set=utf8mb4','-e',q],env={**env,'MYSQL_PWD':env['DB_PASSWORD']},capture_output=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
    assert p.returncode==0,'isolated fixture failed; details withheld'
    return p.stdout.strip()
legacy=order()['id'];uid=sessions['superadmin']['user']['id'];staffid=sessions['staff1']['user']['id']
sql(f"UPDATE work_order SET status='ASSIGNED' WHERE id={legacy}; INSERT INTO work_order_assignment(work_order_id,assignee_id,assigner_id,assign_time) VALUES({legacy},{staffid},{uid},NOW())")
repaired=call(f'/work-orders/{legacy}/conversation',sa,'POST');repaircid=repaired['conversationId']
check('历史已派单漏群恢复且不重置工单',bool(repaircid) and repaired['status']=='ASSIGNED')
check('恢复群含居民及当前处理人',call(f'/conversations/{repaircid}',res)['id']==repaircid and call(f'/conversations/{repaircid}',staff)['id']==repaircid)
sql(f"UPDATE work_order SET status='CLOSED' WHERE id={legacy}")
closed=call(f'/work-orders/{legacy}/conversation',sa,'POST')
check('关闭工单群只读不复活',closed['status']=='CLOSED' and call(f'/conversations/{repaircid}',res)['status']=='CLOSED')
check('关闭群不能继续发消息',call(f'/conversations/{repaircid}/messages',res,'POST',{'content':'不能写入'},deny=True))
# Add 27 unread and one already-read notification only to the isolated resident namespace.
rid=sessions['resident1']['user']['id']
seq_base=int(sql("SELECT COALESCE(MAX(seq),0) FROM notification"))+100
for i in range(1,29):
    sql("INSERT INTO notification(seq,user_id,owner_type,title,content,type,source_type,source_id,channels,is_read) VALUES("+str(seq_base+i)+f",{rid},'RESIDENT','cf-通知{i}','cf内容{i}','SYSTEM','SYSTEM',NULL,'WEBSOCKET',"+str(1 if i==28 else 0)+")")
read_title='cf-通知28'
# Exercise real long guest-visible content through the production API in the isolated database.
now=datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None).isoformat(timespec='seconds')
long_title='cf-公告长标题'+('社区便民服务' * 24)
notice=call('/notices',sa,'POST',{'title':long_title,'content':'隔离回归公告正文','publishTime':now,'targets':[{'targetType':'GUEST'}]})
call(f"/notices/{notice['id']}/publish",sa,'PATCH',{'publishTime':now})
# Browser sessions are independent and headless. No external sites or user windows are touched.
with sync_playwright() as pw:
    browser=pw.chromium.launch(headless=True)
    contexts=[]
    def page_for(name=None,width=1366):
        context=browser.new_context(viewport={'width':width,'height':900});contexts.append(context)
        context.route('**/*',lambda route: route.continue_() if route.request.url.startswith(('http://127.0.0.1:5181/','http://127.0.0.1:8087/','blob:','data:')) else route.abort())
        if name:
            login=sessions[name];session={'token':login['token'],'user':login['user'],'expiresAt':int(time.time()*1000)+login['expiresIn']*1000}
            context.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')')
        return context.new_page()
    public=page_for()
    for width in [1920,1366,1024,768,360]:
        public.set_viewport_size({'width':width,'height':800});public.goto(WEB+'/guest/home');public.wait_for_load_state('networkidle');public.add_style_tag(content='html{scrollbar-gutter:stable}')
        rect=public.evaluate("() => ({body:document.body.clientWidth,scroll:document.documentElement.scrollWidth,hero:document.querySelector('.hero').getBoundingClientRect().toJSON()})")
        check('首页'+str(width)+'真实长公告已渲染',public.get_by_text(long_title,exact=True).count()>0)
        check('首页'+str(width)+'含滚动条无整页横溢',rect['scroll']<=rect['body']+1 and rect['hero']['x']>=-1 and rect['hero']['right']<=rect['body']+1)
        if width in [1366,360]:public.screenshot(path=str(OUT/f'home-{width}.png'),full_page=True)
    resident=page_for('resident1');resident.goto(WEB+f'/resident/work-orders/{wid}');resident.wait_for_load_state('networkidle')
    check('居民工单详情顶部沟通入口',resident.get_by_role('link',name='进入工单沟通').count()==1)
    resident.get_by_role('button',name='通知',exact=True).click()
    panel=resident.get_by_role('region',name='未读通知列表');panel.wait_for(state='visible');resident.wait_for_timeout(300)
    expected=len(call('/notifications/unread',res))
    check('铃铛完整未读数量与服务端一致',panel.locator('.bell-item').count()==expected and expected>20)
    check('已读消息不在铃铛',panel.get_by_text(read_title,exact=True).count()==0)
    metrics=panel.evaluate('(n)=>({client:n.clientHeight,scroll:n.scrollHeight})');check('铃铛真实可滚动',metrics['scroll']>metrics['client'])
    panel.evaluate('(n)=>n.scrollTop=n.scrollHeight');check('最后一条未读可到达',panel.locator('.bell-item').last.is_visible())
    resident.screenshot(path=str(OUT/'bell-unread-scroll.png'))
    before=panel.locator('.bell-item').count();panel.locator('.bell-item').last.click();resident.wait_for_timeout(250)
    resident.get_by_role('button',name='通知',exact=True).click();panel.wait_for(state='visible');resident.wait_for_timeout(250)
    check('阅读后该通知移出且数量减一',panel.locator('.bell-item').count()==before-1)
    admin=page_for('superadmin');admin.goto(WEB+'/admin/notices');admin.wait_for_load_state('networkidle')
    admin.get_by_role('button',name='发布公告',exact=True).click();admin.wait_for_timeout(350)
    check('公告不再有ALL单值定向范围下拉',admin.get_by_text('定向范围',exact=True).count()==0)
    check('真正发布范围与定向目标仍保留',admin.get_by_text('发布范围',exact=True).count()>0 and admin.get_by_text('定向目标',exact=True).count()>0)
    admin.screenshot(path=str(OUT/'notice-scope.png'))
    admin.goto(WEB+f'/admin/work-orders/{wid}');admin.wait_for_load_state('networkidle');admin.get_by_role('button',name='进入工单沟通',exact=True).click();admin.wait_for_timeout(350)
    check('管理端入口可达实际共用群聊',admin.locator('#work-order-chat').count()==1)
    admin.goto(WEB+'/admin/conversations');admin.wait_for_load_state('networkidle')
    admin.locator('.el-radio-group').get_by_text('工单群聊',exact=True).click();admin.wait_for_timeout(300)
    check('工单筛选实际选中而非回落全部',admin.locator('input[type=radio][value=WORK_ORDER_GROUP]').is_checked())
    check('工单筛选能显示该会话',admin.get_by_text('工单沟通·'+detail['orderNo'],exact=True).count()>0)
    admin.screenshot(path=str(OUT/'workorder-conversation-center.png'))
    staffpage=page_for('staff1');staffpage.goto(WEB+f'/staff/work-orders/{wid}');staffpage.wait_for_load_state('networkidle');check('服务人员工单顶部群聊入口',staffpage.get_by_role('link',name='进入工单沟通').count()==1)
    for page,name in [(staffpage,'服务人员'),(resident,'居民')]:
        if name=='居民': page.goto(WEB+f'/resident/work-orders/{wid}');page.wait_for_load_state('networkidle')
        check(name+'群聊定位目标唯一',page.locator('#work-order-chat').count()==1)
        page.get_by_role('link',name='进入工单沟通').click();page.wait_for_timeout(500)
        check(name+'入口滚动到真实群聊',page.locator('#work-order-chat').evaluate('(n)=>n.getBoundingClientRect().top')<900)
    for context in contexts:context.close()
    browser.close()
summary={'checkedAt':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'),'environment':{'database':DB,'backend':8087,'frontend':5181},'checks':checks,'passed':len(checks),'failed':0,'externalTradingCalled':False,'userBrowserOperated':False,'businessData':'all synthetic in dedicated database; normal backend was not called'}
(OUT/'regression-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':len(checks),'failed':0},ensure_ascii=False))
