from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from datetime import date,timedelta
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];checks=[]
def check(name,ok,actual):checks.append({'name':name,'passed':bool(ok),'actual':actual});save('remaining-anomalies-progress.json',{'checks':checks})
def burst(calls):
 gate=Barrier(len(calls))
 def one(item):
  path,token,method,body=item;gate.wait();r=request(path,token,method,body);d=r.json();return {'http':r.status_code,'code':d.get('code'),'id':(d.get('data') or {}).get('id') if isinstance(d.get('data'),dict) else None,'message':d.get('message')}
 with ThreadPoolExecutor(max_workers=len(calls)) as ex:return list(ex.map(one,calls))
def house(n):return int(sql("INSERT INTO house(unit_id,community_id,house_number,floor,area,status) VALUES(1,1,'DC-R-"+str(n)+"-"+str(int(time.time())%100000)+"',1,88,'VACANT');SELECT LAST_INSERT_ID()"))
# Same-target lease state race, without activation or payment.
h=house(115);lid=int(sql("INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status) VALUES(1,1,"+str(h)+",CURDATE(),DATE_ADD(CURDATE(),INTERVAL 30 DAY),1000,0,'PENDING');SELECT LAST_INSERT_ID()"))
r=burst([('/leases/'+str(lid)+'/status',sa,'PATCH',{'status':'TO_SIGN'})]*10);logs=int(sql('SELECT COUNT(*) FROM lease_change_log WHERE lease_id='+str(lid)))
check('DEF-115同目标并发状态迁移幂等且只一条留痕',all(x['code']==200 for x in r) and logs==1,{'responses':r,'logs':logs})
# Intake side effects exactly once; invalid subsequent transition explicitly rejects.
o=api('/work-orders',rt,'POST',{'categoryId':1,'title':'dc受理并发','content':'隔离状态机验证','contactPhone':'13800000001'})['id']
r=burst([('/work-orders/'+str(o)+'/intake',sa,'PATCH',{})]*10);side={t:int(sql(q)) for t,q in {'groups':"SELECT COUNT(*) FROM conversation WHERE type='WORK_ORDER_GROUP' AND related_id="+str(o),'process':"SELECT COUNT(*) FROM work_order_process WHERE action='INTAKE' AND work_order_id="+str(o),'residentNotifications':"SELECT COUNT(*) FROM notification WHERE source_type='WORK_ORDER' AND source_id="+str(o)+" AND owner_type='RESIDENT' AND title='工单已受理'"}.items()}
check('DEF-116受理并发只产生一次群留痕通知',all(x['http']<500 and (x['code']==200 or (x['code']==5302 and '从 TO_ASSIGN 流转到 TO_ASSIGN' in x['message'])) for x in r) and any(x['code']==200 for x in r) and all(v==1 for v in side.values()),{'responses':r,**side})
r=request('/work-orders/'+str(o)+'/close',sa,'PATCH',{'remark':'不合法状态'});d=r.json();check('DEF-116错误状态或无派单权限被拒',d.get('code')==5302,{'http':r.status_code,'code':d.get('code')})
# Race withdrawal against approval; synthetic pending applications never reach payment.
for i in range(3):
 h=house(117+i);aid=int(sql("INSERT INTO residence_application(resident_id,community_id,house_id,relation_type,status,remark) VALUES(1,1,"+str(h)+",'TENANT','PENDING','dc撤回审批竞争');SELECT LAST_INSERT_ID()"))
 calls=[('/residence-applications/'+str(aid)+'/withdraw',rt,'PATCH',None),('/residence-applications/'+str(aid)+'/approve',sa,'PATCH',{'leaseStartDate':str(date.today()),'leaseEndDate':str(date.today()+timedelta(days=30)),'monthlyRent':1000,'deposit':0})]
 r=burst(calls);row=sql('SELECT status,COALESCE(lease_id,0) FROM residence_application WHERE id='+str(aid));state,leaseid=row.split('\t');leases=int(sql('SELECT COUNT(*) FROM lease_record WHERE house_id='+str(h)))
 check('DEF-117撤回审批竞争第'+str(i+1)+'轮',sum(x['code']==200 for x in r)==1 and state in ('WITHDRAWN','APPROVED') and leases==(1 if state=='APPROVED' else 0) and all(x['http']<500 for x in r),{'responses':r,'applicationState':state,'leaseRows':leases})
# Source-housing guard and date ordering, in a synthetic historical lease only.
lid=int(sql("INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status) VALUES(1,1,1,CURDATE(),DATE_ADD(CURDATE(),INTERVAL 30 DAY),1000,0,'TO_SIGN');SELECT LAST_INSERT_ID()"))
cid=api('/conversations/lease-groups',sa,'POST',{'leaseId':lid})['id'];before=int(sql('SELECT COUNT(*) FROM viewing_appointment'))
r=request('/conversations/'+str(cid)+'/viewing-appointments',rt,'POST',{'housingId':999999,'appointmentDate':str(date.today()+timedelta(days=1)),'startTime':'15:00:00','endTime':'16:00:00'});d=r.json()
check('DEF-118会话内无关房源被拒且零落库',d.get('code')==400 and int(sql('SELECT COUNT(*) FROM viewing_appointment'))==before,{'http':r.status_code,'code':d.get('code'),'message':d.get('message')})
for days in [3,1,2]:
 sql("INSERT INTO viewing_appointment(user_id,lease_id,housing_id,community_id,appointment_date,start_time,end_time,status,contact_phone) VALUES(1,"+str(lid)+",1,1,DATE_ADD(CURDATE(),INTERVAL "+str(days)+" DAY),'19:00:00','20:00:00','CANCELLED','13800000001')")
rows=api('/leases/'+str(lid)+'/viewings',rt);dates=[r['appointmentDate'] for r in rows]
check('DEF-118租约看房列表按日期降序',len(dates)==3 and dates==sorted(dates,reverse=True),{'dates':dates})
save('remaining-anomalies.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'financialRequests':0,'scope':'仅非交易状态和幂等，不创建支付单'})
print('remaining',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
