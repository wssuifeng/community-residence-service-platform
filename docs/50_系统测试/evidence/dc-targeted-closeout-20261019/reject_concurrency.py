from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
s=load_sessions();sa=s['superadmin']['token'];checks=[]
hid=int(sql("INSERT INTO house(unit_id,community_id,house_number,floor,area,status) VALUES(1,1,'DC-RJ-"+str(time.time_ns()%1000000000000)+"',1,88,'VACANT');SELECT LAST_INSERT_ID()"))
lid=int(sql("INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status) VALUES(1,1,"+str(hid)+",CURDATE(),DATE_ADD(CURDATE(),INTERVAL 30 DAY),1000,0,'ACTIVE');SELECT LAST_INSERT_ID()"))
aid=int(sql("INSERT INTO lease_move_out_application(lease_id,resident_id,community_id,house_id,move_out_date,reason,status) VALUES("+str(lid)+",1,1,"+str(hid)+",DATE_ADD(CURDATE(),INTERVAL 1 DAY),'dc驳回并发','PENDING');SELECT LAST_INSERT_ID()"))
gate=Barrier(10)
def one(n):
 gate.wait();r=request('/lease-move-out-applications/'+str(aid)+'/reject',sa,'PATCH',{'reason':'dc驳回并发'});d=r.json();return {'http':r.status_code,'code':d.get('code'),'message':d.get('message'),'status':(d.get('data') or {}).get('status') if isinstance(d.get('data'),dict) else None}
with ThreadPoolExecutor(max_workers=10) as pool:responses=list(pool.map(one,range(10)))
actual={'responses':responses,'leaseStatus':sql('SELECT status FROM lease_record WHERE id='+str(lid)),'applicationStatus':sql('SELECT status FROM lease_move_out_application WHERE id='+str(aid)),'notificationCount':int(sql("SELECT COUNT(*) FROM notification WHERE source_type='LEASE_MOVE_OUT' AND source_id="+str(lid)+" AND owner_type='RESIDENT'"))}
ok=all(x['code']==200 and x['status']=='REJECTED' for x in responses) and actual['leaseStatus']=='ACTIVE' and actual['applicationStatus']=='REJECTED' and actual['notificationCount']==1
checks.append({'name':'DEF-104十路驳回幂等且通知一次不改变租约','passed':ok,'actual':actual})
save('reject-concurrency.json',{'checks':checks,'passed':int(ok),'financialRequests':0,'scope':'隔离合成历史租约，仅驳回状态与通知，不支付或退款'})
print(json.dumps(checks,ensure_ascii=False))
