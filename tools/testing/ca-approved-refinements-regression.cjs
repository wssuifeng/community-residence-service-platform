/* CA仅隔离库：周模板/单套挂牌/正文修订/租期申请及离线报价。绝不创建渠道交易或查单。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const db='community_residence_ca_20261016',base='http://127.0.0.1:8087/api/v1',checks=[],run=String(Date.now()),env={...process.env};
const out='docs/50_系统测试/evidence/ca-approved-refinements-20261016';fs.mkdirSync(out,{recursive:true});
for(const line of fs.readFileSync('.env','utf8').split(/\r?\n/)){const m=line.match(/^\s*(DB_PASSWORD|DB_USERNAME)\s*=\s*(.*?)\s*$/);if(m)env[m[1]]=m[2].replace(/^(["'])(.*)\1$/,'$2')}
function sql(q){const p=spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['-h127.0.0.1','-u'+(env.DB_USERNAME||'root'),'-D'+db,'-N','-B','--default-character-set=utf8mb4','-e',q],{env:{...env,MYSQL_PWD:env.DB_PASSWORD},windowsHide:true,encoding:'utf8'});assert.equal(p.status,0,'Isolated SQL failed; sensitive details withheld');return p.stdout.trim()}
let spec;
async function request(p,method='GET',body,token,allowError=false){
 if(!spec)spec=await fetch(base.replace('/api/v1','')+'/v3/api-docs').then(r=>r.json());
 assert(!/first-payments|rent-payments|renew-payments|payment\/orders|deposit-refund|\/payments(?:\?|\/|$)/.test(p),'Trading endpoint prohibited');
 const full='/api/v1'+p.split('?')[0],entry=spec&&Object.entries(spec.paths).find(([pattern])=>new RegExp('^'+pattern.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(full));assert(!spec||entry?.[1]?.[method.toLowerCase()],method+' '+full+' absent from running OpenAPI');
 const headers=token?{Authorization:'Bearer '+token}:{};if(body!==undefined&&!(body instanceof FormData))headers['Content-Type']='application/json';
 const response=await fetch(base+p,{method,headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});const result=await response.json();
 if(!allowError)assert(response.ok&&result.code===200,method+' '+p+' failed '+response.status+'/'+result.code+' '+result.message);
 return allowError?{http:response.status,...result}:result.data;
}
function check(name,value){assert(value,name);checks.push({name,passed:true})}
const id=v=>Number(v.id??v.residentId??v);
const image=()=>{const f=new FormData();f.set('file',new Blob([fs.readFileSync('tools/testing/fixtures/ca-test.png')],{type:'image/png'}),'ca-photo.png');f.set('type','IMAGE');return f};
function datePlus(date,months,days=0){const [y,m,d]=date.split('-').map(Number),t=new Date(Date.UTC(y,m-1+months,1));t.setUTCDate(Math.min(d,new Date(Date.UTC(t.getUTCFullYear(),t.getUTCMonth()+1,0)).getUTCDate()));t.setUTCDate(t.getUTCDate()+days);return t.toISOString().slice(0,10)}
async function main(){
 const owner=spawnSync('powershell',['-NoProfile','-Command',"$n=Get-NetTCPConnection -LocalPort 8087 -State Listen | Select-Object -First 1;(Get-CimInstance Win32_Process -Filter ('ProcessId='+$n.OwningProcess)).CommandLine"],{windowsHide:true,encoding:'utf8'});assert(owner.stdout.includes('--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+db+'?'),'Refuse API writes: backend not dedicated CA database');
 spec=await fetch(base.replace('/api/v1','')+'/v3/api-docs').then(r=>r.json());
 const superSession=await request('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'}),root=superSession.token;
 check('源码V1～V42在全新专用库初始化',Number(sql('SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1'))===42);
 const community=await request('/communities','POST',{name:'CA隔离社区'+run.slice(-5),address:'仅隔离验证，不属于正常库',responsibleAdminId:2,businessCode:'CA'+run},root);
 const adminSession=await request('/auth/admin/login','POST',{username:'admin1',password:'Admin123456'}),admin=adminSession.token;
 const building=await request('/buildings','POST',{communityId:id(community),name:'CA一栋',floors:6,businessCode:'1'},root),unit=await request('/units','POST',{buildingId:id(building),name:'CA一单元',businessCode:'1'},root);
 async function house(number){return request('/houses','POST',{unitId:id(unit),houseNumber:number,floor:1,area:80,layout:'2室1厅1卫'},root)}
 const h1=await house('101'),h2=await house('102'),h3=await house('103');
 const housing=await request('/housings','POST',{houseId:id(h1),title:'CA单套挂牌',monthlyRent:3600,deposit:10000,rentType:'RENT',description:'隔离挂牌',createTimeslots:true,timeslotMode:'DEFAULT'},admin);
 const slots=await request(`/housings/${id(housing)}/timeslots?page=1&size=100`,'GET',undefined,admin);
 check('单套挂牌与10个工作日看房时段同次创建',(slots.records??slots).length===10);
 const invalid=await request('/housings','POST',{houseId:id(h3),title:'非法时段不得留半成品',monthlyRent:3600,rentType:'RENT',createTimeslots:true,timeslotMode:'CUSTOM',timeslots:[]},root,true);
 check('单套非法自定义时段零挂牌写入',invalid.code!==200&&sql('SELECT COUNT(*) FROM housing WHERE house_id='+id(h3))==='0');
 const resource=await request('/resources','POST',{communityId:id(community),name:'CA多星期会议室',type:'MEETING_ROOM',capacity:100,slotUnit:30},root);
 const batch={daysOfWeek:[1,2,3,4,5],startTime:'09:00',endTime:'12:00',isAvailable:1};
 let result=await request(`/resources/${id(resource)}/timeslots/batch`,'POST',batch,admin);check('工作日五天共同时间一次新增',result.createdDays.length===5&&result.skippedDays.length===0);
 result=await request(`/resources/${id(resource)}/timeslots/batch`,'POST',batch,admin);check('重复批次精确跳过',result.createdDays.length===0&&result.skippedDays.length===5);
 sql('UPDATE resource_timeslot SET is_available=0 WHERE resource_id='+id(resource)+' AND day_of_week=1');await request(`/resources/${id(resource)}/timeslots/batch`,'POST',batch,admin);check('相同但已停用的周模板不会被重启',sql('SELECT is_available FROM resource_timeslot WHERE resource_id='+id(resource)+' AND day_of_week=1')==='0');
 const before=sql('SELECT COUNT(*) FROM resource_timeslot WHERE resource_id='+id(resource));result=await request(`/resources/${id(resource)}/timeslots/batch`,'POST',{...batch,daysOfWeek:[6,1],startTime:'11:00',endTime:'13:00'},admin,true);check('部分星期冲突整批零新增',result.code!==200&&sql('SELECT COUNT(*) FROM resource_timeslot WHERE resource_id='+id(resource))===before);
 const concurrent=await Promise.all([1,2].map(()=>request(`/resources/${id(resource)}/timeslots/batch`,'POST',{...batch,daysOfWeek:[7],startTime:'16:00',endTime:'17:00'},admin)));check('并发同资源同模板只创建一次',concurrent.reduce((n,r)=>n+r.createdDays.length,0)===1&&sql('SELECT COUNT(*) FROM resource_timeslot WHERE resource_id='+id(resource)+' AND day_of_week=7')==='1');
 const uploaded=await request('/upload','POST',image(),root);
 const body=text=>JSON.stringify({type:'doc',content:[{type:'heading',attrs:{level:2,textAlign:'center'},content:[{type:'text',text,marks:[{type:'bold'}]}]},{type:'image',attrs:{src:uploaded.fileUrl,alt:'CA真实上传图',widthPercent:50,textAlign:'center'}}]});
 const publishTime=new Date().toISOString().slice(0,19),notice=await request('/notices','POST',{title:'CA编辑页面公告',content:'摘要由后端派生',bodyJson:body('初版正文'),publishTime,targets:[]},root);
 await request(`/notices/${id(notice)}/publish`,'PATCH',{publishTime},root);
 const initial=await request('/notices/'+id(notice),'GET',undefined,root);
 const rev=await Promise.all([request(`/notices/${id(notice)}/revisions`,'POST',{expectedVersion:1,title:'CA新版公告',bodyJson:body('新版正文')},root,true),request(`/notices/${id(notice)}/revisions`,'POST',{expectedVersion:1,title:'不应覆盖',bodyJson:body('过期编辑窗口')},root,true)]);
 check('两窗口修订同版本只有一份生效',rev.filter(r=>r.code===200).length===1);
 const revised=await request('/notices/'+id(notice),'GET',undefined,root);check('发布后修订不下线、不改时效',revised.status==='PUBLISHED'&&revised.contentVersion===2&&revised.publishTime===initial.publishTime&&revised.endTime===initial.endTime);
 const history=await request(`/notices/${id(notice)}/revisions`,'GET',undefined,root);check('旧新版本均保留，图片与居中结构保存',history.length===2&&revised.bodyJson.includes('center')&&revised.bodyJson.includes(uploaded.fileUrl));
 check('非管辖管理员不能修订广播',(await request(`/notices/${id(notice)}/revisions`,'POST',{expectedVersion:2,title:'越权',bodyJson:body('越权')},admin,true)).code!==200);
 check('正文拒绝脚本或任意HTML节点',(await request(`/notices/${id(notice)}/revisions`,'POST',{expectedVersion:2,title:'非法',bodyJson:'{"type":"doc","content":[{"type":"script"}]}'},root,true)).code!==200);
 const resident=await request('/residents/admin-create','POST',{communityId:id(community),realName:'CA合成租客',phone:'139'+run.slice(-8),username:'ca合成租客'+run.slice(-5)},root);
 const residentSession=await request('/auth/resident/login','POST',{username:resident.username,password:resident.initialPassword}),residentToken=residentSession.token;
 await request(`/notices/${id(notice)}/view?version=1`,'POST',undefined,residentToken);await request(`/notices/${id(notice)}/view?version=2`,'POST',undefined,residentToken);const viewers=await request(`/notices/${id(notice)}/viewers`,'GET',undefined,root);check('同账号跨版本仍一人、所读版本分别留痕',viewers.total===1&&viewers.records[0].contentVersion===2&&sql('SELECT COUNT(*) FROM notice_version_view WHERE notice_id='+id(notice))==='2');
 const today=new Date().toLocaleDateString('sv-SE',{timeZone:'Asia/Shanghai'}),lease=await request('/leases','POST',{residentId:id(resident),houseId:id(h2),startDate:today,contractMonths:12,firstPaymentMonths:1,paymentCycleMonths:1,monthlyRent:3600,deposit:10000},root);
 // Offline lifecycle fixture only: no payment/settlement APIs or channels are called.
 sql(`UPDATE lease_record SET status='ACTIVE',agreement_status='SIGNED' WHERE id=${id(lease)};INSERT INTO lease_contract_term(lease_id,term_type,start_date,end_date,contract_months,first_payment_months,payment_cycle_months,monthly_rent,deposit,status,content_snapshot) VALUES(${id(lease)},'INITIAL','${today}','${datePlus(today,12,-1)}',12,1,1,3600,10000,'ACTIVE','CA synthetic lifecycle fixture; not channel payment');`);
 const termId=Number(sql('SELECT id FROM lease_contract_term WHERE lease_id='+id(lease)+' LIMIT 1'));
 sql(`INSERT INTO lease_payment(payment_no,lease_id,resident_id,payment_type,channel,months,amount,status,term_id,monthly_rent_snapshot,rent_amount,deposit_amount,period_start,period_end) VALUES('CA_OFFLINE_FIXTURE_FIRST_${run}',${id(lease)},${id(resident)},'FIRST','ALIPAY',1,13600,'SUCCESS',${termId},3600,3600,10000,'${today}','${datePlus(today,1,-1)}');`);
 const relationCount=sql('SELECT COUNT(*) FROM residence_relation WHERE lease_id='+id(lease)),paymentsCount=sql('SELECT COUNT(*) FROM lease_payment WHERE lease_id='+id(lease));
 let quote=await request(`/leases/${id(lease)}/rent-quote?purpose=RENT&months=11`,'GET',undefined,residentToken);check('12个月付1个月后可报价11个月，不收二次押金',quote.months===11&&quote.remainingMonths===11&&Number(quote.amount)===39600&&Number(quote.depositAmount)===0);
 check('上限以合同余月拒绝12个月',(await request(`/leases/${id(lease)}/rent-quote?purpose=RENT&months=12`,'GET',undefined,residentToken,true)).code!==200);
 const extension=await request(`/leases/${id(lease)}/extensions`,'POST',{months:3,reason:'CA延长方案回归'},residentToken);
 quote=await request(`/leases/${id(lease)}/rent-quote?purpose=RENT&months=11`,'GET',undefined,residentToken);check('待审申请不增加可付期限',quote.remainingMonths===11);
 await request(`/leases/${id(lease)}/extensions/${id(extension)}/review`,'POST',{approve:true},admin);await request(`/leases/${id(lease)}/extensions/${id(extension)}/review`,'POST',{approve:true},admin);
 quote=await request(`/leases/${id(lease)}/rent-quote?purpose=RENT&months=14`,'GET',undefined,residentToken);
 check('批准即延长且只延一次，余月14和跨段金额正确',quote.remainingMonths===14&&Number(quote.amount)===50400&&sql('SELECT COUNT(*) FROM lease_contract_term WHERE lease_id='+id(lease)+" AND term_type='EXTENSION'")==='1');
 check('审批和报价不创建订单、居住关系或追加押金',sql('SELECT COUNT(*) FROM residence_relation WHERE lease_id='+id(lease))===relationCount&&sql('SELECT COUNT(*) FROM lease_payment WHERE lease_id='+id(lease))===paymentsCount);
 const fixture={superSession,adminSession,residentSession,community,house:h1,spareHouse:h3,housing,resource,noticeId:id(notice),leaseId:id(lease),today,uploaded};fs.writeFileSync('output/runtime/ca-fixture-private.json',JSON.stringify(fixture));
 fs.writeFileSync(out+'/api.json',JSON.stringify({checkedAt:new Date().toISOString(),database:db,checks,passed:checks.length,externalTradingCalled:false,syntheticPaymentFixture:true,normalDatabaseWritten:false},null,2));console.log(JSON.stringify({isolatedApiPassed:checks.length,externalTradingCalled:false}));
}
if(require.main===module)main().catch(e=>{console.error(e.message);process.exitCode=1});
module.exports={request,sql,base};
