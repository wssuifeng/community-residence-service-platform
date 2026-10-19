import json, urllib.request, urllib.error, time, datetime, pathlib, traceback
BASE = 'http://127.0.0.1:8087'
# Dedicated acceptance environment only; never point this write probe at the normal runtime.
assert BASE == 'http://127.0.0.1:8087'
# Verify the live process artifact path/hash before producing version-bound evidence.
import hashlib, psutil
state_path=pathlib.Path('output/runtime/acceptance-flow-20261020/state.json')
runtime_state=json.loads(state_path.read_text(encoding='utf-8'))
runtime_args=psutil.Process(runtime_state['backendPid']).cmdline()
assert '--server.port=8087' in runtime_args
runtime_jar=pathlib.Path(runtime_args[runtime_args.index('-jar')+1])
assert runtime_jar.resolve() == pathlib.Path('output/runtime/df-closeout/df-final.jar').resolve()
assert hashlib.sha256(runtime_jar.read_bytes()).hexdigest() == 'b87c8d232151d02d70dfb31e001d801c6abb4df0b45e0a2e6b9ca4ec6ed3e541'
RUN_AT = datetime.datetime.now(datetime.timezone.utc).isoformat()
trace=[]

def clean(v):
    if isinstance(v, dict):
        return {k: clean(x) for k,x in v.items() if k.lower() not in {'token','password','access_token','refresh_token'}}
    if isinstance(v, list): return [clean(x) for x in v]
    return v

def request(actor, step, method, path, token=None, body=None, expected_http=200, expected_code=200):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+path, method=method, headers=headers, data=None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8'))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status=r.status; raw=r.read()
    except urllib.error.HTTPError as e:
        status=e.code; raw=e.read()
    try: payload=json.loads(raw)
    except Exception: payload={'raw':raw.decode('utf-8','replace')}
    code=payload.get('code') if isinstance(payload,dict) else None
    entry={'actor':actor,'step':step,'method':method,'path':path,'http':status,'code':code,'data':clean(payload.get('data') if isinstance(payload,dict) else payload)}
    trace.append(entry)
    if status != expected_http or (expected_code is not None and code != expected_code):
        msg=f'{step}: HTTP {status}/code {code}, expected HTTP {expected_http}/code {expected_code}'
        print('FAIL',msg, json.dumps(payload,ensure_ascii=False)[:1800])
        raise RuntimeError(msg)
    print('PASS',actor,step,method,path,'=>',status,code)
    return payload

def login(username,password,resident=False):
    j=request(username,'login','POST','/api/v1/auth/'+('resident' if resident else 'admin')+'/login',body={'username':username,'password':password})
    d=j['data']; return d['token'], d.get('user',{})

def id_of(j):
    d=j.get('data')
    if isinstance(d,dict) and d.get('id') is not None: return d['id']
    raise RuntimeError('missing id in response')

def records_of(j):
    d=j.get('data') or {}
    return d.get('records',d if isinstance(d,list) else [])

def dt(d): return d.isoformat()

def main():
    today=datetime.date.today(); target=today+datetime.timedelta(days=3); lease_start=today; lease_end=today+datetime.timedelta(days=365)
    dow=target.isoweekday(); suffix=str(int(time.time()*1000)%100000)
    st,su=login('superadmin','Admin@123456')
    users=records_of(request('superadmin','list system users','GET','/api/v1/sys-users?page=1&size=100',st))
    ids={u['username']:u['id'] for u in users}
    # E1 / E10b setup
    cid=id_of(request('superadmin','E1 create community','POST','/api/v1/communities',st,{'name':'ACC-FLOW-'+suffix,'address':'Acceptance Flow Road 1','description':'phase60 acceptance isolated','autoApproveResidence':0,'defaultLeaseMonths':12}))
    request('superadmin','E10b bind admin community','POST',f'/api/v1/sys-users/{ids["admin1"]}/communities',st,{'communityId':cid})
    bid=id_of(request('superadmin','E1 create building','POST','/api/v1/buildings',st,{'communityId':cid,'name':'A1','floors':2,'description':'acceptance'}))
    uid=id_of(request('superadmin','E1 create unit','POST','/api/v1/units',st,{'buildingId':bid,'name':'U1','description':'acceptance'}))
    h1=id_of(request('superadmin','E1 create house 101','POST','/api/v1/houses',st,{'unitId':uid,'houseNumber':'101','floor':1,'area':88.5,'layout':'2B1L','orientation':'SOUTH','status':'VACANT'}))
    h2=id_of(request('superadmin','E1 create house 102','POST','/api/v1/houses',st,{'unitId':uid,'houseNumber':'102','floor':1,'area':66.0,'layout':'1B1L','orientation':'EAST','status':'VACANT'}))
    h3=id_of(request('superadmin','E1 create house 103','POST','/api/v1/houses',st,{'unitId':uid,'houseNumber':'103','floor':1,'area':72.0,'layout':'2B1L','orientation':'SOUTH','status':'VACANT'}))
    cat=id_of(request('superadmin','E1 create service category','POST','/api/v1/service-categories',st,{'communityId':cid,'name':'AC-Repair-'+suffix,'description':'acceptance'}))
    resource=id_of(request('superadmin','E1 create public resource','POST','/api/v1/resources',st,{'communityId':cid,'name':'AC-Room-'+suffix,'type':'MEETING_ROOM','location':'A1-1F','capacity':20,'slotUnit':30,'description':'acceptance'}))
    request('superadmin','E1 create resource timeslot','POST',f'/api/v1/resources/{resource}/timeslots',st,{'dayOfWeek':dow,'startTime':'09:00:00','endTime':'11:00:00','isAvailable':1})
    request('superadmin','E10b bind staff capability','PUT',f'/api/v1/staff-capabilities/{ids["staff1"]}',st,{'communityIds':[cid],'categoryIds':[cat]})
    at,au=login('admin1','Admin123456'); sft,su2=login('staff1','Staff123456')
    for step,path in [('E1 read community',f'/api/v1/communities/{cid}'),('E1 read buildings',f'/api/v1/communities/{cid}/buildings'),('E1 read houses',f'/api/v1/units/{uid}/houses?page=1&size=100'),('E1 read resources',f'/api/v1/communities/{cid}/resources')]:
        request('admin1',step,'GET',path,at)
    # E2 owner residence application
    owner_name='acc_owner_'+suffix; owner_phone='139'+suffix.zfill(8)[-8:]
    request('guest', 'E2 register resident', 'POST','/api/v1/auth/resident/register',body={'username':owner_name,'password':'Resident123456','realName':'Acceptance Owner','phone':owner_phone})
    rt,ru=login(owner_name,'Resident123456',True); owner_id=ru['id']
    app_owner=id_of(request(owner_name,'E2 submit owner residence application','POST','/api/v1/residence-applications',rt,{'houseId':h1,'relationType':'OWNER','remark':'acceptance E2'}))
    request('admin1','E2 approve owner residence application','PATCH',f'/api/v1/residence-applications/{app_owner}/approve',at,{'leaseStartDate':dt(lease_start),'leaseEndDate':dt(lease_end),'monthlyRent':0,'deposit':0,'remark':'acceptance E2 approve'})
    request(owner_name,'E2 read own residences','GET',f'/api/v1/residents/{owner_id}/residences',rt)
    # Refresh resident scope after approval establishes the active relation.
    rt,ru=login(owner_name,'Resident123456',True)
    # E4 work order full state machine plus E8 evaluation
    wo=id_of(request(owner_name,'E4 submit work order','POST','/api/v1/work-orders',rt,{'categoryId':cat,'title':'AC acceptance repair','content':'cross-role flow evidence','contactPhone':owner_phone,'address':'A1 U1 101','priority':'HIGH'}))
    request('admin1','E4 intake work order','PATCH',f'/api/v1/work-orders/{wo}/intake',at,{'remark':'accepted for dispatch'})
    request('admin1','E4 assign work order','PATCH',f'/api/v1/work-orders/{wo}/assign',at,{'assigneeId':ids['staff1'],'remark':'assigned to staff1'})
    request('staff1','E4 accept work order','PATCH',f'/api/v1/work-orders/{wo}/accept',sft,{'remark':'accepted'})
    request('staff1','E4 process work order','PATCH',f'/api/v1/work-orders/{wo}/process',sft,{'remark':'started'})
    request('staff1','E4 complete work order','PATCH',f'/api/v1/work-orders/{wo}/complete',sft,{'remark':'completed'})
    request(owner_name,'E4 resident confirm work order','PATCH',f'/api/v1/work-orders/{wo}/confirm',rt,{'remark':'confirmed'})
    request(owner_name,'E8 submit evaluation','POST',f'/api/v1/work-orders/{wo}/evaluation',rt,{'rating':5,'content':'acceptance evaluation','isSatisfied':True,'tags':'QUALITY'})
    request('admin1','E4 close work order','PATCH',f'/api/v1/work-orders/{wo}/close',at,{'remark':'final close'})
    request(owner_name,'E4 read work order timeline','GET',f'/api/v1/work-orders/{wo}/timeline',rt)
    # E5 notice and E6 feedback
    notice=id_of(request('admin1','E5 create notice','POST','/api/v1/notices',at,{'communityId':cid,'targets':[],'isPinned':1,'priority':'NORMAL','type':'ANNOUNCEMENT','title':'AC acceptance notice '+suffix,'content':'notice cross-end evidence','publishTime':dt(datetime.datetime.now().replace(microsecond=0)),'endTime':dt(datetime.datetime.now()+datetime.timedelta(days=7))}))
    request('admin1','E5 publish notice','PATCH',f'/api/v1/notices/{notice}/publish',at,{'publishTime':dt(datetime.datetime.now().replace(microsecond=0))})
    request('guest','E5 guest read published notices','GET',f'/api/v1/notices?page=1&size=50&communityId={cid}')
    request(owner_name,'E5 resident view notice','POST',f'/api/v1/notices/{notice}/view',rt)
    request('admin1','E5 read notice viewers','GET',f'/api/v1/notices/{notice}/viewers?page=1&size=50',at)
    fb=id_of(request(owner_name,'E6 submit feedback','POST','/api/v1/feedbacks',rt,{'communityId':cid,'title':'AC acceptance feedback','content':'feedback session evidence','category':'SUGGESTION'}))
    request('admin1','E6 admin message feedback','POST',f'/api/v1/feedbacks/{fb}/messages',at,{'content':'received and handled'})
    request(owner_name,'E6 resident message feedback','POST',f'/api/v1/feedbacks/{fb}/messages',rt,{'content':'thank you'})
    request(owner_name,'E6 read feedback messages','GET',f'/api/v1/feedbacks/{fb}/messages',rt)
    request('admin1','E6 close feedback','PATCH',f'/api/v1/feedbacks/{fb}/close',at,{'remark':'closed after response'})
    # E7 resource reservation
    request(owner_name,'E7 read available resource slots','GET',f'/api/v1/resources/{resource}/available-slots?startDate={dt(target)}&endDate={dt(target)}',rt)
    reservation=id_of(request(owner_name,'E7 submit resource reservation','POST','/api/v1/resource-reservations',rt,{'resourceId':resource,'reserveDate':dt(target),'startTime':'09:00:00','endTime':'09:30:00','purpose':'acceptance resource flow','contactPhone':owner_phone,'remark':'E7'}))
    request('admin1','E7 confirm resource reservation','PATCH',f'/api/v1/resource-reservations/{reservation}/confirm',at,{'reason':'approved'})
    request('admin1','E7 complete resource reservation','PATCH',f'/api/v1/resource-reservations/{reservation}/complete',at,{'reason':'used and completed'})
    request(owner_name,'E7 read resource reservation','GET',f'/api/v1/resource-reservations/{reservation}',rt)
    # E11 notification chain from work order and notice events
    request(owner_name,'E11 list notifications','GET','/api/v1/notifications?page=1&size=100',rt)
    request(owner_name,'E11 list unread notifications','GET','/api/v1/notifications/unread',rt)
    pull=request(owner_name,'E11 pull notifications','GET','/api/v1/notifications/pull',rt)
    nrows=pull.get('data') if isinstance(pull.get('data'),list) else []
    if nrows:
        nid=nrows[0].get('id')
        if nid: request(owner_name,'E11 mark notification read','PATCH',f'/api/v1/notifications/{nid}/read',rt)
    # E12 housing/guest-to-resident viewing
    housing=id_of(request('admin1','E12 create housing listing','POST','/api/v1/housings',at,{'houseId':h3,'title':'AC viewing listing '+suffix,'description':'viewing cross-end evidence','monthlyRent':4200,'deposit':8400,'rentType':'RENT'}))
    request('admin1','E12 create housing viewing slot','POST',f'/api/v1/housings/{housing}/timeslots',at,{'dayOfWeek':dow,'startTime':'14:00:00','endTime':'15:00:00','isAvailable':1})
    request('guest','E12 guest browse housing','GET',f'/api/v1/housings?page=1&size=50&communityId={cid}')
    request('guest','E12 guest view housing','POST',f'/api/v1/housings/{housing}/view')
    request(owner_name,'E12 resident read available viewing slots','GET',f'/api/v1/housings/{housing}/available-slots?startDate={dt(target)}&endDate={dt(target)}',rt)
    viewing=id_of(request(owner_name,'E12 create viewing appointment','POST','/api/v1/viewing-appointments',rt,{'housingId':housing,'appointmentDate':dt(target),'startTime':'14:00:00','endTime':'15:00:00','contactPhone':owner_phone,'remark':'E12'}))
    request('admin1','E12 confirm viewing appointment','PATCH',f'/api/v1/viewing-appointments/{viewing}/confirm',at,{'reason':'confirmed'})
    request('admin1','E12 assign viewing staff','PATCH',f'/api/v1/viewing-appointments/{viewing}/assign',at,{'assigneeId':ids['staff1']})
    request('staff1','E12 send viewing message','POST',f'/api/v1/viewing-appointments/{viewing}/messages',sft,{'content':'staff confirmed viewing'})
    request(owner_name,'E12 read viewing messages','GET',f'/api/v1/viewing-appointments/{viewing}/messages',rt)
    request('staff1','E12 complete viewing appointment','PATCH',f'/api/v1/viewing-appointments/{viewing}/complete',sft,{'reason':'viewing completed'})
    request(owner_name,'E12 read completed viewing appointment','GET',f'/api/v1/viewing-appointments/{viewing}',rt)
    # E3 tenant listing/application/agreement (financial operation intentionally not repeated)
    tenant_name='acc_tenant_'+suffix; tenant_phone='138'+suffix.zfill(8)[-8:]
    request('guest','E3 register tenant','POST','/api/v1/auth/resident/register',body={'username':tenant_name,'password':'Resident123456','realName':'Acceptance Tenant','phone':tenant_phone})
    tt,tu=login(tenant_name,'Resident123456',True); tenant_id=tu['id']
    rent_housing=id_of(request('admin1','E3 create rental listing','POST','/api/v1/housings',at,{'houseId':h2,'title':'AC rental listing '+suffix,'description':'rental chain evidence','monthlyRent':3600,'deposit':7200,'rentType':'RENT'}))
    tenant_app=id_of(request(tenant_name,'E3 submit tenant application from listing','POST','/api/v1/residence-applications',tt,{'houseId':h2,'housingId':rent_housing,'relationType':'TENANT','remark':'E3 tenant application'}))
    approved=request('admin1','E3 approve tenant application','PATCH',f'/api/v1/residence-applications/{tenant_app}/approve',at,{'leaseStartDate':dt(lease_start),'leaseEndDate':dt(lease_end),'monthlyRent':3600,'deposit':7200,'remark':'E3 approve'})
    lease_id=(approved.get('data') or {}).get('leaseId')
    if not lease_id:
        detail=request('admin1','E3 read tenant application for lease id','GET',f'/api/v1/residence-applications/{tenant_app}',at)
        lease_id=(detail.get('data') or {}).get('leaseId')
    if lease_id:
        request('admin1','E3 read pending lease','GET',f'/api/v1/leases/{lease_id}',at)
        template=id_of(request('admin1','E3 create agreement template','POST','/api/v1/agreement-templates',at,{'communityId':cid,'name':'AC Template '+suffix,'content':'Lease {{租客}} {{房屋}} {{开始日期}} {{结束日期}} {{租金}} {{押金}}','isDefault':0,'remark':'acceptance'}))
        agreement=id_of(request('admin1','E3 create lease agreement','POST','/api/v1/lease-agreements',at,{'leaseId':lease_id,'templateId':template,'title':'AC Agreement '+suffix,'remark':'acceptance'}))
        request(tenant_name,'E3 resident confirm agreement','POST',f'/api/v1/lease-agreements/{agreement}/confirm',tt,{'confirmName':'Acceptance Tenant','remark':'confirmed for evidence'})
        request(tenant_name,'E3 read confirmed agreement','GET',f'/api/v1/lease-agreements/{agreement}',tt)
    # E10b final access check: bound admin can read own community; resident is isolated from admin endpoint
    request('admin1','E10b bound admin reads community','GET',f'/api/v1/communities/{cid}',at)
    # E10b request denial: resident cannot use admin endpoint
    request(owner_name,'E10b resident admin endpoint denied','GET','/api/v1/sys-users?page=1&size=10',rt,expected_http=403,expected_code=403)
    ids_summary={'communityId':cid,'buildingId':bid,'unitId':uid,'houseIds':[h1,h2,h3],'categoryId':cat,'resourceId':resource,'ownerResidentId':owner_id,'ownerApplicationId':app_owner,'workOrderId':wo,'noticeId':notice,'feedbackId':fb,'reservationId':reservation,'housingId':housing,'viewingAppointmentId':viewing,'tenantResidentId':tenant_id,'tenantApplicationId':tenant_app,'leaseId':lease_id if 'lease_id' in locals() else None,'targetDate':dt(target),'dayOfWeek':dow}
    out={'base':BASE,'runAt':RUN_AT,'jarSha256':'b87c8d232151d02d70dfb31e001d801c6abb4df0b45e0a2e6b9ca4ec6ed3e541','financialOperations':0,'ids':ids_summary,'trace':trace}
    path=pathlib.Path('docs/60_评估验收/evidence/acceptance-flow-20261020.json'); path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print('WROTE',path)
    print(json.dumps(ids_summary,ensure_ascii=False))

if __name__=='__main__':
    try: main()
    except Exception:
        traceback.print_exc();
        out={'base':BASE,'runAt':RUN_AT,'jarSha256':'b87c8d232151d02d70dfb31e001d801c6abb4df0b45e0a2e6b9ca4ec6ed3e541','financialOperations':0,'trace':trace,'error':traceback.format_exc()}
        p=pathlib.Path('output/acceptance-flow-partial.json'); p.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
        raise
