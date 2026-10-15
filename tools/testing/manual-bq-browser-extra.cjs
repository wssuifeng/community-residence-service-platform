const fs = require('fs');
const assert = require('assert/strict');
const path = require('path');
const { chromium } = require(path.join(require('os').homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const { request, dir } = require('./manual-bq-regression.cjs');

(async () => {
  const fixture = JSON.parse(fs.readFileSync('output/runtime/bq-fixture-private.json', 'utf8'));
  const checks = [], pageerrors = [];
  const check = (name, value) => { assert(value, name); checks.push({ name, passed: true }); };
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await context.addInitScript(session => sessionStorage.setItem('crsp_session', JSON.stringify({
      token: session.token, user: session.user, expiresAt: Date.now() + 3600000
    })), fixture.adminSession);
    await context.route('**/*', route => {
      const url = new URL(route.request().url());
      if (!['127.0.0.1', 'localhost'].includes(url.hostname) || !['5181', '8087'].includes(url.port) || /payment|refund|lease.*renew/i.test(url.pathname)) return route.abort();
      return route.continue();
    });
    const page = await context.newPage();
    page.on('pageerror', error => pageerrors.push(error.message));
    const renewal = page.waitForResponse(response => response.url().endsWith('/auth/renew'));
    await page.goto('http://127.0.0.1:5181/admin/notices');
    await page.waitForLoadState('networkidle');
    check('前台真实有效会话进入续期窗口自动调用续期接口', (await renewal).status() === 200);
    check('浏览器续期后有效期重新延长约36h', await page.evaluate(() => {
      const session = JSON.parse(sessionStorage.getItem('crsp_session'));
      return session.expiresAt - Date.now() > 35.9 * 3600000;
    }));
    const draft = await request('/notices', 'POST', {
      communityId: fixture.community.id, title: 'BQ公告编辑补充回归', content: '上传、回显和清空',
      images: [fixture.uploaded.fileUrl], publishTime: new Date().toISOString().slice(0, 19)
    }, fixture.adminSession.token);
    await page.goto(`http://127.0.0.1:5181/admin/notices?action=edit&id=${draft.id}`);
    await page.waitForLoadState('networkidle');
    await page.locator('.image-item').waitFor();
    check('管理公告编辑回显实际存图', await page.locator('.image-item img').getAttribute('src') === fixture.uploaded.fileUrl);
    await page.getByRole('button', { name: '发布公告', exact: true }).click();
    check('编辑带图公告后新建不会继承上一条图片', await page.locator('.image-item').count() === 0);
    await page.goto(`http://127.0.0.1:5181/admin/notices?action=edit&id=${draft.id}`);
    await page.waitForLoadState('networkidle');
    await page.locator('.image-remove').click();
    const clearResponse = page.waitForResponse(response => response.url().endsWith(`/notices/${draft.id}`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '保存不发布', exact: true }).click();
    check('管理端移除图片后实际更新保存成功', (await clearResponse).status() === 200);
    check('图片清空已持久化到后端', (await request(`/notices/${draft.id}`, 'GET', undefined, fixture.adminSession.token)).images.length === 0);
    await page.goto(`http://127.0.0.1:5181/admin/notices?action=edit&id=${draft.id}`);
    await page.waitForLoadState('networkidle');
    const upload = page.waitForResponse(response => response.url().includes('/upload') && response.request().method() === 'POST');
    await page.locator('.base-uploader input[type=file]').setInputFiles('output/runtime/bq-image.png');
    check('管理公告选图走真实上传接口', (await upload).status() === 200);
    await page.locator('.image-item').waitFor();
    const saveResponse = page.waitForResponse(response => response.url().endsWith(`/notices/${draft.id}`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '保存不发布', exact: true }).click();
    check('新上传公告图片保存成功', (await saveResponse).status() === 200);
    check('新上传公告图片重新回读存在', (await request(`/notices/${draft.id}`, 'GET', undefined, fixture.adminSession.token)).images.length === 1);
    await page.screenshot({ path: dir + '/notice-real-upload-edit.png', fullPage: true });
    await page.goto('http://127.0.0.1:5181/admin/housing-lease?tab=leases');
    await page.waitForLoadState('networkidle');
    const todos = await request('/statistics/todos', 'GET', undefined, fixture.adminSession.token);
    check('租住Hub子Tab红点与真实后端待办逐项一致', await page.locator('.tab-bar button').evaluateAll((buttons, counts) => {
      const keys = ['applications', 'leases', 'housings', 'moveout', 'agreements'];
      return buttons.every((button, index) => !!button.querySelector('.todo-dot') === ((counts[keys[index]] || 0) > 0));
    }, todos));
    check('补充浏览器检查无pageerror', pageerrors.length === 0);
    fs.writeFileSync(dir + '/browser-extra.json', JSON.stringify({ date: '2026-10-15', checks, pageerrors, transactionsExecuted: false }, null, 2));
    console.log(JSON.stringify({ checks: checks.length, pageerrors: pageerrors.length }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
