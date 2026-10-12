/* 用户授权六项待办：独立浏览器与只读API；禁止交易调用或修改开发数据。 */
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { chromium } = require(process.env.PLAYWRIGHT_NODE_MODULE_PATH ?? path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
const apiBase = process.env.AUDIT_BACKEND ?? 'http://127.0.0.1:8084/api/v1'
const frontend = process.env.AUDIT_FRONTEND ?? 'http://127.0.0.1:5176'
assert(['http://127.0.0.1:8084/api/v1', 'http://127.0.0.1:8087/api/v1'].includes(apiBase))
assert(['http://127.0.0.1:5176', 'http://127.0.0.1:5181'].includes(frontend))
const environment=apiBase.includes(':8087/')?'isolated':'development'
const out='output/playwright', evidence={date:'2026-10-11',backend:apiBase,frontend,mode:'independent-browser-readonly',transactionsExecuted:false,checks:[],observations:[],errors:[],houseRequests:[]}
fs.mkdirSync(out,{recursive:true})
function check(name,condition,detail) { assert(condition,name); evidence.checks.push({name,passed:true,...(detail?{detail}:{})}) }
async function api(p, token, data) {
  assert(!p.startsWith('/payment/') || p.startsWith('/payment/channel-config/'), '交易端点禁用')
  assert(!data || p.startsWith('/auth/'), '只允许认证POST')
  const r=await fetch(apiBase+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(data?{body:JSON.stringify(data)}:{})})
  return {status:r.status,...await r.json()}
}
;(async()=>{
 let browser
 try {
  const auth={}
  for(const [role,username,password,kind] of [['SUPER_ADMIN','superadmin','Admin@123456','admin'],['ADMIN','admin1','Admin123456','admin'],['STAFF','staff1','Staff123456','admin'],['RESIDENT','resident1','Resident123456','resident']]){
    const r=await api('/auth/'+kind+'/login',undefined,{username,password})
    check(role+'测试身份登录',r.code===200);auth[role]=r.data
  }
  for(const [role,expected] of [['ANONYMOUS',401],['ADMIN',403],['STAFF',403],['RESIDENT',403]]) {
    const r=await api('/payment/channel-config/alipay',auth[role]?.token)
    check(role+'不能读取超管敏感配置',r.status===expected)
  }
  for(const role of ['STAFF','RESIDENT']) check(role+'不能读取操作日志',(await api('/operation-logs',auth[role].token)).status===403)
  const logs=await api('/operation-logs?size=10',auth.SUPER_ADMIN.token)
  check('超管可读取操作留痕',logs.code===200)
  evidence.observations.push({item:'DEF-139',logsHaveDesignedFields:logs.data.records.every(l=>'action'in l && 'description'in l && 'operatorName'in l),sampleCount:logs.data.records.length})
  const specification=await fetch(apiBase.replace('/api/v1','')+'/v3/api-docs').then(r=>r.json())
  check('只按现行OpenAPI调用只读接口',!!specification.paths['/api/v1/operation-logs'])
  browser=await chromium.launch({channel:'msedge',headless:true})
  const context=await browser.newContext({viewport:{width:1024,height:700}})
  await context.route('**/*',route=>{
    const u=new URL(route.request().url())
    if(!['127.0.0.1','localhost'].includes(u.hostname)) return route.abort()
    if(u.pathname.startsWith('/api/v1/payment/') && !u.pathname.startsWith('/api/v1/payment/channel-config/') && u.pathname!=='/api/v1/payment/channels') { evidence.errors.push('blocked payment endpoint');return route.abort() }
    if(u.pathname.startsWith('/api/') && !['GET','HEAD'].includes(route.request().method())) {evidence.errors.push('blocked mutating API');return route.abort()}
    return route.continue()
  })
  await context.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify(s)),{token:auth.SUPER_ADMIN.token,user:auth.SUPER_ADMIN.user,expiresAt:Date.now()+auth.SUPER_ADMIN.expiresIn*1000})
  const page=await context.newPage()
  page.on('pageerror',e=>evidence.errors.push(e.message))
  page.on('console',m=>{if(m.type()==='error') evidence.errors.push(m.text())})
  page.on('response',r=>{ if(/\/units\/\d+\/houses/.test(r.url())) evidence.houseRequests.push(new URL(r.url()).pathname+new URL(r.url()).search) })
  const started=Date.now()
  await page.goto(frontend+'/admin/community'+(process.env.AUDIT_COMMUNITY_ID?'?communityId='+Number(process.env.AUDIT_COMMUNITY_ID):''),{waitUntil:'networkidle'})
  await page.locator('.grid-block').first().waitFor()
  check('无Vite旧编译错误覆盖页面',await page.locator('vite-error-overlay').count()===0)
  const initialCards=await page.locator('.grid-block').count(), firstRequests=evidence.houseRequests.length
  const buildingCount=Number(await page.locator('.metric-cell b').nth(0).innerText()), totalHouses=Number(await page.locator('.metric-cell b').nth(2).innerText())
  check('社区初次加载按首楼栋而非全部房屋',firstRequests>0 && initialCards>0 && (buildingCount===1 || initialCards<totalHouses))
  evidence.observations.push({item:'DEF-154',initialCards,firstRequests,elapsedMs:Date.now()-started,communityMetrics:await page.locator('.metric-strip').innerText()})
  for(const width of [1024,768]) {
    await page.setViewportSize({width,height:800})
    await page.waitForFunction(()=>document.documentElement.scrollWidth<=innerWidth)
    check(width+'px社区结构无页面级横向溢出',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))
    await page.screenshot({path:out+'/nontransaction-structure-'+environment+'-'+width+'-20261011.png',fullPage:true})
  }
  const selector=page.locator('.filter-select').first()
  await selector.click()
  const options=page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
  await options.first().waitFor()
  if(buildingCount>1) {
    check('完整楼栋元数据可选择',await options.count()===buildingCount)
    const second=await options.nth(1).innerText()
    const loaded=page.waitForResponse(r=>/\/units\/\d+\/houses/.test(r.url()) && r.status()===200)
    await options.nth(1).click();await loaded;await page.locator('.grid-block').first().waitFor()
    check('第二楼栋切换可见房屋',await page.locator('.grid-block').count()>0)
    evidence.observations.push({item:'DEF-154-scope-change',selected:second,newRequests:evidence.houseRequests.length-firstRequests})
    check('切换楼栋只请求新范围房屋',evidence.houseRequests.length>firstRequests)
    const afterSecond=evidence.houseRequests.length
    await selector.click();await options.first().waitFor();await options.nth(0).click();await page.waitForLoadState('networkidle')
    check('回切首楼栋复用缓存不再读取全部',evidence.houseRequests.length===afterSecond)
  } else await page.keyboard.press('Escape')
  await page.getByRole('button',{name:/结构维护/}).click()
  await page.getByRole('menuitem',{name:/删除楼栋/}).waitFor()
  check('DEF-137删除楼栋与删除单元有真实入口',await page.getByRole('menuitem',{name:/删除楼栋/}).isVisible() && await page.getByRole('menuitem',{name:/删除单元/}).isVisible())
  await page.keyboard.press('Escape')
  await page.goto(frontend+'/admin/system?tab=logs',{waitUntil:'networkidle'})
  check('日志页面可渲染',await page.getByPlaceholder('搜索操作人/描述').isVisible())
  if(logs.data.records[0]?.description) check('日志列表显示服务器留痕描述',(await page.locator('body').innerText()).includes(logs.data.records[0].description))
  check('独立浏览器无未捕获错误/写请求/交易请求',evidence.errors.length===0)
  fs.writeFileSync(out+'/nontransaction-readonly-'+environment+'-regression-20261011.json',JSON.stringify(evidence,null,2))
  console.log(JSON.stringify({checks:evidence.checks.length,errors:evidence.errors.length,initialCards,firstRequests}))
 } catch(e) { evidence.failure=e.message;fs.writeFileSync(out+'/nontransaction-readonly-'+environment+'-regression-20261011.json',JSON.stringify(evidence,null,2));console.error(e.message);process.exitCode=1 }
 finally { if(browser) await browser.close() }
})()
