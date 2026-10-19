from support import *
from datetime import date,timedelta
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token']
assert sql("SELECT COUNT(*) FROM resident WHERE username LIKE 'dc_pool_%'")=='0'
values=','.join("('dc_pool_%03d',p.password_hash,'隔离居民%03d','1397000%04d','ACTIVE')"%(i,i,i) for i in range(1,200))
# Each SELECT copies only the seeded demonstration password hash inside the dedicated database.
for i in range(1,200):
 sql("INSERT INTO resident(username,password_hash,real_name,phone,status) SELECT 'dc_pool_%03d',password_hash,'隔离居民%03d','1397000%04d','ACTIVE' FROM resident WHERE id=1"%(i,i,i))
sql("INSERT INTO residence_relation(resident_id,community_id,house_id,relation_type,move_in_date,is_primary) SELECT id,1,1,'OWNER',CURDATE(),1 FROM resident WHERE username LIKE 'dc_pool_%'")
sql("INSERT INTO work_order(order_no,resident_id,community_id,category_id,title,content,contact_phone,status) SELECT CONCAT('DC-POOL-',id),id,1,1,'隔离负载工单','仅本批合成数据',phone,'PENDING' FROM resident")
rows=[]
for i in range(200):
 name='resident1' if i==0 else 'dc_pool_%03d'%i
 auth=s['resident1'] if i==0 else login(name,'Resident123456','resident')
 rid=auth['user']['id'];wid=int(sql('SELECT id FROM work_order WHERE resident_id='+str(rid)+' ORDER BY id LIMIT 1'))
 rows.append({'username':name,'token':auth['token'],'residentId':rid,'workOrderId':wid})
(PRIVATE/'pool.json').write_text(json.dumps(rows),'utf-8')
(PRIVATE/'tokens.csv').write_text(''.join(x['username']+','+x['token']+','+str(x['workOrderId'])+'\n' for x in rows),'utf-8')
comm=api('/communities',sa,'POST',{'name':'dc独立社区','address':'仅隔离测试','description':'dc-closeout'})['id']
build=api('/buildings',sa,'POST',{'communityId':comm,'name':'隔离楼','floors':10})['id']
unit=api('/units',sa,'POST',{'buildingId':build,'name':'隔离单元'})['id']
house=api('/houses',sa,'POST',{'unitId':unit,'houseNumber':'101','floor':1,'area':88,'layout':'两室一厅'})['id']
a2=api('/sys-users',sa,'POST',{'username':'dc_admin2','password':'Admin123456','realName':'隔离管理员','phone':'13870009999','role':'ADMIN'})['id']
api('/sys-users/'+str(a2)+'/communities',sa,'POST',{'communityId':comm})
s['admin2']=login('dc_admin2','Admin123456');s['resident2']=login('dc_pool_001','Resident123456','resident')
(PRIVATE/'sessions.json').write_text(json.dumps(s),'utf-8')
resource=int(sql("SELECT id FROM public_resource LIMIT 1"));sql('UPDATE public_resource SET capacity=1,slot_unit=30 WHERE id='+str(resource))
sql('DELETE FROM resource_timeslot WHERE resource_id='+str(resource));sql('DELETE FROM housing_timeslot WHERE housing_id=1')
for day in range(1,8):
 sql("INSERT INTO resource_timeslot(resource_id,community_id,day_of_week,start_time,end_time) VALUES(%d,1,%d,'08:00:00','20:00:00')"%(resource,day))
 sql("INSERT INTO housing_timeslot(housing_id,day_of_week,start_time,end_time) VALUES(1,%d,'08:00:00','20:00:00')"%day)
feedback=api('/feedbacks',rt,'POST',{'communityId':1,'title':'dc隔离反馈','content':'权限专项合成数据','category':'INQUIRY'})['id']
fixtures={'community2':comm,'building2':build,'unit2':unit,'house2':house,'admin2':a2,'resource':resource,'housing':1,'feedback':feedback,'workOrder1':rows[0]['workOrderId'],'workOrder2':rows[1]['workOrderId'],'date':str(date.today()+timedelta(days=10))}
save('fixtures.json',fixtures)
save('account-pool-summary.json',{'residentAccounts':200,'systemAccountsLoggedIn':len(s)-2,'registeredTotal':int(sql('SELECT (SELECT COUNT(*) FROM resident)+(SELECT COUNT(*) FROM sys_user)')),'authenticatedResidents':len(rows),'credentialsOrTokensExported':False})
print('fixtures ready',fixtures,'pool',len(rows))
