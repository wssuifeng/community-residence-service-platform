/* DEF-168 真实前后端回归：仅允许本机隔离端口，不连接用户浏览器，不调用支付渠道。 */
const fs = require('fs')
const assert = require('node:assert/strict')
const { chromium } = require(process.env.PLAYWRIGHT_NODE_MODULE_PATH
  ?? require('node:path').join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
const backend = 'http://127.0.0.1:8085/api/v1'
const frontend = 'http://127.0.0.1:5178'
// 前置：全新隔离测试库已运行 Flyway 种子；本脚本会在该库保留回归夹具。
const output = 'output/playwright'
const evidence = { date: '2026-10-11', environment: 'isolated DB community_residence_def168 / Redis 14', paymentExecuted: false, checks: [] }
async function request(path, method = 'GET', data, token, allowError = false) {
  const response = await fetch(backend + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(data ? { body: JSON.stringify(data) } : {}) })
  const result = await response.json()
  if (!allowError && result.code !== 200) throw new Error(`${method} ${path}: ${response.status}/${result.code} ${result.message}`)
  return allowError ? { status: response.status, ...result } : result.data
}
function check(name, condition) { assert(condition, name); evidence.checks.push({ name, passed: true }) }
async function pageFor(browser, auth) {
  const context = await browser.newContext({ viewport: { width: 1366, height: 768 } })
  await context.addInitScript(session => sessionStorage.setItem('crsp_session', JSON.stringify(session)), { token: auth.token, user: auth.user, expiresAt: Date.now() + auth.expiresIn * 1000 })
  return await context.newPage()
}
;(async () => {
  let browser
  try {
    const admin = await request('/auth/admin/login', 'POST', { username: 'admin1', password: 'Admin123456' })
    const channels = await request('/payment/channels', 'GET', undefined, admin.token)
    check('隔离实例所有支付渠道禁用', channels.every(c => !c.enabled))
    const suffix = String(Date.now()).slice(-8), username = 'def168_' + suffix
    await request('/auth/resident/register', 'POST', { username, password: 'Resident123456', realName: '协议确认测试居民', phone: '139' + suffix })
    const resident = await request('/auth/resident/login', 'POST', { username, password: 'Resident123456' })
    const application = await request('/residence-applications', 'POST', { houseId: 2, relationType: 'TENANT', remark: 'DEF-168 隔离回归' }, resident.token)
    const approved = await request('/residence-applications/' + application.id + '/approve', 'PATCH', { leaseStartDate: '2026-10-12', leaseEndDate: '2027-10-12', monthlyRent: 3000, deposit: 6000 }, admin.token)
    const leaseId = approved.leaseId
    assert(leaseId, '审批须返回关联租约ID')
    let lease = await request('/leases/' + leaseId, 'GET', undefined, resident.token)
    check('入住审核只到待签约且不输出旧承租确认字段', lease.status === 'TO_SIGN' && !('tenantConfirmAt' in lease))
    check('签约前房屋仍空置', (await request('/houses/2', 'GET', undefined, admin.token)).status === 'VACANT')
    const bypass = await request('/leases/' + leaseId + '/status', 'PATCH', { status: 'ACTIVE' }, admin.token, true)
    check('管理端真实状态接口不能绕过居民确认', bypass.code !== 200)
    const template = await request('/agreement-templates', 'POST', { communityId: 1, name: 'DEF-168 正文快照模板', content: '租赁协议\n居民：{{租客姓名}}\n房屋：{{房屋位置}}\n月租金：{{月租金}} 元\n押金：{{押金}} 元\n租期：{{租期开始}} 至 {{租期结束}}\n请居民阅读当前条款并自主确认。', isDefault: 1 }, admin.token)
    browser = await chromium.launch({ channel: 'msedge', headless: true })
    const adminPage = await pageFor(browser, admin), residentPage = await pageFor(browser, resident)
    await adminPage.goto(frontend + '/admin/leases/' + leaseId, { waitUntil: 'networkidle' })
    const issue = adminPage.getByRole('button', { name: '同意并发出协议', exact: true })
    await issue.waitFor(); check('管理端无独立承租及直接生效按钮', await adminPage.getByRole('button', { name: /^(确认承租|置为生效|确认协议)$/ }).count() === 0)
    await issue.click()
    const dialog = adminPage.getByRole('dialog', { name: '同意并发出租赁协议' })
    await dialog.waitFor(); await dialog.getByRole('button', { name: '同意并发出协议', exact: true }).click()
    await adminPage.getByText('您发出协议时已表示同意').waitFor()
    await adminPage.screenshot({ path: output + '/def168-admin-issued-1366.png', fullPage: true })
    let agreements = await request('/lease-agreements?leaseId=' + leaseId, 'GET', undefined, admin.token)
    const agreement = agreements[0]
    check('发出时只记录管理方同意，不代填居民确认', agreement.status === 'PARTIAL' && agreement.adminConfirmTime && agreement.adminConfirmName === '社区管理员' && !agreement.tenantConfirmTime)
    const adminConfirm = await request('/lease-agreements/' + agreement.id + '/confirm', 'POST', {}, admin.token, true)
    check('管理方不能调用居民同意端点', adminConfirm.status === 403)
    await residentPage.goto(frontend + '/resident/housing-lease', { waitUntil: 'networkidle' })
    await residentPage.locator('.lease-row').first().click()
    await residentPage.getByRole('button', { name: '查看协议并确认', exact: true }).click()
    const drawer = residentPage.getByRole('dialog', { name: '协议正文' })
    await drawer.waitFor(); check('居民正文显示自己的快照且未同意时租约不生效', (await drawer.textContent()).includes('协议确认测试居民') && (await request('/leases/' + leaseId, 'GET', undefined, resident.token)).status === 'TO_SIGN')
    await residentPage.setViewportSize({ width: 1024, height: 600 })

    const accept = drawer.getByRole('button', { name: '同意协议并生效', exact: true })
    await accept.scrollIntoViewIfNeeded(); const box = await accept.boundingBox()
    await residentPage.waitForFunction(() => { const d = document.querySelector('.el-drawer'); return !!d && d.getBoundingClientRect().width <= innerWidth && d.getBoundingClientRect().left >= 0 })
    await residentPage.screenshot({ path: output + '/def168-resident-body-1024.png' })
    check('1024×600 正文同意按钮在可视区域', box && box.x >= 0 && box.y >= 0 && box.x + box.width <= 1024 && box.y + box.height <= 600)
    await accept.click(); await residentPage.getByText('双方已同意，租约已生效。').waitFor()
    lease = await request('/leases/' + leaseId, 'GET', undefined, resident.token)
    check('居民点击后租约生效与房态翻转', lease.status === 'ACTIVE' && lease.agreementStatus === 'SIGNED' && (await request('/houses/2', 'GET', undefined, admin.token)).status === 'OCCUPIED')
    const signed = await request('/lease-agreements/' + agreement.id, 'GET', undefined, resident.token)
    const retried = await request('/lease-agreements/' + agreement.id + '/confirm', 'POST', { confirmName: '伪造姓名' }, resident.token)
    check('重复同意只读且不覆盖居民首次姓名时间', retried.tenantConfirmTime === signed.tenantConfirmTime && retried.tenantConfirmName === '协议确认测试居民')
    await residentPage.screenshot({ path: output + '/def168-resident-signed-1024.png', fullPage: true })
    await adminPage.reload({ waitUntil: 'networkidle' }); check('管理端已签协议只读无撤回或重复同意', await adminPage.getByRole('button', { name: /^(撤回协议|确认协议|置为生效)$/ }).count() === 0)
    // 真正并发请求：另一个待签约租约，两个发出请求只有一个成功。
    const extra = await request('/leases', 'POST', { residentId: resident.user.id, houseId: 3, startDate: '2026-10-12', endDate: '2027-10-12', monthlyRent: 2800, deposit: 5600 }, admin.token)
    await request('/leases/' + extra.id + '/status', 'PATCH', { status: 'TO_SIGN' }, admin.token)
    const parallel = await Promise.all([0, 1].map(() => request('/lease-agreements', 'POST', { leaseId: extra.id, templateId: template.id }, admin.token, true)))
    check('两个真实并发发出请求仅一个成功', parallel.filter(r => r.code === 200).length === 1)
    agreements = await request('/lease-agreements?leaseId=' + extra.id, 'GET', undefined, admin.token)
    check('数据库不存在重复未完成协议', agreements.length === 1)
    const withdrawn = agreements[0]
    await request('/lease-agreements/' + withdrawn.id + '/cancel?reason=' + encodeURIComponent('回归重发条款'), 'POST', undefined, admin.token)
    const cancelledAccept = await request('/lease-agreements/' + withdrawn.id + '/confirm', 'POST', {}, resident.token, true)
    check('撤回后的旧正文不能被同意', cancelledAccept.code !== 200)
    const reissued = await request('/lease-agreements', 'POST', { leaseId: extra.id, templateId: template.id }, admin.token)
    check('重发新协议无居民继承确认', reissued.id !== withdrawn.id && !reissued.tenantConfirmTime)
    const race = await Promise.all([
      request('/lease-agreements/' + reissued.id + '/cancel?reason=' + encodeURIComponent('并发撤回'), 'POST', undefined, admin.token, true),
      request('/lease-agreements/' + reissued.id + '/confirm', 'POST', {}, resident.token, true)
    ])
    check('真实撤回与同意竞争只允许一个成功', race.filter(r => r.code === 200).length === 1)
    const winner = await request('/lease-agreements/' + reissued.id, 'GET', undefined, admin.token)
    const finalLease = await request('/leases/' + extra.id, 'GET', undefined, admin.token)
    check('并发最终协议/租约状态一致', (winner.status === 'SIGNED' && finalLease.status === 'ACTIVE') || (winner.status === 'CANCELLED' && finalLease.status === 'TO_SIGN' && finalLease.agreementStatus === 'NONE'))
    evidence.ids = { applicationId: application.id, leaseId, agreementId: agreement.id, raceLeaseId: extra.id }
    evidence.success = true
  } catch (error) { evidence.success = false; evidence.error = error.message; process.exitCode = 1 }
  finally { if (browser) await browser.close(); fs.writeFileSync(output + '/def168-real-flow-20261011.json', JSON.stringify(evidence, null, 2)); console.log(JSON.stringify(evidence, null, 2)) }
})()
