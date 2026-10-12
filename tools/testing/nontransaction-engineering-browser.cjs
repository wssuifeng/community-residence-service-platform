/* 独立headless浏览器；只能连接本机隔离5181/8087，交易接口全部阻断，不接管用户窗口。 */
const assert=require('node:assert/strict'),path=require('node:path'),{sql,frontend,request,id}=require('./nontransaction-fixture-helpers.cjs')
const {chromium}=require(process.env.PLAYWRIGHT_NODE_MODULE_PATH??path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
async function run({auth,fixture,evidence,check}){
 const browser=await chromium.launch({channel:'msedge',headless:true}), pages={}
 evidence.browser={mode:'independent-headless',pages:[],blockedTransactions:[],pageErrors:[],screenshots:[]}
 async function make(role,session){
  const context=await browser.newContext({viewport:{width:1024,height:768}})
  await context.route('**/*',route=>{
   const req=route.request(),u=new URL(req.url())
   if(!['127.0.0.1','localhost'].includes(u.hostname))return route.abort()
   if(!['5181','8087'].includes(u.port))return route.abort()
   if(u.pathname.startsWith('/api/')&&/payment|refund|renew/i.test(u.pathname)&&u.pathname!=='/api/v1/payment/channels'){evidence.browser.blockedTransactions.push(u.pathname);return route.abort()}
   if(u.pathname.startsWith('/api/')&&!['GET','HEAD'].includes(req.method())&&!/\/(?:notifications\/(?:\d+\/read|read-all)|conversations\/\d+\/read|housings\/\d+\/view)$/.test(u.pathname)){evidence.browser.pageErrors.push('unexpected-write-blocked '+req.method()+' '+u.pathname);return route.abort()}
   return route.continue()
  })
  await context.addInitScript(session=>{
   if(session)sessionStorage.setItem('crsp_session',JSON.stringify(session))
   window.__bfFrames=[];window.__bfSubscribed=false
   const Native=window.WebSocket
   window.WebSocket=class extends Native{
    constructor(...args){super(...args);this.addEventListener('message',event=>{if(typeof event.data==='string')window.__bfFrames.push({at:Date.now(),text:event.data})})}
    send(data){if(typeof data==='string'&&data.includes('SUBSCRIBE')&&data.includes('/user/queue/notifications'))window.__bfSubscribed=true;return super.send(data)}
   }
  },session?{token:session.token,user:session.user,expiresAt:Date.now()+session.expiresIn*1000}:null)
  const page=await context.newPage();page.on('pageerror',e=>evidence.browser.pageErrors.push(role+': '+e.message));pages[role]=page;return page
 }
 async function shot(page,name){const file='output/playwright/bf-'+name+'-20261011.png';await page.screenshot({path:file,fullPage:true});evidence.browser.screenshots.push(file)}
 try{
  const res=await make('resident',auth.resident), admin=await make('admin',auth.adminA), staff=await make('staff',auth.staff), superPage=await make('super',auth.super), guest=await make('guest')
  // 原DEF-146旧页面分支：先打开双方在租页面，再通过现行迁出端点变更并刷新。
  const houseId=id(fixture.housesA[4].id), residentId=id(auth.resident.user.id), communityId=id(fixture.A.id)
  const relation=await request('/residence-relations','POST',{residentId,houseId,relationType:'TENANT',moveInDate:'2026-10-01'},auth.adminA.token)
  sql(`INSERT INTO lease_record(tenant_id,community_id,house_id,start_date,end_date,monthly_rent,deposit,status,agreement_status,remark) VALUES(${residentId},${communityId},${houseId},'2026-10-01','2027-10-01',1000,0,'ACTIVE','SIGNED','${fixture.tag}LIVE')`)
  const liveId=Number(sql(`SELECT id FROM lease_record WHERE remark='${fixture.tag}LIVE'`)[0][0]), lease=await request('/leases/'+liveId,'GET',undefined,auth.resident.token)
  await res.goto(frontend+'/resident/housing-lease?tab=lease',{waitUntil:'networkidle'});await admin.goto(frontend+'/admin/leases/'+liveId,{waitUntil:'networkidle'})
  let row=res.locator('.lease-row').filter({has:res.getByRole('heading',{name:lease.houseLocation,exact:true})})
  check('DEF-146变更前两端真实页面显示在租',(await row.innerText()).includes('在租')&&(await admin.locator('body').innerText()).includes('在租'))
  await request('/residence-relations/'+relation.id+'/move-out','PATCH',{moveOutDate:new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai'}).format(new Date())},auth.adminA.token)
  await res.reload({waitUntil:'networkidle'});await admin.reload({waitUntil:'networkidle'})
  row=res.locator('.lease-row').filter({has:res.getByRole('heading',{name:lease.houseLocation,exact:true})})
  check('DEF-146旧页面刷新后双方显示已搬出',(await row.innerText()).includes('已搬出')&&(await admin.locator('body').innerText()).includes('已搬出'))
  await shot(res,'migration-resident');await shot(admin,'migration-admin')
  // WebSocket事件实测：等待真实SUBSCRIBE完成而非固定等待，通知触发来自真实确认动作。
  await res.goto(frontend+'/resident/notifications',{waitUntil:'networkidle'});await res.waitForFunction(()=>window.__bfSubscribed,{},{timeout:15000})
  const appointment=await request('/viewing-appointments','POST',{housingId:fixture.housing.id,appointmentDate:fixture.appointmentDate,startTime:'12:00',endTime:'13:00'},auth.resident.token)
  const start=Date.now();await request('/viewing-appointments/'+appointment.id+'/confirm','PATCH',{reason:fixture.tag+'WS确认'},auth.adminA.token)
  await res.waitForFunction(start=>window.__bfFrames.some(e=>e.at>=start&&e.text.includes('看房预约已确认')),start,{timeout:5000})
  const frame=await res.evaluate(start=>window.__bfFrames.find(e=>e.at>=start&&e.text.includes('看房预约已确认')),start)
  check('真实在线WebSocket确认通知在3秒内抵达',frame.at-start<=3000,{latencyMs:frame.at-start})
  await res.reload({waitUntil:'networkidle'});const notice=res.locator('.notification-items > li').filter({hasText:'看房预约已确认'}).first();await notice.click();await res.waitForURL('**/resident/viewing-appointments/*')
  check('居民通知真实点击到看房详情深链',new URL(res.url()).pathname==='/resident/viewing-appointments/'+appointment.id)
  await staff.goto(frontend+'/staff/notifications',{waitUntil:'networkidle'});const staffNotice=staff.locator('.notification-items > li').filter({hasText:/带看|分配/}).first();if(await staffNotice.count()){await staffNotice.click();await staff.waitForURL('**/staff/viewings');check('带看分配通知真实点击到人员工作台',new URL(staff.url()).pathname==='/staff/viewings')}
  await staff.goto(frontend+'/staff/notifications',{waitUntil:'networkidle'});await staff.locator('.notification-items > li').filter({hasText:'新工单派发'}).first().click();await staff.waitForURL('**/staff/work-orders/'+fixture.workOrderId);check('工单派单通知真实点击到本人工单详情',new URL(staff.url()).pathname==='/staff/work-orders/'+fixture.workOrderId)
  // 同ID不同身份空间夹具，记录实际非交易通知读取/已读隔离；不重跑支付成功事件。
  const collisionResident=await request('/auth/resident/login','POST',{username:'resident1',password:'Resident123456'})
  check('通知撞号前置身份确实同ID',collisionResident.user.id===auth.super.user.id)
  const collisionId=id(collisionResident.user.id), titleR=fixture.tag+'居民身份通知',titleU=fixture.tag+'管理身份通知'
  sql(`INSERT INTO notification(seq,user_id,owner_type,title,content,type,source_type,is_read) SELECT COALESCE(MAX(seq),0)+1,${collisionId},'RESIDENT','${titleR}','${fixture.tag}','SYSTEM','SYSTEM',0 FROM notification`)
  sql(`INSERT INTO notification(seq,user_id,owner_type,title,content,type,source_type,is_read) SELECT COALESCE(MAX(seq),0)+1,${collisionId},'USER','${titleU}','${fixture.tag}','SYSTEM','SYSTEM',0 FROM notification`)
  const noticesR=await request('/notifications?size=100','GET',undefined,collisionResident.token),noticesU=await request('/notifications?size=100','GET',undefined,auth.super.token)
  check('同ID居民与超管通知双向不串号',noticesR.records.some(n=>n.title===titleR)&&!noticesR.records.some(n=>n.title===titleU)&&noticesU.records.some(n=>n.title===titleU)&&!noticesU.records.some(n=>n.title===titleR))
  const rNotice=noticesR.records.find(n=>n.title===titleR)
  check('同ID超管不能已读居民身份通知',(await request('/notifications/'+rNotice.id+'/read','PATCH',{},auth.super.token,true)).code!==200)
  sql(`DELETE FROM notification WHERE title IN ('${titleR}','${titleU}') AND content='${fixture.tag}'`)
  // 界面检查仅限明确列出的核心页，交易面板被主动阻断不判通过。
  const cases=[['admin','/admin/residents'],['admin','/admin/residents/'+residentId],['admin','/admin/work-orders'],['admin','/admin/reservations'],['admin','/admin/feedbacks'],['admin','/admin/notices'],['admin','/admin/housing-lease?tab=housings&view=viewings'],['super','/admin/system?tab=logs'],['resident','/resident/housing-lease?tab=lease'],['resident','/resident/reservations?tab=viewing'],['resident','/resident/viewing-appointments/'+fixture.appointment.id],['resident','/resident/profile'],['staff','/staff/viewings'],['staff','/staff/work-orders'],['guest','/guest/housings/'+fixture.housing.id],['guest','/auth/login']]
  for(const [role,url] of cases){const page=pages[role];for(const width of [1024,768,...(['resident','staff','guest'].includes(role)?[360]:[])]){
   await page.setViewportSize({width,height:800});await page.goto(frontend+url,{waitUntil:'networkidle'});await page.locator('#app').waitFor();
   check(role+' '+url+' '+width+'px无编译错误且页面已渲染',await page.locator('vite-error-overlay').count()===0&&(await page.locator('#app').innerText()).trim().length>15)
   const layout=await page.evaluate(()=>({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth>innerWidth,offenders:[...document.querySelectorAll('#app *')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.right>innerWidth+2&&r.left>=0}).slice(0,5).map(e=>({tag:e.tagName,class:e.className,right:Math.round(e.getBoundingClientRect().right)}))}))
   evidence.browser.pages.push({role,url,width,...layout});if(role==='super'&&width===768)await shot(page,'logs-page-768');if(width>=768)check(role+' '+url+' '+width+'px无页面级溢出',!layout.overflow,{scrollWidth:layout.scrollWidth});if(layout.overflow){evidence.observations.push({item:'UI',role,url,width,scrollWidth:layout.scrollWidth,classification:'待对照既有响应式设计；体验意见不自动判为实现缺陷'});await shot(page,'overflow-'+role+'-'+url.split('?')[0].replace(/\W+/g,'-')+'-'+width)}
  }}
  // 日志详情真实交互：使用现有搜索与行详情，不猜后端接口。
  await superPage.setViewportSize({width:768,height:800});await superPage.goto(frontend+'/admin/system?tab=logs',{waitUntil:'networkidle'})
  const search=superPage.getByPlaceholder('操作人/描述');await search.fill('办理居民迁出');await search.press('Enter');await superPage.locator('.el-table__body-wrapper tbody tr').first().click()
  const logDialog=superPage.getByRole('dialog',{name:'操作日志详情'});await logDialog.waitFor();check('日志详情展示安全日期与关系ID',(await logDialog.locator('.request-params').innerText()).includes('moveOutDate'))
  await shot(superPage,'safe-log-detail-768');await logDialog.getByRole('button',{name:'关闭',exact:true}).click()
  // 空态与受控加载失败/恢复：只模拟自己的测试响应，不把此响应当产品故障证据。
  await admin.route('**/api/v1/work-orders?*',route=>route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({code:500,message:'BF隔离模拟加载失败',data:null})}))
  await admin.goto(frontend+'/admin/work-orders',{waitUntil:'networkidle'});check('受控列表失败结束加载且给出错误提示',await admin.locator('.el-loading-mask:visible').count()===0&&(await admin.locator('body').innerText()).includes('BF隔离模拟加载失败'))
  await admin.unroute('**/api/v1/work-orders?*');await admin.reload({waitUntil:'networkidle'});check('列表重新加载后恢复且无编译错误',await admin.locator('vite-error-overlay').count()===0&&await admin.locator('.el-loading-mask:visible').count()===0)
  check('独立浏览器无未捕获脚本错误',evidence.browser.pageErrors.length===0,{errors:evidence.browser.pageErrors})
 }finally{try{sql(`DELETE FROM notification WHERE content='${fixture.tag}' AND title IN ('${fixture.tag}居民身份通知','${fixture.tag}管理身份通知')`)}finally{await browser.close()}}
}
module.exports={run}
