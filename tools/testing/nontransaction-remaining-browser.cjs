/* bj有限路由核查：实际router清单、真实非交易详情、独立无头Edge，不接管用户浏览器。 */
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict')
const {frontend,request,sql,id}=require('./nontransaction-fixture-helpers.cjs')
const ts=require('../../frontend/node_modules/typescript')
const {chromium}=require(process.env.PLAYWRIGHT_NODE_MODULE_PATH??path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
function routerInventory(){
 const rows=[]
 for(const group of ['admin','resident','staff','guest']){
  const source=fs.readFileSync('frontend/src/router/'+group+'.ts','utf8')
  const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const module={exports:{}};new Function('module','exports',code)(module,module.exports)
  function walk(nodes,parent=''){for(const node of nodes){const url=node.path.startsWith('/')?node.path:parent+'/'+node.path;if(node.children)walk(node.children,url);else rows.push({group,path:url.replace(/\/$/,''),name:node.name,title:node.meta?.title,redirect:!!node.redirect,redirectValue:node.redirect})}}
  walk(module.exports.default)
 }
 return rows
}
async function fixtures({auth,fixture,check}){
 const housing=await request('/housings','POST',{houseId:fixture.housesA[2].id,title:fixture.tag+'路由房源',description:'真实非交易动态详情',monthlyRent:1000,deposit:1000,rentType:'RENT'},auth.adminA.token)
 fixture.housingId=housing.id
 const d=new Date();d.setDate(d.getDate()+2);const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(d),week=new Date(date+'T12:00:00+08:00').getDay()||7
 await request('/housings/'+housing.id+'/timeslots','POST',{dayOfWeek:week,startTime:'10:00:00',endTime:'11:00:00',isAvailable:1},auth.adminA.token)
 const viewing=await request('/viewing-appointments','POST',{housingId:housing.id,appointmentDate:date,startTime:'10:00:00',endTime:'10:30:00',remark:'bj详情夹具'},auth.resident.token)
 fixture.viewingId=viewing.id
 await request('/viewing-appointments/'+viewing.id+'/assign','PATCH',{assigneeId:auth.otherStaff.user.id},auth.adminA.token)
 await request('/viewing-appointments/'+viewing.id+'/confirm','PATCH',{reason:'本批确认'},auth.adminA.token)
 const notice=await request('/notices','POST',{title:fixture.tag+'公开公告',content:'游客真实公开详情',publishTime:new Date(Date.now()-60000).toISOString().slice(0,19),targets:[{targetType:'GUEST'}]},auth.super.token)
 fixture.publicNoticeId=notice.id;fixture.publicNoticeIds=[notice.id]
 await request('/notices/'+notice.id+'/publish','PATCH',{publishTime:new Date(Date.now()-60000).toISOString().slice(0,19)},auth.super.token)
 check('动态详情使用真实房源/预约/公开公告夹具',housing.id>0&&viewing.id>0&&notice.id>0)
 fixture.publicNoticeIds=[notice.id]
}
async function run({auth,fixture,evidence,check,out}){
 fs.mkdirSync(out,{recursive:true})
 const inventory=routerInventory(),names=new Map(inventory.filter(r=>r.name).map(r=>[r.name,r.path]))
 evidence.browser={mode:'independent-headless',pages:[],errors:[],blocked:[],responses:[],screenshots:[],interactions:[],exclusions:[]}
 const b=evidence.browser
 // 无法与自动财务取数分离的详情整体延期，而非阻断请求后算完整通过。
 const excluded={'/admin/leases/:id':'详情自动读取押金/退款，待交易区块恢复后用真实租约核查'}
 function routeUrl(row){let u=row.path;let dyn
  if(u.includes('work-orders/:id'))dyn=fixture.reassignedOrderId||fixture.orderId
  else if(u.includes('feedbacks/:id'))dyn=fixture.feedbackId
  else if(u.includes('notices/:id'))dyn=row.group==='guest'?fixture.publicNoticeId:fixture.noticeId
  else if(u.includes('evaluations/:id'))dyn=fixture.evaluationId
  else if(u.includes('communities/:id'))dyn=fixture.A.id
  else if(u.includes('sys-users/:id'))dyn=auth.adminA.user.id
  else if(u.includes('residents/:id'))dyn=auth.resident.user.id
  else if(u.includes('resources/:id'))dyn=fixture.resourceId
  else if(u.includes('housings/:id'))dyn=fixture.housingId
  else if(u.includes('viewing-appointments/:id'))dyn=fixture.viewingId
  else if(u.includes('conversations/:id'))dyn=fixture.closedGroupId
  if(u.includes(':id')){assert(dyn,'缺少动态详情夹具 '+u);u=u.replace(':id',dyn)}
  if(u==='/resident/reservations/create')u+='?resourceId='+fixture.resourceId
  return u
 }
 for(const row of inventory)if(!excluded[row.path])routeUrl(row)
 const cases=[]
 for(const row of inventory.filter(r=>!r.redirect)){
  if(excluded[row.path]){b.exclusions.push({path:row.path,reason:excluded[row.path]});continue}
  cases.push({role:row.group==='admin'?(row.path==='/admin/system'?'super':'admin'):row.group,path:row.path,url:routeUrl(row),title:row.title,scope:'page-load-and-layout'})
 }
 const tabs={
  '/admin/community':['tree','housing','resources'],
  '/admin/dashboard':['overview','details'],
  '/admin/housing-lease':['applications','leases','housings','moveout','agreements'],
  '/admin/system':['users','configs','logs'],
  '/admin/residents':['list','relations'],
  '/admin/reservations':['reservations','violations'],
  '/resident/housing-lease':['lease','housing','application'],
  '/resident/reservations':['resource','viewing']
 }
 for(const [url,values] of Object.entries(tabs))for(const tab of values)cases.push({role:url==='/admin/system'?'super':url.startsWith('/admin')?'admin':'resident',url:url+'?tab='+tab,path:url,scope:'hub-tab-load-and-layout'})
 // 房源子分区不是独立route，但有实际不同表单/列表。
 for(const view of ['houses','viewings','list'])cases.push({role:'admin',path:'/admin/housing-lease',url:'/admin/housing-lease?tab=housings&view='+view,scope:'housing-subview'})
 const serializable=inventory.map(({redirectValue,...row})=>({...row,scope:excluded[row.path]?'deferred-financial-detail':row.redirect?'legacy-alias':'page-load-and-layout'}))
 fs.writeFileSync(out+'/route-inventory.json',JSON.stringify({date:new Date().toISOString(),source:'frontend/src/router/{admin,resident,staff,guest}.ts',widths:[1440,1024,768],navigation:'1440加载后依次切换1024/768，验证断点响应，别名单独加载',routes:serializable,cases,exclusions:b.exclusions,notes:['独立页面与Hub各Tab核查加载、归属、页面溢出，不代替所有按钮/全业务组合','居民租约Tab本批无租约，为真实空态；非空费用/协议与支付退款区块不在本次范围','系统支付配置只读/offline，不保存凭据或启用渠道','旧别名单宽度核查重定向，不重复计为独立功能页']},null,2))
 const browser=await chromium.launch({channel:'msedge',headless:true}),contexts={},pages={}
 let current=null
 try{
  for(const [role,session] of Object.entries({super:auth.super,admin:auth.adminA,resident:auth.resident,staff:auth.otherStaff,guest:null})){
   const context=await browser.newContext({viewport:{width:1024,height:900}});contexts[role]=context
   await context.route('**/*',route=>{
    const req=route.request(),u=new URL(req.url());if(!['127.0.0.1','localhost'].includes(u.hostname)||!['5181','8087'].includes(u.port)){b.blocked.push({path:u.pathname,reason:'非隔离地址'});return route.abort()}
    const offlineRead=req.method()==='GET'&&['/api/v1/payment/channels','/api/v1/payment/channel-config/alipay'].includes(u.pathname)
    if(u.pathname.startsWith('/api/')&&/payment|refund|renew/i.test(u.pathname)&&!offlineRead){b.blocked.push({path:u.pathname,reason:'暂停财务端点',case:current?.url});return route.abort()}
    if(u.pathname.startsWith('/api/')&&!['GET','HEAD'].includes(req.method())&&!/\/(?:notices|housings)\/\d+\/view$|\/notifications\/(?:\d+\/read|read-all)$/.test(u.pathname)){b.blocked.push({method:req.method(),path:u.pathname,reason:'非计划写操作',case:current?.url});return route.abort()}
    return route.continue()
   })
   if(session)await context.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify({token:s.token,user:s.user,expiresAt:Date.now()+s.expiresIn*1000})),session)
   const page=await context.newPage();pages[role]=page
   page.on('pageerror',e=>b.errors.push({role,case:current?.url,message:e.message}))
   page.on('response',r=>{if(r.url().includes('/api/')&&r.status()>=400)b.responses.push({role,case:current?.url,path:new URL(r.url()).pathname,status:r.status()})})
  }
  for(let n=0;n<cases.length;n++){
   const c=cases[n],page=pages[c.role];current=c
   for(const width of [1440,1024,768]){
    await page.setViewportSize({width,height:900});if(width===1440)await page.goto(frontend+c.url,{waitUntil:'networkidle'});await page.waitForTimeout(220)
    const info=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,loading:[...document.querySelectorAll('.el-loading-mask')].filter(e=>e.getBoundingClientRect().height>0).length,viteError:!!document.querySelector('vite-error-overlay'),text:document.body.innerText.slice(0,350),overflow:[...document.querySelectorAll('main *')].filter(e=>e.getBoundingClientRect().right>innerWidth+2&&e.getBoundingClientRect().width>0).slice(0,5).map(e=>({tag:e.tagName,classes:e.className}))}))
    const actual=new URL(page.url());b.pages.push({...c,width,...info,actual:actual.pathname+actual.search})
    check(c.role+' '+c.url+' '+width+'px加载结束',info.loading===0&&!info.viteError,{loading:info.loading})
    check(c.role+' '+c.url+' '+width+'px页面无横向溢出',info.scrollWidth<=width,{scrollWidth:info.scrollWidth})
    check(c.role+' '+c.url+' '+width+'px路由正确',actual.pathname===new URL(frontend+c.url).pathname&&!/页面不存在|无权访问该页面/.test(info.text))
    if(width===768){const file=out+'/bj-route-'+String(n+1).padStart(2,'0')+'-'+c.role+'-768.png';await page.screenshot({path:file,fullPage:true});b.screenshots.push(file)}
   }
   if(n%10===0)console.log('bj route '+(n+1)+'/'+cases.length+' '+c.url)
  }
  // 旧别名仅读导航；会产生创建弹窗的别名也不提交表单。
  for(const row of inventory.filter(r=>r.redirect)){
   if(!row.path||['/admin','/resident','/guest','/staff'].includes(row.path))continue
   if(row.path.includes('leases')){b.exclusions.push({path:row.path,reason:'旧租约别名透传支付深链，金融非空态另测'});continue}
   let url=routeUrl(row),role=row.group==='admin'?'super':row.group,page=pages[role];current={url,role}
   const to={query:{},params:{},path:row.path};let target=typeof row.redirectValue==='function'?row.redirectValue(to):row.redirectValue
   const expectedPath=typeof target==='string'?target:target.path||names.get(target.name)
   await page.setViewportSize({width:1024,height:900});await page.goto(frontend+url,{waitUntil:'networkidle'})
   const actual=new URL(page.url());check('旧别名 '+row.path+' 正确落地',actual.pathname===expectedPath,{actual:actual.pathname,expected:expectedPath})
   b.interactions.push({kind:'legacy-alias',from:row.path,to:actual.pathname+actual.search})
  }
  const admin=pages.admin
  for(const width of [768,1024]){
   await admin.setViewportSize({width,height:900});await admin.goto(frontend+'/admin/community',{waitUntil:'networkidle'})
   const button=admin.getByRole('button',{name:'打开管理菜单'})
   check('平板'+width+'px侧栏默认不占内容宽度',await admin.locator('.app-sidebar:visible').count()===0)
   await button.click();await admin.locator('.admin-menu-drawer').waitFor({state:'visible'});await admin.waitForTimeout(320)
   check('平板'+width+'px菜单覆盖展开',await admin.locator('.app-sidebar:visible').count()===1&&await button.getAttribute('aria-expanded')==='true')
   const file=out+'/def178-sidebar-open-'+width+'.png';await admin.screenshot({path:file,fullPage:true});b.screenshots.push(file)
   await admin.keyboard.press('Escape');await admin.locator('.admin-menu-drawer').waitFor({state:'hidden'})
   check('平板'+width+'pxEsc关闭菜单',await button.getAttribute('aria-expanded')==='false')
   await button.click();await admin.locator('.admin-menu-drawer').waitFor({state:'visible'});await admin.waitForTimeout(320);await admin.mouse.click(width-10,100);await admin.locator('.admin-menu-drawer').waitFor({state:'hidden'})
   check('平板'+width+'px点击蒙层关闭菜单',await button.getAttribute('aria-expanded')==='false')
   await button.click();await admin.locator('.admin-menu-drawer').waitFor({state:'visible'});await admin.waitForTimeout(320);await admin.locator('.admin-menu-drawer').getByRole('link',{name:'工单调度',exact:true}).click();await admin.waitForURL('**/admin/work-orders');await admin.locator('.admin-menu-drawer').waitFor({state:'hidden'})
   check('平板'+width+'px切页关闭菜单',await admin.locator('.app-sidebar:visible').count()===0)
  }
  await admin.setViewportSize({width:1440,height:900});await admin.waitForTimeout(200);check('桌面仍保留常驻侧栏',await admin.locator('.app-sidebar:visible').count()===1&&await admin.getByRole('button',{name:'打开管理菜单'}).count()===0)
  // 权限及只读交互代表项，而非只做成功页加载。
  const resident=pages.resident;await resident.goto(frontend+'/resident/conversations/'+fixture.closedGroupId,{waitUntil:'networkidle'})
  check('CLOSED群页面明确显示只读',await resident.getByText(/只读|已关闭|已结束/).count()>0)
  const guest=pages.guest;await guest.goto(frontend+'/guest/housings/'+fixture.housingId,{waitUntil:'networkidle'})
  check('游客房源详情保留注册登录看房引导',await guest.getByText(/登录.*预约|注册.*预约|登录后/).count()>0)
  await resident.goto(frontend+'/resident/work-orders/'+fixture.reassignedOrderId,{waitUntil:'networkidle'})
  check('居民改派工单真实详情加载',await resident.getByText(fixture.tag+'改派边界').count()>0)
  check('浏览器无未处理脚本错误',b.errors.length===0,{count:b.errors.length})
  check('浏览器未触发暂停金融接口',!b.blocked.some(x=>x.reason==='暂停财务端点'))
  check('浏览器无计划外写操作',!b.blocked.some(x=>x.reason==='非计划写操作'))
 }finally{await browser.close();fs.writeFileSync(out+'/browser-pages.json',JSON.stringify(b,null,2))}
}
module.exports={run,fixtures,routerInventory}
