/* 本机开发只读核对；允许登录但禁止业务写、交易/主动查单接口。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process')
const {properties}=require('./nontransaction-fixture-helpers.cjs'),base='http://127.0.0.1:8084'
const out='docs/50_系统测试/evidence/'+(process.argv.includes('--buttons')?'nontransaction-buttons-20261012':process.argv.includes('--remaining')?'nontransaction-remaining-20261012':'nontransaction-matrix-20261012')+'/development-'+(process.argv.includes('--baseline-only')?'before-start':'readonly')+'.json'
const evidence={date:new Date().toISOString(),database:'community_residence',transactionsExecuted:false,businessWrites:false,checks:[]}
function select(statement){assert(statement.startsWith('SELECT '));const c=properties(),r=spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['--protocol=TCP','--host=127.0.0.1','--user='+(c.DB_USERNAME||'root'),'--database=community_residence','--batch','--skip-column-names','--execute',statement],{env:{...process.env,MYSQL_PWD:c.DB_PASSWORD},encoding:'utf8',windowsHide:true});assert.equal(r.status,0,'开发只读SQL失败（不回显凭据）');return r.stdout.trim().split(String.fromCharCode(10)).filter(Boolean).map(l=>l.trim().split(String.fromCharCode(9)))}
function counts(){return select('SELECT (SELECT COUNT(*) FROM community),(SELECT COUNT(*) FROM house),(SELECT COUNT(*) FROM resident),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0]}
function check(name,ok){assert(ok,name);evidence.checks.push({name,passed:true})}
;(async()=>{
 try{
  const baseline=JSON.parse(fs.readFileSync('output/playwright/rental-closure-development-baseline-20261012.json','utf8')).counts
  evidence.before=counts();check('开发业务数量与bh迁移前一致',JSON.stringify(baseline)===JSON.stringify(evidence.before))
  if(process.argv.includes('--baseline-only'))return
  const open=await fetch(base+'/v3/api-docs').then(r=>r.json())
  const login=await fetch(base+'/api/v1/auth/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'superadmin',password:'Admin@123456'})}).then(r=>r.json());assert.equal(login.code,200)
  for(const p of ['/notices?size=1','/feedbacks?size=1','/resource-reservations?size=1','/evaluations?size=1','/evaluations/unsatisfied?size=1','/statistics/evaluations','/payment/channels']){
   const u=new URL(base+'/api/v1'+p);check('GET契约'+u.pathname,!!open.paths[u.pathname]?.get)
   assert(!/payment|refund|renew/i.test(p)||p==='/payment/channels')
   const response=await fetch(u,{headers:{Authorization:'Bearer '+login.data.token}}),body=await response.json();check('开发只读'+u.pathname,response.status===200&&body.code===200);if(p==='/payment/channels')check('开发渠道继续停用',body.data.every(c=>!c.enabled))
  }
  evidence.demoAccounts=[]
  for(const [username,password,role,kind] of [['superadmin','Admin@123456','SUPER_ADMIN','admin'],['admin1','Admin123456','ADMIN','admin'],['staff1','Staff123456','STAFF','admin'],['resident1','Resident123456','RESIDENT','resident']]){
   const path='/api/v1/auth/'+kind+'/login';check('演示登录契约'+path,!!open.paths[path]?.post)
   const r=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})}).then(r=>r.json())
   const ok=r.code===200&&r.data.user.role===role;check('演示账号可用'+username,ok);evidence.demoAccounts.push({username,role,loginVerified:ok})
  }
  check('前端5176可访问',(await fetch('http://127.0.0.1:5176/admin/evaluations')).ok)
 }catch(e){evidence.error=e.message;process.exitCode=1}
 finally{evidence.after=counts();check('开发只读期间业务数量不变',JSON.stringify(evidence.before)===JSON.stringify(evidence.after));fs.mkdirSync(require('node:path').dirname(out),{recursive:true});fs.writeFileSync(out,JSON.stringify(evidence,null,2));console.log(JSON.stringify({checks:evidence.checks.length,error:evidence.error,counts:evidence.after}))}
})()
