/* 独立无头浏览器：四域代表页/详情/错误态，仅本机隔离端口与明确读回执。 */
const path=require('node:path')
const {frontend}=require('./nontransaction-fixture-helpers.cjs')
const {chromium}=require(process.env.PLAYWRIGHT_NODE_MODULE_PATH??path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
async function run({auth,fixture,evidence,check,out}){
 const browser=await chromium.launch({channel:'msedge',headless:true})
 evidence.browser={mode:'independent-headless',pages:[],errors:[],blocked:[],screenshots:[],interactions:[]}
 const pages={}
 async function make(role,session){
  const context=await browser.newContext({viewport:{width:1024,height:768}})
  await context.route('**/*',route=>{
   const req=route.request(),u=new URL(req.url())
   if(!['127.0.0.1','localhost'].includes(u.hostname)||!['5181','8087'].includes(u.port)){evidence.browser.blocked.push({path:u.pathname,reason:'非隔离地址'});return route.abort()}
   if(u.pathname.startsWith('/api/')&&/payment|refund|renew/i.test(u.pathname)&&u.pathname!=='/api/v1/payment/channels'){evidence.browser.blocked.push({path:u.pathname,reason:'暂停交易端点'});return route.abort()}
   if(u.pathname.startsWith('/api/')&&!['GET','HEAD'].includes(req.method())&&!/\/notices\/\d+\/view$|\/notifications\/(?:\d+\/read|read-all)$/.test(u.pathname)){evidence.browser.blocked.push({method:req.method(),path:u.pathname,reason:'非计划写操作'});return route.abort()}
   return route.continue()
  })
  await context.addInitScript(session=>{sessionStorage.setItem('crsp_session',JSON.stringify({token:session.token,user:session.user,expiresAt:Date.now()+session.expiresIn*1000}))},session)
  const page=await context.newPage();page.on('pageerror',e=>evidence.browser.errors.push(role+': '+e.message));pages[role]=page;return page
 }
 async function shot(page,name){await page.waitForFunction(() => !document.querySelector('.el-drawer') || [...document.querySelectorAll('.el-drawer')].every(e => e.getBoundingClientRect().right <= innerWidth + 1));const file=out+'/'+name+'.png';await page.screenshot({path:file,fullPage:true});evidence.browser.screenshots.push(file)}
 async function layout(page,role,url,width){const info=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,loading:document.querySelectorAll('.el-loading-mask').length,viteError:!!document.querySelector('vite-error-overlay')}));evidence.browser.pages.push({role,url,...info});check(role+' '+url+' '+width+'px页面无横向溢出',info.scrollWidth<=width,{width:info.scrollWidth});check(role+' '+url+' '+width+'px加载结束',info.loading===0&&!info.viteError)}
 try{
  const admin=await make('admin',auth.adminA),resident=await make('resident',auth.resident),stranger=await make('stranger',auth.stranger),otherStaff=await make('otherStaff',auth.otherStaff)
  const routes=[['admin','/admin/notices'],['admin','/admin/feedbacks'],['admin','/admin/reservations'],['admin','/admin/evaluations'],['resident','/resident/notices'],['resident','/resident/notices/'+fixture.noticeId],['resident','/resident/feedbacks'],['resident','/resident/feedbacks/'+fixture.feedbackId],['resident','/resident/reservations'],['resident','/resident/work-orders/'+fixture.orderId]]
  for(const [role,url] of routes){const page=pages[role];for(const width of [1440,1024,768]){await page.setViewportSize({width,height:900});await page.goto(frontend+url,{waitUntil:'networkidle'});await layout(page,role,url,width);if(width===768)await shot(page,'bi-'+role+'-'+url.replace(/\W+/g,'-')+'-768')}}
  await resident.goto(frontend+'/resident/feedbacks/'+fixture.feedbackId,{waitUntil:'networkidle'})
  const feedbackText=await resident.locator('body').innerText();check('浏览器办结反馈明确只读',feedbackText.includes('已办结')&&await resident.locator('textarea:visible').count()===0)
  await admin.goto(frontend+'/admin/notices',{waitUntil:'networkidle'});await admin.getByText(fixture.tag+'社区定向公告',{exact:true}).first().click();await admin.locator('.viewer-entry').click();await admin.waitForSelector('.el-drawer:visible');await admin.waitForLoadState('networkidle');check('浏览器管理员可打开公告阅读明细',await admin.locator('.el-drawer:visible').innerText().then(t=>t.includes('居民')));await shot(admin,'bi-notice-viewers-768');await admin.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await admin.goto(frontend+'/admin/evaluations',{waitUntil:'networkidle'});await admin.getByText('跟进记录',{exact:true}).first().click();await admin.waitForSelector('.el-drawer:visible');await admin.getByText(fixture.tag+'管理员跟进',{exact:true}).waitFor({state:'visible'});check('浏览器评价跟进记录可达',await admin.locator('.el-drawer:visible').innerText().then(t=>t.includes(fixture.tag+'管理员跟进')));await shot(admin,'bi-evaluation-followup-768')
  for(const [role,page,url] of [['stranger',stranger,'/resident/work-orders/'+fixture.orderId],['otherStaff',otherStaff,'/staff/work-orders/'+fixture.orderId]]){
   const responses=[];page.on('response',r=>{if(r.url().includes('/api/v1/work-orders/'+fixture.orderId))responses.push({path:new URL(r.url()).pathname,http:r.status()})});await page.goto(frontend+url,{waitUntil:'networkidle'});const t=await page.locator('body').innerText();check(role+'浏览器越权显示错误且不泄露评价',!t.includes(fixture.tag+'不满意评价')&&responses.some(r=>r.http===403));evidence.browser.interactions.push({role,url,responses});await shot(page,'bi-denied-'+role)
  }
  // 管理评价列表受控失败再恢复，检查加载与重试不留下遮罩。
  await admin.route('**/api/v1/evaluations?*',route=>route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({code:500,message:'BI隔离模拟加载失败',data:null})}));await admin.goto(frontend+'/admin/evaluations',{waitUntil:'networkidle'});check('评价列表失败结束加载',await admin.locator('.el-loading-mask:visible').count()===0);await shot(admin,'bi-evaluation-load-error');await admin.unroute('**/api/v1/evaluations?*');await admin.reload({waitUntil:'networkidle'});check('评价列表恢复后显示夹具',await admin.locator('body').innerText().then(t=>t.includes(fixture.tag+'不满意评价')))
  check('独立浏览器无未捕获脚本错误',evidence.browser.errors.length===0)
 }finally{await browser.close()}
}
module.exports={run}
