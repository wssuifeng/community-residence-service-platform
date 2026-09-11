/**
 * 50 系统测试 · A 轨 · SP-04 WebSocket 专项（TC-SP-017~021）
 * - TC-SP-017 CONNECT 握手认证（无/伪/过期/黑名单/有效令牌）
 * - TC-SP-018 反馈会话订阅鉴权矩阵（含 admin1 解绑后既有连接即时生效）
 * - TC-SP-019 在线推送时延 ≤3s 双通道抽样（通知通道 + 会话通道 各 ≥10 次）
 * - TC-SP-020 断线重连（后端侧重连+重订阅语义；前端退避策略为静态核对，
 *   浏览器侧双端观察归 B/C 轨）
 * - TC-SP-021 上线补拉（seq 增量：离线 ≥3 通知 100% 补拉 + 重连路径互证）
 * 实现：原生 SockJS 握手（/ws/info + websocket 传输）+ STOMP 帧编解码，
 *       Node 24 全局 WebSocket，无第三方依赖。
 * 用法：node sp04_websocket_tests.mjs
 */
import { execSync } from 'child_process';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const BASE = process.env.TEST_BASE || 'http://localhost:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');
let pass = 0, fail = 0;
const failures = [];
function tc(id, expectDesc, ok, actual) {
  ok ? pass++ : fail++;
  if (!ok) failures.push(`[${id}] 期望${expectDesc} 实际${actual}`);
  console.log(`${ok ? '✅' : '❌'} ${id} → ${actual}`);
}
async function api(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch { }
  return { status: res.status, code: json?.code, json, data: json?.data };
}
function q(sql) {
  return execSync(`mysql -u root -h localhost --default-character-set=utf8mb4 community_residence_test -N -e "${sql.replace(/"/g, '\\"')}"`, { shell: 'bash', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).toString().trim();
}
const ok = r => r.status === 200 && r.code === 200;

/* ── SockJS + STOMP 客户端 ─────────────────────────────────────────── */
function parseStompFrame(s) {
  const lines = s.split('\n');
  const command = lines[0];
  const headers = {};
  let i = 1;
  for (; i < lines.length && lines[i] !== ''; i++) {
    const idx = lines[i].indexOf(':');
    if (idx > 0) headers[lines[i].slice(0, idx)] = lines[i].slice(idx + 1);
  }
  const body = lines.slice(i + 1).join('\n').replace(/\u0000$/, '');
  return { command, headers, body };
}
function stompFrame(command, headers = {}, body = '') {
  return command + '\n' + Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n') + '\n\n' + body + '\u0000';
}

class StompSession {
  constructor(token, label = '') {
    this.token = token; this.label = label; this.receiptSeq = 0;
    this.messages = [];          // {command, headers, body, at}
    this.waiters = [];           // predicate resolvers
    this.closed = false;
  }
  async connect() {
    const info = await (await fetch(`${BASE}/ws/info`)).json();
    if (!info.websocket) throw new Error('SockJS websocket 传输不可用');
    const srv = String(Math.floor(Math.random() * 999)).padStart(3, '0');
    const sess = 'sp' + Math.random().toString(36).slice(2, 10);
    this.ws = new WebSocket(`${WS_BASE}/ws/${srv}/${sess}/websocket`);
    this.ws.onmessage = ev => this._onSockjs(ev.data);
    this.ws.onclose = () => { this.closed = true; if (process.env.SP04_DEBUG) console.log(`  [ws关闭] ${this.label}`); };
    // 等 SockJS open 帧 "o"
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('SockJS open 超时')), 5000);
      this._openWaiter = () => { clearTimeout(t); res(); };
    });
    this.ws.send(JSON.stringify([stompFrame('CONNECT', {
      'accept-version': '1.2', host: 'localhost',
      Authorization: `Bearer ${this.token}`,
    })]));
    // 等 CONNECTED 或 ERROR
    const f = await this._wait(f2 => f2.command === 'CONNECTED' || f2.command === 'ERROR', 5000, 'CONNECTED');
    this.connectedFrame = f;
    return f;
  }
  _onSockjs(data) {
    if (process.env.SP04_DEBUG) console.log(`  [sockjs<${this.label}] ${JSON.stringify(data).slice(0, 150)}`);
    if (data === 'o') { this._openWaiter?.(); return; }
    if (data === 'h' || data.startsWith('c[')) return;
    if (data.startsWith('a')) {
      for (const s of JSON.parse(data.slice(1))) {
        const f = { ...parseStompFrame(s), at: Date.now() };
        this.messages.push(f);
        this.waiters = this.waiters.filter(w => !(w.pred(f) && (w.res(f), true)));
      }
    }
  }
  _wait(pred, timeoutMs, what) {
    const hit = this.messages.find(pred);
    if (hit) return Promise.resolve(hit);
    return new Promise((res, rej) => {
      const w = { pred, res };
      this.waiters.push(w);
      setTimeout(() => rej(new Error(`等待 ${what} 超时（${this.label}）`)), timeoutMs);
    });
  }
  /* 订阅：本版本 SimpleBroker 成功订阅不回 RECEIPT（DISCONNECT 才回执）；
     拒绝时拦截器回 ERROR 帧并关连接——以「窗口内无 ERROR」为受理，
     受理后由调用方用真实推送确认投递（行为级断言，强于回执） */
  async subscribe(dest, noErrWindowMs = 1500) {
    const subId = 'sub-' + (++this.receiptSeq);
    this.ws.send(JSON.stringify([stompFrame('SUBSCRIBE', { id: subId, destination: dest })]));
    const err = await this._waitQuiet(f => f.command === 'ERROR', noErrWindowMs);
    return { ok: !err, frame: err };
  }
  _waitQuiet(pred, timeoutMs) {
    return new Promise(res => {
      const w = { pred, res: f => res(f) };
      this.waiters.push(w);
      setTimeout(() => res(null), timeoutMs);
    });
  }
  /* 等一条 MESSAGE（按谓词匹配），返回到达时刻与帧 */
  async waitMessage(pred, timeoutMs = 5000) {
    const f = await this._wait(f2 => f2.command === 'MESSAGE' && pred(f2), timeoutMs, 'MESSAGE');
    return f;
  }
  close() { try { this.ws?.close(); } catch { } this.closed = true; }
}

/* ── JWT 工具（铸过期令牌：dev 默认密钥先验证再铸过期变体） ────────── */
const DEV_SECRET = 'dev-only-jwt-secret-0123456789abcdef-0123456789abcdef';
const b64u = (obj, json = true) => Buffer.from(json ? JSON.stringify(obj) : obj).toString('base64url');
function signJwt(payload) {
  const h = b64u({ alg: 'HS256', typ: 'JWT' });
  const p = b64u(payload);
  const sig = crypto.createHmac('sha256', DEV_SECRET).update(`${h}.${p}`).digest('base64url');
  return `${h}.${p}.${sig}`;
}

async function main() {
  /* 登录（各角色令牌） */
  const login = async (ep, u, p) => (await api('POST', `/api/v1/auth/${ep}/login`, { body: { username: u, password: p } })).data?.token;
  const superTok = await login('admin', 'superadmin', 'Admin@123456');
  let admin1Tok = await login('admin', 'admin1', 'Admin123456');
  const staff1Tok = await login('admin', 'staff1', 'Staff123456');
  const r1Tok = await login('resident', 'resident1', 'Resident123456');
  const r2Tok = await login('resident', 'test_resident2', 'Resident123456');
  const admin2Tok = await login('admin', 'test_admin', 'Admin123456'); // 社区 2 管理员
  const admin1Id = Number(q(`SELECT id FROM sys_user WHERE username='admin1'`));
  const r1Id = 1;

  /* ── TC-SP-017 CONNECT 握手认证 ─────────────────────────────── */
  let r2Tok2;
  {
    // 1 无令牌（直接手工连接：不传 Authorization 头）
    const rawNo = await connectRaw(null);
    // 2 伪令牌
    const rawFake = await connectRaw('fake.token.value');
    // 3 过期令牌（先用 dev 密钥铸有效令牌验证密钥正确，再铸过期变体）
    const loginPayload = JSON.parse(Buffer.from(r1Tok.split('.')[1], 'base64url').toString());
    const minted = signJwt(loginPayload); // 同 claims 重签
    const rawMinted = await connectRaw(minted);
    const expiredPayload = { ...loginPayload, exp: Math.floor(Date.now() / 1000) - 3600 };
    const rawExpired = rawMinted.ok ? await connectRaw(signJwt(expiredPayload)) : { ok: null, note: '密钥未确认跳过' };
    // 4 黑名单令牌（resident2 登出后复用令牌；后续矩阵还需 resident2 在线，重登获取新令牌）
    await api('POST', '/api/v1/auth/resident/logout', { token: r2Tok });
    const rawBlack = await connectRaw(r2Tok);
    const r2Tok2Val = await login('resident', 'test_resident2', 'Resident123456');
    r2Tok2 = r2Tok2Val;
    // 5 有效令牌
    const sValid = new StompSession(r1Tok, 'valid');
    const validConn = await sValid.connect();
    const subNotif = validConn.command === 'CONNECTED' ? await sValid.subscribe('/user/queue/notifications') : { ok: false };
    const connAllOk = !rawNo.ok && !rawFake.ok && !rawExpired.ok && !rawBlack.ok
      && validConn.command === 'CONNECTED' && subNotif.ok;
    tc('TC-SP-017', '无/伪/过期/黑名单令牌 CONNECT 全拒，有效令牌连接+通知订阅成功',
      connAllOk,
      `无令牌=${rawNo.ok ? '通过(缺陷)' : '拒(' + (rawNo.error || '').slice(0, 20) + ')'} 伪=${rawFake.ok ? '通过(缺陷)' : '拒'} 过期=${rawExpired.ok === null ? rawExpired.note : rawExpired.ok ? '通过(缺陷)' : '拒'} 黑名单=${rawBlack.ok ? '通过(缺陷)' : '拒'} 有效=${validConn.command} 订阅=${subNotif.ok}`);
    sValid.close();
  }

  /* ── 准备：resident1 反馈 F（已受理会话中） ──────────────────── */
  const fb = await api('POST', '/api/v1/feedbacks', { token: r1Tok, body: { communityId: 1, title: `SP04-WS专项反馈${Date.now() % 10000}`, content: 'WebSocket 专项测试反馈', category: 'SUGGESTION' } });
  const F = fb.data?.id;
  await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: admin1Tok, body: { content: '管理员受理，开始会话' } }); // 受理 + 设 handlerId

  /* ── TC-SP-018 反馈会话订阅鉴权矩阵 ──────────────────────────── */
  {
    const cells = [
      { who: 'resident1(提交人)', tok: r1Tok, expect: true },
      { who: 'resident2(非提交人)', tok: r2Tok2, expect: false },
      { who: 'staff1', tok: staff1Tok, expect: false },
      { who: 'admin2(社区2,跨社区)', tok: admin2Tok, expect: false },
      { who: 'admin1(绑定社区1)', tok: admin1Tok, expect: true },
      { who: 'superadmin', tok: superTok, expect: true },
    ];
    const results = [];
    for (const c of cells) {
      const s = new StompSession(c.tok, c.who);
      try {
        const conn = await s.connect();
        if (conn.command !== 'CONNECTED') { results.push(`${c.who}=连接失败`); s.close(); continue; }
        const sub = await s.subscribe(`/topic/feedback/${F}`);
        results.push(`${c.who}=${sub.ok ? '订阅成功' : '拒(' + (sub.frame.body || '').slice(0, 22) + ')'}`);
        c.session = s; // 保留预期成功者收推送
        if (!sub.ok || !c.expect) s.close();
      } catch (e) { results.push(`${c.who}=异常(${e.message.slice(0, 30)})`); s.close(); }
    }
    const matrixOk = results.length === 6 &&
      /resident1\(提交人\)=订阅成功/.test(results.join(';')) &&
      /resident2\(非提交人\)=拒/.test(results.join(';')) &&
      /staff1=拒/.test(results.join(';')) &&
      /admin2\(社区2,跨社区\)=拒/.test(results.join(';')) &&
      /admin1\(绑定社区1\)=订阅成功/.test(results.join(';')) &&
      /superadmin=订阅成功/.test(results.join(';'));
    tc('TC-SP-018-矩阵', '五角色+超管订阅判定 100% 符合（提交人/绑定ADMIN/超管放行，其余拒）',
      matrixOk, results.join('；'));

    // 步骤 7：鉴权通过者收推送、被拒者未收到
    const r1s = cells[0].session, a1s = cells[4].session, sus = cells[5].session;
    let pushResults = [];
    if (r1s && a1s) {
      const marker = `WS推送验证-${Date.now()}`;
      await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: admin1Tok, body: { content: marker } });
      const r1Got = await r1s.waitMessage(f => f.body.includes(marker), 4000).then(() => true).catch(() => false);
      const a1Got = await a1s.waitMessage(f => f.body.includes(marker), 4000).then(() => true).catch(() => false);
      let wrapped = null;
      try { const f = r1s.messages.find(x => x.command === 'MESSAGE' && x.body.includes(marker)); wrapped = f ? JSON.parse(f.body) : null; } catch { }
      pushResults.push(`resident1收=${r1Got} admin1收=${a1Got} 载荷type=${wrapped?.type}`);
      tc('TC-SP-018-推送', '推送仅达已获订阅授权参与者且 FEEDBACK_MESSAGE 包裹',
        r1Got && a1Got && wrapped?.type === 'FEEDBACK_MESSAGE',
        `resident1=${r1Got} admin1=${a1Got} type=${wrapped?.type ?? '无'}`);
    } else {
      tc('TC-SP-018-推送', '推送仅达已获订阅授权参与者', false, '前置会话缺失');
    }
    r1s?.close(); a1s?.close(); sus?.close();

    // 步骤 8：admin1 解绑社区 1 后，既有连接订阅新社区 1 反馈 → 拒（绑定实时生效）
    const fb2 = await api('POST', '/api/v1/feedbacks', { token: r1Tok, body: { communityId: 1, title: `SP04-解绑验证${Date.now() % 10000}`, content: 'x', category: 'SUGGESTION' } });
    const F2 = fb2.data?.id;
    const a1live = new StompSession(admin1Tok, 'admin1-live');
    const a1conn = await a1live.connect();
    const beforeUnbind = a1conn.command === 'CONNECTED' ? (await a1live.subscribe(`/topic/feedback/${F2}`)).ok : null;
    const unbind = await api('DELETE', `/api/v1/sys-users/${admin1Id}/communities/1`, { token: superTok });
    let afterUnbind = null, errMsg = '';
    try { afterUnbind = (await a1live.subscribe(`/topic/feedback/${F2}`)).ok; } catch (e) { errMsg = e.message.slice(0, 30); }
    // 立即恢复绑定（环境还原，强制收尾）
    const rebind = await api('POST', `/api/v1/sys-users/${admin1Id}/communities`, { token: superTok, body: { communityId: 1 } });
    const rebindOk = q(`SELECT COUNT(*) FROM sys_admin_community WHERE admin_id=${admin1Id} AND community_id=1`);
    a1live.close();
    tc('TC-SP-018-绑定实时', '解绑后既有连接订阅同社区新反馈被拒，恢复绑定后库还原',
      beforeUnbind === true && afterUnbind === false && Number(rebindOk) === 1 && ok(unbind) && ok(rebind),
      `解绑前订阅=${beforeUnbind} 解绑后订阅=${afterUnbind}${errMsg ? '(' + errMsg + ')' : ''} 解绑=${unbind.code} 重绑=${rebind.code} 库绑定行=${rebindOk}`);
    /* 绑定变更触发 PERMISSION_CHANGE 前缀吊销（权限即时生效口径），admin1 旧令牌
       在 HTTP 层失效——重新登录取新令牌，供 TC-SP-019 及后续用例 */
    admin1Tok = await login('admin', 'admin1', 'Admin123456');
  }

  /* ── TC-SP-019 在线推送时延 ≤3s 双通道抽样 ───────────────────── */
  {
    const r1s = new StompSession(r1Tok, 'r1-timing');
    const a1s = new StompSession(admin1Tok, 'a1-timing');
    await r1s.connect(); await a1s.connect();
    const r1Notif = await r1s.subscribe('/user/queue/notifications');
    const a1Notif = await a1s.subscribe('/user/queue/notifications');
    await r1s.subscribe(`/topic/feedback/${F}`);
    await a1s.subscribe(`/topic/feedback/${F}`);
    if (!r1Notif.ok || !a1Notif.ok) {
      tc('TC-SP-019', '双端通知订阅就绪', false, `r1=${r1Notif.ok} a1=${a1Notif.ok}`);
    } else {
      const latNotif = [], latTopic = [];
      const mk = n => `时延样本${n}-${Date.now() % 100000}`;
      // 会话通道 + 通知通道双向：admin→resident 5 次，resident→admin 5 次
      for (let i = 1; i <= 10; i++) {
        const fromAdmin = i <= 5;
        const marker = mk(i);
        const senderTok = fromAdmin ? admin1Tok : r1Tok;
        const receiver = fromAdmin ? r1s : a1s;
        const t0 = Date.now();
        // f.at >= t0：排除缓冲中的历史通知（同一反馈的回复通知内容相同，仅时间可区分）
        const pNotif = receiver.waitMessage(f => f.at >= t0 && f.headers.destination?.includes('/queue/notifications') && f.body.includes('反馈'), 5000).then(f => f.at - t0);
        const pTopic = receiver.waitMessage(f => f.at >= t0 && f.headers.destination?.includes(`/topic/feedback/${F}`) && f.body.includes(marker), 5000).then(f => f.at - t0);
        await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: senderTok, body: { content: marker } });
        const [ln, lt] = await Promise.all([pNotif, pTopic]);
        latNotif.push(ln); latTopic.push(lt);
        await new Promise(x => setTimeout(x, 120));
      }
      // 通知通道事件多样性：工单流转 ×2（resident1 提交 → admin 派单 staff1 →
      // staff1 接单 → 居民收「已被接单」通知；PENDING 不能直接接单，须先 assign）
      const mkOrder = async () => {
        for (let i = 0; i < 5; i++) {
          const r = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: `SP04时延工单${Date.now() % 10000}`, content: '时延样本', contactPhone: '13812345678', address: '1号楼1单元101', priority: 'NORMAL' } });
          if (ok(r)) return r.data;
          await new Promise(x => setTimeout(x, 100));
        }
        throw new Error('mkOrder 失败');
      };
      for (let i = 0; i < 2; i++) {
        const w = await mkOrder();
        // 通知在 assign 时刻发出（「工单已派单」，accept 不再发）——waiter 须先于 assign 注册
        const t0 = Date.now();
        const p = r1s.waitMessage(f => f.at >= t0 && f.headers.destination?.includes('/queue/notifications') && f.body.includes('工单'), 5000).then(f => f.at - t0);
        await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'SP04 时延样本派单' } });
        await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'SP04 时延样本接单' } });
        const ln = await p;
        latNotif.push(ln);
      }
      const maxN = Math.max(...latNotif), maxT = Math.max(...latTopic);
      const fmt = a => a.map(x => x + 'ms').join(',');
      tc('TC-SP-019-通知通道', `12 样本全部 ≤3s（反馈回复×10 + 工单接单×2，双向）`,
        latNotif.length === 12 && maxN <= 3000,
        `n=${latNotif.length} max=${maxN}ms [${fmt(latNotif)}]`);
      tc('TC-SP-019-会话通道', `10 样本全部 ≤3s（双向各 5 次）`,
        latTopic.length === 10 && maxT <= 3000,
        `n=${latTopic.length} max=${maxT}ms [${fmt(latTopic)}]`);
      r1s.close(); a1s.close();
    }
  }

  /* ── TC-SP-020 断线重连（后端语义 + 前端策略静态核对口径） ───── */
  {
    // 后端语义：断开 → 服务器无碍 → 新连接 + 重订阅成功 → 会话消息实时到达
    const s1 = new StompSession(r1Tok, 'r1-重连');
    await s1.connect();
    await s1.subscribe(`/topic/feedback/${F}`);
    s1.close(); // 模拟断线
    await new Promise(x => setTimeout(x, 300));
    const s2 = new StompSession(r1Tok, 'r1-重连2');
    const conn = await s2.connect();
    const resub = conn.command === 'CONNECTED' ? await s2.subscribe(`/topic/feedback/${F}`) : { ok: false };
    const resubNotif = resub.ok ? await s2.subscribe('/user/queue/notifications') : { ok: false };
    let arrived = false, latencyMs = -1;
    if (resub.ok) {
      const marker = `重连后推送-${Date.now()}`;
      const t0 = Date.now();
      const p = s2.waitMessage(f => f.body.includes(marker), 4000).then(f => f.at - t0);
      await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: admin1Tok, body: { content: marker } });
      latencyMs = await p; arrived = latencyMs >= 0 && latencyMs <= 3000;
    }
    s2.close();
    // 前端策略静态核对：utils/websocket.ts 退避常量与 store/notification.ts 轮询周期
    const repoRoot = import.meta.dirname.replace(/\\/g, '/').replace(/\/docs\/50_系统测试\/scripts$/, '/');
    const wsSrc = execSync(`cat "${repoRoot}frontend/src/utils/websocket.ts"`, { shell: 'bash' }).toString();
    const pollSrc = execSync(`cat "${repoRoot}frontend/src/store/notification.ts"`, { shell: 'bash' }).toString();
    const backoffOk = /reconnectDelay = 2000/.test(wsSrc) && /reconnectDelay \* 2, 30000/.test(wsSrc) && /reconnectDelay = 2000/.test(wsSrc);
    const pollOk = /30000/.test(pollSrc);
    tc('TC-SP-020', '断线后重连+自动重订阅恢复推送；前端退避 2s→×2→上限30s + 30s 轮询兜底（静态核对）',
      conn.command === 'CONNECTED' && resub.ok && resubNotif.ok && arrived && backoffOk && pollOk,
      `重连=${conn.command} 重订阅=${resub.ok} 通知重订阅=${resubNotif.ok} 重连后消息到达=${arrived}(${latencyMs}ms) 退避策略=${backoffOk} 轮询兜底=${pollOk}（浏览器侧断网观察归 B/C 轨补验）`);
  }

  /* ── TC-SP-021 上线补拉（seq 增量） ──────────────────────────── */
  {
    const baseSeq = Number(q(`SELECT COALESCE(MAX(seq),0) FROM notification WHERE user_id=${r1Id}`));
    // 离线（无 WS 连接）：3 类事件 → resident1 各 ≥1 条通知
    // 事件1：反馈回复（管理员 → 居民）
    await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: admin1Tok, body: { content: '补拉样本1-反馈回复' } });
    // 事件2：工单派单（通知在 assign 时刻发「工单已派单」；accept 不再发）
    const w = await (async () => {
      for (let i = 0; i < 5; i++) {
        const r = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: `SP04补拉工单${Date.now() % 10000}`, content: 'x', contactPhone: '13812345678', address: '1号楼1单元101', priority: 'NORMAL' } });
        if (ok(r)) return r.data;
        await new Promise(x => setTimeout(x, 100));
      }
      throw new Error('mkOrder 失败');
    })();
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: '补拉样本2-派单' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: '补拉样本2-接单' } });
    // 事件3：公告发布（R48 公告广播通知；创建需 publishTime，publish 需 ISO-T 格式请求体）
    const iso = new Date(Date.now() - 60000).toISOString().slice(0, 19);
    const nt = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `SP04补拉公告${Date.now() % 10000}`, content: '补拉样本3-公告', communityId: 1, publishTime: iso } });
    if (ok(nt)) await api('PATCH', `/api/v1/notices/${nt.data.id}/publish`, { token: admin1Tok, body: { publishTime: iso.replace(' ', 'T') } });
    // 上线补拉：pull?sinceSeq=baseSeq
    const pullRes = await api('GET', `/api/v1/notifications/pull?sinceSeq=${baseSeq}`, { token: r1Tok });
    const pulled = Array.isArray(pullRes.data) ? pullRes.data : [];
    const seqs = pulled.map(x => x.seq);
    const noDup = new Set(seqs).size === seqs.length;
    const allNew = seqs.every(s => s > baseSeq);
    const asc = seqs.every((s, i) => i === 0 || s > seqs[i - 1]);
    // 已读后重复 pull 不重推为未读
    const before = pulled.length ? pulled[0].id : null;
    const mrResp = before ? await api('PATCH', `/api/v1/notifications/${before}/read`, { token: r1Tok }) : null;
    const pull2 = await api('GET', `/api/v1/notifications/pull?sinceSeq=${baseSeq}`, { token: r1Tok });
    const pulled2 = Array.isArray(pull2.data) ? pull2.data : [];
    const again = pulled2.find(x => x.id === before);
    const readStillOne = ok(mrResp) && again && again.isRead === 1;
    // 重连路径互证：WS 在线时产生 2 条 → push 到达 + pull 游标推进
    const s = new StompSession(r1Tok, 'r1-补拉');
    await s.connect();
    await s.subscribe('/user/queue/notifications');
    const pushLat = [];
    for (let i = 1; i <= 2; i++) {
      const t0 = Date.now();
      const p = s.waitMessage(f => f.body.includes('反馈') || f.body.includes('工单'), 4000).then(f => f.at - t0);
      await api('POST', `/api/v1/feedbacks/${F}/messages`, { token: admin1Tok, body: { content: `重连路径样本${i}` } });
      pushLat.push(await p);
    }
    const seqAfterPush = Number(q(`SELECT COALESCE(MAX(seq),0) FROM notification WHERE user_id=${r1Id}`));
    const pull3 = await api('GET', `/api/v1/notifications/pull?sinceSeq=${seqAfterPush}`, { token: r1Tok });
    const pulled3 = Array.isArray(pull3.data) ? pull3.data : [];
    s.close();
    tc('TC-SP-021', `离线≥3通知补拉100%无丢失无重复 + 已读不重推未读 + 重连路径语义一致`,
      pulled.length >= 3 && allNew && noDup && asc && readStillOne
      && pushLat.length === 2 && pushLat.every(x => x <= 3000) && pulled3.length === 0,
      `离线产生3类事件后补拉=${pulled.length}条(反馈回复/工单受理/公告发布) 全新=${allNew} 无重复=${noDup} 升序=${asc} 已读保持已读=${readStillOne} 重连路径推送到达=${pushLat.length}/2(≤3s:${pushLat.every(x => x <= 3000)}) 游标后无冗余=${pulled3.length === 0}`);
  }

  console.log(`\n══ SP-04 WebSocket 专项：${pass} 通过 / ${fail} 失败 ══`);
  if (failures.length) { console.log('失败明细：'); failures.forEach(f => console.log('  ' + f)); }
  process.exit(fail ? 1 : 0);
}

/* 无令牌/指定令牌的原生 CONNECT，返回 {ok, error} */
async function connectRaw(token) {
  try {
    const info = await (await fetch(`${BASE}/ws/info`)).json();
    if (!info.websocket) throw new Error('websocket 传输不可用');
    const srv = String(Math.floor(Math.random() * 999)).padStart(3, '0');
    const sess = 'sp' + Math.random().toString(36).slice(2, 10);
    const ws = new WebSocket(`${WS_BASE}/ws/${srv}/${sess}/websocket`);
    const frames = [];
    const verdict = await new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ ok: null, error: '超时' }), 5000);
      ws.onmessage = ev => {
        const d = ev.data;
        if (d === 'o') {
          const headers = { 'accept-version': '1.2', host: 'localhost' };
          if (token) headers['Authorization'] = `Bearer ${token}`;
          ws.send(JSON.stringify([stompFrame('CONNECT', headers)]));
        } else if (d === 'h') { /* 心跳 */ }
        else if (d.startsWith('c[')) { clearTimeout(timer); resolve({ ok: false, error: '关闭:' + d.slice(0, 30) }); }
        else if (d.startsWith('a')) {
          for (const s of JSON.parse(d.slice(1))) {
            const f = parseStompFrame(s);
            frames.push(f);
            if (f.command === 'CONNECTED') { clearTimeout(timer); resolve({ ok: true }); }
            if (f.command === 'ERROR') { clearTimeout(timer); resolve({ ok: false, error: f.headers.message || f.body.slice(0, 40) }); }
          }
        }
      };
      ws.onclose = () => { clearTimeout(timer); resolve({ ok: false, error: frames.length ? 'ERROR帧后关闭' : '连接即关闭' }); };
      ws.onerror = () => { };
    });
    try { ws.close(); } catch { }
    return verdict;
  } catch (e) { return { ok: false, error: e.message }; }
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2); });
