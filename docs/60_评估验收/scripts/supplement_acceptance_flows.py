"""Phase 60 isolated non-financial acceptance evidence; no normal-runtime writes."""
import datetime as dt, io, json, os, subprocess, time, traceback, urllib.request, urllib.error
from PIL import Image
from pathlib import Path

BASE='http://127.0.0.1:8087'
INPUT=Path('docs/60_评估验收/evidence/acceptance-flow-20261020.json')
OUT=Path('docs/60_评估验收/evidence/acceptance-flow-20261020')
OUT.mkdir(parents=True, exist_ok=True)
seed=json.loads(INPUT.read_text(encoding='utf-8'))
ids=seed['ids']; cid=ids['communityId']; cat=ids['categoryId']
trace=[]; checks=[]; actors={}

def sanitize(v):
    if isinstance(v,dict): return {k:sanitize(x) for k,x in v.items() if k.lower() not in {'token','password','initialpassword'}}
    if isinstance(v,list): return [sanitize(x) for x in v]
    return v

def api(actor, step, method, path, body=None, *, token=None, deny=False):
    if token is None: token=actors.get(actor,{}).get('token')
    h={'Content-Type':'application/json'}
    if token:h['Authorization']='Bearer '+token
    q=urllib.request.Request(BASE+path,method=method,headers=h,data=None if body is None else json.dumps(body,ensure_ascii=False).encode())
    try:
        with urllib.request.urlopen(q,timeout=20) as r: status=r.status; raw=r.read()
    except urllib.error.HTTPError as e: status=e.code; raw=e.read()
    j=json.loads(raw); code=j.get('code')
    trace.append({'actor':actor,'step':step,'method':method,'path':path,'http':status,'code':code,'message':j.get('message'),'data':sanitize(j.get('data'))})
    if deny: assert code != 200, (step,status,code)
    else: assert status==200 and code==200,(step,status,code,j.get('message'))
    return j.get('data')

def login(name,password,res=False):
    d=api('guest','login '+name,'POST','/api/v1/auth/'+('resident' if res else 'admin')+'/login',{'username':name,'password':password})
    actors[name]=d
    return d

def rows(v): return v if isinstance(v,list) else (v or {}).get('records',[])
def check(name, ok, detail=None):
    checks.append({'name':name,'passed':bool(ok),'detail':detail})
    assert ok,(name,detail)

def sql(sql_text):
    # This connection is fixed to the isolated database. Only evidence SELECTs are accepted.
    assert sql_text.lstrip().upper().startswith('SELECT ')
    env=dict(os.environ)
    for ln in Path('.env').read_text(encoding='utf-8-sig').splitlines():
        if not ln.strip() or ln.lstrip().startswith('#') or '=' not in ln:continue
        k,v=ln.removeprefix('export ').split('=',1);env.setdefault(k.strip(),v.strip().strip('"').strip("'"))
    env['MYSQL_PWD']=env['DB_PASSWORD']
    args=[r'C:\Program Files\MySQL\MySQL Server 9.6\bin\mysql.exe','-h','localhost','-u',env.get('DB_USERNAME','root'),'--batch','--skip-column-names','--default-character-set=utf8mb4','community_residence_dc_20261019','-e',sql_text]
    r=subprocess.run(args,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace')
    assert r.returncode==0,'isolated evidence SELECT failed'
    return [ln.split('\t') for ln in r.stdout.strip().splitlines() if ln]

def upload(actor, path='/api/v1/upload'):
    # Actual tiny synthetic PNG; standard multipart transport, no external file content.
    buffer=io.BytesIO(); Image.new('RGB',(16,16),(30,120,90)).save(buffer,format='PNG'); blob=buffer.getvalue()
    boundary='AcceptanceBoundary20261020'
    payload=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="acceptance.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+blob+f'\r\n--{boundary}--\r\n'.encode())
    req=urllib.request.Request(BASE+path,data=payload,headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Authorization':'Bearer '+actors[actor]['token']})
    with urllib.request.urlopen(req,timeout=20) as r:j=json.load(r)
    assert j['code']==200,j
    trace.append({'actor':actor,'step':'upload synthetic image','method':'POST','path':path,'http':200,'code':200,'data':sanitize(j['data'])})
    return j['data']

def main():
    login('superadmin','Admin@123456');login('admin1','Admin123456');login('staff1','Staff123456')
    suffix=next(x['actor'].split('acc_owner_')[1] for x in seed['trace'] if x['actor'].startswith('acc_owner_'))
    owner='acc_owner_'+suffix;login(owner,'Resident123456',True)
    short=str(int(time.time()*1000)%1000000)
    # R45/E10/E10b: a fresh account with exactly one bound community.
    an='acc_scope_'+short
    a=api('superadmin','create scoped administrator','POST','/api/v1/sys-users',{'username':an,'password':'Admin123456','realName':'Acceptance Scoped Admin','phone':'137'+short.zfill(8),'role':'ADMIN'})
    api('superadmin','bind only acceptance community','POST',f'/api/v1/sys-users/{a["id"]}/communities',{'communityId':cid})
    login(an,'Admin123456')
    own=api(an,'bound community structure visible','GET',f'/api/v1/communities/{cid}/buildings')
    check('R45 own community query includes its building',any(x['id']==ids['buildingId'] for x in rows(own)))
    outside=api(an,'out of scope community structure','GET','/api/v1/communities/1/buildings',deny=True)
    check('R45 out of scope query empty',not rows(outside))
    api(an,'out of scope detail denied','GET','/api/v1/houses/1',deny=True)
    api(an,'global user creation denied','POST','/api/v1/sys-users',{'username':'acc_denied_'+short,'password':'Admin123456','realName':'Denied','phone':'136'+short.zfill(8),'role':'STAFF'},deny=True)
    users=api('superadmin','global community list','GET','/api/v1/communities?page=1&size=100')
    check('R45 superadmin sees both communities',{1,cid} <= {x['id'] for x in rows(users)})
    # R65: four recommendation tiers and a same-tier workload comparison.
    staff=[]
    for idx,(cs,ks) in enumerate([([cid],[cat]),([cid],[cat]),([cid],[]),([],[cat]),([],[])],1):
        n='acc_staff_'+short+'_'+str(idx)
        d=api('superadmin','R65 create candidate '+str(idx),'POST','/api/v1/sys-users',{'username':n,'password':'Staff123456','realName':'Acceptance Staff '+str(idx),'phone':'135'+(short+str(idx)).zfill(8),'role':'STAFF'})
        api('superadmin','R65 save capability '+str(idx),'PUT',f'/api/v1/staff-capabilities/{d["id"]}',{'communityIds':cs,'categoryIds':ks})
        staff.append(d['id'])
    today=dt.date.today().isoformat()
    for shift in ['MORNING','AFTERNOON','EVENING','REST','FULL']:
        api('superadmin','R65 overwrite same-day shift '+shift,'PUT','/api/v1/staff-schedules/batch',{'communityId':cid,'staffIds':[staff[0]],'dates':[today],'shiftType':shift})
        ss=api('superadmin','R65 verify same-day shift '+shift,'GET',f'/api/v1/staff-schedules?communityId={cid}&startDate={today}&endDate={today}')
        ss=[x for x in rows(ss) if x['staffId']==staff[0]]
        check('R65 unique schedule '+shift,len(ss)==1 and ss[0]['shiftType']==shift,ss)
    api('superadmin','R65 clear both binding dimensions','PUT',f'/api/v1/staff-capabilities/{staff[4]}',{'communityIds':[cid],'categoryIds':[cat]})
    api('superadmin','R65 clear with empty arrays','PUT',f'/api/v1/staff-capabilities/{staff[4]}',{'communityIds':[],'categoryIds':[]})
    d=api('superadmin','R65 read cleared capability','GET',f'/api/v1/staff-capabilities/{staff[4]}')
    check('R65 empty arrays clear bindings',not d['communityIds'] and not d['categoryIds'])
    wo=api(owner,'E4 second work order with evidence images','POST','/api/v1/work-orders',{'categoryId':cat,'title':'Acceptance full work order '+short,'content':'image and group chain','contactPhone':'139'+suffix.zfill(8),'priority':'URGENT'})
    wid=wo['id'];upload(owner,f'/api/v1/work-orders/{wid}/attachments')
    intake=api('admin1','E4 intake and group creation','PATCH',f'/api/v1/work-orders/{wid}/intake',{'remark':'accepted'})
    group=intake['conversationId'];check('E4 group automatically created',bool(group))
    api('admin1','E4 assign staff and join group','PATCH',f'/api/v1/work-orders/{wid}/assign',{'assigneeId':staff[0],'remark':'dispatch'})
    opts=api('admin1','R65 recommendation query','GET',f'/api/v1/work-orders/assignable-staff?communityId={cid}&categoryId={cat}')
    selected={x['id']:x for x in rows(opts) if x['id'] in staff}
    check('R65 recommendation tiers',[selected[x]['recommendLevel'] for x in staff]==[1,1,2,3,4])
    ordered=[x['id'] for x in rows(opts)]
    check('R65 same tier lower workload first',ordered.index(staff[1])<ordered.index(staff[0]) and selected[staff[0]]['activeOrderCount']>selected[staff[1]]['activeOrderCount'])
    detail=api('admin1','R65 current dispatch metadata','GET',f'/api/v1/work-orders/{wid}')
    check('R65 dispatch fields filled',detail['dispatchFlag']=='URGENT' and detail['waitedMinutes']>=0 and detail['assigneeActiveOrders']>=1 and bool(detail['assigneeShiftLabel']))
    staffn='acc_staff_'+short+'_1';login(staffn,'Staff123456')
    api(staffn,'E4 service staff group communication','POST',f'/api/v1/conversations/{group}/messages',{'content':'acceptance group communication'})
    api(staffn,'R65 unassigned work order denied','GET',f'/api/v1/work-orders/{ids["workOrderId"]}',deny=True)
    for action in ['accept','process']:
        api(staffn,'E4 '+action,'PATCH',f'/api/v1/work-orders/{wid}/{action}',{'remark':'acceptance '+action})
    upload(staffn,f'/api/v1/work-orders/{wid}/attachments')
    upload(staffn,f'/api/v1/work-orders/{wid}/attachments')
    api(staffn,'E4 complete','PATCH',f'/api/v1/work-orders/{wid}/complete',{'remark':'acceptance completed with images'})
    api(owner,'E4 resident confirmation','PATCH',f'/api/v1/work-orders/{wid}/confirm',{'remark':'confirmed'})
    ev=api(owner,'E8 two-star evaluation','POST',f'/api/v1/work-orders/{wid}/evaluation',{'rating':2,'content':'acceptance followup required','isSatisfied':False})
    eid=ev['id']
    fu=api('admin1','E8 followup result','POST',f'/api/v1/evaluations/{eid}/followup',{'content':'Acceptance issue followed up and resolved'})
    recorded=api('admin1','E8 followup history','GET',f'/api/v1/evaluations/{eid}/followups')
    check('E8 followup trace saved',any(x['id']==fu['id'] for x in rows(recorded)))
    ag=api('admin1','E8 evaluation aggregation','GET',f'/api/v1/statistics/evaluations/aggregation?communityId={cid}')
    api('admin1','E4 final close','PATCH',f'/api/v1/work-orders/{wid}/close',{'remark':'final review completed'})
    g=api(owner,'E4 historical group remains readable','GET',f'/api/v1/conversations/{group}')
    check('E4 closed group retained',g['status']=='CLOSED')
    api(owner,'E4 closed group rejects message','POST',f'/api/v1/conversations/{group}/messages',{'content':'must be rejected'},deny=True)
    done=api(owner,'E4 final closed work order','GET',f'/api/v1/work-orders/{wid}')
    check('E4 work order closed',done['status']=='CLOSED')
    # E6 closed conversation is readonly.
    f=api(owner,'E6 closed feedback final state','GET',f'/api/v1/feedbacks/{ids["feedbackId"]}')
    check('E6 feedback closed',f['status']=='CLOSED')
    api(owner,'E6 post-close message denied','POST',f'/api/v1/feedbacks/{ids["feedbackId"]}/messages',{'content':'must be rejected'},deny=True)
    # Existing main-chain prematurely completed future resource reservation: confirmed requirement defect.
    rr=api(owner,'G-016 premature resource completion evidence','GET',f'/api/v1/resource-reservations/{ids["reservationId"]}')
    check('G-016 future reservation was accepted as completed',rr['status']=='COMPLETED' and rr['reserveDate']>today,{'reserveDate':rr['reserveDate'],'status':rr['status'],'today':today})
    # Read only database cross-checks (no state injection).
    db={}
    db['initializationLogCounts']=sql(f"SELECT target_type,COUNT(*) FROM sys_operation_log WHERE (target_type='COMMUNITY' AND target_id={cid}) OR (target_type='BUILDING' AND target_id={ids['buildingId']}) OR (target_type='UNIT' AND target_id={ids['unitId']}) OR (target_type='HOUSE' AND target_id IN ({','.join(map(str,ids['houseIds']))})) OR (target_type='PUBLIC_RESOURCE' AND target_id={ids['resourceId']}) GROUP BY target_type")
    db['authorizationDenials']=sql(f"SELECT operation_type,target_type,COUNT(*) FROM sys_operation_log WHERE operator_id={a['id']} GROUP BY operation_type,target_type")
    db['workorderNotificationSources']=sql(f"SELECT owner_type,source_type,source_id,title,COUNT(*) FROM notification WHERE source_type='WORK_ORDER' AND source_id={wid} GROUP BY owner_type,source_type,source_id,title")
    db['evaluationSummary']=sql(f"SELECT COUNT(*),SUM(rating),AVG(rating),SUM(is_satisfied) FROM work_order_evaluation WHERE community_id={cid}")
    db['groupParticipants']=sql(f"SELECT owner_type,COUNT(*) FROM conversation_participant WHERE conversation_id={group} GROUP BY owner_type")
    out={'recordedAt':dt.datetime.now().isoformat(),'base':BASE,'jarSha256':seed['jarSha256'],'normalDatabaseWrites':0,'financialOperations':0,'ids':{'scopedAdminId':a['id'],'scopedAdminUsername':an,'staffIds':staff,'testStaffUsername':staffn,'workOrderId':wid,'groupId':group,'evaluationId':eid,'followupId':fu['id']},'checks':checks,'trace':trace,'databaseReadOnly':db,'remaining':['E2 moveout segment','E3 first-payment-to-archive segment with user financial confirmation','E5 scheduled auto-offline','E6 realtime and E11 same-event websocket','E7 violation branch','N8 browser version matrix']}
    (OUT/'supplement.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'assertions':len(checks),'requests':len(trace),'ids':out['ids']},ensure_ascii=False))

if __name__=='__main__':
    try:main()
    except Exception:
        (OUT/'supplement-partial.json').write_text(json.dumps({'checks':checks,'trace':trace,'error':traceback.format_exc()},ensure_ascii=False,indent=2),encoding='utf-8')
        raise
