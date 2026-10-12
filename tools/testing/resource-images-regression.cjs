/* 固定隔离8087/5181；仅资源上传回归，禁止交易及主动查单。 */
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { request, sql, base, database } = require('./nontransaction-fixture-helpers.cjs')
const { chromium } = require(path.join(require('node:os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
const tag = 'IMG' + Date.now().toString().slice(-8)
const evidence = { date: '2026-10-11', database, transactionsExecuted: false, checks: [], cleanup: false }
const uploaded = []
function check(name, ok) { assert(ok, name); evidence.checks.push({ name, passed: true }) }
const counts = () => sql('SELECT (SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0]
async function upload(content, name, token, expectError = false) {
  const api = await fetch('http://127.0.0.1:8087/v3/api-docs').then(r => r.json())
  assert(api.paths['/api/v1/upload']?.post, '上传端点不在OpenAPI')
  const form = new FormData(); form.append('file', new Blob([content], { type: 'image/png' }), name); form.append('type', 'IMAGE')
  const response = await fetch(base + '/upload', { method: 'POST', headers: { Authorization: 'Bearer ' + token }, body: form })
  const body = await response.json()
  if (expectError) { assert.notEqual(body.code, 200); return }
  assert.equal(body.code, 200, body.message); uploaded.push(body.data.fileUrl); return body.data.fileUrl
}
;(async () => { let admin, community, browser, residentId
try {
  check('隔离数据库身份', sql('SELECT DATABASE()')[0][0] === database); evidence.before = counts()
  admin = await request('/auth/admin/login', 'POST', { username: 'superadmin', password: 'Admin@123456' })
  community = await request('/communities', 'POST', { name: tag + '图片回归', address: '独立上传夹具' }, admin.token)
  const image = fs.readFileSync('frontend/public/images/handtest/house-building.png')
  const imageName = 'fixture.png'
  const real = await upload(image, imageName, admin.token)
  check('真实图片上传后可访问', (await fetch('http://127.0.0.1:8087' + real)).ok)
  await upload(Buffer.from('<html>fake</html>'), 'fake.png', admin.token, true); check('伪装图片HTTP拒绝', true)
  const payload = { communityId: community.id, name: tag + '接口资源', type: 'GYM', capacity: 5, images: [real] }
  const resource = await request('/resources', 'POST', payload, admin.token)
  check('资源图片真实保存返回数组', resource.images[0] === real)
  for (const [name, images] of [['新增外部URL', ['https://example.com/x.png']], ['不存在平台图', ['/uploads/' + 'a'.repeat(32) + '.png']], ['超6张', Array(7).fill(real)], ['路径越界', ['/uploads/../secret.png']]]) {
    const rejected = await request('/resources/' + resource.id, 'PUT', { ...payload, images }, admin.token, true)
    check(name + '拒绝且原图不变', rejected.code !== 200 && (await request('/resources/' + resource.id, 'GET', undefined, admin.token)).images[0] === real)
  }
  await request('/resources/' + resource.id, 'PUT', { ...payload, images: [] }, admin.token)
  check('空数组清空但不物理删除文件', (await request('/resources/' + resource.id, 'GET', undefined, admin.token)).images.length === 0 && (await fetch('http://127.0.0.1:8087' + real)).ok)
  sql("UPDATE public_resource SET images='https://example.com/old.png' WHERE id=" + resource.id)
  check('历史外部图可原样保留', (await request('/resources/' + resource.id, 'PUT', { ...payload, images: ['https://example.com/old.png'] }, admin.token)).images[0] === 'https://example.com/old.png')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ viewport: { width: 1024, height: 768 } }), page = await context.newPage(), errors = []
  page.on('pageerror', e => errors.push(e.message))
  await context.route('**/*', route => { const u = new URL(route.request().url()); return !['127.0.0.1', 'localhost'].includes(u.hostname) || !['8087', '5181'].includes(u.port) || (/^\/api/.test(u.pathname) && /payment|refund|renew/i.test(u.pathname)) ? route.abort() : route.continue() })
  await context.addInitScript(session => sessionStorage.setItem('crsp_session', JSON.stringify(session)), { token: admin.token, user: admin.user, expiresAt: Date.now() + admin.expiresIn * 1000 })
  await page.goto('http://127.0.0.1:5181/admin/community?tab=resources'); await page.waitForLoadState('networkidle')
  await page.locator('.resource-list .el-select').first().click(); await page.getByRole('option', { name: community.name, exact: true }).click()
  await page.getByRole('button', { name: '新建资源', exact: true }).click()
  let dialog = page.getByRole('dialog', { name: '新建公共资源' })
  check('图片表单没有URL输入框', await dialog.getByPlaceholder('图片地址', { exact: false }).count() === 0)
  await dialog.getByPlaceholder('如：篮球场').fill(tag + '浏览器资源')
  const uploadedResponse = page.waitForResponse(r => r.url().endsWith('/api/v1/upload') && r.request().method() === 'POST')
  await dialog.locator('input[type="file"]').setInputFiles({ name: imageName, mimeType: 'image/png', buffer: image })
  const imageBody = await (await uploadedResponse).json(); assert.equal(imageBody.code, 200); uploaded.push(imageBody.data.fileUrl)
  await dialog.getByRole('button', { name: '保存资源', exact: true }).waitFor(); await page.waitForTimeout(250)
  for (const width of [1024, 768]) {
    await page.setViewportSize({ width, height: 768 }); await page.waitForTimeout(400)
    await dialog.getByRole('button', { name: /预览图片/ }).scrollIntoViewIfNeeded()
    check(width + '资源图片表单无页面横向溢出', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    await page.screenshot({ path: 'output/playwright/bh-resource-images-' + width + '-20261011.png', fullPage: true })
  }
  const savedResponse = page.waitForResponse(r => r.url().endsWith('/api/v1/resources') && r.request().method() === 'POST')
  await dialog.getByRole('button', { name: '保存资源', exact: true }).click(); const saved = await (await savedResponse).json(); assert.equal(saved.code, 200, '浏览器保存失败')
  const created = saved.data
  check('真实浏览器上传并保存资源图', created.images.length === 1 && created.images[0] === imageBody.data.fileUrl)
  const building = await request('/buildings', 'POST', { communityId: community.id, name: '图片回归楼', floors: 1 }, admin.token)
  const unit = await request('/units', 'POST', { buildingId: building.id, name: '1单元' }, admin.token)
  const house = await request('/houses', 'POST', { unitId: unit.id, houseNumber: '101', floor: 1, area: 80, layout: '三室一厅一卫' }, admin.token)
  const resident = await request('/residents/admin-create', 'POST', { communityId: community.id, username: tag + '_res', realName: tag + '住户', phone: '139' + tag.slice(3, 10) + '1', houseId: house.id, relationType: 'OWNER', moveInDate: '2026-01-01' }, admin.token)
  residentId = resident.residentId
  const session = await request('/auth/resident/login', 'POST', { username: resident.username, password: resident.initialPassword })
  check('居民不可修改公共资源图片', (await request('/resources/' + created.id, 'PUT', { ...payload, images: [] }, session.token, true)).code !== 200)
  const residentContext = await browser.newContext({ viewport: { width: 1024, height: 768 } })
  await residentContext.route('**/*', route => { const u = new URL(route.request().url()); return !['127.0.0.1', 'localhost'].includes(u.hostname) || !['8087', '5181'].includes(u.port) || (/^\/api/.test(u.pathname) && /payment|refund|renew/i.test(u.pathname)) ? route.abort() : route.continue() })
  await residentContext.addInitScript(value => sessionStorage.setItem('crsp_session', JSON.stringify(value)), { token: session.token, user: session.user, expiresAt: Date.now() + session.expiresIn * 1000 })
  const residentPage = await residentContext.newPage()
  await residentPage.goto('http://127.0.0.1:5181/resident/resources/' + created.id); await residentPage.waitForLoadState('networkidle')
  check('居民详情真实加载上传图片', await residentPage.locator('img[src="' + imageBody.data.fileUrl + '"]').evaluateAll(items => items.some(img => img.complete && img.naturalWidth > 0)))
  await residentPage.screenshot({ path: 'output/playwright/bh-resource-images-resident-20261011.png', fullPage: true })
  await residentContext.close()
  const row = page.locator('tr').filter({ hasText: tag + '浏览器资源' }); await row.getByRole('button', { name: '编辑', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '编辑公共资源' }); await dialog.getByRole('button', { name: /预览图片/ }).click()
  check('预览大图可见', await page.getByRole('dialog', { name: '图片预览' }).locator('img').isVisible())
  await page.getByRole('dialog', { name: '图片预览' }).getByRole('button', { name: /close|关闭/i }).click()
  await dialog.getByRole('button', { name: /移除图片/ }).click(); await dialog.getByRole('button', { name: '取消', exact: true }).click(); await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  check('移除后取消原图不变', (await request('/resources/' + created.id, 'GET', undefined, admin.token)).images.length === 1)
  await row.getByRole('button', { name: '编辑', exact: true }).click(); dialog = page.getByRole('dialog', { name: '编辑公共资源' })
  await dialog.getByRole('button', { name: /移除图片/ }).click()
  const clearedResponse = page.waitForResponse(r => r.url().endsWith('/api/v1/resources/' + created.id) && r.request().method() === 'PUT')
  await dialog.getByRole('button', { name: '保存资源', exact: true }).click(); assert.equal((await (await clearedResponse).json()).code, 200)
  check('浏览器移除后保存明确清空', (await request('/resources/' + created.id, 'GET', undefined, admin.token)).images.length === 0)
  check('浏览器无脚本异常', errors.length === 0); evidence.pageErrors = errors
  evidence.after = counts(); check('支付退款记录数量不变', JSON.stringify(evidence.before) === JSON.stringify(evidence.after))
  console.log(JSON.stringify({ checks: evidence.checks.length, success: true }))
} catch (error) { evidence.error = error.message; console.error(error.message); process.exitCode = 1 }
finally {
  if (browser) await browser.close()
  if (admin && community) try { await request('/communities/' + community.id, 'DELETE', undefined, admin.token); evidence.cleanup = true } catch (error) { evidence.cleanupError = error.message }
  if (evidence.cleanup && residentId) sql('DELETE FROM resident WHERE id=' + Number(residentId))
  evidence.uploadedFixtureFiles = uploaded;
  const uploadRoot = path.resolve('output/runtime/resource-images-bh-isolated-20261011')
  for (const url of uploaded) { assert(/^\/uploads\/[0-9a-f]{32}\.(png|jpg|jpeg|gif)$/.test(url)); const file = path.resolve(uploadRoot, path.basename(url)); assert(path.dirname(file) === uploadRoot); if (fs.existsSync(file)) fs.unlinkSync(file) }
  evidence.uploadFilesCleanup = true; fs.mkdirSync('output/playwright', { recursive: true }); fs.writeFileSync('output/playwright/resource-images-regression-20261011.json', JSON.stringify(evidence, null, 2))
}
})()
