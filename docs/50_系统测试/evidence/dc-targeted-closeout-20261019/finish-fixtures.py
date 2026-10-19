from support import *
from datetime import date,timedelta
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token']
rows=json.loads((PRIVATE/'pool.json').read_text('utf-8'))
comm=2;build=2;unit=2;house=4;a2=4
# binding already created during fixture setup
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
