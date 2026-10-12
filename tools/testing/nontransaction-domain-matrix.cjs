/* 批次bi：固定隔离库，四域真实API权限矩阵；禁止支付/主动查单/退款。 */
const fs=require('node:fs'),assert=require('node:assert/strict')
const {database,base,frontend,sql,id,request}=require('./nontransaction-fixture-helpers.cjs')
const remaining=process.argv.includes('--remaining'),run=Date.now().toString().slice(-8),tag=(remaining?'BJ':'BI')+run,out='docs/50_系统测试/evidence/'+(remaining?'nontransaction-remaining-20261012':'nontransaction-matrix-20261012')
const evidence={date:new Date().toISOString(),database,backend:base,frontend,transactionsExecuted:false,checks:[],failures:[],communities:[],users:[],residents:[],cleanup:false}
const auth={},fixture={tag}
function check(name,ok,detail){const row={name,passed:!!ok,...(detail?{detail}:{})};evidence.checks.push(row);if(!ok)evidence.failures.push(row)}
async function denied(name,p,method,data,token,expected){const r=await request(p,method,data,token,true);check(name,expected?r.status===expected:r.status===403||r.status===404,{http:r.status,code:r.code});return r}
function finances(){return {payments:Number(sql('SELECT COUNT(*) FROM lease_payment')[0][0]),refunds:Number(sql('SELECT COUNT(*) FROM lease_deposit_refund')[0][0])}}
function tomorrow(offset=1){const date=new Date();date.setDate(date.getDate()+offset);const s=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(date);const d=new Date(s+'T12:00:00+08:00');return {date:s,week:d.getDay()||7}}
async function user(role,n){const username='bi_'+role.toLowerCase()+'_'+run+n;const row=await request('/sys-users','POST',{username,password:'Test123456',realName:tag+role+n,phone:'138'+run.slice(0,7)+n,role},auth.super.token);evidence.users.push(row.id);return request('/auth/admin/login','POST',{username,password:'Test123456'})}
async function resident(n,community,house){const row=await request('/residents/admin-create','POST',{communityId:community.id,realName:tag+'居民'+n,phone:'139'+run.slice(0,7)+n,username:'bi_res_'+run+n,houseId:house.id,relationType:'OWNER',moveInDate:tomorrow(-2).date},auth.super.token);evidence.residents.push(row.residentId);return request('/auth/resident/login','POST',{username:row.username,password:row.initialPassword})}
async function notification(name,session,source,id0){const rows=await request('/notifications?size=100','GET',undefined,session.token);check(name,rows.records.some(n=>n.sourceType===source&&n.sourceId===id0))}
;(async()=>{
 try{
  check('固定隔离库门禁',sql('SELECT DATABASE()')[0][0]===database)
  evidence.financialBefore=finances();auth.super=await request('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'})
  check('双支付渠道停用',(await request('/payment/channels','GET',undefined,auth.super.token)).every(c=>!c.enabled))
  for(const name of ['A','B']){
   const c=await request('/communities','POST',{name:tag+name,address:'隔离矩阵夹具'},auth.super.token);fixture[name]=c;evidence.communities.push(c.id)
   await request('/structures/batch-generate','POST',{communityId:c.id,buildingNamePrefix:tag,buildingStartNo:1,buildingEndNo:1,unitCountPerBuilding:1,floorsPerUnit:2,housesPerFloor:2,layout:'二室一厅一卫',area:60,dryRun:false},auth.super.token)
   const b=(await request('/communities/'+c.id+'/buildings?size=100','GET',undefined,auth.super.token)).records[0]
   const u=(await request('/buildings/'+b.id+'/units?size=100','GET',undefined,auth.super.token)).records[0]
   fixture['houses'+name]=(await request('/units/'+u.id+'/houses?size=100','GET',undefined,auth.super.token)).records
  }
  auth.adminA=await user('ADMIN',1);auth.adminB=await user('ADMIN',2);auth.staff=await user('STAFF',3);auth.otherStaff=await user('STAFF',4)
  for(const side of ['A','B']){await request('/sys-users/'+auth['admin'+side].user.id+'/communities','POST',{communityId:fixture[side].id},auth.super.token);auth['admin'+side]=await request('/auth/admin/login','POST',{username:auth['admin'+side].user.username,password:'Test123456'})}
  auth.resident=await resident(5,fixture.A,fixture.housesA[0]);auth.stranger=await resident(6,fixture.A,fixture.housesA[1]);auth.residentB=await resident(7,fixture.B,fixture.housesB[0])
  const now=new Date(Date.now()-60000).toISOString().slice(0,19)
  const notice=await request('/notices','POST',{title:tag+'社区定向公告',content:'隔离定向权限矩阵',publishTime:now,targets:[{targetType:'COMMUNITY',targetId:fixture.A.id}]},auth.adminA.token);fixture.noticeId=notice.id
  await denied('公告草稿居民不可读','/notices/'+notice.id,'GET',undefined,auth.resident.token)
  await denied('公告跨社区管理员不可读','/notices/'+notice.id,'GET',undefined,auth.adminB.token)
  await denied('公告居民不可发布','/notices/'+notice.id+'/publish','PATCH',{publishTime:now},auth.resident.token,403)
  await denied('公告跨社区管理员不可发布','/notices/'+notice.id+'/publish','PATCH',{publishTime:now},auth.adminB.token)
  await request('/notices/'+notice.id+'/publish','PATCH',{publishTime:now},auth.adminA.token)
  check('社区公告目标居民可读',(await request('/notices/'+notice.id,'GET',undefined,auth.resident.token)).id===notice.id)
  await denied('社区公告其他社区居民不可读','/notices/'+notice.id,'GET',undefined,auth.residentB.token)
  await denied('社区公告游客不可读','/notices/'+notice.id,'GET')
  await notification('定向公告触达本社区居民',auth.resident,'NOTICE',notice.id)
  const otherNotifications=await request('/notifications?size=100','GET',undefined,auth.residentB.token);check('公告不触达其他社区',!otherNotifications.records.some(n=>n.sourceType==='NOTICE'&&n.sourceId===notice.id))
  await request('/notices/'+notice.id+'/view','POST',undefined,auth.resident.token)
  await denied('他社区居民不可写公告阅读','/notices/'+notice.id+'/view','POST',undefined,auth.residentB.token)
  const viewers=await request('/notices/'+notice.id+'/viewers','GET',undefined,auth.adminA.token);check('公告阅读记录归本人',viewers.records.some(v=>v.residentId===auth.resident.user.id))
  await denied('他社区管理员不可读公告阅读明细','/notices/'+notice.id+'/viewers','GET',undefined,auth.adminB.token)
  const feedback=await request('/feedbacks','POST',{communityId:fixture.A.id,title:tag+'反馈',content:'隔离反馈矩阵',category:'INQUIRY'},auth.resident.token);fixture.feedbackId=feedback.id
  for(const [role,session] of [['其他居民',auth.stranger],['他社区管理员',auth.adminB],['服务人员',auth.staff]]){
   await denied('反馈详情拒绝'+role,'/feedbacks/'+feedback.id,'GET',undefined,session.token)
   await denied('反馈消息读取拒绝'+role,'/feedbacks/'+feedback.id+'/messages','GET',undefined,session.token)
   await denied('反馈消息写入拒绝'+role,'/feedbacks/'+feedback.id+'/messages','POST',{content:'越权消息'},session.token)
  }
  await request('/feedbacks/'+feedback.id+'/messages','POST',{content:tag+'管理员受理'},auth.adminA.token)
  check('反馈首次回复进入会话',(await request('/feedbacks/'+feedback.id,'GET',undefined,auth.resident.token)).status==='IN_SESSION')
  await request('/feedbacks/'+feedback.id+'/messages','POST',{content:tag+'本人回复'},auth.resident.token)
  const msgs=await request('/feedbacks/'+feedback.id+'/messages','GET',undefined,auth.resident.token);check('本人可读完整反馈会话',msgs.length===2)
  await denied('居民不能办结反馈','/feedbacks/'+feedback.id+'/close','PATCH',{remark:'越权'},auth.resident.token,403)
  await request('/feedbacks/'+feedback.id+'/close','PATCH',{remark:tag+'办结'},auth.adminA.token)
  await notification('反馈办结通知本人',auth.resident,'FEEDBACK',feedback.id)
  const closed=await request('/feedbacks/'+feedback.id+'/messages','POST',{content:'已关闭追加'},auth.resident.token,true);check('办结后不可追加会话',closed.code!==200&&closed.status<500,{http:closed.status,code:closed.code})
  check('办结历史消息仍可读',(await request('/feedbacks/'+feedback.id+'/messages','GET',undefined,auth.resident.token)).length===3)
  const resource=await request('/resources','POST',{communityId:fixture.A.id,name:tag+'会议室',type:'MEETING_ROOM',capacity:1,slotUnit:30,images:[]},auth.adminA.token);fixture.resourceId=resource.id
  const target=tomorrow();await request('/resources/'+resource.id+'/timeslots','POST',{dayOfWeek:target.week,startTime:'09:00',endTime:'12:00',isAvailable:1},auth.adminA.token)
  const reservationBody={resourceId:resource.id,reserveDate:target.date,startTime:'09:00',endTime:'09:30',purpose:'隔离预约',contactPhone:'139'+run.slice(0,7)+'5'}
  await denied('游客不能创建公共资源预约','/resource-reservations','POST',reservationBody,undefined,401)
  const reserved=await request('/resource-reservations','POST',reservationBody,auth.resident.token);fixture.reservationId=reserved.id
  for(const [role,session] of [['其他居民',auth.stranger],['他社区管理员',auth.adminB],['服务人员',auth.staff]])await denied('预约详情拒绝'+role,'/resource-reservations/'+reserved.id,'GET',undefined,session.token)
  await denied('他社区管理员不能确认预约','/resource-reservations/'+reserved.id+'/confirm','PATCH',{reason:'越权'},auth.adminB.token)
  await denied('其他居民不能取消预约','/resource-reservations/'+reserved.id+'/cancel','PATCH',{reason:'越权'},auth.stranger.token,403)
  await request('/resource-reservations/'+reserved.id+'/confirm','PATCH',{reason:'隔离矩阵确认'},auth.adminA.token);await notification('预约确认通知本人',auth.resident,'RESOURCE_RESERVATION',reserved.id)
  const conflict=await request('/resource-reservations','POST',reservationBody,auth.stranger.token,true);check('已确认预约阻断同容量冲突',conflict.code!==200&&conflict.status<500,{http:conflict.status,code:conflict.code})
  await request('/resource-reservations/'+reserved.id+'/cancel','PATCH',{reason:'本人取消'},auth.resident.token)
  const next=await request('/resource-reservations','POST',reservationBody,auth.stranger.token);check('取消预约后释放同一时段',next.id!==reserved.id)
  const optionalContact={...reservationBody,startTime:'10:00',endTime:'10:30'};delete optionalContact.contactPhone
  const optionalResult=await request('/resource-reservations','POST',optionalContact,auth.resident.token,true)
  check('选填联系电话省略仍成功创建',optionalResult.status===200&&optionalResult.code===200,{http:optionalResult.status,code:optionalResult.code})
  if(optionalResult.code===200){const saved=sql('SELECT contact_phone FROM resource_reservation WHERE id='+id(optionalResult.data.id))[0][0];const profile=sql('SELECT phone FROM resident WHERE id='+id(auth.resident.user.id))[0][0];check('省略联系信息使用本人档案且非空',saved===profile&&!!saved)}
  const category=await request('/service-categories','POST',{communityId:fixture.A.id,name:tag+'评价服务',isActive:1},auth.adminA.token)
  const order=await request('/work-orders','POST',{categoryId:category.id,title:tag+'评价链',content:'完整非交易流转',contactPhone:'139'+run.slice(0,7)+'5',priority:'NORMAL'},auth.resident.token);fixture.orderId=order.id
  await request('/work-orders/'+order.id+'/intake','PATCH',{},auth.adminA.token)
  await request('/work-orders/'+order.id+'/assign','PATCH',{assigneeId:auth.staff.user.id},auth.adminA.token)
  await request('/work-orders/'+order.id+'/accept','PATCH',{},auth.staff.token)
  await request('/work-orders/'+order.id+'/process','PATCH',{},auth.staff.token)
  await request('/work-orders/'+order.id+'/complete','PATCH',{remark:'隔离测试处理结果'},auth.staff.token)
  await request('/work-orders/'+order.id+'/confirm','PATCH',{},auth.resident.token)
  const evalBody={rating:2,content:tag+'不满意评价',isSatisfied:false}
  await denied('非提交人不能评价工单','/work-orders/'+order.id+'/evaluation','POST',evalBody,auth.stranger.token,403)
  const evaluation=await request('/work-orders/'+order.id+'/evaluation','POST',evalBody,auth.resident.token);fixture.evaluationId=evaluation.id
  for(const [role,session] of [['其他居民',auth.stranger],['未派服务人员',auth.otherStaff],['他社区管理员',auth.adminB]])await denied('评价详情拒绝'+role,'/work-orders/'+order.id+'/evaluation','GET',undefined,session.token)
  for(const [role,session] of [['本人',auth.resident],['被派人员',auth.staff],['社区管理员',auth.adminA],['超管',auth.super]])check('评价详情授权'+role,(await request('/work-orders/'+order.id+'/evaluation','GET',undefined,session.token)).id===evaluation.id)
  await denied('他社区管理员不能评价跟进','/evaluations/'+evaluation.id+'/followup','POST',{content:'越权'},auth.adminB.token)
  await request('/evaluations/'+evaluation.id+'/followup','POST',{content:tag+'管理员跟进'},auth.adminA.token)
  check('跟进记录留存',(await request('/evaluations/'+evaluation.id+'/followups','GET',undefined,auth.adminA.token)).length===1)
  await denied('他社区管理员不能读跟进记录','/evaluations/'+evaluation.id+'/followups','GET',undefined,auth.adminB.token)
  const duplicate=await request('/work-orders/'+order.id+'/evaluation','POST',evalBody,auth.resident.token,true);check('一单一评阻断重复',duplicate.code!==200&&duplicate.status<500,{http:duplicate.status,code:duplicate.code})
  await request('/work-orders/'+order.id+'/close','PATCH',{},auth.adminA.token)
  const orderEnd=await request('/work-orders/'+order.id,'GET',undefined,auth.resident.token);check('工单全链终审关闭',orderEnd.status==='CLOSED')
  const group=sql('SELECT status FROM conversation WHERE type=\'WORK_ORDER_GROUP\' AND related_id='+id(order.id));check('工单终审关闭沟通群',group[0]?.[0]==='CLOSED')
  evidence.financialAfter=finances();check('支付退款数量完全未变',JSON.stringify(evidence.financialBefore)===JSON.stringify(evidence.financialAfter))
  if(remaining){await require('./nontransaction-remaining-regression.cjs').run({auth,fixture,evidence,check,out});if(!process.argv.includes('--api-only')){await require('./nontransaction-remaining-browser.cjs').fixtures({auth,fixture,check});await require('./nontransaction-remaining-browser.cjs').run({auth,fixture,evidence,check,out})}}
  else if(!process.argv.includes('--api-only'))await require('./nontransaction-domain-browser.cjs').run({auth,fixture,evidence,check,out})
 }catch(e){evidence.error=e.message;process.exitCode=1}
 finally{
  for(const communityId of evidence.communities){try{await request('/communities/'+communityId,'DELETE',undefined,auth.super.token)}catch(e){evidence.cleanupError=e.message;process.exitCode=1}}
  for(const noticeId of fixture.publicNoticeIds||[]){try{const row=await request('/notices/'+noticeId,'GET',undefined,auth.super.token);if(row.status==='PUBLISHED')await request('/notices/'+noticeId+'/withdraw','PATCH',{reason:'本批隔离夹具回收'},auth.super.token);await request('/notices/'+noticeId,'DELETE',undefined,auth.super.token)}catch(e){evidence.cleanupError=e.message;process.exitCode=1}}
  // 社区删除保护账号；仅清理本次登记的合成账号及其身份空间通知。
  for(const residentId of evidence.residents){sql("DELETE FROM notification_channel_log WHERE notification_id IN (SELECT id FROM notification WHERE owner_type='RESIDENT' AND user_id="+id(residentId)+')');sql("DELETE FROM notification WHERE owner_type='RESIDENT' AND user_id="+id(residentId));sql('DELETE FROM resident WHERE id='+id(residentId))}
  for(const userId of evidence.users){sql("DELETE FROM notification_channel_log WHERE notification_id IN (SELECT id FROM notification WHERE owner_type='USER' AND user_id="+id(userId)+')');sql("DELETE FROM notification WHERE owner_type='USER' AND user_id="+id(userId));sql('DELETE FROM sys_user WHERE id='+id(userId))}
  evidence.cleanup=!evidence.cleanupError;evidence.financialAfterCleanup=finances()
  evidence.fixtureCleanupCounts={}
  if(evidence.communities.length){const ids=evidence.communities.map(id).join(',');for(const table of ['community','house','work_order','feedback','resource_reservation','work_order_evaluation'])evidence.fixtureCleanupCounts[table]=Number(sql('SELECT COUNT(*) FROM '+table+' WHERE '+(table==='community'?'id':'community_id')+' IN ('+ids+')')[0][0])}
  for(const [table,ids0] of [['resident',evidence.residents],['sys_user',evidence.users]])if(ids0.length)evidence.fixtureCleanupCounts[table]=Number(sql('SELECT COUNT(*) FROM '+table+' WHERE id IN ('+ids0.map(id).join(',')+')')[0][0])
  check('本次夹具关联及合成账号精准回收',Object.values(evidence.fixtureCleanupCounts).every(v=>v===0),evidence.fixtureCleanupCounts)

  fs.mkdirSync(out,{recursive:true});const file=out+'/domain-matrix-'+(process.argv.includes('--api-only')?'api':'browser')+'.json';fs.writeFileSync(file,JSON.stringify(evidence,null,2));console.log(JSON.stringify({checks:evidence.checks.length,failures:evidence.failures,error:evidence.error,cleanup:evidence.cleanup,evidence:file}));if(evidence.failures.length)process.exitCode=1
 }
})()
