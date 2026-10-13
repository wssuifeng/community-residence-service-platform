/* 生产分包加载复验：独立无头浏览器，只读正常库，禁止业务写和交易请求。 */
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { properties } = require('./nontransaction-fixture-helpers.cjs');
const base = 'http://127.0.0.1:8084', preview = 'http://127.0.0.1:5182';
const out = path.resolve(__dirname, '../../docs/50_系统测试/evidence/autonomous-closeout-20261013');
const report = { date: new Date().toISOString(), database: 'community_residence', productionPreview: preview,
  businessWrites: false, transactionsExecuted: false, checks: [], pageErrors: [], consoleErrors: [], failedLocalResponses: [], blockedRequests: [] };
function check(name, condition) { assert(condition, name); report.checks.push({ name, passed: true }); }
function counts() {
  const env = properties();
  const result = spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe', ['--host=127.0.0.1', '--user=' + (env.DB_USERNAME || 'root'), '--database=community_residence', '--batch', '--skip-column-names', '--execute', 'SELECT (SELECT COUNT(*) FROM community),(SELECT COUNT(*) FROM house),(SELECT COUNT(*) FROM resident),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)'], { env: { ...process.env, MYSQL_PWD: env.DB_PASSWORD }, encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 0, '正常库只读SQL失败（不输出凭据）');
  return result.stdout.trim().split('\t').map(Number);
}
async function login(username, password, kind) {
  const result = await fetch(base + '/api/v1/auth/' + kind + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) }).then(r => r.json());
  check('演示登录 ' + username, result.code === 200); return result.data;
}
async function get(route, token, origin = base) {
  return fetch(origin + route, { headers: token ? { Authorization: 'Bearer ' + token } : {} }).then(r => r.json());
}
(async () => {
  let browser;
  try {
    report.before = counts();
    check('原正常基线保留', JSON.stringify(report.before) === '[1,600,9,1,0,0]');
    const auth = {
      admin: await login('superadmin', 'Admin@123456', 'admin'),
      communityAdmin: await login('admin1', 'Admin123456', 'admin'),
      staff: await login('staff1', 'Staff123456', 'admin'),
      resident: await login('resident1', 'Resident123456', 'resident')
    };
    const channels = await get('/api/v1/payment/channels', auth.admin.token);
    check('程序支付渠道全部停用', channels.code === 200 && channels.data.every(c => !c.enabled));
    const event = await get('/api/v1/statistics/staff/trend?days=7', auth.staff.token);
    const dev = await get('/api/v1/statistics/staff/trend?days=7', auth.staff.token, 'http://127.0.0.1:5176');
    const prod = await get('/api/v1/statistics/staff/trend?days=7', auth.staff.token, preview);
    check('开发和生产代理均指向当前8084', event.code === 200 && dev.code === 200 && prod.code === 200 && JSON.stringify(event.data) === JSON.stringify(dev.data) && JSON.stringify(event.data) === JSON.stringify(prod.data));
    const { chromium } = require(path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
    browser = await chromium.launch({ channel: 'msedge', headless: true });
    const cases = [
      { role: 'guest', routes: ['/guest/home', '/guest/housings'] },
      { role: 'resident', routes: ['/resident/work-orders', '/resident/resources'] },
      { role: 'staff', routes: ['/staff/dashboard', '/staff/viewings'] },
      { role: 'admin', routes: ['/admin/dashboard', '/admin/residents', '/admin/community', '/admin/system?tab=logs'] }
    ];
    for (const item of cases) {
      const context = await browser.newContext({ viewport: { width: 1024, height: 900 } });
      await context.route('**/*', route => {
        const req = route.request(), u = new URL(req.url());
        const local = ['127.0.0.1', 'localhost'].includes(u.hostname) && ['8084', '5182'].includes(u.port);
        if (!local) return route.abort();
        const forbidden = u.pathname.startsWith('/api/') && (!['GET', 'HEAD'].includes(req.method()) || (/payment|refund|renew/i.test(u.pathname) && u.pathname !== '/api/v1/payment/channels'));
        if (forbidden) { report.blockedRequests.push({ path: u.pathname, method: req.method() }); return route.abort(); }
        return route.continue();
      });
      if (auth[item.role]) await context.addInitScript(s => sessionStorage.setItem('crsp_session', JSON.stringify({ token: s.token, user: s.user, expiresAt: Date.now() + s.expiresIn * 1000 })), auth[item.role]);
      const page = await context.newPage();
      page.on('console', message => { if (message.type() === 'error' && !message.text().includes('net::ERR_ABORTED')) report.consoleErrors.push({ role: item.role, message: message.text().slice(0, 700) }); });
      page.on('pageerror', e => report.pageErrors.push({ role: item.role, message: e.message, stack: e.stack }));
      page.on('response', r => { const u = new URL(r.url()); if (r.status() >= 400 && ['127.0.0.1', 'localhost'].includes(u.hostname)) report.failedLocalResponses.push({ role: item.role, path: u.pathname, status: r.status() }); });
      for (const route of item.routes) for (const width of [1024, 768]) {
        await page.setViewportSize({ width, height: 900 });
        const response = await page.goto(preview + route);
        await page.waitForLoadState('networkidle');
        check(item.role + ' ' + route + ' @' + width + '生产加载', response.status() === 200 && new URL(page.url()).pathname === route.split('?')[0] && (await page.locator('#app').innerText()).trim().length > 10);
        if (route === '/staff/dashboard' || route === '/admin/dashboard') {
          await page.locator('canvas').first().waitFor({ state: 'visible' });
          check(item.role + '图表真实Canvas @' + width, await page.locator('canvas').count() > 0);
          if (width === 768) await page.screenshot({ path: path.join(out, item.role + '-production-768.png'), fullPage: true });
        }
        if (route === '/admin/residents') {
          await page.getByRole('button', { name: '代建居民', exact: true }).click();
          const dialog = page.locator('.el-dialog:visible'); await dialog.waitFor({ state: 'visible' });
          check('EP弹窗和输入组件已加载 @' + width, await dialog.locator('input').count() > 0);
          if (width === 768) await page.screenshot({ path: path.join(out, 'ep-dialog-production-768.png'), fullPage: true });
          await dialog.getByRole('button', { name: '取消', exact: true }).click();
          await dialog.waitFor({ state: 'hidden' });
          check('EP弹窗取消关闭且未提交 @' + width, true);
        }
      }
      await context.close();
    }
    check('没有分包初始化或页面JS异常', report.pageErrors.length === 0);
    check('没有组件初始化console异常', report.consoleErrors.length === 0);
    check('没有本机资源或API失败', report.failedLocalResponses.length === 0);
    check('没有触发业务写或交易端点', report.blockedRequests.length === 0);
    report.after = counts(); check('正常库数量未改变', JSON.stringify(report.before) === JSON.stringify(report.after));
  } catch (error) { report.error = error.message; process.exitCode = 1; }
  finally {
    if (browser) await browser.close();
    fs.writeFileSync(path.join(out, 'production-readonly.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ checks: report.checks.length, before: report.before, after: report.after, pageErrors: report.pageErrors, failedLocalResponses: report.failedLocalResponses, error: report.error }));
  }
})();
