from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from datetime import date,timedelta
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];pool=json.loads((PRIVATE/'pool.json').read_text('utf-8'));checks=[]
def check(name,ok,actual):
 checks.append({'name':name,'passed':bool(ok),'actual':actual});save('old-anomalies-progress.json',{'checks':checks})
def burst(path,tokens,body,method='POST'):
 gate=Barrier(len(tokens))
 def one(token):
  gate.wait();r=request(path,token,method,body);d=r.json();return {'http':r.status_code,'code':d.get('code'),'data':d.get('data'),'message':d.get('message')}
 with ThreadPoolExecutor(max_workers=len(tokens)) as ex:return list(ex.map(one,tokens))
def lease(status,number,start=-10,end=20):
 hid=int(sql("INSERT INTO house(unit_id,community_id,house_number,floor,area,status) VALUES(1,1,'DC-DEF-"+str(number)+"-"+str(int(time.time())%100000)+"',1,88,'VACANT');SELECT LAST_INSERT_ID()"))
 lid=int(sql("INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status,remark) VALUES(1,1,"+str(hid)+",DATE_ADD(CURDATE(),INTERVAL "+str(start)+" DAY),DATE_ADD(CURDATE(),INTERVAL "+str(end)+" DAY),1000,0,'"+status+"','dc合成非交易边界');SELECT LAST_INSERT_ID()"))
 return lid,hid
# No payment records, channel calls or refund actions. Synthetic ACTIVE history only tests state/relationship logic.
lid,hid=lease('ACTIVE',104)
body={'moveOutDate':str(date.today()+timedelta(days=1)),'reason':'dc非金融并发申请'}
r=burst('/leases/'+str(lid)+'/move-out-applications',[rt]*10,body);rows=int(sql('SELECT COUNT(*) FROM lease_move_out_application WHERE lease_id='+str(lid)))
check('DEF-104十路提交只有一条待审申请',sum(x['code']==200 for x in r)==1 and rows==1,{'responses':r,'rows':rows});aid=int(sql('SELECT id FROM lease_move_out_application WHERE lease_id='+str(lid)+' LIMIT 1'))
r=burst('/lease-move-out-applications/'+str(aid)+'/approve',[sa]*10,{},'PATCH');state=sql('SELECT status FROM lease_record WHERE id='+str(lid));logs=int(sql("SELECT COUNT(*) FROM lease_change_log WHERE lease_id="+str(lid)+" AND change_type='STATUS'"));notify=int(sql("SELECT COUNT(*) FROM notification WHERE source_type='LEASE_MOVE_OUT' AND source_id="+str(lid)+" AND owner_type='RESIDENT'"))
check('DEF-104十路审核副作用仅一次且输家幂等',all(x['code']==200 for x in r) and state=='MOVED_OUT' and logs==1 and notify==1,{'responses':r,'leaseState':state,'statusLogs':logs,'residentNotifications':notify})
lid,hid=lease('TO_SIGN',106)
r=burst('/conversations/lease-groups',[sa]*10,{'leaseId':lid});ids={x['data']['id'] for x in r if x['code']==200};rows=int(sql("SELECT COUNT(*) FROM conversation WHERE type='LEASE_GROUP' AND related_id="+str(lid)))
check('DEF-106租约群十路创建只保留一条',all(x['code']==200 for x in r) and len(ids)==1 and rows==1,{'ids':list(ids),'rows':rows,'responses':r})
cid=next(iter(ids));sql("UPDATE conversation SET status='CLOSED',closed_at=NOW() WHERE id="+str(cid));sql('UPDATE lease_record SET conversation_id=NULL WHERE id='+str(lid));api('/conversations/lease-groups',sa,'POST',{'leaseId':lid})
actual=sql('SELECT l.status,l.conversation_id,c.status FROM lease_record l JOIN conversation c ON c.id=l.conversation_id WHERE l.id='+str(lid))
check('DEF-119补回链不覆盖租约且不重开关闭群',actual==f'TO_SIGN\t{cid}\tCLOSED',{'row':actual})
before=int(sql('SELECT COUNT(*) FROM viewing_appointment'));r=request('/conversations/'+str(cid)+'/viewing-appointments',rt,'POST',{'appointmentDate':str(date.today()+timedelta(days=1)),'startTime':'15:00:00','endTime':'16:00:00'});d=r.json()
check('DEF-110关闭租约群禁止发起看房且零落库',d.get('code')==5604 and int(sql('SELECT COUNT(*) FROM viewing_appointment'))==before,{'http':r.status_code,'code':d.get('code')})
# DTO accepts TO_SIGN; current FIRST activation boundary rejects manual ACTIVE.
lid,hid=lease('PENDING',108);r=request('/leases/'+str(lid)+'/status',sa,'PATCH',{'status':'TO_SIGN'});d=r.json();check('DEF-108待审核可达待签约',d.get('code')==200 and sql('SELECT status FROM lease_record WHERE id='+str(lid))=='TO_SIGN',{'http':r.status_code,'code':d.get('code')})
r=request('/leases/'+str(lid)+'/status',sa,'PATCH',{'status':'ACTIVE'});d=r.json();check('DEF-115/123现行FIRST边界拒绝手动生效',d.get('code')==5004 and sql('SELECT status FROM lease_record WHERE id='+str(lid))=='TO_SIGN',{'http':r.status_code,'code':d.get('code')})
for idx,(a,b,move) in enumerate([(2,20,1),(-10,2,3)]):
 lid,hid=lease('ACTIVE',130+idx,a,b);r=request('/leases/'+str(lid)+'/move-out-applications',rt,'POST',{'moveOutDate':str(date.today()+timedelta(days=move))});d=r.json();check('DEF-130租期边界'+str(idx),d.get('code')==400 and int(sql('SELECT COUNT(*) FROM lease_move_out_application WHERE lease_id='+str(lid)))==0,{'http':r.status_code,'code':d.get('code')})
# Two callers (admin and resident) open the same DIRECT group from simultaneous requests.
for idx in [17,18]:
 rid=pool[idx]['residentId'];tokens=[sa if n%2 else pool[idx]['token'] for n in range(10)];gate=Barrier(10)
 def direct(item):
  n,token=item;gate.wait();return api('/conversations/direct',token,'POST',{'communityId':1,**({'residentId':rid} if n%2 else {})})['id']
 with ThreadPoolExecutor(max_workers=10) as ex:ids=list(ex.map(direct,enumerate(tokens)))
 check('DEF-213跨端十路直通建群'+str(idx),len(set(ids))==1,{'ids':ids,'residentId':rid})
# Correct sinceSeq parameter with nonzero cursor, not the legacy lastSeq typo.
rows=api('/notifications/pull?sinceSeq=0',rt);cursor=sorted(x['seq'] for x in rows)[len(rows)//2];tail=api('/notifications/pull?sinceSeq='+str(cursor),rt)
check('TC-NF-016通知补拉sinceSeq非零边界',all(x['seq']>cursor for x in tail) and len(tail)>0,{'cursor':cursor,'returned':len(tail),'minSeq':min(x['seq'] for x in tail) if tail else None})
save('old-anomalies.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'financialRequests':0,'syntheticLeaseSeed':'仅独立库合成历史状态、押金0，无支付单，无外部渠道调用'})
print('old anomalies',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
