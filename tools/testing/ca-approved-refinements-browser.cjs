/* 独立headless浏览器只连接CA隔离服务；不接管或关闭用户浏览器。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require('C:/Users/17841/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const {request}=require('./ca-approved-refinements-regression.cjs');
const dir='docs/50_系统测试/evidence/ca-approved-refinements-20261016',checks=[],errors=[];
function check(name,value){assert(value,name);checks.push({name,passed:true})}
(async()=>{
 const f=JSON.parse(fs.readFileSync('output/runtime/ca-fixture-private.json','utf8'));
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});let blockedTrading=0,external=0;
 async function open(session,width=1280){const context=await browser.newContext({viewport:{width,height:900}});await context.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify({token:s.token,user:s.user,expiresAt:Date.now()+s.expiresIn*1000})),session);
  await context.route('**/*',r=>{const u=new URL(r.request().url());if(!['127.0.0.1','localhost'].includes(u.hostname)||!['5181','8087'].includes(u.port)){external++;return r.abort()}if(/first-payments|rent-payments|renew-payments|payment\/orders|deposit-refunds|\/payments(?:\/|$)/.test(u.pathname)){blockedTrading++;return r.abort()}return r.continue()});
  const p=await context.newPage();p.on('pageerror',e=>errors.push(e.message));return p;
 }
 try {
  const root=await open(f.superSession),admin=await open(f.adminSession),resident=await open(f.residentSession);
  const notice=await request('/notices/'+f.noticeId,'GET',undefined,f.superSession.token);
  await root.goto('http://127.0.0.1:5181/admin/notices');await root.waitForLoadState('networkidle');await root.locator('button.notice-card').filter({hasText:notice.title}).first().click();
  await root.getByRole('button',{name:'修订正文',exact:true}).click();const canvas=root.getByRole('textbox',{name:'公告正文编辑器'});await canvas.waitFor();
  check('公告编辑以文稿区而非旧纯文本/独立图片表单为主体',await canvas.getAttribute('contenteditable')==='true'&&await root.locator('.notice-form textarea').count()===0);
  await canvas.fill('CA浏览器实时撰写的新正文');await canvas.press('ControlOrMeta+A');await root.getByRole('button',{name:'加粗',exact:true}).click();await root.getByRole('button',{name:'居中',exact:true}).click();await canvas.press('ArrowRight');await canvas.press('End');await canvas.press('Enter');
  await root.getByLabel('选择公告图片').setInputFiles('tools/testing/fixtures/ca-test.png');await root.locator('.notice-editor-canvas img').waitFor();await root.locator('.notice-editor-canvas img').click();await root.getByLabel('图片宽度比例').selectOption('50');
  await root.getByRole('button',{name:'预览正文',exact:true}).click();await root.locator('.document-sheet .notice-rich-body img').waitFor();check('即时预览保留居中/加粗/半宽真实图片',await root.locator('.document-sheet strong').count()>0&&(await root.locator('.document-sheet .notice-rich-body img').getAttribute('style')).includes('50%'));
  await root.setViewportSize({width:768,height:900});await root.evaluate(()=>window.scrollTo(0,0));await root.screenshot({path:dir+'/notice-editor-768.png',fullPage:true});check('768宽度编辑器无横向溢出',await root.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  const response=root.waitForResponse(r=>r.url().endsWith('/notices/'+f.noticeId+'/revisions')&&r.request().method()==='POST');await root.getByRole('button',{name:'保存修订并生效',exact:true}).click();const saved=await (await response).json();check('编辑页面显式保存后新版生效',saved.code===200);
  const revised=await request('/notices/'+f.noticeId,'GET',undefined,f.superSession.token);check('正文是结构化保存，保留原发布状态',revised.status==='PUBLISHED'&&revised.contentVersion===notice.contentVersion+1&&revised.bodyJson.includes('bold')&&revised.bodyJson.includes('center'));
  await resident.goto('http://127.0.0.1:5181/resident/notices/'+f.noticeId);await resident.locator('.notice-rich-body strong').waitFor();check('居民端使用相同结构实时读取新版正文和图片',await resident.locator('.notice-rich-body img').count()===1&&(await resident.locator('.notice-rich-body').innerText()).includes('CA浏览器实时撰写的新正文'));await resident.screenshot({path:dir+'/notice-resident-render.png',fullPage:true});
  await root.setViewportSize({width:1024,height:900});await root.goto('http://127.0.0.1:5181/admin/housing-lease?tab=housings');await root.waitForLoadState('networkidle');await root.locator('.hub-panel:visible .filter-bar .el-select').first().click();await root.locator('.el-select-dropdown__item:visible').filter({hasText:f.community.name}).click();
  const card=root.locator('.house-card').filter({hasText:'101'}).filter({hasText:f.community.name});await card.first().getByRole('button',{name:'管理房屋图片',exact:true}).click();
  const initialImages=await request('/houses/'+f.house.id+'/images','GET',undefined,f.superSession.token);const album=root.getByRole('dialog',{name:'管理房屋图片'});await album.getByLabel('上传房屋图片').setInputFiles(['tools/testing/fixtures/ca-test.png','tools/testing/fixtures/ca-test.png','tools/testing/fixtures/ca-test.png']);await album.getByText('已成功3/3',{exact:true}).waitFor({timeout:15000});
  const images=await request('/houses/'+f.house.id+'/images','GET',undefined,f.superSession.token);check('真实浏览器一次选三图，顺序串行保存到同一房屋',images.length===initialImages.length+3);await root.screenshot({path:dir+'/album-multiple-1024.png',fullPage:true});await album.getByRole('button',{name:'关闭',exact:true}).click();
  const spare=root.locator('.house-card').filter({hasText:'103'}).filter({hasText:f.community.name});await spare.first().getByRole('button',{name:'挂牌',exact:true}).click();check('单套挂牌显示同时配置看房和默认/自定义选择',await root.getByText('同时配置可预约看房时段',{exact:true}).isVisible()&&await root.getByText('自定义',{exact:true}).isVisible());await root.getByRole('dialog',{name:'房屋挂牌'}).getByRole('button',{name:'取消',exact:true}).click();
  let navigations=0;admin.on('request',req=>{if(req.isNavigationRequest()&&req.resourceType()==='document')navigations++});await admin.goto('http://127.0.0.1:5181/admin/housing-lease?tab=applications');await admin.waitForLoadState('networkidle');const beforeNav=navigations;
  const run=String(Date.now()),applicant=await request('/residents/admin-create','POST',{communityId:f.community.id,realName:'CA新待审'+run.slice(-5),phone:'138'+run.slice(-8),username:'ca待审'+run.slice(-5)},f.superSession.token);
  const applicantSession=await request('/auth/resident/login','POST',{username:applicant.username,password:applicant.initialPassword});const app=await request('/residence-applications','POST',{houseId:f.house.id,housingId:f.housing.id,relationType:'TENANT',remark:'CA页面新鲜度回归'},applicantSession.token);
  const row=admin.locator('.hub-panel:visible tbody tr').filter({hasText:applicant.realName});await row.waitFor({timeout:38000});check('新申请到达后当前列表无需刷新浏览器即可显示',navigations===beforeNav&&await row.isVisible());
  await request('/residence-applications/'+app.id+'/reject','PATCH',{reason:'CA隔离回归，不影响正常数据'},f.adminSession.token);await admin.getByRole('tab',{name:'租约管理',exact:true}).click();await admin.getByRole('tab',{name:'入住申请',exact:true}).click();await row.getByText('已驳回',{exact:true}).waitFor({timeout:10000});check('Tab往返重新读取列表而非永久缓存旧状态',navigations===beforeNav);await admin.screenshot({path:dir+'/applications-live-refresh.png',fullPage:true});
  check('浏览器没有未捕获脚本错误',errors.length===0);check('未执行交易请求和外部网络访问',blockedTrading===0&&external===0);
  fs.writeFileSync(dir+'/browser.json',JSON.stringify({checkedAt:new Date().toISOString(),checks,passed:checks.length,uncaughtErrors:errors,blockedTradingRequests:blockedTrading,externalRequests:external,userBrowserOperated:false},null,2));console.log(JSON.stringify({headlessBrowserPassed:checks.length,userBrowserOperated:false}));
 } finally {await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
