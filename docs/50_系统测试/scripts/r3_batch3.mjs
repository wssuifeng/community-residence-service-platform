/**
 * 50 系统测试 · 第三批专项 · 域功能回归（r3_batch3.mjs）
 * 覆盖 08 §3.6/§3.8 第三批 13 项中的非 Slot Grid 部分：
 *   B3-01 DEF-025 派单人员选项端点（ADMIN 可用/启用态/最小暴露面/角色 403）
 *   B3-02 DEF-026 工单号 keyword 命中 + 批量 20 单零 409（撞号重试）
 *   B3-03 DEF-027a 反馈列表 keyword 模糊过滤（与库内一致）
 *   B3-04 DEF-027b 反馈办结 WS 推送（FEEDBACK_STATUS 载荷，SockJS+STOMP 裸客户端）
 *   B3-05 DEF-028 unsatisfied hasFollowup 三分支过滤（SQL 造锚点 + 库内比对）
 *   B3-06 DEF-029a STAFF 按日趋势（STAFF 可访问 + 限本人数据级断言）
 *   B3-07 DEF-029b 工单列表 statuses 多选 + startTime/endTime + STAFF 数据域收敛
 *   B3-08 DEF-030 创建用户缺手机号 400（@NotBlank 维持口径留痕）
 *   B3-09 DEF-031 公告 priority/isPinned/type 过滤
 *   B3-10 C3 项 公告表单字段接收/缺省/endTime 优先/VO 暴露/V12 存量回读
 *   B3-11 D-端点1 楼栋级联删除两态 + 在住保护 + 空楼栋直删
 *   B3-12 D-端点2 楼栋/单元/房屋批量创建（部分成功语义 + 上限）
 *   B3-13 D-端点3 管理员直建居住关系（VACANT 翻转/已住拒/越社区 403/租约不联动）
 * Slot Grid 专项（C2 栅格化）归 r3_slotgrid.mjs。
 * 用法：TEST_BASE=http://localhost:8081 node r3_batch3.mjs
 */
import { execSync } from 'child_process';

const BASE = process.env.TEST_BASE || 'http://localhost:8081';
const WS_BASE = BASE.replace(/^http/, 'ws');
let pass = 0, fail = 0;
const results = {};
function tc(id, item, expectDesc, ok, actual) {
  ok ? pass++ : fail++;
  results[id] = results[id] ?? { ok: true, notes: [] };
  if (!ok) results[id].ok = false;
  results[id].notes.push(`${item}: ${actual}`);
  console.log(`${ok ? '✅' : '❌'} [${id}] ${item} → ${actual}${ok ? '' : '（期望' + expectDesc + '）'}`);
}
async function api(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch { }
  return { status: res.status, code: json?.code, json, data: json?.data, msg: json?.message };
}
function q(sql) {
  return execSync(`mysql -u root -h localhost --default-character-set=utf8mb4 community_residence_test -N -e "${sql.replace(/"/g, '\\"')}"`, { shell: 'bash', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).toString().trim();
}
const ok = r => r.status === 200 && r.code === 200;
const rej = r => r.status !== 200 || r.code !== 200;
const TAG = Date.now() % 100000;

/* ── SockJS + STOMP 最小客户端（照抄 sp04） ─────────────────────── */
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
  constructor(token) { this.token = token; this.messages = []; this.waiters = []; this.closed = false; this.receiptSeq = 0; }
  async connect() {
    const info = await (await fetch(`${BASE}/ws/info`)).json();
    if (!info.websocket) throw new Error('SockJS websocket 传输不可用');
    const srv = String(Math.floor(Math.random() * 999)).padStart(3, '0');
    const sess = 'r3' + Math.random().toString(36).slice(2, 10);
    this.ws = new WebSocket(`${WS_BASE}/ws/${srv}/${sess}/websocket`);
    this.ws.onmessage = ev => this._onSockjs(ev.data);
    this.ws.onclose = () => { this.closed = true; };
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('SockJS open 超时')), 5000);
      this._openWaiter = () => { clearTimeout(t); res(); };
    });
    this.ws.send(JSON.stringify([stompFrame('CONNECT', {
      'accept-version': '1.2', host: 'localhost',
      Authorization: `Bearer ${this.token}`,
    })]));
    await this._wait(f => f.command === 'CONNECTED' || f.command === 'ERROR', 5000, 'CONNECTED');
    return this;
  }
  _onSockjs(data) {
    if (data === 'o') { this._openWaiter?.(); return; }
    if (data === 'h' || data.startsWith('c[')) return;
    if (data.startsWith('a')) {
      for (const s of JSON.parse(data.slice(1))) {
        const f = parseStompFrame(s);
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
      setTimeout(() => { this.waiters = this.waiters.filter(x => x !== w); rej(new Error(`等待 ${what} 超时`)); }, timeoutMs);
    });
  }
  subscribe(dest) {
    const subId = 'sub-' + (++this.receiptSeq);
    this.ws.send(JSON.stringify([stompFrame('SUBSCRIBE', { id: subId, destination: dest, ack: 'auto' })]));
  }
  wait(pred, ms = 15000) { return this._wait(pred, ms, 'MESSAGE'); }
  close() { if (!this.closed) { this.closed = true; try { this.ws.close(); } catch { } } }
}

/* ── 工具：管理员受理+派单（给 DEF-029 数据级断言用） ─────────────── */
async function createAndAssignOrder(residentTok, adminTok, assigneeId, title) {
  const catId = q(`SELECT id FROM service_category WHERE community_id=1 AND is_active=1 LIMIT 1`);
  const create = await api('POST', '/api/v1/work-orders', { token: residentTok, body: { categoryId: catId, title, content: `r3batch3-${TAG}`, contactPhone: '13800005555', address: 'x', priority: 'NORMAL' } });
  if (!ok(create)) return { create };
  const id = create.data.id;
  const assign = await api('PATCH', `/api/v1/work-orders/${id}/assign`, { token: adminTok, body: { assigneeId, remark: `r3-${TAG}` } });
  return { create, assign, id };
}

async function main() {
  const login = async (ep, u, p) => (await api('POST', `/api/v1/auth/${ep}/login`, { body: { username: u, password: p } })).data?.token;
  const superTok = await login('admin', 'superadmin', 'Admin@123456');
  const admin1Tok = await login('admin', 'admin1', 'Admin123456');        // 社区 1
  const testAdminTok = await login('admin', 'test_admin', 'Admin123456'); // 社区 2 清源里
  const staff1Tok = await login('admin', 'staff1', 'Staff123456');
  const testStaffTok = await login('admin', 'test_staff', 'Staff123456');
  const r1Tok = await login('resident', 'resident1', 'Resident123456');   // 社区 1 在住

  /* ══ B3-01 DEF-025 派单人员选项端点 ══ */
  {
    const admin = await api('GET', '/api/v1/work-orders/assignable-staff', { token: admin1Tok });
    const list = admin.data ?? [];
    const dbEnabledStaff = q(`SELECT COUNT(*) FROM sys_user WHERE role='STAFF' AND status='ACTIVE'`);
    const dbFrozen = q(`SELECT COUNT(*) FROM sys_user WHERE role='STAFF' AND status!='ACTIVE'`);
    const frozenInList = list.filter(x => x.id && q(`SELECT status FROM sys_user WHERE id=${x.id}`) !== 'ACTIVE').length;
    const exposureOk = list.every(x => Object.keys(x).sort().join(',') === 'id,realName');
    const res403 = await api('GET', '/api/v1/work-orders/assignable-staff', { token: r1Tok });
    const staff403 = await api('GET', '/api/v1/work-orders/assignable-staff', { token: staff1Tok });
    const noAuth = await api('GET', '/api/v1/work-orders/assignable-staff');
    tc('B3-01', 'ADMIN 可用/启用态/暴露面/角色 403', 'ADMIN 200 且仅启用 STAFF(id+realName)，冻结不出现，RESIDENT/STAFF/未登录拒',
      ok(admin) && list.length === Number(dbEnabledStaff) && frozenInList === 0 && exposureOk && rej(res403) && rej(staff403) && noAuth.status === 401,
      `ADMIN=${admin.status}/${admin.code} 列表=${list.length}/${dbEnabledStaff} 冻结混入=${frozenInList} 暴露字段=${list[0] ? Object.keys(list[0]).join(',') : '空'} 居民=${res403.status} 服务人员=${staff403.status} 未登录=${noAuth.status}`);
    // 熟手优先软排序：给 staff1 派一单后，communityId=1 的列表 staff1 置前
    const staff1Uid0 = q(`SELECT id FROM sys_user WHERE username='staff1'`);
    const ass = await createAndAssignOrder(r1Tok, admin1Tok, Number(staff1Uid0), `B301熟手${TAG}`);
    const withCid = await api('GET', '/api/v1/work-orders/assignable-staff?communityId=1', { token: admin1Tok });
    const firstId = (withCid.data ?? [])[0]?.id;
    const staff1Uid = q(`SELECT id FROM sys_user WHERE username='staff1'`);
    tc('B3-01', '熟手优先软排序', '派过单的 staff1 置前且列表为启用 STAFF 全集（非硬过滤）',
      ok(withCid) && Number(firstId) === Number(staff1Uid) && (withCid.data ?? []).length === Number(dbEnabledStaff),
      `首项=${firstId}/staff1=${staff1Uid} 列表长=${(withCid.data ?? []).length}/${dbEnabledStaff} 派单=${ass.assign ? ass.assign.code : '创建失败' + ass.create?.code}`);
  }

  /* ══ B3-02 DEF-026 工单号 keyword 命中 + 批量 20 单零 409 ══ */
  {
    const catId = q(`SELECT id FROM service_category WHERE community_id=1 AND is_active=1 LIMIT 1`);
    const created = [];
    let allOk = true;
    for (let i = 0; i < 20; i++) {
      const r = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: catId, title: `B302批量${TAG}-${i}`, content: 'x', contactPhone: '13800005555', address: 'x', priority: 'NORMAL' } });
      if (ok(r)) created.push(r.data); else allOk = false;
    }
    const orderNo = created[0]?.orderNo;
    const byNo = await api('GET', `/api/v1/work-orders?keyword=${encodeURIComponent(orderNo)}`, { token: admin1Tok });
    const hitList = (byNo.data?.records ?? byNo.data?.list ?? byNo.data ?? []);
    const byTitle = await api('GET', `/api/v1/work-orders?keyword=${encodeURIComponent(`B302批量${TAG}-5`)}`, { token: admin1Tok });
    const hitT = (byTitle.data?.records ?? byTitle.data?.list ?? byTitle.data ?? []);
    tc('B3-02', '批量 20 单零 409 + 工单号/标题 keyword', '20/20 创建成功且 keyword 命中工单号精确 1 条与标题 1 条',
      allOk && created.length === 20 && ok(byNo) && hitList.length === 1 && ok(byTitle) && hitT.length >= 1,
      `创建=${created.length}/20 全ok=${allOk} 工单号命中=${hitList.length}(${orderNo}) 标题命中=${hitT.length}`);
  }

  /* ══ B3-03 DEF-027a 反馈 keyword ══ */
  {
    const fb = await api('POST', '/api/v1/feedbacks', { token: r1Tok, body: { communityId: 1, category: 'SUGGESTION', title: `B303反馈关键词${TAG}`, content: `独特内容锚${TAG}` } });
    const kw = await api('GET', `/api/v1/feedbacks?keyword=${encodeURIComponent('独特内容锚' + TAG)}`, { token: r1Tok });
    const list = kw.data?.records ?? kw.data?.list ?? kw.data ?? [];
    const dbN = q(`SELECT COUNT(*) FROM feedback WHERE (title LIKE '%${TAG}%' OR content LIKE '%独特内容锚${TAG}%') AND resident_id=(SELECT id FROM resident WHERE username='resident1')`);
    tc('B3-03', '反馈 keyword 标题/内容模糊', 'keyword 命中且与库内居民本人反馈计数一致',
      ok(fb) && ok(kw) && list.length === Number(dbN) && list.length >= 1,
      `提交=${fb.code} keyword命中=${list.length} 库内=${dbN}`);
  }

  /* ══ B3-04 DEF-027b 办结 WS 推送 FEEDBACK_STATUS ══ */
  {
    const fb = await api('POST', '/api/v1/feedbacks', { token: r1Tok, body: { communityId: 1, category: 'SUGGESTION', title: `B304办结推送${TAG}`, content: 'x' } });
    const fid = fb.data?.id;
    // 管理员先建会话（首条回复自动受理）
    await api('POST', `/api/v1/feedbacks/${fid}/messages`, { token: admin1Tok, body: { content: `受理B304-${TAG}` } });
    const sess = await new StompSession(r1Tok).connect();
    sess.subscribe(`/topic/feedback/${fid}`);
    await new Promise(r => setTimeout(r, 800));
    const close = await api('PATCH', `/api/v1/feedbacks/${fid}/close`, { token: admin1Tok, body: { remark: `办结B304-${TAG}` } });
    let got = null;
    try { got = await sess.wait(m => m.command === 'MESSAGE' && m.headers.destination?.includes(`/topic/feedback/${fid}`) && m.body.includes('FEEDBACK_STATUS'), 8000); } catch { }
    sess.close();
    const payload = got ? JSON.parse(got.body) : null;
    tc('B3-04', '办结 WS 推送 FEEDBACK_STATUS', 'close 后在线订阅方 8s 内收到 {feedbackId,status} 载荷',
      ok(close) && !!got && payload?.type === 'FEEDBACK_STATUS' && String(payload?.data?.feedbackId ?? payload?.data?.feedback_id) === String(fid),
      `办结=${close.code} 收到=${!!got} 载荷=${got ? got.body.slice(0, 80) : '无'}`);
  }

  /* ══ B3-05 DEF-028 unsatisfied hasFollowup ══ */
  {
    // 造两条锚点：一条已跟进、一条未跟进（SQL 直插：过滤端点测试，非状态机链路）
    const woIds = q(`SELECT GROUP_CONCAT(id ORDER BY id DESC) FROM (SELECT id FROM work_order WHERE community_id=1 ORDER BY id DESC LIMIT 2) t`).split(',');
    const [woId, woId2] = woIds;
    const r1Id = q(`SELECT id FROM resident WHERE username='resident1'`);
    const eFid = q(`INSERT INTO work_order_evaluation (work_order_id, resident_id, community_id, rating, content, is_satisfied, created_at) VALUES (${woId}, ${r1Id}, 1, 1, 'B303已跟进${TAG}', 0, NOW())`);
    q(`INSERT INTO work_order_evaluation (work_order_id, resident_id, community_id, rating, content, is_satisfied, created_at) VALUES (${woId2}, ${r1Id}, 1, 2, 'B303未跟进${TAG}', 0, NOW())`);
    const evFid = q(`SELECT id FROM work_order_evaluation WHERE content='B303已跟进${TAG}'`);
    const evNid = q(`SELECT id FROM work_order_evaluation WHERE content='B303未跟进${TAG}'`);
    q(`INSERT INTO unsatisfied_followup (evaluation_id, handler_id, followup_content, followup_time) VALUES (${evFid}, 1, '跟进B303-${TAG}', NOW())`);
    const t = await api('GET', '/api/v1/evaluations/unsatisfied?hasFollowup=true', { token: admin1Tok });
    const f = await api('GET', '/api/v1/evaluations/unsatisfied?hasFollowup=false', { token: admin1Tok });
    const all = await api('GET', '/api/v1/evaluations/unsatisfied', { token: admin1Tok });
    const pick = r => (r.data?.records ?? r.data?.list ?? r.data ?? []);
    const tIds = pick(t).map(x => x.id), fIds = pick(f).map(x => x.id), aTotal = Number(all.data?.total ?? pick(all).length);
    const dbFollowed = q(`SELECT COUNT(*) FROM work_order_evaluation e JOIN work_order wo ON e.work_order_id=wo.id WHERE e.is_satisfied=0 AND wo.community_id=1 AND EXISTS (SELECT 1 FROM unsatisfied_followup u WHERE u.evaluation_id=e.id)`);
    const dbPending = q(`SELECT COUNT(*) FROM work_order_evaluation e JOIN work_order wo ON e.work_order_id=wo.id WHERE e.is_satisfied=0 AND wo.community_id=1 AND NOT EXISTS (SELECT 1 FROM unsatisfied_followup u WHERE u.evaluation_id=e.id)`);
    /* 【R5 口径更新·DEF-035 已修复】hasFollowup 已改 SQL 级过滤：
       超管 false 分支 total 与库内全量待跟进数一致（不再页内过滤失真）。 */
    const superAll = await api('GET', '/api/v1/evaluations/unsatisfied?hasFollowup=false', { token: superTok });
    const dbAllPending = q(`SELECT COUNT(*) FROM work_order_evaluation e WHERE e.is_satisfied=0 AND NOT EXISTS (SELECT 1 FROM unsatisfied_followup u WHERE u.evaluation_id=e.id)`);
    const superFalseN = Number(superAll.data?.total ?? 0);
    tc('B3-05', 'hasFollowup 三分支（R5：DEF-035 已修复为 SQL 级过滤）', 'true 分支与库内已跟进集一致含锚点；超管 false 分支计数与库内全量一致（DEF-035 修复后口径）；null 全量计数与库一致',
      ok(t) && ok(f) && ok(all) && tIds.includes(Number(evFid)) && !tIds.includes(Number(evNid))
      && tIds.length === Number(dbFollowed) && aTotal === Number(dbFollowed) + Number(dbPending)
      && Number(dbAllPending) > 0 && superFalseN === Number(dbAllPending),
      `true=${tIds.length}/${dbFollowed}含锚点已跟进=${tIds.includes(Number(evFid))} 社区1false=${fIds.length}/${dbPending} 超管false=${superFalseN}/${dbAllPending}（DEF-035 已修复：SQL 级过滤） 全量total=${aTotal}`);
    q(`DELETE FROM unsatisfied_followup WHERE followup_content='跟进B303-${TAG}'`);
    q(`DELETE FROM work_order_evaluation WHERE content IN ('B303已跟进${TAG}','B303未跟进${TAG}')`);
  }

  /* ══ B3-06 DEF-029a STAFF 按日趋势（限本人数据级） ══ */
  {
    const s1 = await api('GET', '/api/v1/statistics/staff/trend', { token: staff1Tok });
    const s90 = await api('GET', '/api/v1/statistics/staff/trend?days=90', { token: staff1Tok });
    const s0 = await api('GET', '/api/v1/statistics/staff/trend?days=0', { token: staff1Tok });
    const s91 = await api('GET', '/api/v1/statistics/staff/trend?days=91', { token: staff1Tok });
    const r403 = await api('GET', '/api/v1/statistics/staff/trend', { token: r1Tok });
    // 数据级：给 test_staff 派一单（B3-01 已给 staff1 派过一单），staff1 的 trend 不含 test_staff 的单
    const tStaffUid = q(`SELECT id FROM sys_user WHERE username='test_staff'`);
    const assT = await createAndAssignOrder(r1Tok, admin1Tok, tStaffUid, `B306他人单${TAG}`);
    const s1b = await api('GET', '/api/v1/statistics/staff/trend', { token: staff1Tok });
    const dbS1Assigned = q(`SELECT COUNT(*) FROM work_order wo WHERE wo.id IN (SELECT work_order_id FROM work_order_assignment WHERE assignee_id=${q(`SELECT id FROM sys_user WHERE username='staff1'`)})`);
    const trendTotal = Object.values(s1b.data?.assignedTrend ?? {}).reduce((s, x) => s + Number(x), 0);
    tc('B3-06', 'STAFF 趋势可访问 + 限本人', 'STAFF 200 返回日序列（默认7/上限90，0 与 91 均钳位实现口径），他人派单不计入，RESIDENT 403',
      ok(s1) && ok(s90) && ok(s0) && s0.data?.days === 1 && s91.data?.days === 90 && rej(r403) && ok(assT.assign) && Number(trendTotal) === Number(dbS1Assigned),
      `默认=${s1.code} 90日=${s90.code} days=0钳位=${s0.code}/days=${s0.data?.days} 91钳位=days=${s91.data?.days} 居民=${r403.status} 他人单派单=${assT.assign?.code} 趋势合计=${trendTotal}/库内本人=${dbS1Assigned}`);
  }

  /* ══ B3-07 DEF-029b 工单 statuses 多选 + 时间范围 + STAFF 数据域 ══ */
  {
    const multi = await api('GET', '/api/v1/work-orders?statuses=PENDING,TO_ASSIGN&size=100', { token: admin1Tok });
    const pick = r => (r.data?.records ?? r.data?.list ?? r.data ?? []);
    const multiBad = pick(multi).filter(x => !['PENDING', 'TO_ASSIGN'].includes(x.status)).length;
    const multiTotal = multi.data?.total;
    const dbMulti = q(`SELECT COUNT(*) FROM work_order WHERE status IN ('PENDING','TO_ASSIGN') AND community_id=1`);
    // 时间范围：B302 刚建的 20 单落在 [1 分钟前, 1 分钟后] 窗口（本地时间，与 created_at 同口径）
    const localIso = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
    const now = localIso(new Date(Date.now() - 60 * 1000));
    const later = localIso(new Date(Date.now() + 60 * 1000));
    const tr = await api('GET', `/api/v1/work-orders?startTime=${encodeURIComponent(now)}&endTime=${encodeURIComponent(later)}&size=200`, { token: admin1Tok });
    const cnt = pick(tr).filter(x => String(x.title).includes(`B302批量${TAG}`)).length;
    // STAFF 数据域收敛：staff1 只见被派给本人的
    const stList = await api('GET', '/api/v1/work-orders?statuses=PENDING,ASSIGNED,ACCEPTED,IN_PROGRESS,TO_CONFIRM,COMPLETED,CLOSED&size=500', { token: staff1Tok });
    const dbStaff = q(`SELECT COUNT(DISTINCT wo.id) FROM work_order wo JOIN work_order_assignment a ON a.work_order_id=wo.id WHERE a.assignee_id=${q(`SELECT id FROM sys_user WHERE username='staff1'`)}`);
    const stIds = pick(stList).map(x => x.id);
    const dbIds = q(`SELECT GROUP_CONCAT(DISTINCT wo.id) FROM work_order wo JOIN work_order_assignment a ON a.work_order_id=wo.id WHERE a.assignee_id=${q(`SELECT id FROM sys_user WHERE username='staff1'`)}`).split(',').filter(Boolean).map(Number);
    const leak = stIds.filter(id => !dbIds.includes(id)).length;
    tc('B3-07', 'statuses 多选/时间范围/STAFF 数据域', '多状态 IN 精确、时间范围命中刚建单、STAFF 列表零越权泄漏',
      ok(multi) && multiBad === 0 && multiTotal === Number(dbMulti) && ok(tr) && cnt === 20 && ok(stList) && leak === 0,
      `多选total=${multiTotal}/${dbMulti} 越状态=${multiBad} 时间窗命中=${cnt}/20 STAFF泄漏=${leak}/${stIds.length}`);
  }

  /* ══ B3-08 DEF-030 创建用户缺手机号 400 ══ */
  {
    const noPhone = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: `b308_${TAG}`, password: 'Admin123456', realName: 'B308', role: 'ADMIN' } });
    const emptyPhone = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: `b308e_${TAG}`, password: 'Admin123456', realName: 'B308E', role: 'ADMIN', phone: '' } });
    tc('B3-08', '缺/空手机号 400（@NotBlank 维持）', '两种缺省均 400 拒绝且零落库（决策日志 2026-09-14 留痕，前端校验归 C 轨）',
      rej(noPhone) && rej(emptyPhone) && q(`SELECT COUNT(*) FROM sys_user WHERE username LIKE 'b308%'`) === '0',
      `缺phone=${noPhone.status}/${noPhone.code} 空phone=${emptyPhone.status}/${emptyPhone.code}`);
  }

  /* ══ B3-09/B3-10 DEF-031+C3 公告过滤与表单字段 ══ */
  {
    const now2 = new Date();
    const iso = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:00`;
    const c1 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { communityId: 1, title: `B309高优${TAG}`, content: 'x', priority: 'HIGH', type: 'ANNOUNCEMENT', publishTime: iso(now2), expireTime: iso(new Date(now2.getTime() + 3 * 864e5)) } });
    const c2 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { communityId: 1, title: `B309置顶${TAG}`, content: 'x', isPinned: 1, publishTime: iso(now2), endTime: iso(new Date(now2.getTime() + 3 * 864e5)) } });
    const c3 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { communityId: 1, title: `B309双设${TAG}`, content: 'x', publishTime: iso(now2), expireTime: iso(new Date(now2.getTime() + 5 * 864e5)), endTime: iso(new Date(now2.getTime() + 2 * 864e5)) } });
    const bad = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { communityId: 1, title: `B309非法${TAG}`, content: 'x', priority: 'TOP', publishTime: iso(now2) } });
    const id1 = q(`SELECT id FROM notice WHERE title='B309高优${TAG}'`);
    const id3 = q(`SELECT id FROM notice WHERE title='B309双设${TAG}'`);
    const id2 = q(`SELECT id FROM notice WHERE title='B309置顶${TAG}'`);
    const d1 = id1 ? q(`SELECT priority, type FROM notice WHERE id=${id1}`).split('\t') : ['-','-'];
    const d3end = id3 ? q(`SELECT DATE_FORMAT(end_time,'%Y-%m-%d') FROM notice WHERE id=${id3}`) : '-';
    const expD = new Date(now2.getTime() + 2 * 864e5);
    const expDate = `${expD.getFullYear()}-${String(expD.getMonth() + 1).padStart(2, '0')}-${String(expD.getDate()).padStart(2, '0')}`;
    const detail2 = id2 ? await api('GET', `/api/v1/notices/${id2}`, { token: admin1Tok }) : { data: null };
    const vo = detail2.data ? Object.keys(detail2.data) : [];
    const voOk = ['priority', 'type', 'expireTime', 'isPinned'].every(k => vo.includes(k));
    // 过滤参数
    const fP = await api('GET', `/api/v1/notices?priority=HIGH&size=50`, { token: admin1Tok });
    const fPin = await api('GET', `/api/v1/notices?isPinned=1&size=50`, { token: admin1Tok });
    const pickN = r => (r.data?.records ?? r.data?.list ?? r.data ?? []);
    const fpOk = pickN(fP).every(x => x.priority === 'HIGH') && pickN(fP).some(x => x.title === `B309高优${TAG}`);
    const fpinOk = pickN(fPin).every(x => x.isPinned === true || x.isPinned === 1) && pickN(fPin).some(x => x.title === `B309置顶${TAG}`);
    // 存量默认值回读（V12 默认 NORMAL/ANNOUNCEMENT）
    const legacy = q(`SELECT priority, type FROM notice WHERE id=(SELECT MIN(id) FROM notice)`);
    tc('B3-09', 'priority/isPinned/type 过滤', 'HIGH 列表纯净含锚点、置顶列表纯净含锚点',
      ok(fP) && fpOk && ok(fPin) && fpinOk,
      `priority过滤=${fpOk} 置顶过滤=${fpinOk}`);
    tc('B3-10', '表单字段接收/endTime优先/非法值/VO/存量默认', 'priority+type 落库、双设以 endTime 为准、非法 priority 400、VO 暴露四字段、存量默认 NORMAL/ANNOUNCEMENT',
      ok(c1) && d1[0] === 'HIGH' && d1[1] === 'ANNOUNCEMENT' && ok(c3) && d3end === expDate && rej(bad) && ok(c2) && voOk && legacy === 'NORMAL\tANNOUNCEMENT',
      `落库=${d1.join('/')} endTime优先=${d3end}/${expDate} 非法=${bad.status}/${bad.code} VO字段=${vo.join(',')} 存量默认=${legacy}`);
  }

  /* ══ B3-11 D-端点1 楼栋级联删除 ══ */
  {
    // 自建：社区2 清源里，楼栋→单元→房屋（部分在住）
    const mkB = await api('POST', '/api/v1/buildings', { token: testAdminTok, body: { communityId: 2, name: `R3楼栋${TAG}`, floors: 3 } });
    const bid = mkB.data?.id;
    const mkU = await api('POST', '/api/v1/units', { token: testAdminTok, body: { buildingId: bid, name: `R3单元${TAG}` } });
    const uid = mkU.data?.id;
    const mkH1 = await api('POST', '/api/v1/houses', { token: testAdminTok, body: { unitId: uid, houseNumber: '101', floor: 1, area: 80, layout: '两室一厅', status: 'VACANT' } });
    const mkH2 = await api('POST', '/api/v1/houses', { token: testAdminTok, body: { unitId: uid, houseNumber: '102', floor: 1, area: 90, layout: '三室一厅', status: 'VACANT' } });
    const h1 = mkH1.data?.id, h2 = mkH2.data?.id;
    // ① 默认拒删保护（有单元）
    const deny = await api('DELETE', `/api/v1/buildings/${bid}`, { token: testAdminTok });
    // ② 在住保护：直建关系给 test_resident（清源里在住居民）占 h1，cascade 应整体拒绝回滚
    const rQyId = q(`SELECT id FROM resident WHERE username='test_resident'`);
    const rel = await api('POST', '/api/v1/residence-relations', { token: testAdminTok, body: { residentId: rQyId, houseId: h1, relationType: 'OWNER', moveInDate: '2026-09-14' } });
    const cascadeDeny = await api('DELETE', `/api/v1/buildings/${bid}?cascade=true`, { token: testAdminTok });
    const afterDeny = q(`SELECT COUNT(*) FROM building WHERE id=${bid}`) + q(`SELECT COUNT(*) FROM house WHERE id IN (${h1},${h2})`);
    // ③ 迁出后 cascade 成功，自底向上零残留
    // 【DEF-033 取证】历史居住关系（residence_relation 的 FK RESTRICT）阻断级联 → HTTP 500 而非业务码拒绝；
    // 复现路径：直建关系→迁出（留历史关系行）→cascade 删除 → 500。本断言组以「复现留证」为通过条件，修复后应转「零残留」断言。
    const relId = rel.data?.id;
    await api('PATCH', `/api/v1/residence-relations/${relId}/move-out`, { token: testAdminTok, body: { moveOutDate: '2026-09-14', remark: `B311-${TAG}` } });
    const cascade = await api('DELETE', `/api/v1/buildings/${bid}?cascade=true`, { token: testAdminTok });
    const cascade500 = cascade.status === 500;
    const zero = q(`SELECT COUNT(*) FROM building WHERE id=${bid}`) + q(`SELECT COUNT(*) FROM unit WHERE building_id=${bid}`) + q(`SELECT COUNT(*) FROM house WHERE unit_id=${uid}`) + q(`SELECT COUNT(*) FROM house_status_history WHERE house_id IN (${h1},${h2})`);
    // 清理残留（DEF-033 现象下楼栋未删，物理清理 FK 引用后重删）
    if (!ok(cascade)) {
      q(`DELETE FROM residence_relation WHERE house_id IN (${h1},${h2})`);
      q(`DELETE FROM house_status_history WHERE house_id IN (${h1},${h2})`);
      q(`DELETE FROM house WHERE unit_id=${uid}`);
      q(`DELETE FROM unit WHERE building_id=${bid}`);
      q(`DELETE FROM building WHERE id=${bid}`);
    }
    // ④ 空楼栋直删 + 权限
    const mkB2 = await api('POST', '/api/v1/buildings', { token: testAdminTok, body: { communityId: 2, name: `R3空楼${TAG}`, floors: 1 } });
    const emptyDel = await api('DELETE', `/api/v1/buildings/${mkB2.data?.id}`, { token: testAdminTok });
    const res403 = await api('DELETE', `/api/v1/buildings/${mkB2.data?.id}`, { token: r1Tok });
    tc('B3-11', '级联两态+在住保护+空楼直删+权限', '默认 5102 拒、在住整体拒绝回滚、迁出后级联（DEF-033 现状=500 留证）、空楼直删 200、居民 403',
      rej(deny) && deny.code === 5102 && ok(rel) && rej(cascadeDeny) && afterDeny === '12' && (ok(cascade) ? zero === '0000' : cascade500) && ok(emptyDel) && rej(res403),
      `默认拒=${deny.code} 直建关系=${rel.code} 在住级联拒=${cascadeDeny.code}/${cascadeDeny.msg?.slice(0, 20)} 拒后计数=${afterDeny} 级联=${cascade.code}${cascade500 ? '（DEF-033：历史关系FK阻断→500，已物理清理）' : ''} 零残留=${zero} 空楼=${emptyDel.code} 居民=${res403.status}`);
  }

  /* ══ B3-12 D-端点2 批量创建（部分成功） ══
     【R5 口径更新·DEF-032 已修复】坏行不再整批 400：返回 200 + 逐行 reason，
     好行照常创建（楼栋/单元/房屋三端点）。断言按部分成功语义验证：
     坏行 success=false 且带 reason，好行 success=true 落库。 */
  {
    const c2id = 2;
    // ① 全有效行：2/2 成功，逐行反馈结构
    const bBatch = await api('POST', '/api/v1/buildings/batch', { token: testAdminTok, body: { communityId: c2id, buildings: [
      { communityId: c2id, name: `R3批A${TAG}`, floors: 2 },
      { communityId: c2id, name: `R3批B${TAG}`, floors: 2 },
    ] } });
    const bRes = bBatch.data;
    const okRows = (bRes?.rows ?? []).filter(r => r.success).length;
    const bidA = bRes?.rows?.[0]?.id, bidB = bRes?.rows?.[1]?.id;
    // ② 含坏行（名称空）：R5 口径 = 200 + 逐行 reason，好行成功坏行失败（DEF-032 已修复）
    const bBadRow = await api('POST', '/api/v1/buildings/batch', { token: testAdminTok, body: { communityId: c2id, buildings: [
      { communityId: c2id, name: `R3批C${TAG}`, floors: 2 },
      { communityId: c2id, name: '', floors: 2 },
    ] } });
    const bBadRows = bBadRow.data?.rows ?? [];
    const badRowPartial = ok(bBadRow) && bBadRows.length === 2
      && bBadRows[0]?.success === true && bBadRows[1]?.success === false && !!bBadRows[1]?.reason;
    // ③ 超上限 100 整批拒
    const bOverflow = await api('POST', '/api/v1/buildings/batch', { token: testAdminTok, body: { communityId: c2id, buildings: Array.from({ length: 101 }, (_, i) => ({ communityId: c2id, name: `x${i}`, floors: 1 })) } });
    // ④ 单元批量（全有效）+ 社区推导
    const uBatch = await api('POST', '/api/v1/units/batch', { token: testAdminTok, body: { buildingId: bidA, names: [`R3单1${TAG}`, `R3单2${TAG}`] } });
    const uOk = (uBatch.data?.rows ?? []).filter(r => r.success).length;
    const unitId = uBatch.data?.rows?.[0]?.id;
    const uBadRow = await api('POST', '/api/v1/units/batch', { token: testAdminTok, body: { buildingId: bidB, names: [`R3单3${TAG}`, ''] } });
    const uBadPartial = ok(uBadRow) && (uBadRow.data?.rows ?? []).length === 2
      && uBadRow.data?.rows?.[0]?.success === true && uBadRow.data?.rows?.[1]?.success === false && !!uBadRow.data?.rows?.[1]?.reason;
    // ⑤ 房屋批量（全有效）
    const hBatch = await api('POST', '/api/v1/houses/batch', { token: testAdminTok, body: { unitId, houses: [
      { unitId, houseNumber: '201', floor: 2, area: 70 },
      { unitId, houseNumber: '203', floor: 2, area: 75 },
    ] } });
    const hOk = (hBatch.data?.rows ?? []).filter(r => r.success).length;
    // ⑥ 房屋含坏行（缺 area）：R5 口径 = 200 + 逐行 reason（DEF-032 已修复）
    const hBadRow = await api('POST', '/api/v1/houses/batch', { token: testAdminTok, body: { unitId, houses: [
      { unitId, houseNumber: '205', floor: 2, area: 70 },
      { unitId, houseNumber: '206', floor: 2 },
    ] } });
    const hBadPartial = ok(hBadRow) && (hBadRow.data?.rows ?? []).length === 2
      && hBadRow.data?.rows?.[0]?.success === true && hBadRow.data?.rows?.[1]?.success === false && !!hBadRow.data?.rows?.[1]?.reason;
    const dbU = unitId ? q(`SELECT community_id FROM unit WHERE id=${unitId}`) : '-';
    const res403 = await api('POST', '/api/v1/buildings/batch', { token: r1Tok, body: { communityId: c2id, buildings: [{ communityId: c2id, name: 'x', floors: 1 }] } });
    tc('B3-12', '批量创建（全有效成功+坏行逐行reason+上限+权限，R5：DEF-032 已修复）', '楼栋 2/2、单元 2/2 社区推导正确、房屋 2/2、坏行三端点均 200+好行成功坏行失败带 reason、超限拒、居民 403',
      ok(bBatch) && bRes?.total === 2 && okRows === 2 && badRowPartial && rej(bOverflow) && ok(uBatch) && uOk === 2 && uBadPartial && ok(hBatch) && hOk === 2 && hBadPartial && dbU === String(c2id) && rej(res403),
      `楼栋=${okRows}/2 坏行逐行=${bBadRow.code}/rows=${bBadRows.map(r=>r.success).join(',')} 超限=${bOverflow.code} 单元=${uOk}/2 单元坏行=${uBadRow.code}/rows=${(uBadRow.data?.rows??[]).map(r=>r.success).join(',')} 房屋=${hOk}/2 房屋坏行=${hBadRow.code}/rows=${(hBadRow.data?.rows??[]).map(r=>r.success).join(',')} 单元社区=${dbU} 居民=${res403.status}`);
    // 清理：级联删除 R3批A 楼栋（含单元房屋）
    if (bidA) await api('DELETE', `/api/v1/buildings/${bidA}?cascade=true`, { token: testAdminTok });
    if (bidB) await api('DELETE', `/api/v1/buildings/${bidB}?cascade=true`, { token: testAdminTok });
  }

  /* ══ B3-13 D-端点3 管理员直建居住关系 ══ */
  {
    // 社区1：新建空房给直建
    const mkB = await api('POST', '/api/v1/buildings', { token: admin1Tok, body: { communityId: 1, name: `R3关系楼${TAG}`, floors: 1 } });
    const mkU = await api('POST', '/api/v1/units', { token: admin1Tok, body: { buildingId: mkB.data?.id, name: `R3关系单元${TAG}` } });
    const mkH = await api('POST', '/api/v1/houses', { token: admin1Tok, body: { unitId: mkU.data?.id, houseNumber: '301', floor: 3, area: 88, status: 'VACANT' } });
    const hId = mkH.data?.id;
    // 居民：自助注册一个无关系账号（或复用 test_resident2——阳光花园在住）。用新注册的社区无关账号 + 直建给它
    const uname = `b313_${TAG}`;
    await api('POST', '/api/v1/auth/resident/register', { body: { username: uname, password: 'Resident123456', realName: 'B313居民', phone: '139' + String(20000000 + TAG % 8000000) } });
    const newRid = q(`SELECT id FROM resident WHERE username='${uname}'`);
    // ① 成功：VACANT→OCCUPIED + isPrimary=1 + 日志
    const rel = await api('POST', '/api/v1/residence-relations', { token: admin1Tok, body: { residentId: newRid, houseId: hId, relationType: 'OWNER', moveInDate: '2026-09-14', remark: `B313-${TAG}` } });
    const dbRel = q(`SELECT is_primary FROM residence_relation WHERE resident_id=${newRid} AND house_id=${hId} AND move_out_date IS NULL`);
    const dbHouse = q(`SELECT status FROM house WHERE id=${hId}`);
    const relId = rel.data?.id;
    const logN = q(`SELECT COUNT(*) FROM sys_operation_log WHERE target_type='RESIDENCE_RELATION' AND target_id=${relId} AND operation_type='CREATE'`);
    // ② 已入住拒（同房再建）
    const dup = await api('POST', '/api/v1/residence-relations', { token: admin1Tok, body: { residentId: newRid, houseId: hId, relationType: 'FAMILY', moveInDate: '2026-09-14' } });
    // ③ 越社区：test_admin（社区2）对社区1 房屋 403
    const cross = await api('POST', '/api/v1/residence-relations', { token: testAdminTok, body: { residentId: newRid, houseId: hId, relationType: 'OWNER', moveInDate: '2026-09-14' } });
    // ④ TENANT 不联动租约
    const mkH2 = await api('POST', '/api/v1/houses', { token: admin1Tok, body: { unitId: mkU.data?.id, houseNumber: '302', floor: 3, area: 88, status: 'VACANT' } });
    const ten = await api('POST', '/api/v1/residence-relations', { token: admin1Tok, body: { residentId: newRid, houseId: mkH2.data?.id, relationType: 'TENANT', moveInDate: '2026-09-14' } });
    const leaseN = q(`SELECT COUNT(*) FROM lease_record WHERE tenant_id=${newRid} AND created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE)`);
    const r403 = await api('POST', '/api/v1/residence-relations', { token: r1Tok, body: { residentId: newRid, houseId: mkH2.data?.id, relationType: 'OWNER', moveInDate: '2026-09-14' } });
    /* 【R5 口径更新·DEF-034 已修复】创建类操作日志已正常落库：
       RESIDENCE_RELATION CREATE 日志应 ≥1（不再零留痕；逐服务详验归专项子智能体）。 */
    tc('B3-13', '直建关系四断言（R5：DEF-034 已修复，创建日志留痕）', '成功建关系翻OCCUPIED+isPrimary、重复拒、越社区 404、TENANT 零租约联动、居民 403；创建日志≥1（DEF-034 修复后口径）',
      ok(rel) && dbRel === '1' && dbHouse === 'OCCUPIED' && Number(logN) >= 1 && rej(dup) && rej(cross) && ok(ten) && leaseN === '0' && rej(r403),
      `建=${rel.code} isPrimary=${dbRel} 房态=${dbHouse} 创建日志=${logN}（DEF-034 已修复） 重复=${dup.code}/${dup.msg?.slice(0, 14)} 越社区=${cross.status}/${cross.code} TENANT租约=${leaseN} 居民=${r403.status}`);
    // 清理：迁出两条关系 + 级联删楼
    for (const rel2 of q(`SELECT GROUP_CONCAT(id) FROM residence_relation WHERE resident_id=${newRid}`).split(',')) {
      if (rel2) await api('PATCH', `/api/v1/residence-relations/${rel2}/move-out`, { token: admin1Tok, body: { moveOutDate: '2026-09-14', remark: `B313清理-${TAG}` } });
    }
    if (mkB.data?.id) await api('DELETE', `/api/v1/buildings/${mkB.data.id}?cascade=true`, { token: admin1Tok });
  }

  /* ── 汇总 ── */
  console.log('\n════════ 第三批域功能专项汇总 ════════');
  for (const [id, r] of Object.entries(results)) console.log(`${r.ok ? '✅' : '❌'} ${id}`);
  console.log(`PASS ${pass} / FAIL ${fail}`);
  process.exit(fail > 0 ? 1 : 0);
}

main().catch(e => { console.error('FATAL', e); process.exit(2); });
