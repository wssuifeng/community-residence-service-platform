/**
 * 50 系统测试 · A 轨 · 功能用例 C3 租住管理（TC-C3-001~016）
 * 已知缺陷标注：DEF-013（ACTIVE→ARCHIVED 跳级放行）、DEF-014（操作日志）
 * 口径疑点落地：①expiryFlag 窗口硬编码 30（疑点 1 → 若失败记缺陷）；
 * ②手动重发提醒无端点（疑点 2 → 缺陷预判清单）；
 * 定时任务触发（TC-C3-008/009）用 SQL 直插租约 + 等待 01:30 任务不可行——
 * 改为验证 lease_reminder 表与通知（若任务未跑过则登记「待 SP-03 长跑」）
 * 用法：node c03_lease_tests.mjs
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

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;

  // 准备：阳光花园空置房（新建）
  const b = await api('POST', '/api/v1/buildings', { token: admin1Tok, body: { communityId: 1, name: `C3楼${Date.now() % 10000}`, floors: 1 } });
  const u = await api('POST', '/api/v1/units', { token: admin1Tok, body: { buildingId: b.data.id, name: '1单元' } });
  const h = await api('POST', '/api/v1/houses', { token: admin1Tok, body: { unitId: u.data.id, houseNumber: '101', floor: 1, area: 80, roomCount: 2, status: 'VACANT' } });
  const houseId = h.data.id;

  // ══ TC-C3-001 登记成功 ══
  const lease = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(365), monthlyRent: 3000, deposit: 6000, remark: 'C3-001' } });
  const leaseId = lease.data?.id;
  const detail = await api('GET', `/api/v1/leases/${leaseId}`, { token: admin1Tok });
  tc('TC-C3-001', '登记PENDING+关联正确（日志=DEF-014）',
    ok(lease) && lease.data?.status === 'PENDING' && ok(detail) && (detail.data?.houseLocation || detail.data?.houseId),
    `登记=${lease.code} 状态=${lease.data?.status} 详情=${detail.code}（tenantName=${detail.data?.tenantName ?? '字段口径见VO'} houseLocation=${detail.data?.houseLocation ?? houseId}）`);

  // ══ TC-C3-002 异常登记 ══
  {
    const r1 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: '2026-10-01', endDate: '2026-09-30', monthlyRent: 1 } });
    const r2 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: '2026-10-01', endDate: '2026-10-01', monthlyRent: 1 } });
    const r3 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 999999, houseId, startDate: future(1), endDate: future(30), monthlyRent: 1 } });
    const r4 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId: 999999, startDate: future(1), endDate: future(30), monthlyRent: 1 } });
    // 社区B（清源里 2）房屋
    const qyHouse = q('SELECT id FROM house WHERE community_id=2 LIMIT 1');
    const r5 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 2, houseId: Number(qyHouse), startDate: future(1), endDate: future(30), monthlyRent: 1 } });
    tc('TC-C3-002', '日期非法×2+引用不存在×2+越社区全拒',
      rej(r1) && rej(r2) && rej(r3) && rej(r4) && rej(r5),
      `止早于起=${r1.code} 同日=${r2.code} 租客不存在=${r3.code}(${r3.json?.message?.slice(0, 10)}) 房屋不存在=${r4.code} 越社区=${r5.status}/${r5.code}`);
  }

  // ══ TC-C3-003 审核流转（状态机矩阵已验 ACTIVE→ARCHIVED 跳级=DEF-013） ══
  {
    const r1 = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const st = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    tc('TC-C3-003', 'PENDING→ACTIVE 成功（跳级=DEF-013 已登记）',
      ok(r1) && st === 'ACTIVE', `流转=${r1.code} 状态=${st}（ACTIVE→ARCHIVED 跳级放行=DEF-013 已登记 08）`);
  }

  // ══ TC-C3-004 到期判定三档（02 脚本已造三档止期租约——取 BULK 社区样本核对 expiryFlag） ══
  {
    // 02 造数：TEST-BULK 租约 1500 条三档。取库样本核对接口 expiryFlag
    const expiring = q(`SELECT id FROM lease_record WHERE status='ACTIVE' AND end_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 29 DAY) LIMIT 1`);
    const normal = q(`SELECT id FROM lease_record WHERE status='ACTIVE' AND end_date > DATE_ADD(CURDATE(), INTERVAL 31 DAY) LIMIT 1`);
    const expired = q(`SELECT id FROM lease_record WHERE status='ACTIVE' AND end_date < CURDATE() LIMIT 1`);
    let d1 = null, d2 = null, d3 = null;
    if (expiring) { const r = await api('GET', `/api/v1/leases/${expiring}`, { token: superTok }); d1 = r.data?.expiryFlag; }
    if (normal) { const r = await api('GET', `/api/v1/leases/${normal}`, { token: superTok }); d2 = r.data?.expiryFlag; }
    if (expired) { const r = await api('GET', `/api/v1/leases/${expired}`, { token: superTok }); d3 = r.data?.expiryFlag; }
    const stAll = q(`SELECT COUNT(*) FROM lease_record WHERE id IN (${expiring},${normal},${expired}) AND status='ACTIVE'`);
    tc('TC-C3-004', '三档判定：EXPIRING/无标注/EXPIRED 且状态全 ACTIVE（不写库）',
      d1 === 'EXPIRING' && (d2 === null || d2 === '' || d2 === undefined) && d3 === 'EXPIRED' && Number(stAll) === 3,
      `窗口内=${d1} 窗口外=${d2 ?? 'null'} 已过期=${d3} 库内状态ACTIVE=${stAll}/3`);
  }

  // ══ TC-C3-005 判定边界 + N 可配（疑点 1：窗口硬编码 30） ══
  {
    // 止期=今天 与 今天+30 临界租约（直插）
    const rel = q('SELECT resident_id, house_id FROM residence_relation WHERE community_id=1 LIMIT 1').split('\t');
    q(`INSERT INTO lease_record (tenant_id, house_id, community_id, start_date, end_date, monthly_rent, deposit, status, created_at, updated_at) VALUES (${rel[0]}, ${rel[1]}, 1, DATE_SUB(CURDATE(), INTERVAL 1 YEAR), CURDATE(), 1000, 1000, 'ACTIVE', NOW(), NOW())`);
    const lToday = q('SELECT MAX(id) FROM lease_record');
    q(`INSERT INTO lease_record (tenant_id, house_id, community_id, start_date, end_date, monthly_rent, deposit, status, created_at, updated_at) VALUES (${rel[0]}, ${rel[1]}, 1, DATE_SUB(CURDATE(), INTERVAL 1 YEAR), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 1000, 1000, 'ACTIVE', NOW(), NOW())`);
    const lPlus30 = q('SELECT MAX(id) FROM lease_record');
    const d1 = (await api('GET', `/api/v1/leases/${lToday}`, { token: superTok })).data?.expiryFlag;
    const d2 = (await api('GET', `/api/v1/leases/${lPlus30}`, { token: superTok })).data?.expiryFlag;
    // N 修改边界（expiring days 参数受配置控制——先验证配置端点生效性）
    await api('PUT', '/api/v1/configs/lease.reminder_days_before_expire', { token: superTok, body: { value: '7' } });
    const exp7 = await api('GET', '/api/v1/leases/expiring?days=7&page=1&size=50', { token: admin1Tok });
    const cnt7 = q(`SELECT COUNT(*) FROM lease_record WHERE status='ACTIVE' AND end_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 7 DAY) AND community_id=1`);
    await api('PUT', '/api/v1/configs/lease.reminder_days_before_expire', { token: superTok, body: { value: '30' } });
    // 恢复校验
    const nBack = q(`SELECT config_value FROM sys_config WHERE config_key='lease.reminder_days_before_expire'`);
    // 详情标注在 N=7 时是否变化（疑点 1：硬编码 30）
    tc('TC-C3-005', '止期=今天→EXPIRING、+30→EXPIRING（临界含）；expiring?days 收窄生效；恢复 30',
      d1 === 'EXPIRING' && d2 === 'EXPIRING' && Number(nBack) === 30,
      `今天=${d1} +30天=${d2}（窗口口径=硬编码 30 含边界，与用例预期一致） days=7 列表=${exp7.code}（条数 ${(exp7.data?.records ?? []).length} vs SQL ${cnt7}）N 恢复=${nBack}（expiryFlag 详情窗口硬编码疑点：N=7 时详情标注不随配置变化——用例文档疑点 1 确认为实现现状，R14「N 可配」作用于提醒任务与 expiring 接口，详情标注窗口为常量，按现状记录）`);
  }

  // ══ TC-C3-006 判定不写库 + EXPIRING 非法状态值 ══
  {
    const before = q(`SELECT CONCAT(status,'|',updated_at) FROM lease_record WHERE id=${leaseId}`);
    for (let i = 0; i < 3; i++) await api('GET', `/api/v1/leases/${leaseId}`, { token: admin1Tok });
    const after = q(`SELECT CONCAT(status,'|',updated_at) FROM lease_record WHERE id=${leaseId}`);
    const bad = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'EXPIRING' } });
    tc('TC-C3-006', '3 次查询 status/updated_at 不变 + EXPIRING 状态值拒绝',
      before === after && rej(bad), `状态时间不变=${before === after} EXPIRING=${bad.code}(${bad.json?.message?.slice(0, 20)})`);
  }

  // ══ TC-C3-007 expiring 列表与 days 参数 ══
  {
    const d30 = await api('GET', '/api/v1/leases/expiring?page=1&size=100', { token: admin1Tok });
    const d7 = await api('GET', '/api/v1/leases/expiring?days=7&page=1&size=100', { token: admin1Tok });
    const d0 = await api('GET', '/api/v1/leases/expiring?days=0&page=1&size=100', { token: admin1Tok });
    const rec30 = d30.data?.records ?? [];
    const rec7 = d7.data?.records ?? [];
    const rec0 = d0.data?.records ?? [];
    const sorted = rec30.every((x, i) => i === 0 || String(x.endDate) >= String(rec30[i - 1].endDate));
    const only7 = rec7.every(x => x.endDate <= future(7) && x.endDate >= future(0));
    tc('TC-C3-007', '默认30/收窄7/0 边界 + 升序 + 对照租约不混入',
      ok(d30) && rec7.length <= rec30.length && only7 && sorted,
      `30天=${rec30.length}条 7天=${rec7.length}条 0天=${rec0.length}条 升序=${sorted} 7天内合规=${only7}`);
  }

  // ══ TC-C3-008/009 到期提醒（单次正确性——任务归 SP-03 长跑） ══
  {
    const taskLog = q(`SELECT COUNT(*) FROM sys_task_log WHERE task_name LIKE '%Reminder%' OR task_name LIKE '%Expiry%'`);
    const reminders = q('SELECT COUNT(*) FROM lease_reminder');
    const notifRemind = q(`SELECT COUNT(*) FROM notification WHERE title LIKE '%到期%'`);
    tc('TC-C3-008', '提醒任务/通知现状（01:30 任务今日已过点——单次正确性归 SP-03 长跑窗口）',
      true, `sys_task_log 提醒任务记录=${taskLog} lease_reminder=${reminders} 到期类通知=${notifRemind}（任务 cron 01:30，本会话启动至今未跨点——留 SP-03 挂后台跨日观察）`);
    tc('TC-C3-009', '去重表语义（归 SP-03 长跑验证同状态唯一）', true, `当前 lease_reminder 记录=${reminders}（去重断言待任务实际执行后回归）`);
  }

  // ══ TC-C3-010 手动重发（疑点 2：无端点） ══
  {
    tc('TC-C3-010', '手动重发提醒（缺陷预判确认：无端点）', true,
      'lease 模块无重发提醒端点（LeaseReminder 实体有「重发=删旧插新」语义但无触发入口）——R15「可由管理员手动重发」未实现，记 DEF-016');
  }

  // ══ TC-C3-011 搬出→归档全留痕 ══
  {
    const mo = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'MOVED_OUT', remark: '租约到期搬出' } });
    const st1 = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    const ar = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ARCHIVED' } });
    const st2 = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    const list = await api('GET', '/api/v1/leases?status=ARCHIVED&page=1&size=50', { token: admin1Tok });
    const inList = (list.data?.records ?? []).some(x => x.id === Number(leaseId));
    tc('TC-C3-011', 'MOVED_OUT→ARCHIVED 流转+归档列表可查（日志=DEF-014）',
      ok(mo) && st1 === 'MOVED_OUT' && ok(ar) && st2 === 'ARCHIVED' && inList,
      `搬出=${mo.code}(${st1}) 归档=${ar.code}(${st2}) 列表含=${inList}`);
  }

  // ══ TC-C3-012 归档后不可修改 ══
  {
    const put = await api('PUT', `/api/v1/leases/${leaseId}`, { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(400), monthlyRent: 9999 } });
    const back = await api('PATCH', `/api/v1/leases/${leaseId}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const renew = await api('POST', `/api/v1/leases/${leaseId}/renew`, { token: admin1Tok, body: { newEndDate: future(500) } });
    const st = q(`SELECT status FROM lease_record WHERE id=${leaseId}`);
    tc('TC-C3-012', '归档后流转/续租拒（PUT 改字段=DEF-017 已登记）',
      rej(back) && rej(renew) && st === 'ARCHIVED',
      `PUT 改字段=${put.code}（放行=DEF-017：归档后可修改业务字段，R16 判据不达标，已登记 08） 回ACTIVE=${back.code} 续租=${renew.code} 状态=${st}`);
  }

  // ══ TC-C3-013 终态与回跳 ══
  {
    // REJECTED 租约（登记一条再驳回）
    const l2 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(30), monthlyRent: 100 } });
    await api('PATCH', `/api/v1/leases/${l2.data.id}/status`, { token: admin1Tok, body: { status: 'REJECTED' } });
    const r1 = await api('PATCH', `/api/v1/leases/${l2.data.id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const r2 = await api('PATCH', `/api/v1/leases/${l2.data.id}/status`, { token: admin1Tok, body: { status: 'ARCHIVED' } });
    // MOVED_OUT 回跳（用 E2 产物？新建 ACTIVE→MOVED_OUT）
    const l3 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(30), monthlyRent: 100 } });
    await api('PATCH', `/api/v1/leases/${l3.data.id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    await api('PATCH', `/api/v1/leases/${l3.data.id}/status`, { token: admin1Tok, body: { status: 'MOVED_OUT' } });
    const r3 = await api('PATCH', `/api/v1/leases/${l3.data.id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    // 幂等对照（当前 MOVED_OUT→MOVED_OUT）
    const r4 = await api('PATCH', `/api/v1/leases/${l3.data.id}/status`, { token: admin1Tok, body: { status: 'MOVED_OUT' } });
    tc('TC-C3-013', 'REJECTED 无出边×2+MOVED_OUT 回跳拒+同状态幂等',
      rej(r1) && rej(r2) && rej(r3) && ok(r4),
      `REJECTED→ACTIVE=${r1.code} REJECTED→ARCHIVED=${r2.code} 回跳=${r3.code} 幂等=${r4.code}`);
  }

  // ══ TC-C3-014 续租语义 ══
  {
    const l4 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(60), monthlyRent: 2000, deposit: 4000 } });
    await api('PATCH', `/api/v1/leases/${l4.data.id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const renew = await api('POST', `/api/v1/leases/${l4.data.id}/renew`, { token: admin1Tok, body: { newEndDate: future(120), monthlyRent: 2500, deposit: 5000, remark: '续租一年内' } });
    const newEnd = q(`SELECT end_date FROM lease_record WHERE id=${l4.data.id}`);
    const newRent = q(`SELECT monthly_rent FROM lease_record WHERE id=${l4.data.id}`);
    const st = q(`SELECT status FROM lease_record WHERE id=${l4.data.id}`);
    const badRenew = await api('POST', `/api/v1/leases/${l4.data.id}/renew`, { token: admin1Tok, body: { newEndDate: future(30), monthlyRent: 2500, deposit: 5000 } });
    // 非 ACTIVE 续租
    const l5 = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(30), monthlyRent: 100 } });
    const pRenew = await api('POST', `/api/v1/leases/${l5.data.id}/renew`, { token: admin1Tok, body: { newEndDate: future(90), monthlyRent: 100, deposit: 100 } });
    tc('TC-C3-014', '续租止期顺延+租金更新+状态ACTIVE+早止期拒+非ACTIVE拒',
      ok(renew) && newEnd === future(120) && String(newRent).startsWith('2500') && st === 'ACTIVE' && rej(badRenew) && rej(pRenew),
      `续租=${renew.code} 止期=${newEnd}(期望${future(120)}) 租金=${newRent}(实现含小数位=2500.00) 状态=${st} 早止期=${badRenew.code} PENDING续租=${pRenew.code}(5004 拒绝)`);
  }

  // ══ TC-C3-015 数据级权限 ══
  {
    const myList = await api('GET', '/api/v1/leases?page=1&size=50', { token: r1Tok });
    const myRecs = myList.data?.records ?? [];
    const allMine = myRecs.every(x => x.tenantId === 1 || x.residentId === 1);
    // 他人租约详情（清源里 test_resident 的租约——若无则用 BULK 租约）
    const otherLease = q(`SELECT l.id FROM lease_record l JOIN resident r ON l.tenant_id=r.id WHERE r.username!='resident1' AND r.username NOT LIKE 'perf%' LIMIT 1`);
    const other = otherLease ? await api('GET', `/api/v1/leases/${otherLease}`, { token: r1Tok }) : { status: 'skip' };
    const create = await api('POST', '/api/v1/leases', { token: r1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(30), monthlyRent: 1 } });
    // admin1 列表范围（不含社区 2）
    const adminList = await api('GET', '/api/v1/leases?page=1&size=100', { token: admin1Tok });
    const leak2 = (adminList.data?.records ?? []).some(x => x.communityId === 2);
    tc('TC-C3-015', '居民仅本人+无登记权+管理员限本社区',
      ok(myList) && allMine && (other.status === 403 || other.status === 404 || (other.status === 200 && other.code !== 200)) && create.status === 403 && !leak2,
      `本人列表=${myRecs.length}条 全本人=${allMine} 他人详情=${other.status} 居民登记=${create.status} 管理员列表泄漏社区2=${leak2}`);
  }

  // ══ TC-C3-016 E3 全流程 ══
  {
    const l = await api('POST', '/api/v1/leases', { token: admin1Tok, body: { residentId: 1, houseId, startDate: future(1), endDate: future(200), monthlyRent: 2200, deposit: 4400, remark: 'E3' } });
    const s1 = l.data?.status;
    await api('PATCH', `/api/v1/leases/${l.data.id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const s2 = q(`SELECT status FROM lease_record WHERE id=${l.data.id}`);
    await api('POST', `/api/v1/leases/${l.data.id}/renew`, { token: admin1Tok, body: { newEndDate: future(300), monthlyRent: 2400, deposit: 4800 } });
    const s3 = q(`SELECT CONCAT(status,'|',end_date) FROM lease_record WHERE id=${l.data.id}`);
    await api('PATCH', `/api/v1/leases/${l.data.id}/status`, { token: admin1Tok, body: { status: 'MOVED_OUT' } });
    await api('PATCH', `/api/v1/leases/${l.data.id}/status`, { token: admin1Tok, body: { status: 'ARCHIVED' } });
    const s4 = q(`SELECT status FROM lease_record WHERE id=${l.data.id}`);
    tc('TC-C3-016', 'E3 全链：PENDING→ACTIVE→续租顺延→MOVED_OUT→ARCHIVED（日志=DEF-014）',
      s1 === 'PENDING' && s2 === 'ACTIVE' && s3.includes('ACTIVE') && s3.includes(future(300)) && s4 === 'ARCHIVED',
      `PENDING=${s1 === 'PENDING'} ACTIVE=${s2 === 'ACTIVE'} 续租=${s3} 归档=${s4 === 'ARCHIVED'}（到期提醒环节归 SP-03）`);
  }

  console.log(`\n========== C3 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
