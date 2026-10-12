/* 仅供本机隔离非交易回归；不连接开发库，不输出凭据或令牌。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process'),path=require('node:path')
const database='community_residence_payment_config_test',base='http://127.0.0.1:8087/api/v1',frontend='http://127.0.0.1:5181'
function properties(){const result={};for(const line of fs.readFileSync('.env','utf8').split(/\r?\n/)){const match=line.match(/^\s*(DB_PASSWORD|DB_USERNAME)\s*=\s*(.*?)\s*$/);if(match)result[match[1]]=match[2].replace(/^(["'])(.*)\1$/,'$2')}return result}
function sql(statement){
 const credentials=properties()
 const result=spawnSync(process.env.MYSQL_EXECUTABLE??'C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['--protocol=TCP','--host=127.0.0.1','--port=3306','--user='+ (credentials.DB_USERNAME||'root'),'--database='+database,'--batch','--skip-column-names','--execute',statement],{env:{...process.env,MYSQL_PWD:credentials.DB_PASSWORD},encoding:'utf8',windowsHide:true})
 assert.equal(result.status,0,'隔离SQL夹具执行失败（不回显请求或凭据）')
 return result.stdout.trim().split('\n').filter(Boolean).map(line=>line.trim().split('\t'))
}
function id(value){assert(Number.isSafeInteger(Number(value))&&Number(value)>0);return Number(value)}
let specification
async function validateContract(p,method){
 if(!specification)specification=await fetch(base.replace('/api/v1','')+'/v3/api-docs').then(r=>r.json())
 const u=new URL(base+p), entry=specification.paths[u.pathname]?[u.pathname,specification.paths[u.pathname]]:Object.entries(specification.paths).find(([pattern])=>new RegExp('^'+pattern.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(u.pathname))
 assert(entry&&entry[1][method.toLowerCase()],`复验工具接口不在现行OpenAPI：${method} ${u.pathname}`)
 const params=entry[1][method.toLowerCase()].parameters??[]
 for(const param of params)if(param.in==='query'&&param.required)assert(u.searchParams.has(param.name),`复验工具缺少OpenAPI必填查询参数：${param.name}`)
}
async function request(p,method='GET',data,token,allowError=false){
 assert(!/payment|refund|renew/i.test(p)||p==='/payment/channels','禁止交易端点（包括可能主动查单的GET）')
 assert(p.startsWith('/')&&!p.includes('://'))
 await validateContract(p,method)
 const response=await fetch(base+p,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(data!==undefined?{body:JSON.stringify(data)}:{})})
 const body=await response.json(), result={status:response.status,...body}
 if(!allowError)assert(response.status===200&&body.code===200,`${method} ${p}: ${response.status}/${body.code} ${body.message}`)
 return allowError?result:body.data
}
module.exports={database,base,frontend,sql,id,request,properties}
