/* 批次bf：固定隔离库/端口，原设计非交易工程复核；合成账号/审计留存，社区夹具回收。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{database,base,frontend,sql,id,request}=require('./nontransaction-fixture-helpers.cjs')
const out='output/playwright', run=Date.now().toString().slice(-8), tag='BF'+run
const evidence={date:new Date().toISOString(),database,backend:base,frontend,transactionsExecuted:false,checks:[],observations:[],communities:[],cleanup:false}
function check(name,ok,detail){if(!ok)evidence.observations.push({failedExpectation:name,...detail});assert(ok,name);evidence.checks.push({name,passed:true,...(detail?{detail}:{})})}
const auth={};const fixture={tag}
function financialCounts(){return {payments:Number(sql('SELECT COUNT(*) FROM lease_payment')[0][0]),refunds:Number(sql('SELECT COUNT(*) FROM lease_deposit_refund')[0][0])}}
async function deny(name,p,method,data,token,status){const r=await request(p,method,data,token,true);check(name,status?r.status===status:r.code!==200,{http:r.status,code:r.code});return r}
async function makeUser(role,n){const row=await request('/sys-users','POST',{username:'bf_'+role.toLowerCase()+'_'+run+n,password:'Test123456',realName:tag+role+n,phone:'138'+run.slice(0,7)+n,role},auth.super.token);const session=await request('/auth/admin/login','POST',{username:row.username,password:'Test123456'});return {row,...session}}
async function register(n){const username='bf_res_'+run+n;await request('/auth/resident/register','POST',{username,password:'Test123456',realName:tag+'居民'+n,phone:'139'+run.slice(0,7)+n});return request('/auth/resident/login','POST',{username,password:'Test123456'})}
function tomorrow(offset=1){const now=new Date();const date=new Date(Date.UTC(Number(new Intl.DateTimeFormat('en',{timeZone:'Asia/Shanghai',year:'numeric'}).format(now)),Number(new Intl.DateTimeFormat('en',{timeZone:'Asia/Shanghai',month:'numeric'}).format(now))-1,Number(new Intl.DateTimeFormat('en',{timeZone:'Asia/Shanghai',day:'numeric'}).format(now))+offset));return {date:date.toISOString().slice(0,10),week:date.getUTCDay()||7}}
;(async()=>{
 try{
  auth.super=await request('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'})
  check('隔离数据库身份符合门禁',sql('SELECT DATABASE()')[0][0]===database)
  evidence.financialBefore=financialCounts()
  check('支付渠道全停用',(await request('/payment/channels','GET',undefined,auth.super.token)).every(c=>!c.enabled))
  for(const name of ['A','B']){const c=await request('/communities','POST',{name:tag+name,address:'独立非交易测试'},auth.super.token);fixture[name]=c;evidence.communities.push(c.id);await request('/structures/batch-generate','POST',{communityId:c.id,buildingNamePrefix:tag,buildingStartNo:1,buildingEndNo:1,buildingNameSuffix:name,unitCountPerBuilding:1,floorsPerUnit:3,housesPerFloor:2,area:60,dryRun:false},auth.super.token);const b=(await request('/communities/'+c.id+'/buildings?size=100','GET',undefined,auth.super.token)).records[0];const u=(await request('/buildings/'+b.id+'/units?size=100','GET',undefined,auth.super.token)).records[0];fixture['houses'+name]=(await request('/units/'+u.id+'/houses?size=100','GET',undefined,auth.super.token)).records;}
  auth.adminA=await makeUser('ADMIN',1);auth.adminB=await makeUser('ADMIN',2);auth.staff=await makeUser('STAFF',3);auth.otherStaff=await makeUser('STAFF',4)
  await request('/sys-users/'+auth.adminA.user.id+'/communities','POST',{communityId:fixture.A.id},auth.super.token);await request('/sys-users/'+auth.adminB.user.id+'/communities','POST',{communityId:fixture.B.id},auth.super.token)
  for(const role of ['adminA','adminB'])await deny(role+'绑定变更即时吊销旧令牌','/notifications','GET',undefined,auth[role].token,401)
  for(const role of ['adminA','adminB'])auth[role]={...auth[role],...await request('/auth/admin/login','POST',{username:auth[role].user.username,password:'Test123456'})}
  for(const role of ['adminA','adminB'])check(role+'变更后立即重新登录的新令牌可用',(await request('/notifications','GET',undefined,auth[role].token,true)).code===200)
  const oldStaffToken=auth.otherStaff.token
  await request('/sys-users/'+auth.otherStaff.user.id+'/status','PATCH',{status:'FROZEN',reason:tag+'冻结回归'},auth.super.token)
  await deny('系统人员冻结即时吊销旧令牌','/notifications','GET',undefined,oldStaffToken,401)
  check('冻结账号重新登录被5201拒绝',(await request('/auth/admin/login','POST',{username:auth.otherStaff.user.username,password:'Test123456'},undefined,true)).code===5201)
  await request('/sys-users/'+auth.otherStaff.user.id+'/status','PATCH',{status:'ACTIVE',reason:tag+'恢复夹具'},auth.super.token)
  auth.otherStaff={...auth.otherStaff,...await request('/auth/admin/login','POST',{username:auth.otherStaff.user.username,password:'Test123456'})}
  check('解冻后新登录立即可用',(await request('/notifications','GET',undefined,auth.otherStaff.token,true)).code===200)
  auth.resident=await register(5);auth.stranger=await register(6)
  const bad=await request('/auth/admin/login','POST',{username:auth.adminA.user.username,password:'incorrect'},undefined,true), missing=await request('/auth/admin/login','POST',{username:'nonexistent_'+run,password:'incorrect'},undefined,true)
  check('错误密码与不存在账号对外提示一致',bad.code!==200&&bad.code===missing.code&&bad.message===missing.message)
  await deny('伪造JWT被401拒绝','/notifications','GET',undefined,'invalid.jwt.signature',401)
  for(const [role,session] of Object.entries({anonymous:null,adminA:auth.adminA,staff:auth.staff,resident:auth.resident})){
   const expected=role==='anonymous'?401:403
   await deny(role+'不能新增社区','/communities','POST',{name:tag+'非法',address:'不可写'},session?.token,expected)
   await deny(role+'不能更改全局注册开关','/configs/registration.enabled','PUT',{value:'false'},session?.token,expected)
   await deny(role+'不能新增系统账号','/sys-users','POST',{username:'invalid_'+run,password:'Test123456',realName:'非法创建',phone:'137'+run,role:'SUPER_ADMIN'},session?.token,expected)
  }
  await deny('跨社区管理员不能查看房屋','/houses/'+fixture.housesA[0].id,'GET',undefined,auth.adminB.token)
  await deny('跨社区管理员不能修改房屋','/houses/'+fixture.housesA[0].id,'PUT',{unitId:fixture.housesA[0].unitId,houseNumber:'not-written',floor:1,area:60,status:'VACANT'},auth.adminB.token)
  const residentId=id(auth.resident.user.id), otherId=id(auth.stranger.user.id), communityId=id(fixture.A.id), house0=id(fixture.housesA[0].id), house1=id(fixture.housesA[1].id)
  const relation0=await request('/residence-relations','POST',{residentId,houseId:house0,relationType:'TENANT',moveInDate:tomorrow(-3).date},auth.adminA.token)
  const relation1=await request('/residence-relations','POST',{residentId,houseId:house1,relationType:'TENANT',moveInDate:tomorrow(-3).date},auth.adminA.token)
  fixture.relation0=relation0;fixture.relation1=relation1
  const paged=await request('/residents/'+residentId+'/residences?size=1','GET',undefined,auth.adminA.token)
  check('管理员居住关系分页保留授权范围总数',paged.total===2&&paged.records.length===1,{total:paged.total,records:paged.records.length})
  sql(`INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status,agreement_status,remark) VALUES(${residentId},${communityId},${house0},'2026-10-01','2027-10-01',1000,0,'ACTIVE','SIGNED','${tag}ACTIVE'),(${residentId},${communityId},${house0},'2025-01-01','2025-02-01',1000,0,'ARCHIVED','SIGNED','${tag}HISTORY')`)
  const leaseId=Number(sql(`SELECT id FROM lease_record WHERE remark='${tag}ACTIVE'`)[0][0]), historyId=Number(sql(`SELECT id FROM lease_record WHERE remark='${tag}HISTORY'`)[0][0]);fixture.leaseId=leaseId
  sql(`INSERT INTO residence_relation(resident_id,community_id,house_id,relation_type,move_in_date,is_primary) VALUES(${otherId},${communityId},${house1},'COHABITANT','2026-10-01',0)`)
  await deny('跨社区不能迁出居住关系','/residence-relations/'+relation0.id+'/move-out','PATCH',{moveOutDate:tomorrow(0).date},auth.adminB.token)
  await deny('居民不能操作管理员迁出端点','/residence-relations/'+relation0.id+'/move-out','PATCH',{moveOutDate:tomorrow(0).date},auth.resident.token,403)
  await deny('其他居民不能读取本人租约','/leases/'+leaseId,'GET',undefined,auth.stranger.token,403)
  await request('/residence-relations/'+relation0.id+'/move-out','PATCH',{moveOutDate:tomorrow(0).date,reason:tag+'迁出'},auth.adminA.token)
  check('DEF-146管理与居民租约同步已迁出',(await request('/leases/'+leaseId,'GET',undefined,auth.resident.token)).status==='MOVED_OUT'&&(await request('/leases/'+leaseId,'GET',undefined,auth.adminA.token)).status==='MOVED_OUT')
  check('DEF-146历史租约保持归档',(await request('/leases/'+historyId,'GET',undefined,auth.resident.token)).status==='ARCHIVED')
  check('DEF-146无其他同住者房屋回空置',(await request('/houses/'+house0,'GET',undefined,auth.adminA.token)).status==='VACANT')
  check('DEF-146居民关系历史与管理端一致',(await request('/residents/'+residentId+'/residences?status=MOVED_OUT','GET',undefined,auth.resident.token)).records.some(r=>r.id===relation0.id))
  const again=await deny('已迁出关系重复操作被状态门禁拒绝','/residence-relations/'+relation0.id+'/move-out','PATCH',{moveOutDate:tomorrow(0).date},auth.adminA.token);check('重复迁出使用现行5207',again.code===5207)
  await request('/residence-relations/'+relation1.id+'/move-out','PATCH',{moveOutDate:tomorrow(0).date},auth.adminA.token)
  check('DEF-146其他同住者未迁出时房屋保持入住',(await request('/houses/'+house1,'GET',undefined,auth.adminA.token)).status==='OCCUPIED')
  const categories=[]
  for(const [name,parentId,isActive] of [['启用父',null,1],['停用父',null,0]])categories.push(await request('/service-categories','POST',{communityId,name:tag+name,parentId,isActive},auth.adminA.token))
  const activeChild=await request('/service-categories','POST',{communityId,name:tag+'启用子',parentId:categories[0].id,isActive:1},auth.adminA.token)
  const inactiveChild=await request('/service-categories','POST',{communityId,name:tag+'停用子',parentId:categories[0].id,isActive:0},auth.adminA.token)
  const orphan=await request('/service-categories','POST',{communityId,name:tag+'停用父启用子',parentId:categories[1].id,isActive:1},auth.adminA.token)
  const flatten=rows=>rows.flatMap(r=>[r,...flatten(r.children||[])])
  const publicIds=flatten(await request('/communities/'+communityId+'/service-categories')).map(r=>r.id), allIds=flatten(await request('/communities/'+communityId+'/service-categories/all','GET',undefined,auth.adminA.token)).map(r=>r.id)
  check('DEF-145公开类别树排除停用父/子及停用父的子树',publicIds.includes(activeChild.id)&&![categories[1].id,inactiveChild.id,orphan.id].some(i=>publicIds.includes(i)))
  check('DEF-145管理树保留停用类别供维护',[activeChild.id,inactiveChild.id,orphan.id].every(i=>allIds.includes(i)))
  fixture.categoryId=activeChild.id
  const order=await request('/work-orders','POST',{categoryId:activeChild.id,title:tag+'权限工单',content:'独立非交易权限夹具',contactPhone:'139'+run.slice(0,7)+'6',priority:'NORMAL'},auth.stranger.token);fixture.workOrderId=order.id
  await deny('其他居民不能读取他人工单','/work-orders/'+order.id,'GET',undefined,auth.resident.token,403)
  await deny('跨社区管理员不能读取工单','/work-orders/'+order.id,'GET',undefined,auth.adminB.token)
  await request('/work-orders/'+order.id+'/assign','PATCH',{assigneeId:auth.staff.user.id,remark:tag+'派单'},auth.adminA.token)
  check('被派服务人员能读取本人工单',(await request('/work-orders/'+order.id,'GET',undefined,auth.staff.token)).id===order.id)
  await deny('未被派人员不能读取工单','/work-orders/'+order.id,'GET',undefined,auth.otherStaff.token,403)
  await deny('跨社区管理员不能读取居民详情','/residents/'+otherId,'GET',undefined,auth.adminB.token)
  check('真实工单派单触发服务人员通知',(await request('/notifications?size=100','GET',undefined,auth.staff.token)).records.some(n=>n.sourceType==='WORK_ORDER'&&n.sourceId===order.id))
  const crossCategories=await request('/communities/'+communityId+'/service-categories/all','GET',undefined,auth.adminB.token,true)
  check('跨社区类别管理树被拒或授权过滤为空',[403,404].includes(crossCategories.status)||(crossCategories.code===200&&Array.isArray(crossCategories.data)&&crossCategories.data.length===0))
  const form=new FormData();form.append('file',new Blob(['社区ID,姓名,手机号,证件号\n'+communityId+',重复合成居民,139'+run.slice(0,7)+'5,\n'+communityId+',重复合成居民,139'+run.slice(0,7)+'5,\n'],{type:'text/csv'}),'bf.csv')
  const imported=await fetch(base+'/residents/import',{method:'POST',headers:{Authorization:'Bearer '+auth.adminA.token},body:form}).then(r=>r.json())
  check('DEF-142现行CSV重复手机号两行零成功',imported.code===200&&imported.data.success===0&&imported.data.fail===2)
  evidence.observations.push({item:'DEF-142',scope:'现行CSV入口已执行；历史Excel文件/入口缺少原始场景，不新增Excel功能或将旧现场判通过'})
  const target=tomorrow(1);fixture.appointmentDate=target.date
  fixture.housing=await request('/housings','POST',{houseId:fixture.housesA[2].id,title:tag+'看房A',monthlyRent:1000,deposit:0},auth.adminA.token)
  const housing2=await request('/housings','POST',{houseId:fixture.housesA[3].id,title:tag+'看房B',monthlyRent:1000,deposit:0},auth.adminA.token)
  for(const housing of [fixture.housing,housing2])await request('/housings/'+housing.id+'/timeslots','POST',{dayOfWeek:target.week,startTime:'09:00',endTime:'14:00',isAvailable:1},auth.adminA.token)
  check('游客可读取房源与可预约时段',(await request('/housings/'+fixture.housing.id)).id===fixture.housing.id&&Array.isArray(await request('/housings/'+fixture.housing.id+'/available-slots?startDate='+target.date)))
  const payload={housingId:fixture.housing.id,appointmentDate:target.date,startTime:'09:00',endTime:'10:00'}
  await deny('游客不能创建看房预约','/viewing-appointments','POST',payload,undefined,401)
  for(const [name,data] of [['过去日期',{...payload,appointmentDate:tomorrow(-1).date}],['反向时段',{...payload,startTime:'11:00',endTime:'10:00'}],['时段外',{...payload,startTime:'08:00',endTime:'09:00'}]])await deny('看房拒绝'+name,'/viewing-appointments','POST',data,auth.resident.token)
  fixture.appointment=await request('/viewing-appointments','POST',payload,auth.resident.token)
  const appointmentId=fixture.appointment.id
  await deny('同房源重叠预约被拒','/viewing-appointments','POST',payload,auth.stranger.token)
  await deny('同居民跨房源重叠预约被拒','/viewing-appointments','POST',{...payload,housingId:housing2.id},auth.resident.token)
  const adjacent=await request('/viewing-appointments','POST',{...payload,startTime:'10:00',endTime:'11:00'},auth.resident.token)
  check('首尾相接看房时段允许预约',adjacent.status==='TO_CONFIRM')
  for(const [role,token,status] of [['其他居民',auth.stranger.token,403],['未分配服务人员',auth.otherStaff.token,403],['跨社区管理员',auth.adminB.token,null]])await deny(role+'不能读取预约详情','/viewing-appointments/'+appointmentId,'GET',undefined,token,status)
  await request('/viewing-appointments/'+appointmentId+'/assign','PATCH',{assigneeId:auth.staff.user.id},auth.adminA.token)
  check('已分配人员能读本人预约',(await request('/viewing-appointments/'+appointmentId,'GET',undefined,auth.staff.token)).assigneeId===auth.staff.user.id)
  await deny('服务人员不能代居民取消预约','/viewing-appointments/'+appointmentId+'/cancel','PATCH',{reason:'不应执行'},auth.staff.token,403)
  await deny('其他居民不能取消本人预约','/viewing-appointments/'+appointmentId+'/cancel','PATCH',{reason:'不应执行'},auth.stranger.token,403)
  await request('/viewing-appointments/'+appointmentId+'/confirm','PATCH',{reason:tag+'确认'},auth.adminA.token)
  const detail=await request('/viewing-appointments/'+appointmentId,'GET',undefined,auth.resident.token);fixture.appointment=detail
  const conversationId=detail.conversationId;fixture.conversationId=conversationId
  if(conversationId){await request('/conversations/'+conversationId+'/messages','POST',{content:tag+'居民沟通'},auth.resident.token);check('带看人员能读群消息',(await request('/conversations/'+conversationId+'/messages','GET',undefined,auth.staff.token)).some(m=>m.content===tag+'居民沟通'));await deny('其他居民不能读看房群','/conversations/'+conversationId,'GET',undefined,auth.stranger.token,403);await deny('未分配人员不能读看房群','/conversations/'+conversationId,'GET',undefined,auth.otherStaff.token,403)}
  const residentNotifications=await request('/notifications?size=100','GET',undefined,auth.resident.token)
  check('看房确认真实触发居民通知',residentNotifications.records.some(n=>n.sourceId===appointmentId))
  const ownNotice=residentNotifications.records.find(n=>n.sourceId===appointmentId)
  await deny('其他居民不能将本人通知标记已读','/notifications/'+ownNotice.id+'/read','PATCH',{},auth.stranger.token)
  await request('/notifications/'+ownNotice.id+'/read','PATCH',{},auth.resident.token)
  check('本人已读持久化',(await request('/notifications?size=100','GET',undefined,auth.resident.token)).records.find(n=>n.id===ownNotice.id).isRead===1)
  const pulled=await request('/notifications/pull?sinceSeq=0','GET',undefined,auth.resident.token)
  check('离线增量补拉不丢且不重复',pulled.some(n=>n.id===ownNotice.id)&&new Set(pulled.map(n=>n.id)).size===pulled.length)
  check('服务人员收到分配通知',(await request('/notifications?size=100','GET',undefined,auth.staff.token)).records.some(n=>n.sourceId===appointmentId))
  await request('/viewing-appointments/'+adjacent.id+'/cancel','PATCH',{reason:tag+'取消'},auth.resident.token)
  check('居民本人取消状态持久化',(await request('/viewing-appointments/'+adjacent.id,'GET',undefined,auth.resident.token)).status==='CANCELLED')
  await deny('已取消预约不能再确认','/viewing-appointments/'+adjacent.id+'/confirm','PATCH',{reason:'不应执行'},auth.adminA.token)
  await request('/viewing-appointments/'+appointmentId+'/complete','PATCH',{reason:tag+'完成'},auth.adminA.token)
  check('看房完成终态持久化',(await request('/viewing-appointments/'+appointmentId,'GET',undefined,auth.staff.token)).status==='COMPLETED')
  await deny('已完成预约不能重复完成','/viewing-appointments/'+appointmentId+'/complete','PATCH',{reason:'不应执行'},auth.adminA.token)
  await request('/housings/'+housing2.id+'/status','PATCH',{status:'OFFLINE'},auth.adminA.token)
  check('房源下架从公开列表移除',!(await request('/housings?communityId='+communityId+'&size=100')).records.some(h=>h.id===housing2.id))
  await deny('下架房源不能创建新预约','/viewing-appointments','POST',{...payload,housingId:housing2.id,startTime:'12:00',endTime:'13:00'},auth.resident.token)
  const logs=await request('/operation-logs?size=100&module=RESIDENT','GET',undefined,auth.super.token)
  check('新操作日志采集安全业务ID',logs.records.some(l=>l.targetId===relation0.id&&l.requestParams&&JSON.parse(l.requestParams).residentId===residentId))
  check('迁出状态流转有社区归属与安全日期审计',logs.records.some(l=>l.targetId===relation0.id&&l.action==='STATUS'&&l.communityId===communityId&&l.requestParams&&JSON.parse(l.requestParams).id===relation0.id&&JSON.parse(l.requestParams).moveOutDate===tomorrow(0).date))
  const sysLogs=await request('/operation-logs?size=100','GET',undefined,auth.super.token)
  check('新日志不含合成账号密码或请求凭据',sysLogs.records.filter(l=>l.targetId===auth.adminA.user.id||l.targetId===auth.staff.user.id).every(l=>!JSON.stringify(l).includes('Test123456')))
  const oldResidentToken=auth.stranger.token
  await request('/residents/'+otherId+'/status','PATCH',{status:'FROZEN',reason:tag+'居民吊销回归'},auth.adminA.token)
  await deny('居民冻结即时吊销旧令牌','/notifications','GET',undefined,oldResidentToken,401)
  check('被冻结居民重新登录被5201拒绝',(await request('/auth/resident/login','POST',{username:auth.stranger.user.username,password:'Test123456'},undefined,true)).code===5201)
  await request('/residents/'+otherId+'/status','PATCH',{status:'ACTIVE',reason:tag+'恢复夹具'},auth.adminA.token)
  auth.stranger=await request('/auth/resident/login','POST',{username:auth.stranger.user.username,password:'Test123456'})
  check('居民解冻后新登录立即可用',(await request('/notifications','GET',undefined,auth.stranger.token,true)).code===200)
  const token=auth.adminB.token;await request('/auth/admin/logout','POST',{},token)
  await deny('登出后原令牌被401拒绝','/notifications','GET',undefined,token,401)
  auth.adminB={...auth.adminB,...await request('/auth/admin/login','POST',{username:auth.adminB.user.username,password:'Test123456'})}
  if(!process.argv.includes('--api-only'))await require('./nontransaction-engineering-browser.cjs').run({auth,fixture,evidence,check,request})
 }catch(error){evidence.failure=error.message;console.error(error.message);process.exitCode=1}
 finally{
  if(auth.super)for(const communityId of evidence.communities)try{await request('/communities/'+communityId,'DELETE',undefined,auth.super.token)}catch(e){evidence.cleanupFailure=e.message;process.exitCode=1}
  evidence.cleanup=!evidence.cleanupFailure&&evidence.communities.length>0
  evidence.financialAfter=financialCounts();if(evidence.financialBefore){const same=JSON.stringify(evidence.financialBefore)===JSON.stringify(evidence.financialAfter);evidence.checks.push({name:'隔离回归前后支付与退款记录数量不变',passed:same});if(!same){evidence.failure='unexpected-financial-row-change';process.exitCode=1}}
  fs.mkdirSync(out,{recursive:true});const file=out+'/nontransaction-engineering-'+(process.argv.includes('--api-only')?'api':'full')+'-20261011.json';fs.writeFileSync(file,JSON.stringify(evidence,null,2));console.log(JSON.stringify({checks:evidence.checks.length,observations:evidence.observations.length,cleanup:evidence.cleanup,failure:evidence.failure??null,evidence:file}))
 }
})()
