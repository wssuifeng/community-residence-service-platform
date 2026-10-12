/* 批次be隔离写入回归：固定8087/5181、独立库/Redis12，不触碰开发库与交易接口。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process')
const base='http://127.0.0.1:8087/api/v1', out='output/playwright'
const evidence={date:'2026-10-11',database:'community_residence_payment_config_test',transactionsExecuted:false,checks:[],observations:[],fixtureCommunityId:null,cleanup:false}
fs.mkdirSync(out,{recursive:true})
function check(name,condition,detail) {assert(condition,name);evidence.checks.push({name,passed:true,...(detail?{detail}:{})})}
async function call(path,method='GET',data,token,raw=false) {
 assert(!/payment|refund|renew/i.test(path),'禁止交易端点')
 const response=await fetch(base+path,{method,headers:{...(data instanceof FormData?{}:{'Content-Type':'application/json'}),...(token?{Authorization:'Bearer '+token}:{})},...(data!==undefined?{body:data instanceof FormData?data:JSON.stringify(data)}:{})})
 const body=await response.json();if(raw)return {status:response.status,...body};assert(response.status===200&&body.code===200,`${method} ${path}: ${response.status}/${body.code} ${body.message}`);return body.data
}
;(async()=>{
 let superAdmin, oldThreshold
 try{
  superAdmin=await call('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'})
  const suffix=String(Date.now()).slice(-8), community=await call('/communities','POST',{name:'BE隔离结构'+suffix,address:'自动化隔离夹具'},superAdmin.token)
  evidence.fixtureCommunityId=community.id
  const generated=await call('/structures/batch-generate','POST',{communityId:community.id,buildingNamePrefix:'BE',buildingStartNo:1,buildingEndNo:10,buildingNameSuffix:'栋',unitCountPerBuilding:1,floorsPerUnit:20,housesPerFloor:6,area:60,dryRun:false},superAdmin.token)
  const buildings=await call('/communities/'+community.id+'/buildings?size=100','GET',undefined,superAdmin.token)
  const units=await call('/buildings/'+buildings.records[0].id+'/units?size=100','GET',undefined,superAdmin.token)
  const unitId=units.records[0].id, houses=await call('/units/'+unitId+'/houses?size=100','GET',undefined,superAdmin.token)
  check('代表性结构夹具包含10栋及单元超过单页房屋',buildings.total===10&&houses.total===120)
  const overFloor=await call('/houses','POST',{unitId,houseNumber:'BE-OVERFLOOR',floor:21,area:60,status:'VACANT'},superAdmin.token,true)
  evidence.observations.push({item:'DEF-135',buildingFloors:20,requestedFloor:21,accepted:overFloor.code===200,classification:'existing endpoint lacks upper-floor constraint; design decision remains'})
  if(overFloor.code===200) await call('/houses/'+overFloor.data.id,'DELETE',undefined,superAdmin.token)
  const original=await call('/configs/registration.enabled','GET',undefined,superAdmin.token)
  const rejected=await call('/configs/registration.enabled','PUT',{value:'invalid'},superAdmin.token,true)
  check('DEF-140非法注册开关HTTP400且不改变配置',rejected.status===400&&(await call('/configs/registration.enabled','GET',undefined,superAdmin.token)).value===original.value)
  const create=await call('/residents/admin-create','POST',{communityId:community.id,realName:'重复导入测试',phone:'138'+suffix},superAdmin.token)
  const repeated=await call('/residents/admin-create','POST',{communityId:community.id,realName:'重复导入测试',phone:'138'+suffix},superAdmin.token,true)
  check('DEF-142同手机号代建重复请求被拒',repeated.code!==200)
  const csv='社区ID,姓名,手机号,证件号\n'+community.id+',重复导入测试,138'+suffix+',\n'+community.id+',重复导入测试,138'+suffix+',\n'
  const form=new FormData();form.append('file',new Blob([csv.replaceAll('\\n','\n')],{type:'text/csv'}),'duplicate.csv')
  const imported=await call('/residents/import','POST',form,superAdmin.token)
  check('DEF-142重复CSV逐行失败不产生重复账号',imported.success===0&&imported.fail===2)
  const residentName='be_'+suffix,phone='139'+suffix
  await call('/auth/resident/register','POST',{username:residentName,password:'Resident123456',realName:'看房违约隔离测试',phone})
  const resident=await call('/auth/resident/login','POST',{username:residentName,password:'Resident123456'})
  const housing=await call('/housings','POST',{houseId:houses.records[0].id,title:'BE看房处置隔离房源',monthlyRent:1000,deposit:0},superAdmin.token)
  await call('/housings/'+housing.id+'/timeslots','POST',{dayOfWeek:1,startTime:'09:00',endTime:'12:00',isAvailable:1},superAdmin.token)
  oldThreshold=(await call('/configs/violation.max_count','GET',undefined,superAdmin.token)).value
  await call('/configs/violation.max_count','PUT',{value:'3'},superAdmin.token)
  const payload={housingId:housing.id,appointmentDate:'2026-10-12',startTime:'09:00',endTime:'10:00'}
  let last
  for(let n=1;n<=3;n++) {
   const appointment=await call('/viewing-appointments','POST',payload,resident.token); last=appointment
   if(n===1) {
    const duplicate=await call('/viewing-appointments','POST',payload,resident.token,true)
    check('预约重叠冲突被拒',duplicate.code!==200)
    const staff=await call('/auth/admin/login','POST',{username:'staff1',password:'Staff123456'})
    check('未分配服务人员不能读看房会话',(await call('/viewing-appointments/'+appointment.id+'/messages','GET',undefined,staff.token,true)).status===403)
    const stranger=await call('/auth/resident/login','POST',{username:'resident1',password:'Resident123456'})
    check('其他居民不能读取预约详情',(await call('/viewing-appointments/'+appointment.id,'GET',undefined,stranger.token,true)).status===403)
    const admin=await call('/auth/admin/login','POST',{username:'admin1',password:'Admin123456'})
    const cross=await call('/viewing-appointments/'+appointment.id,'GET',undefined,admin.token,true)
    check('未绑定该社区管理员不能读取预约',cross.status===404||cross.status===403)
   }
   await call('/viewing-appointments/'+appointment.id+'/confirm','PATCH',{reason:'隔离回归确认预约'},superAdmin.token)
   if(n===1) {
    const results=await Promise.all([0,1].map(()=>call('/viewing-appointments/'+appointment.id+'/violate','PATCH',{reason:'隔离回归未到场'},superAdmin.token,true)))
    check('并发同预约违约只成功一次',results.filter(r=>r.code===200).length===1&&results.filter(r=>r.code!==200).length===1)
   } else await call('/viewing-appointments/'+appointment.id+'/violate','PATCH',{reason:'隔离回归未到场'},superAdmin.token)
   if(n<3) check('阈值前居民旧令牌仍可读本人预约',(await call('/viewing-appointments','GET',undefined,resident.token,true)).status===200)
  }
  const oldToken=await call('/viewing-appointments','GET',undefined,resident.token,true)
  check('DEF-161第三次违约吊销旧令牌',oldToken.status===401)
  const frozenLogin=await call('/auth/resident/login','POST',{username:residentName,password:'Resident123456'},undefined,true)
  check('DEF-161冻结账号重新登录被拒',frozenLogin.code===5201)
  const double=await call('/viewing-appointments/'+last.id+'/violate','PATCH',{reason:'重复标记'},superAdmin.token,true)
  check('重复违约标记被状态机拒绝',double.code!==200)
  const violations=await call('/residents/'+resident.user.id+'/violations?size=100','GET',undefined,superAdmin.token)
  check('重复请求不新增违约且实际留痕冻结措施',violations.total===3&&violations.records.some(v=>v.punishment.includes('冻结')))
  const logs=await call('/operation-logs?module=HOUSING&action=DISPOSAL&keyword=看房预约违约处置','GET',undefined,superAdmin.token)
  check('DEF-139模块/操作类型/关键词过滤实际生效',logs.records.length>=3&&logs.records.every(l=>l.module==='HOUSING'&&l.action==='DISPOSAL'&&l.description.includes('看房预约违约处置')&&l.operatorName&&l.ipAddress))
  const browser=spawnSync(process.execPath,['tools/testing/nontransaction-readonly-regression.cjs'],{cwd:process.cwd(),env:{...process.env,AUDIT_BACKEND:base,AUDIT_FRONTEND:'http://127.0.0.1:5181',AUDIT_COMMUNITY_ID:String(community.id)},encoding:'utf8',timeout:120000})
  check('独立真实浏览器/低分辨率只读回归通过',browser.status===0,browser.stdout.trim()||browser.stderr.trim())
 }catch(e){evidence.failure=e.message;console.error(e.message);process.exitCode=1}
 finally{
  if(superAdmin&&oldThreshold!==undefined)try{await call('/configs/violation.max_count','PUT',{value:oldThreshold},superAdmin.token)}catch(e){evidence.restoreFailure=e.message;process.exitCode=1}
  if(superAdmin&&evidence.fixtureCommunityId)try{await call('/communities/'+evidence.fixtureCommunityId,'DELETE',undefined,superAdmin.token);evidence.cleanup=true}catch(e){evidence.cleanupFailure=e.message;process.exitCode=1}
  fs.writeFileSync(out+'/nontransaction-isolated-regression-20261011.json',JSON.stringify(evidence,null,2));console.log(JSON.stringify({checks:evidence.checks.length,cleanup:evidence.cleanup,failure:evidence.failure}))
 }
})()
