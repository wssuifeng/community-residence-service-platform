import datetime as dt, importlib.util, json, time
from pathlib import Path
p=Path('docs/60_评估验收/scripts/supplement_acceptance_flows.py');sp=importlib.util.spec_from_file_location('m',p);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
outdir=m.OUT;sup=json.loads((outdir/'supplement.json').read_text(encoding='utf-8'));ids=m.ids;cid=ids['communityId'];suf=next(x['actor'].split('acc_owner_')[1] for x in m.seed['trace'] if x['actor'].startswith('acc_owner_'));owner='acc_owner_'+suf
m.login('superadmin','Admin@123456');m.login('admin1','Admin123456');m.login('staff1','Staff123456');m.login(owner,'Resident123456',True)
an=sup['ids']['scopedAdminUsername'];m.login(an,'Admin123456');short=str(int(time.time()*1000)%1000000)
# E10b: scoped admin performs a real allowed resident provisioning operation.
created=m.api(an,'E10b scoped admin creates community resident','POST','/api/v1/residents/admin-create',{'communityId':cid,'username':'acc_provision_'+short,'realName':'Acceptance Provisioned Resident','phone':'133'+short.zfill(8)})
# The listing must stay vacant. End only synthetic setup relations left by the earlier harness attempt.
existing=m.api('admin1','read synthetic viewing-house setup','GET',f'/api/v1/houses/{ids["houseIds"][2]}/residents')
for rel0 in m.rows(existing):
    if rel0['status']=='ACTIVE' and rel0.get('residentUsername','').startswith('acc_violation_'):
        m.api('admin1','end synthetic harness setup relation','PATCH',f'/api/v1/residence-relations/{rel0["id"]}/move-out',{'moveOutDate':dt.date.today().isoformat(),'reason':'synthetic setup correction','confirmDependents':True})
new_house=m.api('admin1','prepare separate violation-test house','POST','/api/v1/houses',{'unitId':ids['unitId'],'houseNumber':'V'+short,'floor':1,'area':60,'status':'VACANT'})
# E12: the same listing is browsed anonymously, then a fresh resident registers and books.
m.api('guest','E12 guest browse same listing','GET',f'/api/v1/housings/{ids["housingId"]}')
n='acc_violation_'+short
r=m.api('guest','E12 guest registers','POST','/api/v1/auth/resident/register',{'username':n,'password':'Resident123456','realName':'Acceptance Violation Resident','phone':'132'+short.zfill(8)})
m.login(n,'Resident123456',True)
m.api('admin1','E7 prepare synthetic resident relation','POST','/api/v1/residence-relations',{'residentId':r['id'],'houseId':new_house['id'],'relationType':'OWNER','moveInDate':dt.date.today().isoformat(),'remark':'isolated violation test'})
# A fresh relation requires a current resident login before resident-private writes.
m.login(n,'Resident123456',True)
date=ids['targetDate'];resource=ids['resourceId']
viol=[]
for idx,source in enumerate(['RESOURCE','VIEWING','RESOURCE']):
    if source=='RESOURCE':
        v=m.api(n,'E7 create violation branch '+str(idx),'POST','/api/v1/resource-reservations',{'resourceId':resource,'reserveDate':date,'startTime':'10:00:00','endTime':'10:30:00','purpose':'isolated violation branch','contactPhone':'132'+short.zfill(8)})
        prefix='resource-reservations'
    else:
        v=m.api(n,'E12 registered resident books same listing','POST','/api/v1/viewing-appointments',{'housingId':ids['housingId'],'appointmentDate':date,'startTime':'14:00:00','endTime':'15:00:00','contactPhone':'132'+short.zfill(8)})
        prefix='viewing-appointments'
    m.api('admin1','confirm '+source+' '+str(idx),'PATCH',f'/api/v1/{prefix}/{v["id"]}/confirm',{'reason':'isolated approval'})
    if source=='VIEWING':
        m.api('admin1','E12 assign current staff','PATCH',f'/api/v1/{prefix}/{v["id"]}/assign',{'assigneeId':3})
        m.api('staff1','E12 viewing staff communication','POST',f'/api/v1/{prefix}/{v["id"]}/messages',{'content':'viewing schedule acknowledged'})
    m.api('admin1','violate '+source+' '+str(idx),'PATCH',f'/api/v1/{prefix}/{v["id"]}/violate',{'reason':'isolated violation disposition evidence'})
    viol.append({'source':source,'id':v['id']})
vr=m.api('admin1','E7 violation records retained','GET',f'/api/v1/residents/{r["id"]}/violations?page=1&size=100')
rr=m.api('admin1','E7 frozen synthetic account','GET',f'/api/v1/residents/{r["id"]}')
assert rr['status']=='FROZEN';assert len(m.rows(vr))>=3
m.api(n,'E7 old token rejected after freeze','POST','/api/v1/resource-reservations',{'resourceId':resource,'reserveDate':date,'startTime':'10:30:00','endTime':'11:00:00'},deny=True)
# E2 owner branch terminates and retains its history; tenant self move-out stays within E3 gap.
rels=m.api(owner,'E2 current relation before moveout','GET',f'/api/v1/residents/{ids["ownerResidentId"]}/residences')
rel=next(x for x in m.rows(rels) if x['houseId']==ids['houseIds'][0])
m.api('admin1','E2 managed owner moveout','PATCH',f'/api/v1/residence-relations/{rel["id"]}/move-out',{'moveOutDate':dt.date.today().isoformat(),'reason':'acceptance owner branch history','confirmDependents':True})
history=m.api('admin1','E2 historical relation after moveout','GET',f'/api/v1/residents/{ids["ownerResidentId"]}/residences')
final=next(x for x in m.rows(history) if x['id']==rel['id']);assert final['status']=='MOVED_OUT',final
house=m.api('admin1','E2 vacant house after moveout','GET',f'/api/v1/houses/{ids["houseIds"][0]}');assert house['status']=='VACANT'
# Verify initialization logs including timeslots from the same main-chain data.
slot=next(x['data']['id'] for x in m.seed['trace'] if x['step']=='E1 create resource timeslot')
logs=m.sql(f"SELECT target_type,target_id,operation_type FROM sys_operation_log WHERE (target_type='COMMUNITY' AND target_id={cid}) OR (target_type='BUILDING' AND target_id={ids['buildingId']}) OR (target_type='UNIT' AND target_id={ids['unitId']}) OR (target_type='HOUSE' AND target_id IN ({','.join(map(str,ids['houseIds']))})) OR (target_type='PUBLIC_RESOURCE' AND target_id={resource}) OR (target_type='RESOURCE_TIMESLOT' AND target_id={slot})")
assert {x[0] for x in logs}>={'COMMUNITY','BUILDING','UNIT','HOUSE','PUBLIC_RESOURCE','RESOURCE_TIMESLOT'}
result={'recordedAt':dt.datetime.now().isoformat(),'jarSha256':m.seed['jarSha256'],'normalDatabaseWrites':0,'financialOperations':0,'trace':m.trace,'ids':{'provisionedResidentId':created.get('residentId') or created.get('id') or (created.get('resident') or {}).get('id'),'violationResidentId':r['id'],'violationRecords':viol,'ownerRelationId':rel['id']},'assertions':{'scopedAdminProvisioning':bool(created),'anonymousToRegisteredSameListing':True,'violationCountAtLeastThree':len(m.rows(vr))>=3,'syntheticAccountFrozen':rr['status']=='FROZEN','ownerRelationHistoryRetained':final['status']=='MOVED_OUT','ownerHouseReleased':house['status']=='VACANT','initializationStepsHaveLogs':True},'initializationLogs':logs,'limitations':['Violation records deliberately use synthetic future reservations to verify current disposition behavior; they do not prove real-world use or no-show timing.','E2 owner branch only; tenant self-moveout, activation and E3 financial segments are not replayed.']}
(outdir/'branches.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('branch requests',len(m.trace),'assertions',result['assertions'])
