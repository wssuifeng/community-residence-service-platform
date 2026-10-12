/* 固定8087/5181隔离实例，只验证居民账号与关系，禁止交易端点。 */
const fs=require('node:fs'),assert=require('node:assert/strict')
const { request, sql, database, base }=require('./nontransaction-fixture-helpers.cjs')
const tag='BH'+Date.now().toString().slice(-8), communities=[], residents=[]
const evidence={date:'2026-10-11',database,transactionsExecuted:false,checks:[],cleanup:false}
function check(name,ok){assert(ok,name);evidence.checks.push({name,passed:true})}
function phone(n){return '139'+tag.slice(2,9)+n}
function transactionCounts(){return sql('SELECT (SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0]}
async function uploadCsv(csv,token){
 const api=await fetch('http://127.0.0.1:8087/v3/api-docs').then(r=>r.json());assert(api.paths['/api/v1/residents/import']?.post?.requestBody?.content['multipart/form-data'],'CSV接口与OpenAPI不符')
 const form=new FormData();form.append('file',new Blob([csv],{type:'text/csv'}),'resident-test.csv')
 const response=await fetch(base+'/residents/import',{method:'POST',headers:{Authorization:'Bearer '+token},body:form});const body=await response.json();assert.equal(body.code,200,'CSV导入失败');for(const r of body.data.successRows)if(r.residentId)residents.push(r.residentId);return body.data
}
;(async()=>{let admin;try{
 check('隔离数据库身份',sql('SELECT DATABASE()')[0][0]===database);evidence.before=transactionCounts()
 admin=await request('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'})
 const community=await request('/communities','POST',{name:tag+'居民录入',address:'独立居民录入夹具'},admin.token);communities.push(community.id)
 const other=await request('/communities','POST',{name:tag+'其他范围',address:'独立范围夹具'},admin.token);communities.push(other.id)
 const building=await request('/buildings','POST',{communityId:community.id,name:'测试楼',floors:3},admin.token)
 const unit=await request('/units','POST',{buildingId:building.id,name:'1单元'},admin.token)
 const houses=[];for(let i=1;i<=7;i++)houses.push(await request('/houses','POST',{unitId:unit.id,houseNumber:'10'+i,floor:1,area:80,layout:'三室一厅一卫'},admin.token))
 sql('UPDATE house SET status=\'RESERVED\' WHERE id='+houses[2].id)
 const payload={communityId:community.id,username:tag+'_one',realName:tag+'单建',phone:phone(1),email:'resident@example.com',houseId:houses[0].id,relationType:'OWNER',moveInDate:'2026-01-01'}
 const created=await request('/residents/admin-create','POST',payload,admin.token);residents.push(created.residentId)
 check('单建返回真实关系与房屋ID',created.relationId>0&&created.houseId===houses[0].id)
 check('账号邮箱真实保存',(await request('/residents/'+created.residentId,'GET',undefined,admin.token)).email==='resident@example.com')
 check('关系与账号真实关联',sql('SELECT COUNT(*) FROM residence_relation WHERE id='+created.relationId+' AND resident_id='+created.residentId+' AND house_id='+houses[0].id)[0][0]==='1')
 check('房屋同步已入住',(await request('/houses/'+houses[0].id,'GET',undefined,admin.token)).status==='OCCUPIED')
 const session=await request('/auth/resident/login','POST',{username:created.username,password:created.initialPassword})
 const relations=await request('/residents/'+created.residentId+'/residences?status=ACTIVE','GET',undefined,session.token)
 check('新账号登录后可见真实关系',relations.records.some(r=>r.id===created.relationId))
 const denied=await request('/residents/admin-create','POST',{...payload,username:tag+'_failed',phone:phone(2),houseId:houses[2].id},admin.token,true)
 check('关系拒绝时整个单建失败',denied.code!==200)
 check('关系失败后账号零残留',sql("SELECT COUNT(*) FROM resident WHERE username='"+tag+"_failed'")[0][0]==='0')
 for(const [name,body] of [['跨社区房屋',{...payload,communityId:other.id,username:tag+'_cross',phone:phone(3),houseId:houses[3].id}],['半填关系',{...payload,houseId:undefined,username:tag+'_half',phone:phone(3)}],['未来入住日期',{...payload,username:tag+'_future',phone:phone(3),houseId:houses[3].id,moveInDate:'2099-01-01'}],['非法邮箱',{...payload,username:tag+'_email',phone:phone(3),email:'bad-email',houseId:houses[3].id}]]){
  const result=await request('/residents/admin-create','POST',body,admin.token,true);check(name+'拒绝且无账号残留',result.code!==200&&sql("SELECT COUNT(*) FROM resident WHERE username='"+body.username+"'")[0][0]==='0')
 }
 const headers='社区ID,姓名,手机号,证件号,用户名,邮箱,房屋ID,关系类型,入住日期,备注\n'
 const csv=headers+`${community.id},${tag}CSV,${phone(3)},,${tag}_csv,csv@example.com,${houses[1].id},OWNER,2026-01-01,台账录入\n${community.id},${tag}失败,${phone(4)},,${tag}_csv_fail,,${houses[2].id},OWNER,2026-01-01,\n`
 const imported=await uploadCsv(csv,admin.token)
 check('CSV按行部分成功',imported.success===1&&imported.fail===1&&imported.failRows[0].row===2)
 check('CSV成功行返回真实关系',imported.successRows[0].relationId>0&&imported.successRows[0].houseId===houses[1].id)
 check('CSV失败行账号回滚',sql("SELECT COUNT(*) FROM resident WHERE username='"+tag+"_csv_fail'")[0][0]==='0')
 const repeated=await uploadCsv(csv,admin.token);check('重复导入没有重复账号或关系',repeated.success===0&&repeated.fail===2&&sql("SELECT COUNT(*) FROM resident WHERE username='"+tag+"_csv'")[0][0]==='1')
 const old=await uploadCsv(`社区ID,姓名,手机号,证件号\n${community.id},${tag}旧模板,${phone(5)},\n`,admin.token)
 check('旧模板兼容且明确没有关系',old.success===1&&old.successRows[0].relationId==null&&old.successRows[0].houseId==null)
 const manager=await request('/sys-users','POST',{username:tag+'_manager',password:'Test123456',realName:tag+'管理员',phone:'138'+tag.slice(2,9)+'9',role:'ADMIN'},admin.token)
 await request('/sys-users/'+manager.id+'/communities','POST',{communityId:community.id},admin.token)
 const managed=await request('/auth/admin/login','POST',{username:manager.username,password:'Test123456'})
 const visible=await request('/residents/'+created.residentId,'GET',undefined,managed.token);check('社区管理员可见本次登记居民',visible.id===created.residentId)
 const out=await request('/residents/admin-create','POST',{...payload,communityId:other.id,username:tag+'_forbidden',phone:phone(6),houseId:undefined,relationType:undefined,moveInDate:undefined},managed.token,true);check('范围外代建拒绝',out.code!==200)
 check('未自动创建租约',sql('SELECT COUNT(*) FROM lease_record WHERE community_id='+community.id)[0][0]==='0')
 const browserResult=await require('./resident-provisioning-browser.cjs')(admin,{communityId:community.id,tag,phone:phone(7),houseId:houses[3].id},evidence,request);if(browserResult?.residentId)residents.push(browserResult.residentId)
 evidence.after=transactionCounts();check('支付和退款记录无变化',JSON.stringify(evidence.before)===JSON.stringify(evidence.after))
 console.log(JSON.stringify({checks:evidence.checks.length,success:true}))
}catch(error){evidence.error=error.message;console.error(error.message);process.exitCode=1}
finally{if(admin){for(const id of communities)try{await request('/communities/'+id,'DELETE',undefined,admin.token)}catch(error){evidence.cleanupError=error.message}if(!evidence.cleanupError){for(const r of sql("SELECT id FROM resident WHERE username='"+tag+"_browser'"))residents.push(Number(r[0]))}if(!evidence.cleanupError&&residents.length)sql('DELETE FROM resident WHERE id IN ('+[...new Set(residents)].map(Number).join(',')+')')}evidence.cleanup=!evidence.cleanupError;fs.mkdirSync('output/playwright',{recursive:true});fs.writeFileSync('output/playwright/resident-provisioning-regression-20261011.json',JSON.stringify(evidence,null,2))}
})()
