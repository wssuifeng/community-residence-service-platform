/* 本批正常库只读核对：允许演示登录，禁止业务写与交易/查单。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process')
const {properties}=require('./nontransaction-fixture-helpers.cjs')
const out='docs/50_系统测试/evidence/staff-rules-20261012'
const report={date:new Date().toISOString(),database:'community_residence',transactionsExecuted:false,businessWrites:false,checks:[]}
function check(name,passed){assert(passed,name);report.checks.push({name,passed:true})}
function counts(){const c=properties();const r=spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['--host=127.0.0.1','--user='+(c.DB_USERNAME||'root'),'--database=community_residence','--batch','--skip-column-names','--execute','SELECT (SELECT COUNT(*) FROM community),(SELECT COUNT(*) FROM house),(SELECT COUNT(*) FROM resident),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)'],{env:{...process.env,MYSQL_PWD:c.DB_PASSWORD},encoding:'utf8',windowsHide:true});assert.equal(r.status,0,'正常只读SQL失败（不输出凭据）');return r.stdout.trim().split('\t').map(Number)}
async function login(username,password,kind){const result=await fetch('http://127.0.0.1:8084/api/v1/auth/'+kind+'/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})}).then(r=>r.json());check('演示登录'+username,result.code===200);return result.data}
;(async()=>{try{
 report.before=counts();const baseline=JSON.parse(fs.readFileSync(out+'/development-before.json','utf8')).counts;check('正常基线保留1/600/9/1/0/0',JSON.stringify(report.before)===JSON.stringify(baseline))
 const admin=await login('superadmin','Admin@123456','admin');await login('admin1','Admin123456','admin');const staff=await login('staff1','Staff123456','admin');await login('resident1','Resident123456','resident')
 const channels=await fetch('http://127.0.0.1:8084/api/v1/payment/channels',{headers:{Authorization:'Bearer '+admin.token}}).then(r=>r.json());check('正常程序渠道全部停用',channels.code===200&&channels.data.every(c=>!c.enabled))
 const stats=await fetch('http://127.0.0.1:8084/api/v1/statistics/staff/trend?days=7',{headers:{Authorization:'Bearer '+staff.token}}).then(r=>r.json());check('正常服务事件接口使用新契约',stats.code===200&&Object.keys(stats.data.submittedTrend).length===7&&stats.data.processingTrend===undefined)
 const proxy=await fetch('http://127.0.0.1:5176/api/v1/statistics/staff/trend?days=7',{headers:{Authorization:'Bearer '+staff.token}}).then(r=>r.json());check('正常前端代理指向当前后端',proxy.code===200&&JSON.stringify(proxy.data)===JSON.stringify(stats.data))
 const docs=await fetch('http://127.0.0.1:8084/v3/api-docs').then(r=>r.json());check('正常OpenAPI带看完成存在',!!docs.paths['/api/v1/viewing-appointments/{id}/complete']?.patch)
 report.after=counts();check('正常只读期间数量未变',JSON.stringify(report.before)===JSON.stringify(report.after))
}catch(error){report.error=error.message;process.exitCode=1}finally{fs.writeFileSync(out+'/development-readonly.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({checks:report.checks.length,counts:report.after,error:report.error}))}})()
