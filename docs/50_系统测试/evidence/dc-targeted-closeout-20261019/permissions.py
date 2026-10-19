from support import *
from ws_client import Stomp
from datetime import datetime
s=load_sessions();f=json.loads((OUT/'fixtures.json').read_text('utf-8'));checks=[]
def check(name,ok,actual):checks.append({'name':name,'passed':bool(ok),'actual':actual})
def test(name,who,method,path,body=None,allow=False):
 r=request(path,s[who]['token'] if who else None,method,body);d=r.json();ok=r.status_code==200 and d.get('code')==200 if allow else r.status_code in (401,403,404) or d.get('code') in (401,403,404,4003)
 check(name,ok,{'http':r.status_code,'code':d.get('code'),'message':d.get('message')});return d.get('data')
notice={'title':'dc越权不应落库','content':'dc权限负例','publishTime':datetime.now().isoformat(timespec='seconds'),'targets':[{'targetType':'COMMUNITY','targetId':2}]}
mutations=[('POST','/communities',{'name':'dc不允许新建','address':'权限负例'}),('PATCH','/communities/1/status',{'status':'INACTIVE'}),('DELETE','/communities/1',None),('PUT','/configs/registration.enabled',{'value':'false'}),('POST','/notices',notice),('POST','/sys-users',{'username':'dc_forbidden','password':'Admin123456','realName':'权限负例','phone':'13879998888','role':'ADMIN'}),('PATCH','/sys-users/2/status',{'status':'FROZEN'}),('POST','/sys-users/2/communities',{'communityId':2}),('DELETE','/sys-users/2/communities/1',None)]
before={t:sql('SELECT COUNT(*) FROM '+t) for t in ['community','sys_user','sys_admin_community','notice']};before['communityStatus']=sql('SELECT status FROM community WHERE id=1');before['userStatus']=sql('SELECT status FROM sys_user WHERE id=2')
for role in ['admin1','resident1','staff1']:
 for i,(m,p,b) in enumerate(mutations):test('SP-005/007/008 '+role+'管理否定'+str(i+1),role,m,p,b)
after={t:sql('SELECT COUNT(*) FROM '+t) for t in ['community','sys_user','sys_admin_community','notice']};after['communityStatus']=sql('SELECT status FROM community WHERE id=1');after['userStatus']=sql('SELECT status FROM sys_user WHERE id=2')
check('越权写入前后数据库对象与状态不变',before==after,{'before':before,'after':after})
for i,p in enumerate(['/work-orders','/feedbacks','/resource-reservations','/leases','/notifications','/statistics/dashboard','/operation-logs','/sys-users','/configs','/residence-applications','/leases/my-summaries','/staff/workbench/summary','/conversations','/conversations/inbox']):test('SP-006游客私有'+str(i+1),None,'GET',p)
for p in ['/communities','/notices','/housings','/housings/1','/units/1/houses']:test('SP-006游客公开正向'+p,None,'GET',p,allow=True)
for m,p,b in [('POST','/work-orders',{'categoryId':1,'title':'dc越权','content':'权限负例','contactPhone':'13800000001'}),('POST','/feedbacks',{'communityId':1,'title':'dc越权','content':'权限负例','category':'INQUIRY'}),('POST','/resource-reservations',{'resourceId':1,'reserveDate':f['date'],'startTime':'10:00:00','endTime':'11:00:00'}),('POST','/viewing-appointments',{'housingId':1,'appointmentDate':f['date'],'startTime':'10:00:00','endTime':'11:00:00'}),('GET','/leases',None),('GET','/residence-applications',None),('POST','/work-orders/1/evaluation',{'rating':5,'isSatisfied':True})]:test('SP-008服务人员居民域'+p,'staff1',m,p,b)
for role in ['resident1','staff1']:
 for p in ['/sys-users','/configs','/statistics/dashboard','/operation-logs']:test('SP-007/008 '+role+'后台读取'+p,role,'GET',p)
for role,paths in [('resident2',['/work-orders/1','/work-orders/1/attachments','/feedbacks/1','/feedbacks/1/messages','/feedbacks/1/attachments']),('staff1',['/work-orders/2','/work-orders/2/attachments','/feedbacks/1','/feedbacks/1/messages']),('admin2',['/work-orders/1','/work-orders/1/attachments','/feedbacks/1','/feedbacks/1/messages','/feedbacks/1/attachments','/residents/1'])]:
 for p in paths:test('SP-007/009/010 '+role+'他人或跨社区'+p,role,'GET',p)
for role in ['admin1','admin2']:
 for p in ['/work-orders','/residents','/resource-reservations','/leases','/feedbacks']:
  data=test('SP-009 '+role+'列表可访问'+p,role,'GET',p,allow=True)
  if isinstance(data,dict):
   records=data.get('records',[]);target=1 if role=='admin1' else 2
   check('SP-009 '+role+'列表社区过滤'+p,all(x.get('communityId',target)==target for x in records),{'recordCount':len(records),'communityIds':list({x.get('communityId') for x in records})})
test('SP-009超管全局工单正向','superadmin','GET','/work-orders',allow=True)
test('C10-014/015按30设计管理员允许读取系统用户详情','admin1','GET','/sys-users/3',allow=True)
test('新增服务人员摘要居民不可读','resident1','GET','/staff/workbench/summary')
test('新增本人租约摘要服务人员不可读','staff1','GET','/leases/my-summaries')
test('新增服务人员摘要本人可读','staff1','GET','/staff/workbench/summary',allow=True)
test('新增本人租约摘要居民可读','resident1','GET','/leases/my-summaries',allow=True)
# Valid assigned order gives a positive control for STAFF, rather than relying on empty data.
positive=api('/work-orders',s['resident1']['token'],'POST',{'categoryId':1,'title':'dc权限正向工单','content':'受影响认证回归','contactPhone':'13800000001'})['id']
api('/work-orders/'+str(positive)+'/assign',s['superadmin']['token'],'PATCH',{'assigneeId':s['staff1']['user']['id']})
test('SP-008正向被派单服务人员可读','staff1','GET','/work-orders/'+str(positive),allow=True)
test('SP-008正向被派单服务人员可接单','staff1','PATCH','/work-orders/'+str(positive)+'/accept',{},allow=True)
for role,allowed in [('resident2',False),('staff1',False),('admin2',False),('resident1',True),('admin1',True)]:
 ws=Stomp(s[role]['token'],'/topic/feedback/1');time.sleep(.6)
 check('SP-010反馈WS订阅 '+role,(not ws.errors and ws.connected) if allowed else bool(ws.errors or not ws.connected),{'connected':ws.connected,'errors':ws.errors});ws.close()
# Shared numeric IDs never replace owner_type.
cid=int(sql("SELECT c.id FROM conversation c JOIN conversation_participant p ON c.id=p.conversation_id WHERE c.type='DIRECT' AND p.owner_type='RESIDENT' AND p.user_id=17 LIMIT 1"))
for role in ['resident1','superadmin']:test('身份空间撞号非参与者不可读 '+role,role,'GET','/conversations/'+str(cid))
log=(PRIVATE/'backend-final-out.log').read_text('utf-8',errors='replace');check('SP-005～010越权日志含URI/IP/原因','越权' in log and 'uri=' in log and 'ip=' in log,{'denyEntries':log.count('越权')})
save('permissions.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'scope':'现行角色矩阵、本人/社区/身份空间、附件与反馈WS；公开房源仍按批准R62全社区浏览，不用旧隐身预期判失败'})
print('permissions',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
