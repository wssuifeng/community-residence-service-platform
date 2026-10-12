import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const option = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
if (args.includes('--help')) {
  console.log('node run-probe.mjs --classpath <target/classpath file> --output <redacted json> [--env-file <ignored .env>]\nOptional PLAYWRIGHT_MODULE_PATH and JAVA_HOME. Fresh sandbox-only 0.01 orders; never logs secrets or attaches to existing browsers.');
  process.exit(0);
}
const classpathFile = option('--classpath');
const outputFile = option('--output');
if (!classpathFile || !outputFile) throw new Error('Classpath and output paths required; use --help');
let inputs = { ...process.env };
const envFile = option('--env-file');
if (envFile) {
  for (const line of fs.readFileSync(envFile, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)\s*$/);
    if (match) inputs[match[1]] = match[2].replace(/^(["'])(.*)\1$/, '$2').trim();
  }
}
const appId = inputs.ALIPAY_PROBE_APP_ID || inputs.PAYMENT_ALIPAY_APP_ID;
const privateKey = inputs.ALIPAY_PROBE_PRIVATE_KEY || inputs.PAYMENT_ALIPAY_MERCHANT_PRIVATE_KEY;
const alipayPublicKey = inputs.ALIPAY_PROBE_PUBLIC_KEY || inputs.PAYMENT_ALIPAY_PUBLIC_KEY;
if (!appId || !privateKey || !alipayPublicKey) throw new Error('Sandbox credentials missing');
const pem = (value, type) => value.includes('-----BEGIN') ? value : '-----BEGIN ' + type + '-----\n' + value.replace(/\s/g, '') + '\n-----END ' + type + '-----';
const applicationPublicKey = crypto.createPublicKey(crypto.createPrivateKey(pem(privateKey, 'PRIVATE KEY')));
const cp = fs.readFileSync(classpathFile, 'utf8').trim();
if (/spring|community-residence-service-platform[\\/]backend/i.test(cp)) throw new Error('Business classpath detected');
const sdkVersion = cp.match(/alipay-sdk-java-([0-9.A-Z]+)\.jar/)?.[1];
if (!sdkVersion) throw new Error('Official SDK jar not present');
const java = option('--java') || (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe') : 'java');
const childEnv = { ...process.env, ALIPAY_PROBE_APP_ID: appId, ALIPAY_PROBE_PRIVATE_KEY: privateKey,
  ALIPAY_PROBE_PUBLIC_KEY: alipayPublicKey, ALIPAY_PROBE_GATEWAY: 'https://openapi-sandbox.dl.alipaydev.com/gateway.do' };
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const child = spawn(java, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', path.join(here, 'target/classes') + path.delimiter + cp,
  'diagnostic.AlipaySandboxProbe', '0'], { env: childEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
let browser;
const report = { startedAt: new Date().toISOString(), sdkVersion, sandboxOnly: true, amount: '0.01', freshOrders: true,
  isolation: { businessApi: false, database: false, frontendFramework: false, blob: false, existingOrder: false,
    userBrowser: false, nativeSdkForm: true, systemProxyChanged: false, browserProxy: 'direct', credentials: 'same verified sandbox app, input only' },
  probes: [], paymentConfirmed: false };
const safeUrl = (raw) => {
  const u = new URL(raw);
  return { scheme: u.protocol, host: u.hostname, path: u.pathname, keys: [...u.searchParams.keys()].filter(k => k !== 'sign') };
};
const verifyRequest = (request) => {
  const u = new URL(request.url());
  const parameters = new URLSearchParams(u.search);
  const postData = request.postData() || '';
  for (const [k, v] of new URLSearchParams(postData)) parameters.set(k, v);
  const signature = parameters.get('sign');
  parameters.delete('sign');
  const keys = [...parameters.keys()].filter(k => parameters.get(k) !== '').sort();
  const canonical = keys.map(k => k + '=' + parameters.get(k)).join('&');
  let biz;
  try { biz = JSON.parse(parameters.get('biz_content') || 'null'); } catch { /* Only record parse failure, never raw payload. */ }
  return { signedKeys: keys, rsa2Valid: signature ? crypto.verify('RSA-SHA256', Buffer.from(canonical, 'utf8'), applicationPublicKey, Buffer.from(signature, 'base64')) : false,
    bizPresent: !!biz, productCode: biz?.product_code, totalAmount: biz?.total_amount, freshDiagnosticOrder: /^ISO[A-Z0-9]{10,40}$/.test(biz?.out_trade_no || ''),
    bodyPresent: postData.length > 0, charset: parameters.get('charset'), contentType: request.headers()['content-type'],
    subjectHasReplacement: biz?.subject?.includes('\uFFFD') || false };
};
async function getJson(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(45000) });
  return { httpStatus: response.status, ...await response.json() };
}
function waitReady() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Independent server startup timeout')), 15000);
    let buffered = '';
    child.stdout.on('data', chunk => {
      buffered += chunk.toString('utf8');
      for (const line of buffered.split(/\r?\n/)) {
        try { const event = JSON.parse(line); if (event.event === 'ready') { clearTimeout(timer); resolve(event.port); } } catch { /* No raw stdout is logged. */ }
      }
    });
    child.once('error', () => { clearTimeout(timer); reject(new Error('Independent server spawn failed')); });
    child.once('exit', code => { clearTimeout(timer); reject(new Error('Independent server exited ' + code)); });
  });
}
try {
  const port = await waitReady();
  const local = 'http://127.0.0.1:' + port;
  report.health = await getJson(local + '/health');
  report.queryControl = await getJson(local + '/query-control');
  report.directHttp = { transport: 'Node native fetch GET, no browser/proxy/cookies', events: [] };
  try {
    let current = local + '/pay/get';
    for (let hop = 0; hop < 8; hop++) {
      const response = await fetch(current, { redirect: 'manual', signal: AbortSignal.timeout(30000) });
      const u = new URL(current);
      if (u.hostname !== '127.0.0.1') {
        const event = { method: 'GET', status: response.status, url: safeUrl(current) };
        if (u.hostname === 'openapi-sandbox.dl.alipaydev.com' && u.pathname === '/gateway.do') event.outgoing = verifyRequest({ url: () => current, postData: () => '', headers: () => ({}) });
        event.responseHeaders = Object.fromEntries(['date', 'server', 'content-type', 'content-length'].map(k => [k, response.headers.get(k)]).filter(([, v]) => v !== null));
        report.directHttp.events.push(event);
      }
      const location = response.headers.get('location');
      if (location && [301, 302, 303, 307, 308].includes(response.status)) {
        await response.arrayBuffer();
        current = new URL(location, current).href;
      } else {
        const text = await response.text();
        report.directHttp.final = safeUrl(current);
        report.directHttp.bodyCharacterCount = text.length;
        report.directHttp.invalidSignature = text.includes('invalid-signature');
        report.directHttp.whitelabel = text.includes('Whitelabel Error Page');
        report.directHttp.paymentQrMention = /二维码|扫码支付|立即付款/.test(text);
        break;
      }
    }
  } catch (error) { report.directHttp.probeErrorType = error.name; }

  browser = await chromium.launch({ channel: 'msedge', headless: true, args: ['--no-proxy-server'] });
  for (const mode of ['POST', 'GET']) {
    const context = await browser.newContext();
    const page = await context.newPage();
    const probe = { mode, initialCookieCount: (await context.cookies()).length, events: [] };
    let orderNo; let stage = 'navigation';
    const pending = [];
    page.on('response', response => {
      pending.push((async () => {
        const request = response.request();
        if (!request.isNavigationRequest() || request.frame() !== page.mainFrame()) return;
        const u = new URL(response.url());
        const headers = response.headers();
        if (u.hostname === '127.0.0.1') { if (headers['x-probe-order']) orderNo = headers['x-probe-order']; return; }
        const event = { method: request.method(), status: response.status(), url: safeUrl(response.url()) };
        if (u.hostname === 'openapi-sandbox.dl.alipaydev.com' && u.pathname === '/gateway.do') event.outgoing = verifyRequest(request);
        if (headers.location) event.location = safeUrl(new URL(headers.location, response.url()).href);
        for (const key of ['date', 'server', 'content-type', 'content-length', 'alipay-trace-id', 'trace-id', 'x-request-id']) if (headers[key]) (event.responseHeaders ||= {})[key] = headers[key];
        probe.events.push(event);
      })());
    });
    page.on('requestfailed', request => {
      const u = new URL(request.url());
      if (u.protocol.startsWith('http')) (probe.failedResources ||= []).push({ url: safeUrl(request.url()), resourceType: request.resourceType(), errorCategory: request.failure()?.errorText?.match(/net::[A-Z0-9_]+/)?.[0] || 'request_failed' });
    });
    try {
      await page.goto(local + '/pay/' + mode.toLowerCase(), { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForFunction(() => location.hostname !== '127.0.0.1', undefined, { timeout: 15000 });
      await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {});
      await Promise.all(pending);
      stage = 'page-inspection';
      const text = await page.locator('body').innerText({ timeout: 10000 });
      probe.final = safeUrl(page.url());
      probe.dom = await page.evaluate(() => ({ htmlLength: document.documentElement.outerHTML.length, bodyTextLength: document.body?.innerText.length || 0, scripts: document.scripts.length, bodyChildCount: document.body?.children.length || 0, iframeCount: document.querySelectorAll('iframe').length }));
      probe.observation = { title: await page.title(), invalidSignature: text.includes('invalid-signature'), whitelabel: text.includes('Whitelabel Error Page'),
        error404Text: /404|Not Found/i.test(text), loginPage: probe.final.path.includes('/login/'),
        paymentQrMention: /二维码|扫码支付|立即付款/.test(text) };
      stage = 'query-after';
      if (orderNo) probe.queryAfter = await getJson(local + '/query?order=' + encodeURIComponent(orderNo));
      probe.orderNo = orderNo;
    } catch (error) { probe.probeErrorType = error.name; probe.failureStage = stage; probe.errorCategory = error.message?.match(/net::[A-Z0-9_]+/)?.[0] || 'diagnostic_step_failed'; }
    finally { await context.close(); }
    report.probes.push(probe);
    console.log(JSON.stringify({ sdkVersion, mode, signedRequestValid: probe.events.find(e => e.outgoing)?.outgoing.rsa2Valid,
      chain: probe.events.map(e => ({ status: e.status, host: e.url.host, path: e.url.path })), final: probe.final, errorType: probe.probeErrorType }));
  }
} catch (error) { report.runnerErrorType = error.name; process.exitCode = 1; }
finally {
  if (browser) await browser.close().catch(() => {});
  child.kill();
  report.finishedAt = new Date().toISOString();
  fs.mkdirSync(path.dirname(path.resolve(outputFile)), { recursive: true });
  fs.writeFileSync(outputFile, JSON.stringify(report, null, 2) + '\n');
}
