from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from datetime import date,timedelta
import socket,hashlib
s=load_sessions();rt=s['resident1']['token'];sa=s['superadmin']['token'];pool=json.loads((PRIVATE/'pool.json').read_text('utf-8'));checks=[];baseline=[];run_tag=str(int(time.time()))
def check(name,ok,actual=None):
 checks.append({'name':name,'passed':bool(ok),'actual':actual});save('concurrency-progress.json',{'checks':checks,'baseline':baseline})
def call(port,path,token,body):
 assert port in (8087,8089) and not any(x in path for x in ['payment','refund'])
 t=time.perf_counter();r=requests.post('http://127.0.0.1:'+str(port)+'/api/v1'+path,headers={'Authorization':'Bearer '+token},json=body,timeout=15);j=r.json()
 return {'http':r.status_code,'code':j.get('code'),'message':j.get('message'),'id':j.get('data',{}).get('id') if isinstance(j.get('data'),dict) else None,'ms':float('%.3f'%((time.perf_counter()-t)*1000))}
def burst(port,calls):
 gate=Barrier(len(calls))
 def one(item):gate.wait();return call(port,*item)
 with ThreadPoolExecutor(max_workers=len(calls)) as ex:return list(ex.map(one,calls))
resources=[]
for i in range(2):
 rid=int(sql("INSERT INTO public_resource(community_id,name,type,capacity,slot_unit) VALUES(1,'df跨资源%d','GYM',50,30);SELECT LAST_INSERT_ID()"%i));resources.append(rid)
 for day in range(1,8):sql("INSERT INTO resource_timeslot(resource_id,community_id,day_of_week,start_time,end_time) VALUES(%d,1,%d,'08:00:00','20:00:00')"%(rid,day))
sql('DELETE FROM housing_timeslot WHERE housing_id=1')
for day in range(1,8):sql("INSERT INTO housing_timeslot(housing_id,day_of_week,start_time,end_time) VALUES(1,%d,'08:00:00','20:00:00')"%day)
# Bounded insertion delay exposes check-then-insert without changing product code.
sql('DROP TRIGGER IF EXISTS df_resource_delay')
sql('DROP TRIGGER IF EXISTS df_viewing_delay')
sql('CREATE TRIGGER df_resource_delay BEFORE INSERT ON resource_reservation FOR EACH ROW DO SLEEP(0.25)')
sql('CREATE TRIGGER df_viewing_delay BEFORE INSERT ON viewing_appointment FOR EACH ROW DO SLEEP(0.25)')
def schedule_cases(port,prefix):
 rows=[]
 for kind in ['crossResource','crossDomain']:
  for n in range(3):
   day=str(date.today()+timedelta(days=n+1));start='10:00:00' if kind=='crossResource' else '14:00:00';end='11:00:00' if kind=='crossResource' else '15:00:00';marker=prefix+'-'+kind+'-'+str(n)
   resource=lambda rid:('/resource-reservations',rt,{'resourceId':rid,'reserveDate':day,'startTime':start,'endTime':end,'purpose':marker,'contactPhone':'13800000001'})
   other=resource(resources[1]) if kind=='crossResource' else ('/viewing-appointments',rt,{'housingId':1,'appointmentDate':day,'startTime':start,'endTime':end,'remark':marker,'contactPhone':'13800000001'})
   result=burst(port,[resource(resources[0]),other]);count=sum(x['code']==200 for x in result)
   ids=[x['id'] for x in result if x['code']==200]
   rows.append({'kind':kind,'round':n+1,'responses':result,'successfulWrites':count})
   # Leave no occupying records from the preceding baseline round.
   sql("UPDATE resource_reservation SET status='CANCELLED' WHERE purpose='"+marker+"'")
   sql("UPDATE viewing_appointment SET status='CANCELLED' WHERE remark='"+marker+"'")
 return rows
oldjar=ROOT/'output/runtime/dc-closeout/dc-final.jar';oldhash=hashlib.sha256(oldjar.read_bytes()).hexdigest();record=json.loads((ROOT/'docs/50_系统测试/evidence/dc-targeted-closeout-20261019/final-runtime.json').read_text('utf-8'));assert record['jarSHA256']==oldhash
with socket.socket() as sock:assert sock.connect_ex(('127.0.0.1',8089))!=0,'不接管占用端口'
env={k:v for k,v in ENV.items() if not k.startswith('PAYMENT_')};env['JWT_SECRET']='df-isolated-only-secret-20261019-not-for-production';env['PAYMENT_CONFIG_KEY_FILE']=str(PRIVATE/'baseline-payment/master.key')
args=['C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe','-jar',str(oldjar),'--server.port=8089','--spring.config.import=','--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+DB+'?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6387','--upload.dir='+str(PRIVATE/'baseline-uploads'),'--logging.level.com.community.residence=info']
with (PRIVATE/'baseline-backend.log').open('wb') as out:oldproc=subprocess.Popen(args,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=out,stderr=out,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
def changed_lease_case(port,kind):
 h=int(sql("INSERT INTO house(unit_id,community_id,house_number,floor,area,status) VALUES(1,1,'df"+kind[0]+str(port)+run_tag[-6:]+"',1,88,'OCCUPIED');SELECT LAST_INSERT_ID()"))
 lid=int(sql("INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status) VALUES(1,1,"+str(h)+",CURDATE(),DATE_ADD(CURDATE(),INTERVAL 30 DAY),1000,0,'ACTIVE');SELECT LAST_INSERT_ID()"))
 change="status='MOVED_OUT'" if kind=='ended' else 'end_date=DATE_ADD(CURDATE(),INTERVAL 1 DAY)'
 query='START TRANSACTION;UPDATE lease_record SET '+change+' WHERE id='+str(lid)+";SELECT 'READY';DO SLEEP(1.2);COMMIT;"
 args=[MYSQL,'-h127.0.0.1','-u'+ENV.get('DB_USERNAME','root'),'-D'+DB,'-N','-B','--unbuffered','-e',query]
 holder=subprocess.Popen(args,env={**ENV,'MYSQL_PWD':ENV['DB_PASSWORD']},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
 assert holder.stdout.readline().strip()=='READY'
 result=call(port,'/leases/'+str(lid)+'/move-out-applications',rt,{'moveOutDate':str(date.today()+timedelta(days=2)),'reason':'df交错复验'})
 holder.communicate(timeout=5);assert holder.returncode==0
 result['pendingRows']=int(sql("SELECT COUNT(*) FROM lease_move_out_application WHERE lease_id="+str(lid)+" AND status='PENDING'"));return result
try:
 for _ in range(60):
  try:
   if requests.get('http://127.0.0.1:8089/v3/api-docs',timeout=2).status_code==200:break
  except requests.RequestException:pass
  time.sleep(1)
 else:raise RuntimeError('baseline backend did not start')
 baseline=schedule_cases(8089,'df-before')
 oldmove={kind:changed_lease_case(8089,kind) for kind in ['ended','shortened']}
 check('旧版本真实复现跨资源及跨域竞态',all(any(x['kind']==kind and x['successfulWrites']==2 for x in baseline) for kind in ['crossResource','crossDomain']),baseline)
 check('旧版本真实复现退租过期预检',all(x['code']==200 and x['pendingRows']==1 for x in oldmove.values()),oldmove)
finally:
 oldproc.terminate();oldproc.wait(timeout=15)
try:
 fixed=schedule_cases(8087,'df-after')
 for row in fixed:check('修复后'+row['kind']+'第'+str(row['round'])+'轮',row['successfulWrites']==1 and all(x['http']<500 for x in row['responses']),row)
 newmove={kind:changed_lease_case(8087,kind) for kind in ['ended','shortened']}
 for kind,result in newmove.items():check('修复后退租锁内重检-'+kind,result['code']!=200 and result['http']<500 and result['pendingRows']==0,result)
 # Different residents still compete on resource capacity; touching boundaries are allowed.
 sql('UPDATE public_resource SET capacity=1 WHERE id='+str(resources[0]))
 day=str(date.today()+timedelta(days=6));body={'resourceId':resources[0],'reserveDate':day,'startTime':'10:00:00','endTime':'11:00:00','contactPhone':'13800000001','purpose':'df容量'}
 results=burst(8087,[('/resource-reservations',x['token'],body) for x in pool[1:11]])
 check('不同居民同资源10路容量不超售',sum(x['code']==200 for x in results)==1 and all(x['http']<500 for x in results),results)
 for i,(a,b) in enumerate([('17:00:00','18:00:00'),('18:00:00','19:00:00')]):
  result=call(8087,'/resource-reservations',rt,{'resourceId':resources[i],'reserveDate':day,'startTime':a,'endTime':b,'contactPhone':'13800000001','purpose':'df首尾相接'})
  check('首尾相接边界'+str(i+1),result['code']==200,result)
finally:
 sql('DROP TRIGGER IF EXISTS df_resource_delay');sql('DROP TRIGGER IF EXISTS df_viewing_delay')
save('concurrency.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'baselineJarSHA256':oldhash,'baseline':baseline,'fixed':fixed,'oldMoveOut':oldmove,'fixedMoveOut':newmove,'financialRequests':0,'normalEnvironmentTouched':False,'injection':'独立库BEFORE INSERT延迟250ms；独立租约行锁持有1.2秒以控制真实HTTP交错。触发器均移除。'})
print('concurrency',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
assert all(x['passed'] for x in checks)
