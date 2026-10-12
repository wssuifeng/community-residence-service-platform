/* 独立headless浏览器，不接管用户的浏览器会话。 */
const assert=require('node:assert/strict'),path=require('node:path'),{chromium}=require(path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
module.exports=async function(session,fixture,evidence,request){
 const browser=await chromium.launch({channel:'msedge',headless:true}),context=await browser.newContext({viewport:{width:1024,height:768}}),page=await context.newPage(),errors=[]
 page.on('pageerror',e=>errors.push(e.message));const check=(name,ok)=>{assert(ok,name);evidence.checks.push({name,passed:true})}
 await context.route('**/*',route=>{const u=new URL(route.request().url());if(!['127.0.0.1','localhost'].includes(u.hostname)||!['5181','8087'].includes(u.port)||(u.pathname.startsWith('/api/')&&/payment|refund|renew/i.test(u.pathname)))return route.abort();return route.continue()})
 await context.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify(s)),{token:session.token,user:session.user,expiresAt:Date.now()+session.expiresIn*1000})
 try{
  await page.goto('http://127.0.0.1:5181/admin/residents');await page.waitForLoadState('networkidle');await page.getByRole('button',{name:'代建居民',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'代建居民',exact:true});await dialog.waitFor();check('默认明确同时登记关系',await dialog.getByText('同时登记居住关系',{exact:true}).isVisible())
  await dialog.getByText('请选择社区（仅可选您管理的社区）',{exact:true}).click();await page.getByRole('option',{name:fixture.tag+'居民录入',exact:true}).click()
  await dialog.getByPlaceholder('居民真实姓名').fill(fixture.tag+'浏览器');await dialog.getByPlaceholder('用于登录与联系').fill(fixture.phone);await dialog.getByPlaceholder('留空按手机号生成').fill(fixture.tag+'_browser');await dialog.getByPlaceholder('选填，与居民资料一致').fill('browser@example.com')
  await dialog.locator('.el-select').filter({hasText:'请选择楼栋'}).getByRole('combobox').click();await page.getByRole('option',{name:'测试楼',exact:true}).click()
  await dialog.locator('.el-select').filter({hasText:'请选择单元'}).getByRole('combobox').click();await page.getByRole('option',{name:'1单元',exact:true}).click()
  await dialog.locator('.el-select').filter({hasText:'请选择空置房屋'}).getByRole('combobox').click();await page.getByRole('option',{name:/104 · 1层/}).click()
  check('表单1024无页面级横向溢出',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1))
  await page.screenshot({path:'output/playwright/bh-resident-form-1024-20261011.png',fullPage:true})
  await page.setViewportSize({width:768,height:768});await page.waitForTimeout(400);evidence.layout768=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll('body *')].map(e=>({tag:e.tagName,cls:e.className,right:e.getBoundingClientRect().right,width:e.getBoundingClientRect().width,text:e.textContent?.slice(0,50)})).filter(e=>e.right>innerWidth+1&&e.width>0).slice(0,14)}));await page.screenshot({path:'output/playwright/bh-resident-form-768-diagnostic.png',fullPage:true});check('表单768无页面级横向溢出',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await page.screenshot({path:'output/playwright/bh-resident-form-768-20261011.png',fullPage:true})
  const response=page.waitForResponse(r=>r.url().endsWith('/api/v1/residents/admin-create')&&r.request().method()==='POST');await dialog.getByRole('button',{name:'代建账号',exact:true}).click();const body=await (await response).json();assert.equal(body.code,200,'真实代建表单提交失败');const created=body.data
  await page.getByRole('dialog',{name:'代建成功'}).getByText(/居住关系已登记/).waitFor();check('真实表单提交返回账号与关系',created.relationId>0&&created.houseId===fixture.houseId)
  check('真实表单邮箱和关系落库',(await request('/residents/'+created.residentId,'GET',undefined,session.token)).email==='browser@example.com')
  await page.getByRole('button',{name:'我已转交，关闭',exact:true}).click();await page.getByRole('button',{name:'批量导入',exact:true}).click();await page.getByRole('dialog',{name:'批量导入居民'}).getByText(/房屋ID、关系类型和入住日期须全有或全无/).waitFor();check('导入明确新模板关系与旧模板账号模式',await page.getByText(/旧四列模板继续支持/).isVisible())
  check('独立浏览器无脚本异常',errors.length===0);evidence.browser={pageErrors:errors,screenshots:['output/playwright/bh-resident-form-1024-20261011.png','output/playwright/bh-resident-form-768-20261011.png']};return created
 }catch(error){evidence.browserFailure={message:error.message,body:(await page.locator('body').innerText()).slice(-5000).replace(/(初始密码\s*\n)[^\n]+/g,'$1[REDACTED]')};throw error}finally{await browser.close()}
}
