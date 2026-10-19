from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from datetime import date,timedelta
s=load_sessions();sa=s['superadmin']['token'];pool=json.loads((PRIVATE/'pool.json').read_text('utf-8'));f=json.loads((OUT/'fixtures.json').read_text('utf-8'));checks=[]
def check(name,ok,actual):checks.append({'name':name,'passed':bool(ok),'actual':actual})
def burst(path,tokens,body,method='POST'):
 gate=Barrier(len(tokens))
 def one(token):
  gate.wait();r=request(path,token,method,body);d=r.json();return {'http':r.status_code,'code':d.get('code'),'id':(d.get('data') or {}).get('id') if isinstance(d.get('data'),dict) else None}
 with ThreadPoolExecutor(max_workers=len(tokens)) as ex:return list(ex.map(one,tokens))
tokens=[x['token'] for x in pool[:10]]
for kind,path,table,idfield,datefield in [('resource','/resource-reservations','resource_reservation','resource_id','reserve_date'),('viewing','/viewing-appointments','viewing_appointment','housing_id','appointment_date')]:
 for round in range(3):
  day=str(date.today()+timedelta(days=1+round+(0 if kind=='resource' else 3)))
  body={'startTime':'10:00:00','endTime':'11:00:00','contactPhone':'13800000001'}
  body.update({'resourceId':f['resource'],'reserveDate':day,'purpose':'dc并发'} if kind=='resource' else {'housingId':1,'appointmentDate':day})
  result=burst(path,tokens,body);count=int(sql("SELECT COUNT(*) FROM "+table+" WHERE "+datefield+"='"+day+"' AND "+idfield+"="+str(f['resource'] if kind=='resource' else 1)))
  check(kind+' 10路第'+str(round+1)+'轮',sum(x['code']==200 for x in result)==1 and count==1 and all(x['http']<500 for x in result),{'responses':result,'rows':count})
 day=str(date.today()+timedelta(days=7+(0 if kind=='resource' else 1)))
 base={'resourceId':f['resource'],'reserveDate':day,'purpose':'dc边界'} if kind=='resource' else {'housingId':1,'appointmentDate':day}
 for i,(a,b,expect) in enumerate([('10:00:00','12:00:00',True),('12:00:00','14:00:00',True),('08:00:00','10:00:00',True),('11:00:00','13:00:00',False),('09:00:00','11:00:00',False),('09:00:00','13:00:00',False),('10:00:00','11:00:00',False)]):
  body={**base,'startTime':a,'endTime':b,'contactPhone':'13800000001'};r=request(path,pool[i]['token'],'POST',body);d=r.json();count=int(sql("SELECT COUNT(*) FROM "+table+" WHERE "+datefield+"='"+day+"'"))
  check(kind+'边界'+a+'-'+b,(d.get('code')==200)==expect and r.status_code<500 and count==min(i+1,3),{'http':r.status_code,'code':d.get('code'),'rows':count})
# Actual database uniqueness: all requests join the same direct conversation, not a Mock mapper.
r=burst('/conversations/direct',[pool[16]['token']]*10,{'communityId':1})
ids={x['id'] for x in r};rid=pool[16]['residentId'];count=int(sql("SELECT COUNT(*) FROM conversation WHERE type='DIRECT' AND id IN (SELECT conversation_id FROM conversation_participant WHERE owner_type='RESIDENT' AND user_id="+str(rid)+")"))
check('DEF-213真实10路直通会话防重复',all(x['code']==200 for x in r) and len(ids)==1 and count==1,{'responses':r,'rows':count})
save('concurrency.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'httpRaceRequests':70,'scope':'SP-001～004及DEF-213；当前60分钟看房栅格使用整点有效边界；跨模板非法请求不冒充重叠验证'})
print('concurrency',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
