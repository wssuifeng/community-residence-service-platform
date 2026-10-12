/* bl：用户批准的本人事件统计/带看完成权限，固定隔离库与独立无头浏览器。 */
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { base, frontend, database, request, sql, id } = require('./nontransaction-fixture-helpers.cjs')
const out = 'docs/50_系统测试/evidence/staff-rules-20261012'
const run = Date.now().toString().slice(-8), tag = 'BL' + run
const evidence = { date: new Date().toISOString(), database, transactionsExecuted: false, checks: [], failures: [], communities: [], users: [], residents: [], screenshots: [], browserErrors: [] }
const auth = {}, fixture = {}
function check(name, ok, detail) { const row = { name, passed: !!ok, ...(detail ? { detail } : {}) }; evidence.checks.push(row); if (!ok) { evidence.failures.push(row); throw new Error(name) } }
function financialCounts() { return sql('SELECT (SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0].map(Number) }
async function denied(name, p, method, data, session, expected = 403) { const result = await request(p, method, data, session?.token, true); check(name, result.status === expected, { http: result.status, code: result.code }); return result }
async function user(role, n) {
  const row = await request('/sys-users', 'POST', { username: 'bl_' + role.toLowerCase() + '_' + run + n, password: 'Test123456', realName: tag + role + n, phone: '138' + run.slice(0, 7) + n, role }, auth.super.token)
  evidence.users.push(row.id); return request('/auth/admin/login', 'POST', { username: row.username, password: 'Test123456' })
}
function sum(events) { return Object.values(events).reduce((a, b) => a + b, 0) }
async function stats(session, days = 7) { return request('/statistics/staff/trend?days=' + days, 'GET', undefined, session.token) }
async function browserCheck() {
  const { chromium } = require(path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const context = await browser.newContext({ viewport: { width: 1024, height: 900 } })
    await context.route('**/*', route => {
      const u = new URL(route.request().url())
      if (!['127.0.0.1', 'localhost'].includes(u.hostname) || !['8087', '5181'].includes(u.port)) return route.abort()
      if (u.pathname.startsWith('/api/') && /payment|refund|renew/i.test(u.pathname) && u.pathname !== '/api/v1/payment/channels') return route.abort()
      return route.continue()
    })
    await context.addInitScript(s => sessionStorage.setItem('crsp_session', JSON.stringify({ token: s.token, user: s.user, expiresAt: Date.now() + s.expiresIn * 1000 })), auth.staff2)
    const proxied = await context.request.get(frontend + '/api/v1/statistics/staff/trend?days=7', { headers: { Authorization: 'Bearer ' + auth.staff2.token } });
    const proxyData = await proxied.json(); check('浏览器代理与隔离后端事件对象一致', proxied.status() === 200 && JSON.stringify(proxyData.data) === JSON.stringify(await stats(auth.staff2)));
    const page = await context.newPage(); page.on('pageerror', error => evidence.browserErrors.push(error.message))
    await page.goto(frontend + '/staff/dashboard', { waitUntil: 'networkidle' })
    check('工作台真实显示今日两次提交而非当前完成状态', await page.getByTestId('today-submitted').textContent() === '2')
    check('当前卡与历史事件分区', await page.getByText('当前工单', { exact: true }).isVisible() && await page.getByText('近7日工作量事件', { exact: true }).isVisible())
    check('无负荷伪趋势与百分比', !(await page.locator('body').innerText()).includes('趋势待明确'))
    for (const width of [1024, 768]) {
      await page.setViewportSize({ width, height: 900 })
      await page.screenshot({ path: out + '/workload-' + width + '.png', fullPage: true }); evidence.screenshots.push('workload-' + width + '.png')
      check('事件工作台无横向溢出' + width, await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))
    }
    await page.route('**/api/v1/statistics/staff/trend*', route => route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '隔离失败重试夹具', data: null }) }))
    await page.reload({ waitUntil: 'networkidle' }); check('事件接口失败不伪装零值', await page.getByRole('alert').filter({ hasText: '事件统计加载失败' }).isVisible() && await page.getByTestId('today-submitted').count() === 0)
    await page.unroute('**/api/v1/statistics/staff/trend*')
    await page.getByRole('button', { name: '重新加载事件统计', exact: true }).click(); await page.getByTestId('today-submitted').waitFor()
    check('错误原地重试返回真实事件', await page.getByTestId('today-submitted').textContent() === '2')
    await page.setViewportSize({ width: 1024, height: 900 })
    await page.goto(frontend + '/staff/viewings', { waitUntil: 'networkidle' })
    await page.locator('.el-table__row').filter({ hasText: tag + '看房' }).click()
    check('人员页有完成无违约冻结按钮', await page.getByRole('button', { name: '登记完成', exact: true }).isVisible() && await page.getByRole('button', { name: '标记违约', exact: true }).count() === 0)
    await page.getByRole('button', { name: '登记完成', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '完成看房' }); await dialog.locator('input').fill('本人带看真实按钮完成')
    const resultPromise = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/viewing-appointments/' + fixture.viewing.id + '/complete' && response.request().method() === 'PATCH')
    await dialog.getByRole('button', { name: '确认完成', exact: true }).click()
    const result = await resultPromise; check('本人完成按钮成功不再403', result.status() === 200 && (await result.json()).code === 200)
    await page.getByRole('button', { name: '登记完成', exact: true }).waitFor({ state: 'hidden' })
    await page.screenshot({ path: out + '/viewing-completed-1024.png', fullPage: true }); evidence.screenshots.push('viewing-completed-1024.png')
    check('无未捕获浏览器异常', evidence.browserErrors.length === 0)
  } finally { await browser.close() }
}
;(async () => {
  fs.mkdirSync(out, { recursive: true })
  try {
    check('固定隔离数据库门禁', sql('SELECT DATABASE()')[0][0] === database)
    evidence.financialBefore = financialCounts()
    auth.super = await request('/auth/admin/login', 'POST', { username: 'superadmin', password: 'Admin@123456' })
    check('隔离渠道停用', (await request('/payment/channels', 'GET', undefined, auth.super.token)).every(c => !c.enabled))
    for (const side of ['A', 'B']) {
      const c = await request('/communities', 'POST', { name: tag + side, address: '人员规则隔离夹具' }, auth.super.token); fixture[side] = c; evidence.communities.push(c.id)
      await request('/structures/batch-generate', 'POST', { communityId: c.id, buildingNamePrefix: tag, buildingStartNo: 1, buildingEndNo: 1, unitCountPerBuilding: 1, floorsPerUnit: 1, housesPerFloor: 2, layout: '二室一厅一卫', area: 60, dryRun: false }, auth.super.token)
      const b = (await request('/communities/' + c.id + '/buildings?size=10', 'GET', undefined, auth.super.token)).records[0]
      const u = (await request('/buildings/' + b.id + '/units?size=10', 'GET', undefined, auth.super.token)).records[0]
      fixture['houses' + side] = (await request('/units/' + u.id + '/houses?size=10', 'GET', undefined, auth.super.token)).records
    }
    auth.staff1 = await user('STAFF', 1); auth.staff2 = await user('STAFF', 2); auth.adminA = await user('ADMIN', 3); auth.adminB = await user('ADMIN', 4)
    for (const side of ['A', 'B']) {
      const session = auth['admin' + side]; await request('/sys-users/' + session.user.id + '/communities', 'POST', { communityId: fixture[side].id }, auth.super.token)
      auth['admin' + side] = await request('/auth/admin/login', 'POST', { username: session.user.username, password: 'Test123456' })
    }
    const created = await request('/residents/admin-create', 'POST', { communityId: fixture.A.id, houseId: fixture.housesA[0].id, realName: tag + '居民', username: 'bl_res_' + run, phone: '139' + run, relationType: 'OWNER', moveInDate: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()) }, auth.super.token)
    evidence.residents.push(created.residentId); auth.resident = await request('/auth/resident/login', 'POST', { username: created.username, password: created.initialPassword })
    const category = await request('/service-categories', 'POST', { communityId: fixture.A.id, name: tag + '事件服务', isActive: 1 }, auth.adminA.token)
    const order = await request('/work-orders', 'POST', { communityId: fixture.A.id, categoryId: category.id, title: tag + '事件工单', content: '事件归属测试', contactPhone: '139' + run, address: '隔离房屋', priority: 'NORMAL' }, auth.resident.token)
    fixture.orderId = order.id
    await request('/work-orders/' + order.id + '/intake', 'PATCH', {}, auth.adminA.token)
    await request('/work-orders/' + order.id + '/assign', 'PATCH', { assigneeId: auth.staff1.user.id }, auth.adminA.token)
    await request('/work-orders/' + order.id + '/assign', 'PATCH', { assigneeId: auth.staff2.user.id }, auth.adminA.token)
    sql('UPDATE work_order SET created_at=DATE_SUB(NOW(),INTERVAL 30 DAY) WHERE id=' + id(order.id))
    sql('UPDATE work_order_assignment SET assign_time=DATE_SUB(CURDATE(),INTERVAL 1 DAY) WHERE work_order_id=' + id(order.id) + ' AND assignee_id=' + id(auth.staff1.user.id))
    await denied('改派后旧人员详情权限撤销', '/work-orders/' + order.id, 'GET', undefined, auth.staff1)
    const old = await stats(auth.staff1), current = await stats(auth.staff2)
    check('工单创建30天前不影响真实今日派入', current.todayAssigned === 1 && sum(current.assignedTrend) === 1)
    check('旧人员保留昨日真实派入非当前负荷', sum(old.assignedTrend) === 1 && old.todayAssigned === 0)
    await request('/work-orders/' + order.id + '/accept', 'PATCH', {}, auth.staff2.token)
    await request('/work-orders/' + order.id + '/process', 'PATCH', {}, auth.staff2.token)
    await request('/work-orders/' + order.id + '/complete', 'PATCH', { remark: '首次实际提交' }, auth.staff2.token)
    await request('/work-orders/' + order.id + '/return', 'PATCH', { remark: '居民退回重做' }, auth.resident.token)
    await request('/work-orders/' + order.id + '/complete', 'PATCH', { remark: '退回后再次实际提交' }, auth.staff2.token)
    await request('/work-orders/' + order.id + '/confirm', 'PATCH', {}, auth.resident.token)
    const done = await stats(auth.staff2); evidence.statistics = done
    check('两次实际提交计两次非一单一计', done.todaySubmitted === 2 && done.totalSubmitted === 2)
    check('旧人员不继承别人提交结果', (await stats(auth.staff1)).todaySubmitted === 0)
    // 明确的身份/日期否定夹具，仅本次工单；不视作真实业务流转通过。
    sql("INSERT INTO work_order_process(work_order_id,operator_id,operator_type,action,new_status,content,created_at) VALUES (" + id(order.id) + ',' + id(auth.staff2.user.id) + ", 'RESIDENT','COMPLETE','TO_CONFIRM','身份否定夹具',NOW()),(" + id(order.id) + ',' + id(auth.staff2.user.id) + ", 'STAFF','CONFIRM','COMPLETED','动作否定夹具',NOW()),(" + id(order.id) + ',' + id(auth.staff2.user.id) + ", 'STAFF','COMPLETE','TO_CONFIRM','未来否定夹具',DATE_ADD(CURDATE(),INTERVAL 1 DAY))")
    check('居民同数字ID确认或未来时间不混入', (await stats(auth.staff2)).todaySubmitted === 2 && (await stats(auth.staff2)).totalSubmitted === 2)
    check('日期窗口上限90且下限1', (await stats(auth.staff2, 999)).days === 90 && (await stats(auth.staff2, -4)).days === 1)
    await denied('居民不能读服务人员事件统计', '/statistics/staff/trend?days=7', 'GET', undefined, auth.resident)
    await denied('游客不能读事件统计', '/statistics/staff/trend?days=7', 'GET', undefined, null, 401)
    const date = new Date(); date.setDate(date.getDate() + 2); const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(date)
    const housing = await request('/housings', 'POST', { houseId: fixture.housesA[1].id, title: tag + '看房', description: '完成权限', monthlyRent: 1000, deposit: 1000, rentType: 'RENT' }, auth.super.token)
    await request('/housings/' + housing.id + '/timeslots', 'POST', { dayOfWeek: date.getDay() || 7, startTime: '15:00:00', endTime: '16:00:00', isAvailable: 1 }, auth.super.token)
    fixture.viewing = await request('/viewing-appointments', 'POST', { housingId: housing.id, appointmentDate: day, startTime: '15:00:00', endTime: '15:30:00', remark: '本人带看按钮' }, auth.resident.token)
    const viewing = fixture.viewing, action = { reason: '权限回归' }
    await request('/viewing-appointments/' + viewing.id + '/assign', 'PATCH', { assigneeId: auth.staff1.user.id }, auth.super.token)
    await request('/viewing-appointments/' + viewing.id + '/confirm', 'PATCH', action, auth.adminA.token)
    await request('/viewing-appointments/' + viewing.id + '/assign', 'PATCH', { assigneeId: auth.staff2.user.id }, auth.adminA.token)
    for (const [name, session] of [['改派前人员', auth.staff1], ['预约居民', auth.resident], ['跨社区管理', auth.adminB]]) await denied('完成拒绝' + name, '/viewing-appointments/' + viewing.id + '/complete', 'PATCH', action, session, name === '跨社区管理' ? 404 : 403)
    await denied('完成拒绝游客', '/viewing-appointments/' + viewing.id + '/complete', 'PATCH', action, null, 401)
    for (const operation of ['violate', 'confirm', 'cancel']) await denied('人员不获得管理动作' + operation, '/viewing-appointments/' + viewing.id + '/' + operation, 'PATCH', action, auth.staff2)
    await browserCheck()
    const end = await request('/viewing-appointments/' + viewing.id, 'GET', undefined, auth.resident.token)
    check('完成状态与备注真实读回', end.status === 'COMPLETED' && end.remark === '本人带看真实按钮完成')
    const before = Number(sql("SELECT COUNT(*) FROM notification WHERE owner_type='RESIDENT' AND user_id=" + id(created.residentId) + " AND source_type='VIEWING_APPOINTMENT' AND source_id=" + id(viewing.id))[0][0])
    const replay = await request('/viewing-appointments/' + viewing.id + '/complete', 'PATCH', action, auth.staff2.token, true)
    check('重复完成被状态机拒绝且不重复通知', replay.code !== 200 && replay.status < 500 && Number(sql("SELECT COUNT(*) FROM notification WHERE owner_type='RESIDENT' AND user_id=" + id(created.residentId) + " AND source_type='VIEWING_APPOINTMENT' AND source_id=" + id(viewing.id))[0][0]) === before)
    const next = await request('/viewing-appointments', 'POST', { housingId: housing.id, appointmentDate: day, startTime: '15:00:00', endTime: '15:30:00', remark: '并发完成夹具' }, auth.resident.token)
    await request('/viewing-appointments/' + next.id + '/assign', 'PATCH', { assigneeId: auth.staff2.user.id }, auth.adminA.token)
    await request('/viewing-appointments/' + next.id + '/confirm', 'PATCH', action, auth.adminA.token)
    const repeated = await Promise.all([request('/viewing-appointments/' + next.id + '/complete', 'PATCH', action, auth.staff2.token, true), request('/viewing-appointments/' + next.id + '/complete', 'PATCH', action, auth.staff2.token, true)])
    check('并发完成仅一次成功无500', repeated.filter(r => r.code === 200).length === 1 && repeated.every(r => r.status < 500))
    const managed = await request('/viewing-appointments', 'POST', { housingId: housing.id, appointmentDate: day, startTime: '15:00:00', endTime: '15:30:00', remark: '管理员完成权限' }, auth.resident.token)
    await request('/viewing-appointments/' + managed.id + '/confirm', 'PATCH', action, auth.adminA.token)
    await request('/viewing-appointments/' + managed.id + '/complete', 'PATCH', action, auth.adminA.token)
    check('本社区管理员仍能完成', (await request('/viewing-appointments/' + managed.id, 'GET', undefined, auth.super.token)).status === 'COMPLETED')
        check('未运行支付退款', JSON.stringify(evidence.financialBefore) === JSON.stringify(financialCounts()))
  } catch (error) { evidence.error = error.message; evidence.errorLocation = error.stack?.split("\n").slice(1,3); process.exitCode = 1 }
  finally {
    for (const cid of evidence.communities) { try { await request('/communities/' + cid, 'DELETE', undefined, auth.super.token) } catch (error) { evidence.cleanupError = error.message; process.exitCode = 1 } }
    if (!evidence.cleanupError) {
      for (const [type, table, ids] of [['RESIDENT', 'resident', evidence.residents], ['USER', 'sys_user', evidence.users]]) for (const uid of ids) {
        sql("DELETE FROM notification_channel_log WHERE notification_id IN (SELECT id FROM notification WHERE owner_type='" + type + "' AND user_id=" + id(uid) + ')')
        sql("DELETE FROM notification WHERE owner_type='" + type + "' AND user_id=" + id(uid)); sql('DELETE FROM ' + table + ' WHERE id=' + id(uid))
      }
      evidence.cleanupCounts = {}
      for (const [table, ids] of [['community', evidence.communities], ['resident', evidence.residents], ['sys_user', evidence.users]]) if (ids.length) evidence.cleanupCounts[table] = Number(sql('SELECT COUNT(*) FROM ' + table + ' WHERE id IN (' + ids.map(id).join(',') + ')')[0][0])
      evidence.cleanup = Object.values(evidence.cleanupCounts).every(value => value === 0)
    }
    evidence.financialAfter = financialCounts()
    fs.writeFileSync(out + '/regression.json', JSON.stringify(evidence, null, 2) + '\n')
    console.log(JSON.stringify({ checks: evidence.checks.length, failures: evidence.failures, error: evidence.error, cleanup: evidence.cleanup, evidence: out + '/regression.json' }))
  }
})()
