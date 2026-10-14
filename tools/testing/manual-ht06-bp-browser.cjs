/* 独立headless浏览器，仅BP隔离服务；不接管或关闭用户浏览器。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path'),{chromium}=require(path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const {request,dir}=require('./manual-ht06-bp-regression.cjs');
(async()=>{
 const f=JSON.parse(fs.readFileSync('output/runtime/bp-fixture-private.json','utf8')),browser=await chromium.launch({channel:'msedge',headless:true}),errors=[],checks=[];
 const check=(name,ok)=>{assert(ok,name);checks.push({name,passed:true})};
 async function pageFor(session){const c=await browser.newContext({viewport:{width:1024,height:768}});await c.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify({token:s.token,user:s.user,expiresAt:Date.now()+s.expiresIn*1000})),session);await c.route('**/*',r=>{const u=new URL(r.request().url());if(!['localhost','127.0.0.1'].includes(u.hostname)||!['5181','8087'].includes(u.port)||u.pathname.startsWith('/api/v1')&&/payment|refund|renew/i.test(u.pathname))return r.abort();return r.continue()});const p=await c.newPage();p.on('pageerror',e=>errors.push(e.message));return p}
 try {
  const resident=await pageFor(f.renterSession);
  await resident.goto('http://127.0.0.1:5181/resident/conversations/'+f.conversationId);await resident.waitForLoadState('networkidle');
  check('居民群聊显示待支付不是待签约',await resident.getByText(/租约状态 待支付首期费用/).isVisible());
  await resident.getByRole('button',{name:'会话业务功能'}).click();await resident.getByRole('button',{name:'预约本租约房源看房'}).click();
  const d=resident.getByRole('dialog',{name:'在本租约群聊预约看房'});await d.waitFor();await d.locator('.calendar-cell').filter({hasText:new RegExp('^'+Number(f.future.split('-')[2])+'$')}).click();
  await d.locator('.slot-card').filter({hasText:'11:00 ~ 12:00'}).click();await d.getByRole('button',{name:'下一步',exact:true}).click();
  let response=resident.waitForResponse(r=>r.url().endsWith(`/conversations/${f.conversationId}/viewing-appointments`)&&r.request().method()==='POST');
  await d.getByRole('button',{name:'提交预约',exact:true}).click();const created=(await (await response).json()).data;
  check('真实群聊按钮提交会话预约并保持当前会话',created.leaseId===f.lease.id&&created.housingId===f.listing.id&&resident.url().endsWith('/conversations/'+f.conversationId));
  await d.waitFor({state:'hidden'});
  const card=resident.getByRole('button',{name:/租赁协议 #.*查看协议快照/}).first();check('协议业务消息渲染为可点击快照卡片',await card.isVisible());await card.click();
  const agreementDialog=resident.getByRole('dialog',{name:/租赁协议 ·/});await agreementDialog.waitFor();check('点击消息在群聊内读具体协议',await resident.getByText('房屋',{exact:false}).count()>0);
  await resident.screenshot({path:dir+'/resident-agreement-message.png',fullPage:true});
  const admin=await pageFor(f.adminSession);await admin.goto('http://127.0.0.1:5181/admin/residents?tab=relations');await admin.waitForLoadState('networkidle');
  await admin.locator('.resident-select .el-select__wrapper').click();await admin.locator('.el-select-dropdown__item').filter({hasText:'BP主住户'}).click();
  await admin.getByText('BP同住成员 · BP同住成员').waitFor();check('主关系行内直接显示同住成员及账号',await admin.getByText('BP同住成员 · BP同住成员').isVisible());
  const row=admin.locator('.el-table__row').filter({hasText:'BP主住户'});await row.getByRole('button',{name:'搬出登记',exact:true}).click();
  const move=admin.getByRole('dialog',{name:'搬出登记',exact:true});await move.getByText(/主关系账号：.*用户名 BP主住户/).waitFor();await move.getByText(/用户名 BP同住成员/).waitFor();check('迁出首层同时显示主账号和成员账户',await move.getByText(/用户名 BP同住成员/).isVisible());
  await move.getByRole('button',{name:'确认整户迁出'}).click();const second=admin.locator('.el-message-box');await second.getByText('第二次确认主住户迁出').waitFor();check('二次确认仍列出完整整户影响',await second.getByText(/BP同住成员/).isVisible());
  await admin.screenshot({path:dir+'/household-second-confirmation.png',fullPage:true});await second.getByRole('button',{name:'返回核对'}).click();
  check('取消二次确认未改变主成员关系',(await request(`/residence-relations/${f.owner.relationId}/dependents`,'GET',undefined,f.adminSession.token)).length===1);
  await admin.goto('http://127.0.0.1:5181/admin/community?tab=housing');await admin.waitForLoadState('networkidle');
  check('挂牌卡片分开显示房屋与发布及占用状态',await admin.getByText(/房屋：空置/).count()>0&&await admin.getByText(/待支付首期费用/).count()>0);
  await admin.setViewportSize({width:768,height:768});check('挂牌768无整页横向溢出',await admin.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await admin.screenshot({path:dir+'/housing-states-768.png',fullPage:true});
  await admin.goto('http://127.0.0.1:5181/admin/conversations?id='+f.conversationId);await admin.waitForLoadState('networkidle');await admin.getByRole('button',{name:'会话业务功能'}).click();await admin.getByRole('button',{name:'发出 / 查看租赁协议'}).click();
  await admin.getByRole('dialog',{name:'本租约群聊 · 租赁协议'}).waitFor();check('普通管理员在群聊内打开协议面板',await admin.getByRole('dialog',{name:'本租约群聊 · 租赁协议'}).isVisible());
  const root=await pageFor(f.superSession);await root.goto('http://127.0.0.1:5181/admin/community?communityId='+f.community.id);await root.waitForLoadState('networkidle');await root.getByRole('button',{name:'删除',exact:true}).click();
  await root.getByText('禁止删除社区',{exact:true}).waitFor();check('超管UI明确有效关系及进行中租约并禁止删除',await root.locator('.el-message-box').getByText(/有效 2.*进行中 1/).isVisible());await root.screenshot({path:dir+'/community-delete-protected.png',fullPage:true});
  check('浏览器pageerror为0',errors.length===0);
  fs.writeFileSync(dir+'/browser.json',JSON.stringify({date:'2026-10-15',checks,pageerrors:errors,transactionsExecuted:false},null,2));console.log(JSON.stringify({checks:checks.length,pageerrors:errors.length}));
 } finally {await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
