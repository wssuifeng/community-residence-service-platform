from support import *
for _ in range(60):
 try:
  response=requests.get('http://127.0.0.1:8087/v3/api-docs',timeout=3)
  if response.status_code==200:break
 except requests.RequestException:pass
 time.sleep(1)
else:raise RuntimeError('isolated backend unavailable')
sessions={}
for name,pw,kind in [('superadmin','Admin@123456','admin'),('resident1','Resident123456','resident')]:sessions[name]=login(name,pw,kind)
(PRIVATE/'sessions.json').write_text(json.dumps(sessions),'utf-8')
for i in range(1,50):
 sql("INSERT INTO resident(username,password_hash,real_name,phone,status) SELECT 'df_pool_%03d',password_hash,'隔离居民%03d','1398000%04d','ACTIVE' FROM resident WHERE id=1"%(i,i,i))
sql("INSERT INTO residence_relation(resident_id,community_id,house_id,relation_type,move_in_date,is_primary) SELECT id,1,1,'OWNER',CURDATE(),1 FROM resident WHERE username LIKE 'df_pool_%'")
pool=[]
for i in range(50):
 name='resident1' if i==0 else 'df_pool_%03d'%i
 auth=sessions['resident1'] if i==0 else login(name,'Resident123456','resident')
 pool.append({'username':name,'token':auth['token'],'residentId':auth['user']['id']})
(PRIVATE/'pool.json').write_text(json.dumps(pool),'utf-8')
print('ready',len(pool),'accounts; Flyway V'+sql('SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1'))
