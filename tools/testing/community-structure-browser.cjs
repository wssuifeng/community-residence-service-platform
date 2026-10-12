/* 独立headless浏览器，只连接隔离实例，不接管用户浏览器。 */
const assert=require('node:assert/strict'),path=require('node:path'),{chromium}=require(process.env.PLAYWRIGHT_NODE_MODULE_PATH??path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
module.exports=async function(session,fixture,evidence,request){
 const browser=await chromium.launch({channel:'msedge',headless:true});const context=await browser.newContext({viewport:{width:1024,height:768}});const page=await context.newPage();const errors=[]
 page.on('pageerror',error=>errors.push(error.message))
 await context.route('**/*',route=>{const u=new URL(route.request().url());if(!['127.0.0.1','localhost'].includes(u.hostname)||!['5181','8087'].includes(u.port))return route.abort();if(u.pathname.startsWith('/api/')&&/payment|refund|renew/i.test(u.pathname))return route.abort();return route.continue()})
 await context.addInitScript(s=>sessionStorage.setItem('crsp_session',JSON.stringify(s)),{token:session.token,user:session.user,expiresAt:Date.now()+session.expiresIn*1000})
 function check(name,ok){assert(ok,name);evidence.checks.push({name,passed:true})}
 try {
  await page.goto('http://127.0.0.1:5181/admin/community?communityId='+fixture.communityId);await page.waitForLoadState('networkidle');
  const first=page.locator(`[data-house-id="${fixture.houseId}"]`),second=page.locator(`[data-house-id="${fixture.secondHouseId}"]`);
  if(!await first.isVisible()){await page.locator('.filter-select').first().click();await page.getByRole('option',{name:'9层楼',exact:true}).click();await page.waitForLoadState('networkidle')}
  await first.waitFor();await second.waitFor();
  await first.click({modifiers:['Control']});await second.click({modifiers:['Control']});await first.click({button:'right'});
  const menu=page.getByRole('button',{name:/配置所选.*2.*套房屋户型/});await menu.waitFor();check('右键保留两个已选卡片',await menu.isVisible());await menu.click();
  await page.getByPlaceholder('如：三室一厅一卫，留空清除').fill('三室两厅两卫');await page.getByRole('button',{name:'保存',exact:true}).click();await page.getByRole('dialog',{name:'配置所选房屋户型'}).waitFor({state:'hidden'});await page.waitForLoadState('networkidle');
  check('真实卡片弹窗保存后房屋同步',(await request('/houses/'+fixture.houseId,'GET',undefined,session.token)).layout==='三室两厅两卫');
  check('真实弹窗保存后挂牌同步',(await request('/housings/'+fixture.housingId,'GET',undefined,session.token)).layout==='三室两厅两卫');
  await page.evaluate(()=>scrollTo(0,0));await page.waitForTimeout(350);
  await page.screenshot({path:'output/playwright/bg-structure-1024-20261011.png',fullPage:true});
  check('1024结构页无页面级横向溢出',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.setViewportSize({width:768,height:768});await page.screenshot({path:'output/playwright/bg-structure-768-20261011.png',fullPage:true});check('768结构页无页面级横向溢出',await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.setViewportSize({width:1024,height:768});await first.hover();await first.getByRole('button',{name:'901 更多操作',exact:true}).click();await page.getByRole('menuitem',{name:'编辑房屋',exact:true}).click();
  const houseEdit=page.getByRole('dialog',{name:'编辑房屋：901',exact:true});await houseEdit.waitFor();check('单屋户型实际可见而非原生template隐藏',await houseEdit.getByPlaceholder('如：2室1厅1卫').isVisible());check('单屋表单上限随9层楼属性',await houseEdit.getByRole('spinbutton').first().getAttribute('aria-valuemax')==='9');await houseEdit.getByRole('button',{name:'取消',exact:true}).click();
  await page.getByRole('button',{name:'编辑社区',exact:true}).click();
  const edit=page.getByRole('dialog',{name:'编辑社区'});await edit.waitFor();check('社区表单使用责任人账号选择',await edit.getByText('社区责任人',{exact:true}).isVisible());
  check('电话只读不能独立填写',await edit.locator('.el-form-item').filter({has:page.getByText('联系电话',{exact:true})}).locator('input').count()===0);
  await edit.getByRole('button',{name:'取消',exact:true}).click();
  evidence.browser={pageErrors:errors,screenshots:['output/playwright/bg-structure-1024-20261011.png','output/playwright/bg-structure-768-20261011.png']};check('独立页面无脚本异常',errors.length===0);
 }catch(error){evidence.browserFailure={message:error.message,visibleText:(await page.locator('body').innerText()).slice(-7000)};await page.screenshot({path:'output/playwright/bg-browser-failure-20261011.png',fullPage:true});throw error}finally{await browser.close()}
}
