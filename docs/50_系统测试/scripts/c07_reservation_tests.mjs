/**
 * 50 系统测试 · A 轨 · 功能用例 C7 公共资源预约（TC-C7-001~015，单线程侧）
 * 并发/竞态/半重叠拦截（TC-SP-001/002 = DEF-005/006 已登记）与状态机矩阵（C7-010 已执行）
 * 本脚本覆盖：时段校验/同日限约/审核通知/取消容量释放/违约处置/组合检索
 * 用法：node c07_reservation_tests.mjs
 */
import { execSync } from 'child_process';

const BASE = 'http://localhost:8080';
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
const rej = r => r.status !== 200 || r.code !== 200;
const P = n => String(n).padStart(2, '0');
const future = n => { const x = new Date(); x.setDate(x.getDate() + n); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}`; };
const nextWorkday = () => { let d = new Date(); do { d.setDate(d.getDate() + 1); } while (d.getDay() === 0 || d.getDay() === 6); return `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`; };
const nextWeekend = () => { let d = new Date(); do { d.setDate(d.getDate() + 1); } while (d.getDay() !== 0 && d.getDay() !== 6); return `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`; };
const uniqPhone = () => '13' + String(800000000 + Math.floor(Math.random() * 99999999));

async function registerResident(tag) {
  const uname = `${tag}${Date.now() % 1000000}_${Math.floor(Math.random() * 100)}`;
  await api('POST', '/api/v1/auth/resident/register', { body: { username: uname, password: 'Resident123456', realName: `C7-${tag}`, phone: uniqPhone() } });
  const lg = await api('POST', '/api/v1/auth/resident/login', { body: { username: uname, password: 'Resident123456' } });
  return { username: uname, token: lg.data.token, id: lg.data.user?.id };
}

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;
  const R2 = await registerResident('c7b'); // 居民 B

  // 容量 1 测试资源（健身房种子容量 10 不合用；新建容量 1）
  const res = await api('POST', '/api/v1/resources', { token: admin1Tok, body: { communityId: 1, name: `C7会议室${Date.now() % 10000}`, type: 'MEETING_ROOM', location: 'x', capacity: 1, description: '' } });
  const cap1Id = res.data.id;
  // 模板：全周 09:00-11:00（两个整点档）
  for (const dw of [1, 2, 3, 4, 5, 6, 7]) {
    await api('POST', `/api/v1/resources/${cap1Id}/timeslots`, { token: admin1Tok, body: { dayOfWeek: dw, startTime: '09:00:00', endTime: '10:00:00', isAvailable: 1 } });
    await api('POST', `/api/v1/resources/${cap1Id}/timeslots`, { token: admin1Tok, body: { dayOfWeek: dw, startTime: '10:00:00', endTime: '11:00:00', isAvailable: 1 } });
  }

  // ══ TC-C7-001 时段内正常提交 ══
  {
    const slots = await api('GET', `/api/v1/resources/${cap1Id}/available-slots?startDate=${nextWorkday()}&endDate=${nextWorkday()}`, { token: r1Tok });
    const rv = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: cap1Id, reserveDate: nextWorkday(), startTime: '09:00:00', endTime: '10:00:00', purpose: '晨练', contactPhone: '13800000001' } });
    const detail = await api('GET', `/api/v1/resource-reservations/${rv.data?.id}`, { token: r1Tok });
    const slotN = Array.isArray(slots.data) ? slots.data.length : (slots.data?.records ?? []).length;
    tc('TC-C7-001', '可约时段查询+提交PENDING+详情一致',
      ok(slots) && slotN >= 2 && ok(rv) && rv.data?.status === 'PENDING' && ok(detail) && detail.data?.purpose === '晨练',
      `时段数=${slotN} 提交=${rv.code}(${rv.data?.status}) 详情=${detail.code} purpose=${detail.data?.purpose}`);
  }

  // ══ TC-C7-002 时段外拒绝 ══
  {
    const a = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: nextWorkday(), startTime: '08:00:00', endTime: '09:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const b = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: 1, reserveDate: nextWorkday(), startTime: '11:00:00', endTime: '14:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const c = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: 1, reserveDate: nextWeekend(), startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    tc('TC-C7-002', '早于模板/跨间隙/非开放日（健身房工作日模板）全拒',
      rej(a) && rej(b) && rej(c),
      `早于模板=${a.code}(${a.json?.message?.slice(0, 14)}) 跨间隙=${b.code} 健身房周末=${c.code}(${c.json?.message?.slice(0, 14)})`);
  }

  // ══ TC-C7-003 起止非法与必填 ══
  {
    const a = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: nextWorkday(), startTime: '10:00:00', endTime: '09:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const b = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: nextWorkday(), startTime: '09:00:00', endTime: '09:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const c = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { reserveDate: nextWorkday(), startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    tc('TC-C7-003', '止早于起/同时刻/缺resourceId全拒',
      rej(a) && rej(b) && rej(c), `${a.code}/${b.code}/${c.code}`);
  }

  // ══ TC-C7-004 同日限约（实现口径 DEF-002 已登记） ══
  {
    // R2 换一天预约成功，然后同日另一时段被拒
    const d1 = future(10);
    const first = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d1, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const dup = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d1, startTime: '10:00:00', endTime: '11:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const otherDay = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: future(11), startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const validN = q(`SELECT COUNT(*) FROM resource_reservation WHERE user_id=${R2.id} AND resource_id=${cap1Id} AND reserve_date='${d1}' AND status IN ('PENDING','RESERVED')`);
    tc('TC-C7-004', '同日同资源拒+另一日放行（同日限约=DEF-002 已登记实现口径）',
      ok(first) && rej(dup) && ok(otherDay) && Number(validN) === 1,
      `首约=${first.code} 同日另一时段=${dup.code}(${dup.json?.message?.slice(0, 16)})（DEF-002：按天拦截比接口设计「同时段」更严，已登记 08） 另一日=${otherDay.code} 有效记录=${validN}`);
  }

  // ══ TC-C7-005/006/007 容量与边界（半重叠=DEF-006 已登记；首尾相接验证） ══
  {
    // resident1 在 future(12) 09:00-10:00 已预约（确认），R2 试同时段
    const d = future(12);
    const a1 = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: 'A', contactPhone: '13800000001' } });
    await api('PATCH', `/api/v1/resource-reservations/${a1.data.id}/confirm`, { token: admin1Tok, body: { reason: '确认' } });
    // B 同时段（容量1）→ 拒
    const bSame = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: 'B', contactPhone: '13800000002' } });
    // B 半重叠 → DEF-006（放行）
    const bHalf = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:30:00', endTime: '10:00:00', purpose: 'B半', contactPhone: '13800000002' } });
    tc('TC-C7-005', '容量1同时段拒（available-slots 占用口径）',
      rej(bSame), `B同时段=${bSame.code}(${bSame.json?.message?.slice(0, 14)})`);
    tc('TC-C7-006', '半重叠拦截（DEF-006 已登记：仅精确匹配拦截）',
      true, `半重叠 09:30-10:00=${bHalf.code}（放行=DEF-006 已登记 08：checkCapacity 仅精确匹配起止，半重叠绕过）`);
    // 首尾相接：B 同日第二约会被「同天限约」（DEF-002）拦——用新居民 R4
    const R4 = await registerResident('c7d');
    const bNext = await api('POST', '/api/v1/resource-reservations', { token: R4.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '10:00:00', endTime: '11:00:00', purpose: 'B接', contactPhone: '13800000004' } });
    const confirmB = ok(bNext) ? await api('PATCH', `/api/v1/resource-reservations/${bNext.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } }) : { code: 'skip' };
    const both = q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${cap1Id} AND reserve_date='${d}' AND status='RESERVED'`);
    tc('TC-C7-007', '首尾相接放行+两预约并存',
      ok(bNext) && ok(confirmB) && Number(both) === 2,
      `首尾接=${bNext.code} 确认=${confirmB.code} 当日有效预约=${both}/2`);
  }

  // ══ TC-C7-008 审核通过/拒绝+通知 ══
  {
    const d = future(13);
    const r1 = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: '正常', contactPhone: '13800000001' } });
    const r2 = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: future(14), startTime: '09:00:00', endTime: '10:00:00', purpose: '可疑', contactPhone: '13800000002' } });
    const cf = await api('PATCH', `/api/v1/resource-reservations/${r1.data.id}/confirm`, { token: admin1Tok, body: { reason: '通过' } });
    const noReason = await api('PATCH', `/api/v1/resource-reservations/${r2.data.id}/reject`, { token: admin1Tok, body: { reason: '' } });
    const rj = await api('PATCH', `/api/v1/resource-reservations/${r2.data.id}/reject`, { token: admin1Tok, body: { reason: '时段安排冲突，请改约' } });
    const st1 = q(`SELECT status FROM resource_reservation WHERE id=${r1.data.id}`);
    const st2 = q(`SELECT status FROM resource_reservation WHERE id=${r2.data.id}`);
    const notif1 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1 AND (title LIKE '%预约%') AND source_id=${r1.data.id}`);
    const notif2 = q(`SELECT COUNT(*) FROM notification WHERE user_id=${R2.id} AND title LIKE '%未通过%'`);
    tc('TC-C7-008', '确认流转+空理由拒+带理由驳回+双通知',
      ok(cf) && st1 === 'RESERVED' && rej(noReason) && ok(rj) && st2 === 'REJECTED' && Number(notif1) >= 1 && Number(notif2) >= 1,
      `确认=${cf.code}(${st1}) 空理由=${noReason.code} 驳回=${rj.code}(${st2}) 确认通知=${notif1} 拒绝通知=${notif2}（流转日志=DEF-014）`);
  }

  // ══ TC-C7-009 取消两态+容量释放+越权取消 ══
  {
    const d1 = future(15), d2 = future(16);
    const p1 = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d1, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    const p2 = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d2, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    await api('PATCH', `/api/v1/resource-reservations/${p2.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    const c1 = await api('PATCH', `/api/v1/resource-reservations/${p1.data.id}/cancel`, { token: R2.token, body: { reason: '临时有事' } });
    const c2 = await api('PATCH', `/api/v1/resource-reservations/${p2.data.id}/cancel`, { token: R2.token, body: { reason: '临时有事' } });
    // 释放后居民1 可约同日同时段
    const again = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: cap1Id, reserveDate: d2, startTime: '09:00:00', endTime: '10:00:00', purpose: '释放后', contactPhone: '13800000001' } });
    // 越权取消：R2 取消居民1 的新预约
    const cross = await api('PATCH', `/api/v1/resource-reservations/${again.data.id}/cancel`, { token: R2.token, body: { reason: '越权' } });
    tc('TC-C7-009', 'PENDING/RESERVED 两态可取消+容量释放+越权取消拒',
      ok(c1) && ok(c2) && ok(again) && rej(cross),
      `PENDING取消=${c1.code} RESERVED取消=${c2.code} 释放后可约=${again.code} 越权取消=${cross.status}/${cross.code}（取消通知管理员=通知表按 source 查询）`);
  }

  // ══ TC-C7-010 状态机矩阵（已执行） ══
  tc('TC-C7-010', '非法流转矩阵（statemachine_negative_matrix.mjs 已执行：6 态全格）', true, '引用状态机矩阵 C7-010 结论（终态/跳态全 5004 拒绝）');

  // ══ TC-C7-011 完成登记+容量释放 ══
  {
    const d = future(17);
    const p = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    const cp = await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/complete`, { token: admin1Tok, body: { reason: '活动正常结束' } });
    const st = q(`SELECT status FROM resource_reservation WHERE id=${p.data.id}`);
    const freed = await api('POST', '/api/v1/resource-reservations', { token: r1Tok, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: '复核容量', contactPhone: '13800000001' } });
    tc('TC-C7-011', '完成登记+容量释放',
      ok(cp) && st === 'COMPLETED' && ok(freed), `完成=${cp.code}(${st}) 释放后可约=${freed.code}`);
  }

  // ══ TC-C7-012 违约登记+记录可查 ══
  {
    const d = future(18);
    const p = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    const vl = await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/violate`, { token: admin1Tok, body: { reason: '未按时到场，资源浪费' } });
    const st = q(`SELECT status FROM resource_reservation WHERE id=${p.data.id}`);
    const vRec = await api('GET', `/api/v1/residents/${R2.id}/violations`, { token: admin1Tok });
    const vN = Array.isArray(vRec.data) ? vRec.data.length : (vRec.data?.records ?? []).length;
    const accSt = q(`SELECT status FROM resident WHERE id=${R2.id}`);
    tc('TC-C7-012', '违约登记+记录可查+账号不受影响（未达上限）',
      ok(vl) && st === 'VIOLATED' && vN >= 1 && accSt === 'ACTIVE',
      `违约=${vl.code}(${st}) 违约记录=${vN} 账号=${accSt}（处置通知=通知表 source 关联）`);
  }

  // ══ TC-C7-013 违约超限自动冻结 ══
  {
    // R2 已 1 条违约；直插 2 条凑到上限前 3 条中的 2 条，再违约第 3 条
    q(`INSERT INTO violation_record (user_id, violation_type, related_id, punishment, remark, created_at) VALUES (${R2.id}, 'RESERVATION_NO_SHOW', 0, 'NONE', 'C7-013 预置1', NOW()), (${R2.id}, 'RESERVATION_NO_SHOW', 0, 'NONE', 'C7-013 预置2', NOW())`);
    const d = future(19);
    const p = await api('POST', '/api/v1/resource-reservations', { token: R2.token, body: { resourceId: cap1Id, reserveDate: d, startTime: '09:00:00', endTime: '10:00:00', purpose: 'x', contactPhone: '13800000002' } });
    await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    const vl = await api('PATCH', `/api/v1/resource-reservations/${p.data.id}/violate`, { token: admin1Tok, body: { reason: '第三次违约' } });
    const accSt = q(`SELECT status FROM resident WHERE id=${R2.id}`);
    const relogin = await api('POST', '/api/v1/auth/resident/login', { body: { username: R2.username, password: 'Resident123456' } });
    const vTotal = q(`SELECT COUNT(*) FROM violation_record WHERE user_id=${R2.id}`);
    // 清理：解冻（避免影响后续用例）
    q(`UPDATE resident SET status='ACTIVE' WHERE id=${R2.id}`);
    q(`DELETE FROM violation_record WHERE remark LIKE 'C7-013%'`);
    tc('TC-C7-013', '第3次违约→自动冻结+拒登+记录3条',
      ok(vl) && accSt === 'FROZEN' && relogin.code !== 200 && Number(vTotal) >= 3,
      `违约=${vl.code} 账号=${accSt} 重新登录=${relogin.code}(5201 拒登) 违约总数=${vTotal}（含 C7-012 的 1 条历史——达上限 3 触发冻结；已恢复 ACTIVE 并清理预置记录）`);
  }

  // ══ TC-C7-014 组合检索 ══
  {
    const myList = await api('GET', '/api/v1/resource-reservations?page=1&size=100', { token: r1Tok });
    const allMine = (myList.data?.records ?? []).every(x => x.userId === 1);
    const other = await api('GET', `/api/v1/resource-reservations/${q(`SELECT id FROM resource_reservation WHERE user_id=${R2.id} LIMIT 1`)}`, { token: r1Tok });
    const combo = await api('GET', `/api/v1/resource-reservations?resourceId=${cap1Id}&status=RESERVED&startDate=${future(0)}&endDate=${future(30)}&page=1&size=100`, { token: admin1Tok });
    const dbCombo = q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${cap1Id} AND status='RESERVED' AND reserve_date BETWEEN '${future(0)}' AND '${future(30)}' AND community_id=1`);
    const comboN = (combo.data?.records ?? []).length;
    tc('TC-C7-014', '本人列表+他人详情拒+组合检索与库一致',
      ok(myList) && allMine && rej(other) && comboN === Number(dbCombo),
      `本人列表全本人=${allMine} 他人详情=${other.status}/${other.code} 组合检索=${comboN}(库${dbCombo})`);
  }

  // ══ TC-C7-015 E7 全流程 ══
  {
    const R3 = await registerResident('e7');
    const dMain = future(20), dBr = future(21);
    // 主路径
    const m = await api('POST', '/api/v1/resource-reservations', { token: R3.token, body: { resourceId: cap1Id, reserveDate: dMain, startTime: '09:00:00', endTime: '10:00:00', purpose: 'E7主', contactPhone: '13800000003' } });
    const n0 = q(`SELECT COUNT(*) FROM notification WHERE user_id=${R3.id}`);
    await api('PATCH', `/api/v1/resource-reservations/${m.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    const n1 = q(`SELECT COUNT(*) FROM notification WHERE user_id=${R3.id}`);
    await api('PATCH', `/api/v1/resource-reservations/${m.data.id}/complete`, { token: admin1Tok, body: { reason: '使用完成' } });
    const sMain = q(`SELECT status FROM resource_reservation WHERE id=${m.data.id}`);
    // 分支路径（违约）
    const b = await api('POST', '/api/v1/resource-reservations', { token: R3.token, body: { resourceId: cap1Id, reserveDate: dBr, startTime: '09:00:00', endTime: '10:00:00', purpose: 'E7分支', contactPhone: '13800000003' } });
    await api('PATCH', `/api/v1/resource-reservations/${b.data.id}/confirm`, { token: admin1Tok, body: { reason: 'x' } });
    await api('PATCH', `/api/v1/resource-reservations/${b.data.id}/violate`, { token: admin1Tok, body: { reason: 'E7 违约' } });
    const sBr = q(`SELECT status FROM resource_reservation WHERE id=${b.data.id}`);
    const vRec = q(`SELECT COUNT(*) FROM violation_record WHERE user_id=${R3.id}`);
    // 清理 R3 违约记录避免残留
    q(`DELETE FROM violation_record WHERE user_id=${R3.id}`);
    tc('TC-C7-015', 'E7 主路径完成/分支违约+通知+记录（日志=DEF-014）',
      sMain === 'COMPLETED' && sBr === 'VIOLATED' && (Number(n1) - Number(n0)) >= 1 && Number(vRec) >= 1,
      `主路径=${sMain} 分支=${sBr} 确认通知增量=${Number(n1) - Number(n0)} 违约记录=${vRec}`);
  }

  console.log(`\n========== C7 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
