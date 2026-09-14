/**
 * 50 系统测试 · 第二轮 · DEF 全量回归（第一批：域功能类 16 项）
 * DEF-001/002（C4/C7）、003/004/021（C5）、011/012/019/020（C4）、
 * 013/017（C3）、015（C1 资源时段）、014（操作日志 AOP 全域）、023（C12 通知）、
 * 024（上传契约）、010（C2 居民数据范围）、022（评价聚合）、009（令牌同秒吊销）。
 * 致命 DEF-005~008 已由 SP-01 重跑覆盖（20/20）。
 * 用法：TEST_BASE=http://localhost:8081 node r2_def_regression.mjs
 */
import { execSync } from 'child_process';

const BASE = process.env.TEST_BASE || 'http://localhost:8080';
let pass = 0, fail = 0;
const results = {}; // defId -> ok/描述
function tc(defId, item, expectDesc, ok, actual) {
  ok ? pass++ : fail++;
  results[defId] = results[defId] ?? { ok: true, notes: [] };
  if (!ok) results[defId].ok = false;
  results[defId].notes.push(`${item}: ${actual}`);
  console.log(`${ok ? '✅' : '❌'} [${defId}] ${item} → ${actual}${ok ? '' : '（期望' + expectDesc + '）'}`);
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
const P = n => String(n).padStart(2, '0');
const future = n => { const x = new Date(); x.setDate(x.getDate() + n); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}`; };

async function main() {
  const login = async (ep, u, p) => (await api('POST', `/api/v1/auth/${ep}/login`, { body: { username: u, password: p } })).data?.token;
  const superTok = await login('admin', 'superadmin', 'Admin@123456');
  const admin1Tok = await login('admin', 'admin1', 'Admin123456');       // 社区 1
  const admin2Tok = await login('admin', 'test_admin', 'Admin123456');   // 社区 2
  const staff1Tok = await login('admin', 'staff1', 'Staff123456');
  const r1Tok = await login('resident', 'resident1', 'Resident123456');  // 社区 1
  const qyTok = await login('resident', 'test_resident', 'Resident123456'); // 清源里 社区 2
  const TAG = Date.now() % 100000;

  /* ══ DEF-001 无关系账号提工单被拒 ══ */
  {
    const uname = `def001_${TAG}`;
    await api('POST', '/api/v1/auth/resident/register', { body: { username: uname, password: 'Resident123456', realName: 'DEF001', phone: '139' + String(10000000 + Math.floor(Math.random() * 8999999)) } });
    const noRelTok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: uname, password: 'Resident123456' } })).data?.token;
    const bad = await api('POST', '/api/v1/work-orders', { token: noRelTok, body: { categoryId: 1, title: `DEF001无关系${TAG}`, content: 'x', contactPhone: '13812345678', address: 'x', priority: 'NORMAL' } });
    const dbN = q(`SELECT COUNT(*) FROM work_order WHERE title='DEF001无关系${TAG}'`);
    // 对照：resident1（社区 1 在住）可提
    const good = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: `DEF001有关系${TAG}`, content: 'x', contactPhone: '13812345678', address: 'x', priority: 'NORMAL' } });
    tc('DEF-001', '无关系账号提工单', 'code=403 业务拒绝且零落库；在住居民放行',
      bad.code === 403 && Number(dbN) === 0 && ok(good),
      `无关系=${bad.status}/${bad.code}(${(bad.msg || '').slice(0, 18)}) 落库=${dbN} 在住居民=${good.code}`);
  }

  /* ══ DEF-002 同天不同时段预约放行（同时段仍拦） ══
     C7会议室（动态查找：周模板含 09:00 与 10:00 两档的资源，纯净库重建后 id 不固定） */
  {
    const res15 = q(`SELECT rt.resource_id FROM resource_timeslot rt JOIN public_resource r ON rt.resource_id=r.id WHERE r.community_id=1 AND r.name LIKE 'C7会议室%' AND rt.day_of_week=1 AND rt.start_time='09:00:00' LIMIT 1`);
    const RID = Number(res15);
    // 找该资源模板覆盖的未来周几日期
    const nextDayWithSlot = (dow, offsetDays = 14) => { const d = new Date(); d.setDate(d.getDate() + ((dow - d.getDay() + 7) % 7 || 7) + offsetDays); return `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`; };
    // 选一个该资源的 09:00-11:00 两档都空闲的日期（轮次间数据累加，跳过被占日期）
    let date = null;
    for (let off = 7; off <= 70; off += 7) {
      const cand = nextDayWithSlot(1, off);
      const busy = Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${RID} AND reserve_date='${cand}' AND status IN ('PENDING','RESERVED')`));
      if (busy === 0) { date = cand; break; }
    }
    const a = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: RID, reserveDate: date, startTime: '09:00:00', endTime: '10:00:00', purpose: `DEF002-A${TAG}`, contactPhone: '13800005555' } });
    const b = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: RID, reserveDate: date, startTime: '10:00:00', endTime: '11:00:00', purpose: `DEF002-B${TAG}`, contactPhone: '13800005555' } });
    const c = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: RID, reserveDate: date, startTime: '09:00:00', endTime: '10:00:00', purpose: `DEF002-C${TAG}`, contactPhone: '13800005555' } });
    tc('DEF-002', '同天不同时段/同时段', '不同时段放行 + 同时段拦',
      ok(a) && ok(b) && rej(c),
      `资源=${RID} 日期=${date} A(09-10)=${a.code} B(10-11)=${b.code} C(同时段重复)=${c.code}/${(c.msg || '').slice(0, 16)}`);
  }

  /* ══ DEF-003 公告读路径数据范围 ══ */
  {
    // admin1（社区1）建社区 1 定向公告；admin2（社区2）GET 详情应 404；
    // 清源里居民列表不应见社区 1 定向公告
    const localIso = () => { const x = new Date(Date.now() - 60000); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}T${P(x.getHours())}:${P(x.getMinutes())}:${P(x.getSeconds())}`; };
    const n = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `DEF003公告${TAG}`, content: 'x', communityId: 1, publishTime: localIso() } });
    await api('PATCH', `/api/v1/notices/${n.data.id}/publish`, { token: admin1Tok, body: { publishTime: localIso() } });
    const crossAdmin = await api('GET', `/api/v1/notices/${n.data.id}`, { token: admin2Tok });
    const r1List = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const r1See = JSON.stringify(r1List.data?.records ?? r1List.data ?? []).includes(`DEF003公告${TAG}`);
    const qyList = await api('GET', '/api/v1/notices?page=1&size=50', { token: qyTok });
    const qySee = JSON.stringify(qyList.data?.records ?? qyList.data ?? []).includes(`DEF003公告${TAG}`);
    tc('DEF-003', '公告读路径', '跨社区 ADMIN 详情 404；本社区居民可见；范围外居民不可见',
      (crossAdmin.status === 404 || crossAdmin.code === 404 || rej(crossAdmin)) && r1See && !qySee,
      `跨社区详情=${crossAdmin.status}/${crossAdmin.code} 社区1居民可见=${r1See} 清源里居民可见=${qySee}`);
  }

  /* ══ DEF-004 公告 communityId 单目标越权写 ══ */
  {
    const localIso = () => { const x = new Date(Date.now() - 60000); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}T${P(x.getHours())}:${P(x.getMinutes())}:${P(x.getSeconds())}`; };
    const bad = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `DEF004越权${TAG}`, content: 'x', communityId: 2, publishTime: localIso() } });
    const dbN = q(`SELECT COUNT(*) FROM notice WHERE title='DEF004越权${TAG}'`);
    tc('DEF-004', 'communityId 单目标越权写', 'admin1 定向社区 2 被拒且零落库',
      rej(bad) && Number(dbN) === 0,
      `越权创建=${bad.status}/${bad.code} 落库=${dbN}`);
  }

  /* ══ DEF-009 令牌同秒吊销（冻结即时生效） ══ */
  {
    const uname = `def009_${TAG}`;
    await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: uname, password: 'Admin123456', realName: 'DEF009', phone: '139' + String(10000000 + Math.floor(Math.random() * 8999999)), role: 'ADMIN' } });
    const lg = await api('POST', '/api/v1/auth/admin/login', { body: { username: uname, password: 'Admin123456' } });
    const tok = lg.data?.token;
    const uid = Number(q(`SELECT id FROM sys_user WHERE username='${uname}'`));
    // 立刻冻结（同秒窗口）——冻结后旧令牌对受保护端点应立即 401
    //（注意探针须用受保护端点：GET /communities 为公开接口无法区分认证态）
    await api('PATCH', `/api/v1/sys-users/${uid}/status`, { token: superTok, body: { status: 'FROZEN', reason: '回归' } });
    const afterFreeze = await api('GET', '/api/v1/residents?page=1&size=1', { token: tok });
    // 还原 ACTIVE
    await api('PATCH', `/api/v1/sys-users/${uid}/status`, { token: superTok, body: { status: 'ACTIVE', reason: '还原' } });
    tc('DEF-009', '冻结同秒吊销', '冻结后存量令牌立即 401',
      rej(afterFreeze),
      `冻结后访问=${afterFreeze.status}/${afterFreeze.code}`);
  }

  /* ══ DEF-010 居民数据社区过滤 ══ */
  {
    // 清源里居民 id=2（resident 表）；admin1（社区1）直查应 404；admin2（社区2）可见
    const qyResidentId = q(`SELECT id FROM resident WHERE username='test_resident'`);
    const cross = await api('GET', `/api/v1/residents/${qyResidentId}`, { token: admin1Tok });
    const own = await api('GET', `/api/v1/residents/${qyResidentId}`, { token: admin2Tok });
    const admin1List = await api('GET', '/api/v1/residents?page=1&size=200', { token: admin1Tok });
    const listLeak = JSON.stringify(admin1List.data?.records ?? []).includes('"test_resident"');
    tc('DEF-010', '居民列表/详情社区过滤', '越绑定社区 404 + 列表不含跨社区居民',
      rej(cross) && ok(own) && !listLeak,
      `admin1 详情清源里居民=${cross.status}/${cross.code} admin2 详情=${own.code} admin1 列表泄露=${listLeak}`);
  }

  /* ══ DEF-011/012/019/020 工单状态机 ══ */
  {
    const mkOrder = async (title) => {
      for (let i = 0; i < 5; i++) {
        const r = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title, content: 'x', contactPhone: '13812345678', address: 'x', priority: 'NORMAL' } });
        if (ok(r)) return r.data;
        await new Promise(x => setTimeout(x, 100));
      }
      throw new Error('mkOrder 失败');
    };
    // DEF-011：同态重复（accept 二次）应 5302 且时间线零新增
    const w1 = await mkOrder(`DEF011-${TAG}`);
    await api('PATCH', `/api/v1/work-orders/${w1.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w1.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    const p0 = q(`SELECT COUNT(*) FROM work_order_process WHERE work_order_id=${w1.id}`);
    const reAccept = await api('PATCH', `/api/v1/work-orders/${w1.id}/accept`, { token: staff1Tok, body: { remark: 'again' } });
    const p1 = q(`SELECT COUNT(*) FROM work_order_process WHERE work_order_id=${w1.id}`);
    tc('DEF-011', '同态重复 accept', '5302 拒绝且时间线零新增',
      rej(reAccept) && Number(p1) === Number(p0),
      `重复accept=${reAccept.code} 时间线增量=${Number(p1) - Number(p0)}`);
    // DEF-011b：终态重复（close→close）
    const w2 = await mkOrder(`DEF011b-${TAG}`);
    await api('PATCH', `/api/v1/work-orders/${w2.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w2.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w2.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w2.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w2.id}/confirm`, { token: r1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w2.id}/close`, { token: admin1Tok, body: { remark: 'x' } });
    const reClose = await api('PATCH', `/api/v1/work-orders/${w2.id}/close`, { token: admin1Tok, body: { remark: 'again' } });
    tc('DEF-011', '终态重复 close', '拒绝', rej(reClose), `重复close=${reClose.code}`);

    // DEF-012：ACCEPTED 态居民取消应拒；ASSIGNED 改派放行但 ACCEPTED 改派拒
    const w3 = await mkOrder(`DEF012-${TAG}`);
    await api('PATCH', `/api/v1/work-orders/${w3.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w3.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    const cancelAccepted = await api('PATCH', `/api/v1/work-orders/${w3.id}/cancel`, { token: r1Tok, body: { remark: '想取消' } });
    const reassignAccepted = await api('PATCH', `/api/v1/work-orders/${w3.id}/assign`, { token: admin1Tok, body: { assigneeId: 6, remark: '改派' } });
    // 合法：PENDING 态取消放行
    const w4 = await mkOrder(`DEF012ok-${TAG}`);
    const cancelPending = await api('PATCH', `/api/v1/work-orders/${w4.id}/cancel`, { token: r1Tok, body: { remark: '正常取消' } });
    tc('DEF-012', '取消/改派流转集', 'ACCEPTED 取消拒 + ACCEPTED 改派拒 + PENDING 取消放行',
      rej(cancelAccepted) && rej(reassignAccepted) && ok(cancelPending),
      `ACCEPTED取消=${cancelAccepted.code} ACCEPTED改派=${reassignAccepted.code} PENDING取消=${cancelPending.code}`);

    // DEF-019：TO_CONFIRM 居民退回处理中（须填原因）
    const w5 = await mkOrder(`DEF019-${TAG}`);
    await api('PATCH', `/api/v1/work-orders/${w5.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w5.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w5.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w5.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
    const staffNotif0 = q(`SELECT COUNT(*) FROM notification WHERE user_id=3 AND title LIKE '%退回%'`);
    const ret = await api('PATCH', `/api/v1/work-orders/${w5.id}/return`, { token: r1Tok, body: { remark: '未彻底解决' } });
    const st = q(`SELECT status FROM work_order WHERE id=${w5.id}`);
    const staffNotif1 = q(`SELECT COUNT(*) FROM notification WHERE user_id=3 AND title LIKE '%退回%'`);
    // 非本人退回应拒（用清源里居民无权该工单即 403/404 验证）——w5 属社区1，qyTok 无访问权
    const retOther = await api('PATCH', `/api/v1/work-orders/${w5.id}/return`, { token: qyTok, body: { remark: 'x' } });
    tc('DEF-019', '不满意退回处理中', 'TO_CONFIRM 退回 IN_PROGRESS + 通知服务人员 + 非本人拒',
      ok(ret) && st === 'IN_PROGRESS' && Number(staffNotif1) > Number(staffNotif0) && rej(retOther),
      `退回=${ret.code} 状态=${st} 服务人员通知增量=${Number(staffNotif1) - Number(staffNotif0)} 非本人=${retOther.status}`);

    // DEF-020：驳回通知
    const w6 = await mkOrder(`DEF020-${TAG}`);
    const r1Notif0 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    const rej6 = await api('PATCH', `/api/v1/work-orders/${w6.id}/reject`, { token: admin1Tok, body: { remark: '不属于物业职责' } });
    const r1Notif1 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    const rejTitle = q(`SELECT COUNT(*) FROM notification WHERE user_id=1 AND title LIKE '%驳回%' AND created_at >= DATE_SUB(NOW(), INTERVAL 1 MINUTE)`);
    tc('DEF-020', '驳回通知', '驳回后居民收到通知',
      ok(rej6) && Number(r1Notif1) > Number(r1Notif0) && Number(rejTitle) >= 1,
      `驳回=${rej6.code} 通知增量=${Number(r1Notif1) - Number(r1Notif0)} 驳回类=${rejTitle}`);
  }

  /* ══ DEF-013/017 租约状态机 ══ */
  {
    // 造一条 PENDING 租约：找空置房 + resident1
    const houseId = q(`SELECT h.id FROM house h JOIN unit un ON h.unit_id=un.id JOIN building b ON un.building_id=b.id WHERE b.community_id=1 AND h.status='VACANT' AND h.is_deleted=0 LIMIT 1`);
    const mk = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { houseId: Number(houseId), residentId: 1, startDate: future(5), endDate: future(365), monthlyRent: 2000, deposit: 2000 } });
    if (!ok(mk)) { console.error('租约创建失败', mk.json); process.exit(2); }
    const leaseId = mk.data?.id;
    await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    // DEF-013：ACTIVE→ARCHIVED 跳级应拒
    const skip = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ARCHIVED' } });
    const st1 = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    // 线性：ACTIVE→MOVED_OUT→ARCHIVED
    await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'MOVED_OUT' } });
    await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ARCHIVED' } });
    const st2 = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    // DEF-017：ARCHIVED 后 PUT 修改应拒
    const mod = await api('PUT', `/api/v1/leases/${leaseId}`, { token: admin1Tok, body: { houseId: Number(houseId), residentId: 1, startDate: future(5), endDate: future(400), monthlyRent: 3000, deposit: 1000 } });
    tc('DEF-013', '租约跳级归档', 'ACTIVE→ARCHIVED 拒；线性两步达 ARCHIVED',
      rej(skip) && st1 === 'ACTIVE' && st2 === 'ARCHIVED',
      `跳级=${skip.code} 跳级后状态=${st1} 线性后=${st2}`);
    tc('DEF-017', '归档租约禁改', 'ARCHIVED PUT 拒绝',
      rej(mod), `归档后修改=${mod.status}/${mod.code}(${(mod.msg || '').slice(0, 16)})`);
  }

  /* ══ DEF-015 资源时段删除引用保护 ══ */
  {
    // 建新资源 + 时段 + 预约 → 删时段应拒；取消预约后删时段放行
    const res = await api('POST', '/api/v1/resources', { token: admin1Tok, body: { communityId: 1, name: `DEF015资源${TAG}`, type: 'MEETING_ROOM', location: 'x', capacity: 5 } });
    const slot = await api('POST', `/api/v1/resources/${res.data.id}/timeslots`, { token: admin1Tok, body: { dayOfWeek: 3, startTime: '09:00:00', endTime: '10:00:00', isAvailable: 1 } });
    const date = future(20); // 周三对齐由脚本保证：找一个周三
    const nextWed = (() => { const d = new Date(); d.setDate(d.getDate() + ((3 - d.getDay() + 7) % 7 || 7) + 14); return `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`; })();
    const rsv = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: res.data.id, reserveDate: nextWed, startTime: '09:00:00', endTime: '10:00:00', purpose: `DEF015${TAG}`, contactPhone: '13800005555' } });
    const delBusy = await api('DELETE', `/api/v1/timeslots/${slot.data.id}`, { token: admin1Tok });
    // 取消预约后删除放行
    const rsvId = rsv.data?.id;
    await api('PATCH', `/api/v1/resource-reservations/${rsvId}/cancel`, { token: r1Tok, body: { reason: '释放' } });
    const delFree = await api('DELETE', `/api/v1/timeslots/${slot.data.id}`, { token: admin1Tok });
    tc('DEF-015', '时段删除引用保护', '占用中拒删；释放后放行',
      rej(delBusy) && ok(delFree),
      `占用中删除=${delBusy.code}/${(delBusy.msg || '').slice(0, 14)} 释放后=${delFree.code}`);
  }

  /* ══ DEF-014 操作日志 AOP ══ */
  {
    const before = q(`SELECT COUNT(*) FROM sys_operation_log`);
    // 触发多个模块的关键变更
    const c = await api('POST', '/api/v1/communities', { token: superTok, body: { name: `DEF014社区${TAG}`, address: 'x', contactPhone: '13800000000', contactPerson: 'x' } });
    await api('PUT', `/api/v1/communities/${c.data.id}`, { token: superTok, body: { name: `DEF014社区改${TAG}`, address: 'x', contactPhone: '13800000000', contactPerson: 'x' } });
    const w = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: `DEF014-${TAG}`, content: 'x', contactPhone: '13812345678', address: 'x', priority: 'NORMAL' } });
    await api('PATCH', `/api/v1/work-orders/${w.data.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
    const n = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `DEF014公告${TAG}`, content: 'x', communityId: 1, publishTime: '2026-09-11T12:00:00' } });
    await api('PATCH', `/api/v1/notices/${n.data.id}/publish`, { token: admin1Tok, body: { publishTime: '2026-09-11T12:00:00' } });
    const after = q(`SELECT COUNT(*) FROM sys_operation_log`);
    const recent = q(`SELECT COUNT(*) FROM sys_operation_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)`);
    const recentOps = q(`SELECT GROUP_CONCAT(DISTINCT operation_type) FROM sys_operation_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)`);
    // 检索端点可用
    const logApi = await api('GET', '/api/v1/operation-logs?page=1&size=5', { token: superTok });
    tc('DEF-014', '操作日志 AOP 全域', '多模块关键变更落 sys_operation_log + 检索端点可用',
      ok(c) && after > before && Number(recent) >= 4 && ok(logApi),
      `日志总量 ${before}→${after} 近2分钟=${recent} 类型=${recentOps} 检索=${logApi.code}`);
  }

  /* ══ DEF-023 看房预约流转通知 ══ */
  {
    // 选一个 housing 1 的 15:00-16:00 空闲未来日期（轮次间数据累加，跳过被占日期）
    let date = null;
    for (let off = 25; off <= 90; off++) {
      const cand = future(off);
      const busy = Number(q(`SELECT COUNT(*) FROM viewing_appointment WHERE housing_id=1 AND appointment_date='${cand}' AND status IN ('TO_CONFIRM','CONFIRMED')`));
      if (busy === 0) { date = cand; break; }
    }
    const va = await api('POST', '/api/v1/viewing-appointments', { token: r1Tok, body: { housingId: 1, appointmentDate: date, startTime: '15:00:00', endTime: '16:00:00', visitorName: `DEF023访客${TAG}`, contactPhone: '13800005555' } });
    const vaId = va.data?.id;
    const n0 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    const conf = await api('PATCH', `/api/v1/viewing-appointments/${vaId}/confirm`, { token: admin1Tok, body: { reason: 'DEF023回归' } });
    const n1 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    const comp = await api('PATCH', `/api/v1/viewing-appointments/${vaId}/complete`, { token: admin1Tok, body: { reason: 'DEF023回归' } });
    const n2 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    tc('DEF-023', '看房流转通知', 'confirm/complete 均通知预约人',
      ok(va) && ok(conf) && ok(comp) && Number(n1) > Number(n0) && Number(n2) > Number(n1),
      `创建=${va.code} confirm通知+${Number(n1) - Number(n0)} complete通知+${Number(n2) - Number(n1)}`);
  }

  /* ══ DEF-024 文档超限错误契约 ══ */
  {
    const MB = 1024 * 1024;
    const up = async (bytes, filename, type) => {
      const fd = new FormData();
      fd.append('file', new Blob([bytes], { type: 'application/octet-stream' }), filename);
      fd.append('type', type);
      const res = await fetch(`${BASE}/api/v1/upload`, { method: 'POST', headers: { Authorization: `Bearer ${r1Tok}` }, body: fd });
      const j = await res.json().catch(() => null);
      return { status: res.status, code: j?.code, msg: j?.message };
    };
    const big = await up(Buffer.concat([Buffer.from('%PDF'), Buffer.alloc(11 * MB)]), 'big.pdf', 'DOCUMENT');
    const bigger = await up(Buffer.concat([Buffer.from('%PDF'), Buffer.alloc(13 * MB)]), 'bigger.pdf', 'DOCUMENT');
    tc('DEF-024', '文档超限错误契约', '11MB 与 13MB 均 400 + 明确提示（业务层/映射层）',
      big.status === 400 && (big.msg || '').includes('10MB') && bigger.status === 400 && (bigger.msg || '').includes('10MB'),
      `11MB=${big.status}/${big.code}(${(big.msg || '').slice(0, 14)}) 13MB=${bigger.status}/${bigger.code}(${(bigger.msg || '').slice(0, 14)})`);
  }

  /* ══ DEF-022 评价双维度聚合 ══ */
  {
    const agg = await api('GET', '/api/v1/statistics/evaluations/aggregation?communityId=1', { token: admin1Tok });
    const body = JSON.stringify(agg.data ?? {});
    const hasStaff = /byStaff|staff/i.test(body) || (agg.data && (agg.data.byStaff || agg.data.staff));
    const hasCat = /byCategory|category/i.test(body) || (agg.data && (agg.data.byCategory || agg.data.category));
    tc('DEF-022', '评价双维度聚合端点', 'by-staff 与 by-category 两维度输出',
      ok(agg) && hasStaff && hasCat,
      `端点=${agg.code} 返回键=${Object.keys(agg.data ?? {}).join(',')} 长度=${body.length}`);
  }

  /* ══ DEF-021（改判后脚本口径复核）：本地墙钟时间创建→落库一致 ══ */
  {
    const local = () => { const x = new Date(Date.now() + 3600 * 1000); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}T${P(x.getHours())}:${P(x.getMinutes())}:00`; };
    const pt = local();
    const n = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `DEF021复核${TAG}`, content: 'x', communityId: 1, publishTime: pt, endTime: local() } });
    const dbPt = n.data?.id ? q(`SELECT DATE_FORMAT(publish_time, '%Y-%m-%d %H:%i') FROM notice WHERE id=${n.data.id}`) : '(未创建)';
    const dbEt = n.data?.id ? q(`SELECT DATE_FORMAT(end_time, '%Y-%m-%d %H:%i') FROM notice WHERE id=${n.data.id}`) : '';
    const sent = pt.slice(0, 16).replace('T', ' ');
    tc('DEF-021', '公告本地时间口径复核（改判为脚本口径问题）', '本地墙钟输入与落库一致',
      ok(n) && dbPt === sent,
      `传入=${sent} 落库publish=${dbPt} 落库end=${dbEt}`);
  }

  console.log(`\n══ DEF 回归第一批：${pass} 通过 / ${fail} 失败 ══`);
  console.log('\n按 DEF 汇总：');
  for (const [id, r] of Object.entries(results)) console.log(`${r.ok ? '✅' : '❌'} ${id}`);
  process.exit(fail ? 1 : 0);
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2); });
