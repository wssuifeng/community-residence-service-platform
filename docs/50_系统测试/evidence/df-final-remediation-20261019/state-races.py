from support import *
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import sys,socket
mode=sys.argv[1];assert mode in ['baseline','fixed'];port=8089 if mode=='baseline' else 8087
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];pool=json.loads((PRIVATE/'pool.json').read_text('utf-8'));checks=[]
def check(name,ok,actual):checks.append({'name':name,'passed':bool(ok),'actual':actual});save('state-'+mode+'-progress.json',{'checks':checks})
def call(path,method='PATCH',body=None,token=None):
 assert not any(x in path for x in ['payment','refund'])
 t=time.perf_counter();r=requests.request(method,'http://127.0.0.1:'+str(port)+'/api/v1'+path,headers={'Authorization':'Bearer '+(token or sa)},json=body,timeout=15);j=r.json()
 return {'http':r.status_code,'code':j.get('code'),'ms':float('%.3f'%((time.perf_counter()-t)*1000))}
def held_update(table,id,values,action):
 query='START TRANSACTION;UPDATE '+table+' SET '+values+' WHERE id='+str(id)+";SELECT 'READY';DO SLEEP(1.2);COMMIT;"
 p=subprocess.Popen([MYSQL,'-h127.0.0.1','-u'+ENV.get('DB_USERNAME','root'),'-D'+DB,'-N','-B','--unbuffered','-e',query],env={**ENV,'MYSQL_PWD':ENV['DB_PASSWORD']},stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
 assert p.stdout.readline().strip()=='READY';result=action();p.communicate(timeout=5);assert p.returncode==0;return result
def reservation(status='PENDING',resident=1):
 resource=int(sql("INSERT INTO public_resource(community_id,name,type,capacity,slot_unit) VALUES(1,'df状态独立资源','GYM',50,30);SELECT LAST_INSERT_ID()"))
 return int(sql("INSERT INTO resource_reservation(user_id,resource_id,community_id,reserve_date,start_time,end_time,status,purpose,contact_phone) VALUES(%d,%d,1,DATE_ADD(CURDATE(),INTERVAL 6 DAY),'12:00:00','13:00:00','%s','df状态%s','13800000001');SELECT LAST_INSERT_ID()"%(resident,resource,status,mode)))
def burst(path,n,body):
 gate=Barrier(n)
 def one(i):gate.wait();return call(path,body=body)
 with ThreadPoolExecutor(max_workers=n) as ex:return list(ex.map(one,range(n)))
def run():
 for initial,action in [('PENDING','confirm'),('RESERVED','complete')]:
  id=reservation(initial);result=held_update('resource_reservation',id,"status='CANCELLED'",lambda:call('/resource-reservations/'+str(id)+'/'+action,body={'reason':'df旧状态竞争'}));state=sql('SELECT status FROM resource_reservation WHERE id='+str(id))
  expected=(result['code']==5004 and state=='CANCELLED') if mode=='fixed' else (result['code']==200 and state!='CANCELLED')
  check('资源预约旧状态覆盖-'+action,expected,{**result,'finalState':state})
 vid=int(sql("INSERT INTO viewing_appointment(user_id,housing_id,community_id,appointment_date,start_time,end_time,status,contact_phone,remark) VALUES(%d,1,1,DATE_ADD(CURDATE(),INTERVAL %d DAY),'12:00:00','13:00:00','TO_CONFIRM','13800000001','df状态');SELECT LAST_INSERT_ID()"%((pool[24 if mode=='baseline' else 25]['residentId']),9 if mode=='baseline' else 11)))
 result=held_update('viewing_appointment',vid,"status='CANCELLED'",lambda:call('/viewing-appointments/'+str(vid)+'/confirm',body={'reason':'df旧状态竞争'}));state=sql('SELECT status FROM viewing_appointment WHERE id='+str(vid))
 check('看房确认不能覆盖已取消',result['code']==5004 and state=='CANCELLED' if mode=='fixed' else result['code']==200 and state=='RESERVED',{**result,'finalState':state})
 fid=api('/feedbacks',rt,'POST',{'communityId':1,'title':'df闭合竞争'+mode,'content':'仅合成数据','category':'INQUIRY'})['id'];sql("UPDATE feedback SET status='IN_SESSION',handler_id=1 WHERE id="+str(fid))
 result=held_update('feedback',fid,"status='CLOSED'",lambda:call('/feedbacks/'+str(fid)+'/messages','POST',{'content':'df迟到消息'+mode}));count=int(sql("SELECT COUNT(*) FROM feedback_message WHERE feedback_id="+str(fid)+" AND content='df迟到消息"+mode+"'"))
 check('反馈闭合后迟到消息被拒',result['code']==5004 and count==0 if mode=='fixed' else result['code']==200 and count==1,{**result,'newMessages':count})
 sql('CREATE TRIGGER df_resource_update_delay BEFORE UPDATE ON resource_reservation FOR EACH ROW DO SLEEP(0.15)')
 sql('CREATE TRIGGER df_feedback_update_delay BEFORE UPDATE ON feedback FOR EACH ROW DO SLEEP(0.15)')
 try:
  target=pool[20 if mode=='baseline' else 21]['residentId'];rid=reservation('RESERVED',target);results=burst('/resource-reservations/'+str(rid)+'/violate',10,{'reason':'df并发违约'})
  violations=int(sql('SELECT COUNT(*) FROM violation_record WHERE related_id='+str(rid)+" AND violation_type='RESERVATION_NO_SHOW'"))
  check('同预约违约副作用只入账一次',violations==1 and sum(x['code']==200 for x in results)==1 if mode=='fixed' else violations>1,{'responses':results,'violationRows':violations})
  fid=api('/feedbacks',rt,'POST',{'communityId':1,'title':'df重复办结'+mode,'content':'仅合成数据','category':'INQUIRY'})['id'];sql("UPDATE feedback SET status='IN_SESSION',handler_id=1 WHERE id="+str(fid))
  results=burst('/feedbacks/'+str(fid)+'/close',10,{'remark':'df并发办结'})
  messages=int(sql("SELECT COUNT(*) FROM feedback_message WHERE feedback_id="+str(fid)+" AND content LIKE '[办结]%'"))
  check('反馈重复办结副作用只写一次',messages==1 and sum(x['code']==200 for x in results)==1 if mode=='fixed' else messages>1,{'responses':results,'closeMessages':messages})
 finally:
  sql('DROP TRIGGER IF EXISTS df_resource_update_delay');sql('DROP TRIGGER IF EXISTS df_feedback_update_delay')
 # Three distinct resource violations must freeze the account AND invalidate the prior token.
 index=22 if mode=='baseline' else 23;resident=pool[index];ids=[reservation('RESERVED',resident['residentId']) for _ in range(3)]
 for rid in ids:assert call('/resource-reservations/'+str(rid)+'/violate',body={'reason':'df冻结令牌'})['code']==200
 state=sql('SELECT status FROM resident WHERE id='+str(resident['residentId']));tokenResult=call('/notifications','GET',token=resident['token']);revokes=int(sql("SELECT COUNT(*) FROM auth_token_blacklist WHERE reason='PERMISSION_CHANGE' AND jti LIKE 'USER-R-"+str(resident['residentId'])+"-%'"))
 check('资源违约冻结吊销旧令牌',state=='FROZEN' and tokenResult['http']==401 and revokes>=1 if mode=='fixed' else state=='FROZEN' and tokenResult['code']==200 and revokes==0,{'residentState':state,'previousTokenHTTP':tokenResult['http'],'previousTokenCode':tokenResult['code'],'revocationRecords':revokes})
if mode=='baseline':
 with socket.socket() as sock:assert sock.connect_ex(('127.0.0.1',8089))!=0
 env={k:v for k,v in ENV.items() if not k.startswith('PAYMENT_')};env['JWT_SECRET']='df-isolated-only-secret-20261019-not-for-production';env['PAYMENT_CONFIG_KEY_FILE']=str(PRIVATE/'baseline-payment/master.key')
 args=['C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe','-jar',str(ROOT/'output/runtime/dc-closeout/dc-final.jar'),'--server.port=8089','--spring.config.import=','--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+DB+'?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6387','--upload.dir='+str(PRIVATE/'baseline-uploads'),'--logging.level.com.community.residence=info']
 with (PRIVATE/'baseline-state-backend.log').open('wb') as out:proc=subprocess.Popen(args,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=out,stderr=out,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
 try:
  for _ in range(60):
   try:
    if requests.get('http://127.0.0.1:8089/v3/api-docs',timeout=2).status_code==200:break
   except requests.RequestException:pass
   time.sleep(1)
  else:raise RuntimeError('baseline service unavailable')
  run()
 finally:proc.terminate();proc.wait(timeout=15)
else:run()
save('state-'+mode+'.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'financialRequests':0,'normalEnvironmentTouched':False,'meaning':'baseline中passed表示旧缺陷已被真实复现，不能当产品通过；fixed表示整改回归通过。'})
print(mode,sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
assert all(x['passed'] for x in checks)
