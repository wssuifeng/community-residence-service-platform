/**
 * 50 系统测试 · 步骤 6 冒烟测试脚本
 * 范围（06_冒烟测试记录.md §1）：启动健康检查 + 登录认证（4 账号）+
 * E1 社区初始化 / E2 注册入住 / E4 报修工单 / E6 反馈会话 / E7 资源预约 / E10b 权限初始化
 * 被测环境：后端 http://localhost:8080（指向 community_residence_test）
 * 用法：node smoke_test.mjs
 */
const BASE = 'http://localhost:8080';

const results = [];
let passed = 0, failed = 0;

function record(step, ok, detail) {
  results.push({ step, ok, detail });
  ok ? passed++ : failed++;
  console.log(`${ok ? '✅' : '❌'} [${step}] ${detail}`);
}

async function api(method, path, { token, body, expectStatus } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try { json = await res.json(); } catch { /* 非 JSON 响应 */ }
  if (expectStatus !== undefined && res.status !== expectStatus) {
    throw new Error(`${method} ${path} 期望 ${expectStatus} 实际 ${res.status}: ${JSON.stringify(json).slice(0, 200)}`);
  }
  return { status: res.status, json, code: json?.code, data: json?.data };
}

async function login(username, password) {
  // 管理员/服务人员走 /auth/admin/login，居民走 /auth/resident/login（由调用方指定）
  const { json } = await api('POST', '/api/v1/auth/admin/login', {
    body: { username, password }, expectStatus: 200,
  });
  if (json?.code !== 200 || !json?.data?.token) throw new Error(`登录失败 ${username}: ${JSON.stringify(json)}`);
  return json.data;
}

async function residentLogin(username, password) {
  const { json } = await api('POST', '/api/v1/auth/resident/login', {
    body: { username, password }, expectStatus: 200,
  });
  if (json?.code !== 200 || !json?.data?.token) throw new Error(`居民登录失败 ${username}: ${JSON.stringify(json)}`);
  return json.data;
}

const P = n => String(n).padStart(2, '0');
const today = new Date();
const dstr = d => `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`;
const future = n => { const x = new Date(today); x.setDate(x.getDate() + n); return dstr(x); };
// 每次执行生成唯一手机号，避免撞唯一约束（脚本可重复执行）
const uniqPhone = () => '13' + String(800000000 + Math.floor(Math.random() * 99999999));

async function main() {
  // ── 0. 启动健康检查：swagger 可达 + 业务接口未带 token 被拒 ──
  // （GET /communities 为游客公开口径 R12，认证断言用居民端 POST /work-orders）
  try {
    const sw = await fetch(BASE + '/v3/api-docs');
    record('S0-启动健康检查', sw.status === 200, `swagger /v3/api-docs HTTP ${sw.status}`);
  } catch (e) { record('S0-启动健康检查', false, e.message); }
  try {
    const r = await api('POST', '/api/v1/work-orders', { body: {} });
    record('S0-未认证拒绝', r.status === 401, `无 token 调用业务接口 HTTP ${r.status}（GET /communities 为 R12 游客公开口径，不作断言）`);
  } catch (e) { record('S0-未认证拒绝', false, e.message); }

  // ── 1. 登录认证：4 种子账号 ──
  let superTok, adminTok, staffTok, resTok;
  try {
    const s = await login('superadmin', 'Admin@123456');
    superTok = s.token;
    record('S1-登录认证', true, `superadmin 登录成功（user=${s.user?.username ?? s.user?.realName ?? '?'}, expiresIn=${s.expiresIn}）`);
  } catch (e) { record('S1-登录认证', false, e.message); }
  try {
    const a = await login('test_admin', 'Admin123456');
    adminTok = a.token;
    const staff = await login('test_staff', 'Staff123456');
    staffTok = staff.token;
    const res = await residentLogin('test_resident', 'Resident123456');
    resTok = res.token;
    record('S1-登录认证', true, 'admin/staff/resident 三角色登录成功');
  } catch (e) { record('S1-登录认证', false, e.message); }
  try {
    const r = await api('POST', '/api/v1/auth/resident/login', {
      body: { username: 'test_resident_frozen', password: 'Resident123456' },
    });
    record('S1-冻结账号拒登', r.status !== 200 || r.code !== 200, `冻结居民登录被拒（HTTP ${r.status}, code=${r.code}）`);
  } catch (e) { record('S1-冻结账号拒登', false, e.message); }

  // ── E10b 权限初始化：超管建社区 → 建管理员 → 绑定 → 管理员在本社区工作 + 越全局级被拒 ──
  try {
    const c = await api('POST', '/api/v1/communities', {
      token: superTok,
      body: { name: `TEST-冒烟E10b社区${Date.now() % 100000}`, address: '冒烟市E10b路1号', contactPhone: '13800001111', contactPerson: '冒烟超管', description: 'E10b 权限初始化冒烟' },
      expectStatus: 200,
    });
    const cid = c.data.id;
    const uname = `smkadm${Date.now() % 100000}`;
    const u = await api('POST', '/api/v1/sys-users', {
      token: superTok,
      body: { username: uname, password: 'Admin123456', realName: '冒烟管理员', phone: uniqPhone(), role: 'ADMIN' },
      expectStatus: 200,
    });
    const uid = u.data.id;
    await api('POST', `/api/v1/sys-users/${uid}/communities`, { token: superTok, body: { communityId: cid }, expectStatus: 200 });
    // 新管理员登录并在本社区开展工作（查社区楼栋）
    const na = await login(uname, 'Admin123456');
    const b = await api('GET', `/api/v1/communities/${cid}/buildings`, { token: na.token, expectStatus: 200 });
    // 越全局级：管理员创建系统用户应被拒（403）
    const denied = await api('POST', '/api/v1/sys-users', {
      token: na.token,
      body: { username: 'should_fail_x', password: 'Admin123456', realName: 'x', phone: '13800009999', role: 'ADMIN' },
    });
    const deniedOk = denied.status === 403;
    // v1.1 口径：管理员建社区（全局级）应被拒——E1 改由超管执行
    const deniedCreate = await api('POST', '/api/v1/communities', {
      token: adminTok,
      body: { name: 'should_fail_community', address: 'x', contactPhone: '13800001111', contactPerson: 'x' },
    });
    record('E10b-权限初始化', deniedOk && deniedCreate.status === 403,
      `超管建社区+建管理员(${uname})+绑定成功；新管理员可查本社区（楼栋数=${b.data?.records?.length ?? b.data?.total ?? '?'}）；越全局级建系统用户被拒（HTTP ${denied.status}）；管理员建社区被拒（HTTP ${deniedCreate.status}，v1.1 全局级口径）`);
  } catch (e) { record('E10b-权限初始化', false, e.message); }

  // ── E1 社区初始化：社区→楼栋→单元→房屋→资源与时段（v1.1：建社区仅超管；其余管理员在本社区） ──
  try {
    const c = await api('POST', '/api/v1/communities', {
      token: superTok,
      body: { name: `TEST-冒烟E1社区${Date.now() % 100000}`, address: '冒烟市E1路2号', contactPhone: '13800003333', contactPerson: '冒烟管理员', description: 'E1 冒烟' },
      expectStatus: 200,
    });
    const cid = c.data.id;
    // 超管把 E1 社区绑给 test_admin（模拟其管理该社区）；绑定后需重新登录生效（接口文档口径）
    const ta = await api('GET', '/api/v1/sys-users?page=1&size=50', { token: superTok, expectStatus: 200 });
    const taRec = (ta.data?.records ?? []).find(x => x.username === 'test_admin');
    if (!taRec) throw new Error('未找到 test_admin 账号');
    await api('POST', `/api/v1/sys-users/${taRec.id}/communities`, { token: superTok, body: { communityId: cid }, expectStatus: 200 });
    const adminTok2 = (await login('test_admin', 'Admin123456')).token;
    const b = await api('POST', '/api/v1/buildings', { token: adminTok2, body: { communityId: cid, name: '冒烟1号楼', floors: 3, description: '' }, expectStatus: 200 });
    const u = await api('POST', '/api/v1/units', { token: adminTok2, body: { buildingId: b.data.id, name: '1单元', description: '' }, expectStatus: 200 });
    const h = await api('POST', '/api/v1/houses', { token: adminTok2, body: { unitId: u.data.id, houseNumber: '101', floor: 1, area: 88.5, roomCount: 2, layout: '两室一厅', orientation: '南', status: 'VACANT', description: '' }, expectStatus: 200 });
    const r = await api('POST', '/api/v1/resources', { token: adminTok2, body: { communityId: cid, name: '冒烟活动室', type: 'ACTIVITY_ROOM', location: '1号楼首层', capacity: 20, description: '' }, expectStatus: 200 });
    const t = await api('POST', `/api/v1/resources/${r.data.id}/timeslots`, { token: adminTok2, body: { dayOfWeek: 1, startTime: '09:00:00', endTime: '11:00:00', isAvailable: 1 }, expectStatus: 200 });
    record('E1-社区初始化', true, `社区/楼栋/单元/房屋(${h.data.status})/资源/时段 全链创建成功（社区ID=${cid}）`);
  } catch (e) { record('E1-社区初始化', false, e.message); }

  // ── E2 注册→入住申请→审核→居住关系 ──
  try {
    // R7 全局开关：registration.enabled=true 时允许自助注册
    const uname = `smkreg${Date.now() % 100000}`;
    const reg = await api('POST', '/api/v1/auth/resident/register', {
      body: { username: uname, password: 'Resident123456', realName: '冒烟注册居民', phone: uniqPhone(), idCardNumber: '110101199001010011' },
    });
    if (reg.status !== 200 && reg.code !== 200) throw new Error(`注册异常: HTTP ${reg.status} code=${reg.code}`);
    const nl = await residentLogin(uname, 'Resident123456');
    // 观察项（不作冒烟判据，归 A 轨用例裁决）：无居住关系账号提交工单
    const woDenied = await api('POST', '/api/v1/work-orders', {
      token: nl.token,
      body: { categoryId: 1, title: '冒烟观察-无关系工单', content: '无关系账号提交', contactPhone: '13800005555', address: 'x' },
    });
    const obs = `无关系账号提交工单 HTTP ${woDenied.status} code=${woDenied.code}（观察项：R9 口径疑似应拒，归 TC-C2/C4 裁决）`;
    // 申请入住清源里空置房（动态查询，脚本可重复执行）
    const hs = await api('GET', '/api/v1/units/3/houses?page=1&size=10');
    const vacant = (hs.data?.records ?? hs.data ?? []).find(h => h.status === 'VACANT');
    if (!vacant) throw new Error('清源里 1 单元已无空置房（冒烟重复执行耗尽，请重建数据或换单元）');
    const app = await api('POST', '/api/v1/residence-applications', {
      token: nl.token, body: { houseId: vacant.id, relationType: 'TENANT', remark: '冒烟入住申请' }, expectStatus: 200,
    });
    // 管理员审核通过
    const ap = await api('PATCH', `/api/v1/residence-applications/${app.data.id}/approve`, {
      token: adminTok, body: { leaseStartDate: future(1), leaseEndDate: future(365), monthlyRent: 3000, deposit: 6000, remark: '冒烟审核通过' }, expectStatus: 200,
    });
    // 居民端功能开放（获得居住关系后可提交工单——用本社区类别）
    const myCat = await api('GET', '/api/v1/communities/2/service-categories');
    const catId = (myCat.data?.records ?? myCat.data ?? [])[0]?.id ?? 44;
    const wo = await api('POST', '/api/v1/work-orders', {
      token: nl.token,
      body: { categoryId: catId, title: '冒烟-E2居民工单', content: '入住后的第一张工单', contactPhone: '13800005555', address: 'TEST-清源里', priority: 'NORMAL' },
      expectStatus: 200,
    });
    record('E2-注册入住', !!wo.data?.id,
      `注册(${uname})→登录→申请(#${app.data.id} 房${vacant.houseNumber})→审核通过(${ap.data?.status ?? 'OK'})→居住关系建立后工单可提交(#${wo.data?.id})；${obs}`);
  } catch (e) { record('E2-注册入住', false, e.message); }

  // ── E4 报修工单全流程：提交→派单→接单→处理→完成→居民确认 ──
  try {
    // 前置：社区 2（清源里）暂无服务类别，先由管理员创建（R17 类别管理）
    const cat = await api('POST', '/api/v1/service-categories', {
      token: adminTok, body: { communityId: 2, name: '室内维修', description: '冒烟-清源里维修类' }, expectStatus: 200,
    });
    const wo = await api('POST', '/api/v1/work-orders', {
      token: resTok,
      body: { categoryId: cat.data.id, title: '冒烟-厨房水管漏水', content: '厨房水管接口渗水，请尽快处理', contactPhone: '13800005555', address: 'TEST-清源里1号楼101', priority: 'HIGH' },
      expectStatus: 200,
    });
    const id = wo.data.id;
    const st = s => s?.status ?? '?';
    const a1 = await api('PATCH', `/api/v1/work-orders/${id}/assign`, { token: adminTok, body: { assigneeId: 6, remark: '派给测试服务人员' }, expectStatus: 200 });
    const a2 = await api('PATCH', `/api/v1/work-orders/${id}/accept`, { token: staffTok, body: { remark: '已接单' }, expectStatus: 200 });
    const a3 = await api('PATCH', `/api/v1/work-orders/${id}/process`, { token: staffTok, body: { remark: '开始处理' }, expectStatus: 200 });
    const a4 = await api('PATCH', `/api/v1/work-orders/${id}/complete`, { token: staffTok, body: { remark: '更换接口密封圈，漏水已修复' }, expectStatus: 200 });
    const a5 = await api('PATCH', `/api/v1/work-orders/${id}/confirm`, { token: resTok, body: { remark: '确认修复' }, expectStatus: 200 });
    const fin = await api('GET', `/api/v1/work-orders/${id}`, { token: resTok, expectStatus: 200 });
    const tl = await api('GET', `/api/v1/work-orders/${id}/timeline`, { token: resTok, expectStatus: 200 });
    record('E4-报修工单', fin.data?.status === 'COMPLETED',
      `建类别(#${cat.data.id})→工单#${id} 提交→派单(${st(a1.data)})→接单(${st(a2.data)})→处理中(${st(a3.data)})→待确认(${st(a4.data)})→已完成(${fin.data?.status})；时间线记录数=${tl.data?.length ?? tl.data?.total ?? '?'}`);
  } catch (e) { record('E4-报修工单', false, e.message); }

  // ── E6 反馈会话：提交→受理建会话→双向消息→办结只读 ──
  try {
    const fb = await api('POST', '/api/v1/feedbacks', {
      token: resTok, body: { communityId: 2, title: '冒烟-楼道照明投诉', content: '3单元楼道灯不亮三天了', category: 'COMPLAINT' }, expectStatus: 200,
    });
    const id = fb.data.id;
    // 管理员回复自动受理建会话
    const m1 = await api('POST', `/api/v1/feedbacks/${id}/messages`, { token: adminTok, body: { content: '已收到，安排电工今天处理' }, expectStatus: 200 });
    // 居民回消息
    const m2 = await api('POST', `/api/v1/feedbacks/${id}/messages`, { token: resTok, body: { content: '好的，谢谢' }, expectStatus: 200 });
    // 会话消息列表
    const ml = await api('GET', `/api/v1/feedbacks/${id}/messages`, { token: resTok, expectStatus: 200 });
    // 办结
    const cl = await api('PATCH', `/api/v1/feedbacks/${id}/close`, { token: adminTok, body: { remark: '灯已更换，办结' }, expectStatus: 200 });
    // 办结后不可再发消息
    const after = await api('POST', `/api/v1/feedbacks/${id}/messages`, { token: resTok, body: { content: '办结后消息' } });
    const rejected = after.status !== 200 || after.code !== 200;
    record('E6-反馈会话', rejected, `反馈#${id} 提交→受理会话→双向消息（列表=${ml.data?.length ?? ml.data?.total ?? '?'}条）→办结(${cl.data?.status ?? 'OK'})→办结后发消息被拒（HTTP ${after.status}, code=${after.code}）`);
  } catch (e) { record('E6-反馈会话', false, e.message); }

  // ── E7 资源预约：查可约时段→提交→冲突校验→审核 ──
  try {
    // 并发测试健身房（容量1，全周 08:00-18:00 整点档）；实现口径：同资源同天仅一约
    // 动态挑选该居民未预约过的未来日期（脚本可重复执行）
    const myList = await api('GET', '/api/v1/resource-reservations?page=1&size=50', { token: resTok, expectStatus: 200 });
    const myDates = new Set((myList.data?.records ?? []).map(r => String(r.reserveDate).slice(0, 10)));
    let target = null;
    for (let i = 7; i <= 27 && !target; i++) { const d = future(i); if (!myDates.has(d)) target = d; }
    if (!target) throw new Error('未来 7~27 天均已预约该资源（冒烟重复执行耗尽）');
    const slots = await api('GET', `/api/v1/resources/2/available-slots?startDate=${target}&endDate=${target}`, { token: resTok, expectStatus: 200 });
    const body = { resourceId: 2, reserveDate: target, startTime: '08:00:00', endTime: '09:00:00', purpose: '冒烟健身', contactPhone: '13800005555', remark: '' };
    const r1 = await api('POST', '/api/v1/resource-reservations', { token: resTok, body, expectStatus: 200 });
    if (!r1.data?.id) throw new Error(`预约创建失败: code=${r1.code} ${JSON.stringify(r1.json).slice(0, 120)}`);
    // 同一天重复预约应被拒（实现口径的同资源同天拦截；R33 重叠时段拦截归 SP-01 专项）
    const r2 = await api('POST', '/api/v1/resource-reservations', { token: resTok, body: { ...body, purpose: '冲突预约', startTime: '10:00:00', endTime: '11:00:00' } });
    const conflictRejected = r2.status !== 200 || r2.code !== 200;
    // 管理员审核通过
    const cf = await api('PATCH', `/api/v1/resource-reservations/${r1.data.id}/confirm`, { token: adminTok, body: { reason: '按约使用' }, expectStatus: 200 });
    record('E7-资源预约', conflictRejected,
      `预约#${r1.data.id}(${r1.data.status}, ${target} 08:00-09:00)→同天同时段冲突被拒（HTTP ${r2.status}, code=${r2.code}）→审核通过(${cf.data?.status ?? 'OK'})；available-slots 返回=${Array.isArray(slots.data) ? slots.data.length : slots.data?.total ?? '?'}档`);
  } catch (e) { record('E7-资源预约', false, e.message); }

  // ── 汇总 ──
  console.log('\n========== 冒烟汇总 ==========');
  console.log(`通过 ${passed} / ${passed + failed}`);
  if (failed > 0) {
    console.log('失败项：');
    results.filter(r => !r.ok).forEach(r => console.log(`  ❌ ${r.step}: ${r.detail}`));
    process.exit(1);
  }
}

main().catch(e => { console.error('冒烟脚本异常终止:', e); process.exit(2); });
