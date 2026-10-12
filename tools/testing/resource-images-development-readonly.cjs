/* 仅核查本机开发环境；不写业务数据，不调用交易或主动查单接口。 */
const fs = require('node:fs')
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const { properties } = require('./nontransaction-fixture-helpers.cjs')
const credentials = properties()
function select(statement) {
  assert(statement.startsWith('SELECT '))
  const result = spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',
    ['--protocol=TCP', '--host=127.0.0.1', '--user=' + (credentials.DB_USERNAME || 'root'),
     '--database=community_residence', '--batch', '--skip-column-names', '--execute', statement],
    { env: { ...process.env, MYSQL_PWD: credentials.DB_PASSWORD }, encoding: 'utf8', windowsHide: true })
  assert.equal(result.status, 0, '开发只读SQL核查失败（不回显凭据）')
  return result.stdout.trim().split('\n').map(line => line.trim().split('\t'))
}
const counts = () => select('SELECT (SELECT COUNT(*) FROM community),(SELECT COUNT(*) FROM house),(SELECT COUNT(*) FROM resident),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0]
const evidence = { scope: 'resource-images', date: '2026-10-11', transactionsExecuted: false, businessWrites: false, checks: [] }
function check(name, ok) { assert(ok, name); evidence.checks.push({ name, passed: true }) }
let specification
async function get(path, token) {
  if (!specification) specification = await fetch('http://127.0.0.1:8084/v3/api-docs').then(r => r.json())
  const pathname = new URL('http://127.0.0.1:8084' + path).pathname
  const entry = specification.paths[pathname] ?? Object.entries(specification.paths).find(([pattern]) => new RegExp('^' + pattern.replace(/\{[^}]+\}/g, '[^/]+') + '$').test(pathname))?.[1]
  assert(entry?.get, '只读工具路径未匹配当前OpenAPI GET：' + pathname)
  assert(!/payment|refund|renew/i.test(path) || path === '/api/v1/payment/channels')
  const body = await fetch('http://127.0.0.1:8084' + path, { headers: token ? { Authorization: 'Bearer ' + token } : {} }).then(r => r.json())
  assert.equal(body.code, 200, path)
  return body.data
}
;(async () => {
  try {
    evidence.before = counts()
    check('开发数据库身份', select('SELECT DATABASE()')[0][0] === 'community_residence')
    check('V31迁移有效', select('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1')[0][0] === '31')
    const open = await fetch('http://127.0.0.1:8084/v3/api-docs').then(r => r.json())
    for (const key of ['email', 'houseId', 'relationType', 'moveInDate', 'remark']) check('运行中DTO包含' + key, !!open.components.schemas.AdminCreateResidentDTO.properties[key])
    const login = await fetch('http://127.0.0.1:8084/api/v1/auth/admin/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'superadmin', password: 'Admin@123456' }) }).then(r => r.json())
    assert.equal(login.code, 200, '开发登录')
    check('开发渠道继续停用', (await get('/api/v1/payment/channels', login.data.token)).every(c => !c.enabled))
    check('运行中图片契约包含6张限制', open.components.schemas.CreateResourceDTO.properties.images.description.includes('6'))
    const community = (await get('/api/v1/communities?page=1&size=1', login.data.token)).records[0]
    if (community) check('现有公共资源列表可加载', (await get('/api/v1/communities/' + community.id + '/resources?page=1&size=1', login.data.token)).total >= 0)
    check('现有居民列表可加载', (await get('/api/v1/residents?page=1&size=1', login.data.token)).total >= 0)
    check('前端5176可访问', (await fetch('http://127.0.0.1:5176/admin/residents')).ok)
    evidence.after = counts()
    check('当前业务数据数量保持不变', JSON.stringify(evidence.before) === JSON.stringify(evidence.after))
    console.log(JSON.stringify({ checks: evidence.checks.length, success: true }))
  } catch (error) { evidence.error = error.message; console.error(error.message); process.exitCode = 1 }
  finally { fs.writeFileSync('output/playwright/resource-images-development-readonly-20261011.json', JSON.stringify(evidence, null, 2)) }
})()
