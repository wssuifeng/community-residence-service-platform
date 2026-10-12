/* 仅本机隔离实例与现场生成密钥；配置管理回归，禁止支付/查单/退款。 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { chromium } = require(process.env.PLAYWRIGHT_NODE_MODULE_PATH ?? path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
const backend = 'http://127.0.0.1:8087/api/v1'
const frontend = 'http://127.0.0.1:5181'
const endpoint = '/payment/channel-config/alipay'
const evidence = { date:'2026-10-11', database:'community_residence_payment_config_test', credentials:'generated-test-only', transactionsExecuted:false, checks:[], browserErrors:[] }
const out = 'output/playwright'
fs.mkdirSync(out, { recursive:true })
function check(name, condition) { assert(condition,name); evidence.checks.push({name,passed:true}) }
async function api(url, method='GET', data, token, raw=false) {
  assert(url.startsWith('/auth/') || url === endpoint || url === '/payment/channels', '禁止访问交易端点')
  const response = await fetch(backend+url, { method, headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})}, ...(data!==undefined?{body:JSON.stringify(data)}:{}) })
  const body = await response.json()
  if(raw) return {status:response.status,...body}
  assert(response.status===200 && body.code===200, '隔离 API 成功请求被拒：'+url)
  return body.data
}
function keyPair() { return crypto.generateKeyPairSync('rsa',{modulusLength:2048,publicKeyEncoding:{type:'spki',format:'der'},privateKeyEncoding:{type:'pkcs8',format:'der'}}) }
function b64(k) { return k.toString('base64') }
;(async()=>{
  let browser
  try {
    const superAdmin = await api('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'})
    const denied = await api(endpoint,'GET',undefined,undefined,true)
    check('匿名读取被拒401',denied.status===401)
    check('匿名保存被拒401',(await api(endpoint,'PUT',{},undefined,true)).status===401)
    for (const [role,user,password,kind] of [['ADMIN','admin1','Admin123456','admin'],['STAFF','staff1','Staff123456','admin'],['RESIDENT','resident1','Resident123456','resident']]) {
      const auth = await api('/auth/'+kind+'/login','POST',{username:user,password})
      check(role+'不能读取凭据',(await api(endpoint,'GET',undefined,auth.token,true)).status===403)
      check(role+'不能写入凭据',(await api(endpoint,'PUT',{},auth.token,true)).status===403)
      if(role==='ADMIN') check('普通管理员渠道门控不被错误注入社区列',(await api('/payment/channels','GET',undefined,auth.token)).every(c=>!c.enabled))
    }
    let config = await api(endpoint,'GET',undefined,superAdmin.token)
    check('配置响应不含私钥或密文字段',!('merchantPrivateKey' in config) && !('merchantPrivateKeyCiphertext' in config))
    check('默认环境为沙箱且停用',config.environment==='SANDBOX' && config.enabled===false)
    const application = keyPair(), platform = keyPair(), wrong = keyPair()
    const base = {appId:'9021000000000000',environment:'SANDBOX',applicationPublicKey:b64(application.publicKey),merchantPrivateKey:b64(application.privateKey),alipayPublicKey:b64(platform.publicKey),clearPrivateKey:false}
    const payload = ()=>({...base,expectedRevision:config.revision})
    let rejected = await api(endpoint,'PUT',{...payload(),merchantPrivateKey:b64(wrong.privateKey)},superAdmin.token,true)
    check('离线应用密钥错配拒绝且不回显内容',rejected.code===400 && !JSON.stringify(rejected).includes(b64(wrong.privateKey)))
    check('拒绝保存不改变版本',(await api(endpoint,'GET',undefined,superAdmin.token)).revision===config.revision)
    browser = await chromium.launch({channel:'msedge',headless:true})
    const context = await browser.newContext({viewport:{width:1366,height:900}})
    await context.route('**/*',route=>{
      const u=new URL(route.request().url())
      if(!['127.0.0.1','localhost'].includes(u.hostname)) return route.abort()
      if(u.pathname.startsWith('/api/v1/payment/orders') || /(?:first-payments|renew-payments|deposit-refunds)/.test(u.pathname)) {
        evidence.browserErrors.push('unexpected transaction endpoint'); return route.abort()
      }
      return route.continue()
    })
    await context.addInitScript(session=>sessionStorage.setItem('crsp_session',JSON.stringify(session)),{token:superAdmin.token,user:superAdmin.user,expiresAt:Date.now()+superAdmin.expiresIn*1000})
    const page = await context.newPage()
    page.on('pageerror',e=>evidence.browserErrors.push(e.message))
    page.on('console',m=>{if(m.type()==='error') evidence.browserErrors.push(m.text())})
    await page.goto(frontend+'/admin/system?tab=configs',{waitUntil:'networkidle'})
    const panel=page.locator('.alipay-config-panel')
    await panel.getByRole('heading',{name:'支付宝渠道配置'}).waitFor()
    check('复用原全局配置页面而非新增菜单',await panel.isVisible())
    for(const [id,value] of [['alipay-app-id',base.appId],['alipay-application-public-key',base.applicationPublicKey],['alipay-merchant-private-key',base.merchantPrivateKey],['alipay-public-key',base.alipayPublicKey]]) {
      await page.getByTestId(id).fill(value)
    }
    const saved = page.waitForResponse(r=>r.url().endsWith(endpoint) && r.request().method()==='PUT')
    await page.getByTestId('save-alipay-config').click()
    const saveResponse = await (await saved).json()
    check('真实表单保存四项成功',saveResponse.code===200)
    config=saveResponse.data
    await page.waitForFunction(()=>document.querySelector('[data-testid="alipay-merchant-private-key"]').value==='')
    check('表单保存后私钥输入清空',(await page.getByTestId('alipay-merchant-private-key').inputValue())==='')
    check('保存后配置完整但不启用交易',config.configured && config.privateKeyConfigured && !config.enabled)
    const stale = await api(endpoint,'PUT',{...base,expectedRevision:config.revision-1},superAdmin.token,true)
    check('陈旧版本不覆盖',stale.code===5004)
    config=await api(endpoint,'PUT',{...payload(),merchantPrivateKey:''},superAdmin.token)
    check('空私钥保留旧值',config.privateKeyConfigured && config.configured && !config.enabled)
    await page.reload({waitUntil:'networkidle'})
    check('重载仅回填公钥和APPID，不回显私钥',(await page.getByTestId('alipay-app-id').inputValue())===base.appId && (await page.getByTestId('alipay-merchant-private-key').inputValue())==='')
    for(const size of [{width:1024,height:600},{width:768,height:800}]) {
      await page.setViewportSize(size)
      await panel.scrollIntoViewIfNeeded()
      await page.waitForFunction(()=>document.documentElement.scrollWidth<=innerWidth)
      const widths=await panel.evaluate(el=>({width:el.getBoundingClientRect().width,scroll:el.scrollWidth,client:el.clientWidth}))
      check(size.width+'px页面及配置卡无横向溢出',widths.scroll<=widths.client+1)
      await page.evaluate(()=>window.scrollTo({top:0,left:0,behavior:'instant'}))
      await page.waitForFunction(()=>window.scrollY===0)
      await page.screenshot({path:out+'/alipay-config-'+size.width+'-20261011.png',fullPage:true})
    }
    await page.setViewportSize({width:360,height:640})
    check('360px延续系统原桌面端提示',await page.getByText('本系统面向桌面端使用，请使用 768px 以上宽度的设备访问。').isVisible())
    await page.setViewportSize({width:1024,height:768})
    await panel.scrollIntoViewIfNeeded()
    await panel.locator('.clear-private-key').click()
    check('可见清除标签能勾选隐藏原生复选框',await panel.getByRole('checkbox',{name:/明确清除/}).isChecked())
    await page.getByTestId('save-alipay-config').click()
    await page.getByRole('dialog',{name:'清除已保存私钥'}).waitFor()
    await page.getByRole('button',{name:'取消',exact:true}).click()
    check('清除取消不改变私钥',(await api(endpoint,'GET',undefined,superAdmin.token)).privateKeyConfigured)
    await page.getByTestId('save-alipay-config').click()
    const cleared = page.waitForResponse(r=>r.url().endsWith(endpoint)&&r.request().method()==='PUT')
    await page.getByRole('button',{name:'确认清除',exact:true}).click()
    const clearResponse=await(await cleared).json()
    config=clearResponse.data
    check('真实表单明确清除后配置不完整并停用',clearResponse.code===200&&!config.privateKeyConfigured&&!config.configured&&!config.enabled)
    check('保存和清除均未启用任何渠道',(await api('/payment/channels','GET',undefined,superAdmin.token)).every(c=>!c.enabled))
    const malformed=await fetch(backend+endpoint,{method:'PUT',headers:{Authorization:'Bearer '+superAdmin.token,'Content-Type':'application/json'},body:'{"merchantPrivateKey":"'+base.merchantPrivateKey+'",'})
    check('带敏感值的错误JSON返回400而不回显',malformed.status===400 && !(await malformed.text()).includes(base.merchantPrivateKey))
    const logs=['output/payment-config-isolated-backend.stdout.log','output/payment-config-isolated-backend.stderr.log'].map(f=>fs.existsSync(f)?fs.readFileSync(f,'utf8'):'').join('\n')
    check('服务器日志不包含合成私钥原文',!logs.includes(base.merchantPrivateKey) && !logs.includes(b64(wrong.privateKey)))
    check('浏览器无运行时及意外交易请求错误',evidence.browserErrors.length===0)
    evidence.finalConfig={revision:config.revision,privateKeyConfigured:config.privateKeyConfigured,enabled:config.enabled}
  } catch(e) { evidence.error=e.message; throw e }
  finally { if(browser) await browser.close(); fs.writeFileSync(out+'/alipay-config-regression-20261011.json',JSON.stringify(evidence,null,2)) }
  console.log('Independent config browser/API checks passed:',evidence.checks.length,'; no transactions.')
})().catch(e=>{console.error(e.message);process.exitCode=1})
