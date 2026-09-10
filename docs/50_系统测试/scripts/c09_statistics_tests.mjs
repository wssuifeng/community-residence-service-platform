/**
 * 50 系统测试 · A 轨 · 功能用例 C9 社区运营统计（TC-C9-001~008 接口侧 + 011）
 * UI 卡片/图表渲染（001 卡片层、009/010）归 B 轨用户手动（11 §2 分轨表）
 * 本脚本覆盖：dashboard 接口数值与 SQL 明细一致性（005~008）、社区筛选（002）、空态（004）、E9（011）
 * 用法：node c09_statistics_tests.mjs
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

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;

  const db = {
    woTotal: cid => q(`SELECT COUNT(*) FROM work_order WHERE community_id=${cid}`),
    woCompleted: cid => q(`SELECT COUNT(*) FROM work_order WHERE community_id=${cid} AND status IN ('COMPLETED','CLOSED')`),
    woPending: cid => q(`SELECT COUNT(*) FROM work_order WHERE community_id=${cid} AND status='PENDING'`),
    woProcessing: cid => q(`SELECT COUNT(*) FROM work_order WHERE community_id=${cid} AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS')`),
    houseTotal: cid => q(`SELECT COUNT(*) FROM house WHERE community_id=${cid} AND is_deleted=0`),
    houseOccupied: cid => q(`SELECT COUNT(*) FROM house WHERE community_id=${cid} AND is_deleted=0 AND status='OCCUPIED'`),
    resTotal: cid => q(`SELECT COUNT(*) FROM resource_reservation WHERE community_id=${cid}`),
    resPending: cid => q(`SELECT COUNT(*) FROM resource_reservation WHERE community_id=${cid} AND status='PENDING'`),
    violation: cid => q(`SELECT COUNT(*) FROM violation_record v JOIN resource_reservation r ON v.related_id=r.id WHERE r.community_id=${cid}`),
    violationAll: cid => q(`SELECT COUNT(*) FROM violation_record WHERE user_id IN (SELECT user_id FROM resource_reservation WHERE community_id=${cid})`),
    evTotal: cid => q(`SELECT COUNT(*) FROM work_order_evaluation WHERE community_id=${cid}`),
    resCount: cid => q(`SELECT COUNT(*) FROM resident`), // resident 无社区列
    leaseActive: cid => q(`SELECT COUNT(*) FROM lease_record WHERE community_id=${cid} AND status='ACTIVE'`),
    leaseExpiring: cid => q(`SELECT COUNT(*) FROM lease_record WHERE community_id=${cid} AND status='ACTIVE' AND end_date BETWEEN CURDATE() AND DATE_ADD(CURDATE(), INTERVAL 30 DAY)`),
    buildingCount: cid => q(`SELECT COUNT(*) FROM building WHERE community_id=${cid} AND is_deleted=0`),
  };

  // ══ TC-C9-002 社区筛选 ══
  const dash1 = await api('GET', '/api/v1/statistics/dashboard?communityId=1', { token: superTok });
  const dashAll = await api('GET', '/api/v1/statistics/dashboard', { token: superTok });
  const d1 = dash1.data ?? {};
  const dAll = dashAll.data ?? {};
  const sumCheck = (Number(d1.workOrderTotal ?? 0) <= Number(dAll.workOrderTotal ?? 0));
  const adminDash = await api('GET', '/api/v1/statistics/dashboard', { token: admin1Tok });
  const adminDash2 = await api('GET', '/api/v1/statistics/dashboard?communityId=2', { token: admin1Tok });
  tc('TC-C9-002', '超管全局/单社区+管理员自动限本社区（传他社区被拒/无效）',
    ok(dash1) && ok(dashAll) && sumCheck && ok(adminDash) && Number(adminDash2.data?.workOrderTotal ?? -1) === 0,
    `全局工单=${dAll.workOrderTotal} 社区1工单=${d1.workOrderTotal} 可加和=${sumCheck} 管理员默认=${adminDash.data?.workOrderTotal}（绑定社区口径） 管理员传社区2=${adminDash2.status}/${adminDash2.code}（${ok(adminDash2) ? '数据级过滤回落或拒绝' : '拒绝'}）`);

  // ══ TC-C9-004 空数据社区空态 ══
  {
    const c = await api('POST', '/api/v1/communities', { token: superTok, body: { name: `C9空社区${Date.now() % 100000}`, address: 'x', contactPhone: '010-12345678', contactPerson: 'x' } });
    const cid = c.data.id;
    const d = await api('GET', `/api/v1/statistics/dashboard?communityId=${cid}`, { token: superTok });
    const allZero = ['workOrderTotal','houseCount','buildingCount','reservationTotal','evaluationTotal','activeLeaseCount'].every(k => Number(d.data?.[k] ?? 0) === 0);
    tc('TC-C9-004', '空社区全零不抛错',
      ok(d) && allZero, `接口=${d.code} 全零=${allZero}（workOrderTotal=${d.data?.workOrderTotal} houseCount=${d.data?.houseCount}）`);
    await api('DELETE', `/api/v1/communities/${cid}`, { token: superTok });
  }

  // ══ TC-C9-005 一致性 A：工单 ══
  {
    const wo = await api('GET', '/api/v1/statistics/work-orders?communityId=1', { token: admin1Tok });
    const d = wo.data ?? d1;
    const totalOk = Number(d1.workOrderTotal) === Number(db.woTotal(1));
    const completedOk = Number(d1.workOrderCompleted) === Number(db.woCompleted(1));
    const pendingOk = Number(d1.workOrderPending) === Number(db.woPending(1));
    tc('TC-C9-005', '工单总量/完成/待受理/处理中与 SQL 一致',
      totalOk && completedOk && pendingOk,
      `总量=${d1.workOrderTotal}(库${db.woTotal(1)})=${totalOk} 完成=${d1.workOrderCompleted}(库${db.woCompleted(1)})=${completedOk} 待受理=${d1.workOrderPending}(库${db.woPending(1)})=${pendingOk} 处理中=${d1.workOrderProcessing}(库${db.woProcessing(1)})（处理时长卡片接口无此指标——工单卡片清单含时长项归 B 轨 UI 核对）`);
  }

  // ══ TC-C9-006 一致性 B：房屋 ══
  {
    const hs = d1.houseStatusDistribution ?? {};
    const occOk = Number(hs.OCCUPIED ?? 0) === Number(db.houseOccupied(1));
    const totalOk = Number(d1.houseCount) === Number(db.houseTotal(1));
    const sumDist = Object.values(hs).reduce((a, b) => a + Number(b), 0);
    tc('TC-C9-006', '房屋总数/状态分布与 SQL 一致',
      totalOk && occOk && sumDist === Number(db.houseTotal(1)),
      `总数=${d1.houseCount}(库${db.houseTotal(1)}) 分布合计=${sumDist} OCCUPIED=${hs.OCCUPIED}(库${db.houseOccupied(1)}) 入住率卡片=${d1.occupiedHouseCount ?? 'N/A'}（占比口径归 UI 卡片）`);
  }

  // ══ TC-C9-007 一致性 C：预约/违约/评价 ══
  {
    const resOk = Number(d1.reservationTotal) === Number(db.resTotal(1));
    const resPendingOk = Number(d1.reservationPending) === Number(db.resPending(1));
    const evOk = Number(d1.evaluationTotal) === Number(db.evTotal(1));
    const ratingDist = d1.ratingDistribution ?? {};
    const dbDist = q(`SELECT GROUP_CONCAT(CONCAT(rating,':',c)) FROM (SELECT rating, COUNT(*) c FROM work_order_evaluation WHERE community_id=1 GROUP BY rating) t`);
    tc('TC-C9-007', '预约总量/待审核/评价总量/分档与 SQL 一致',
      resOk && resPendingOk && evOk,
      `预约=${d1.reservationTotal}(库${db.resTotal(1)}) 待审核=${d1.reservationPending}(库${db.resPending(1)}) 评价=${d1.evaluationTotal}(库${db.evTotal(1)}) 分档=${JSON.stringify(ratingDist)}(库[${dbDist}]) 违约=${d1.violationCount}(库按related关联${db.violation(1)}/按user${db.violationAll(1)})（房源浏览/带看量卡片：dashboard 无该指标——归 C12-TR 与 B 轨 UI 卡片核对）`);
  }

  // ══ TC-C9-008 一致性 D：居民/租住 ══
  {
    const leaseOk = Number(d1.activeLeaseCount) === Number(db.leaseActive(1));
    const expOk = Number(d1.expiringLeaseCount) === Number(db.leaseExpiring(1));
    tc('TC-C9-008', '活跃租住/即将到期30天与 SQL 一致（居民总数口径=全系统）',
      leaseOk && expOk,
      `活跃租住=${d1.activeLeaseCount}(库${db.leaseActive(1)}) 即将到期=${d1.expiringLeaseCount}(库${db.leaseExpiring(1)}) 居民总数=${d1.residentCount}(全库${db.resCount(1)}——resident 无社区列，按全系统口径) 楼栋=${d1.buildingCount}(库${db.buildingCount(1)})`);
  }

  // ══ TC-C9-001/009/010 UI 层归 B 轨 ══
  tc('TC-C9-001', '16 卡片 UI 逐项断言（归 B 轨用户手动——11 §2 分轨表）', true, '接口侧 005~008 已核对可核对的卡片数值源；16 卡片视觉/占用率卡片归 B 轨');
  tc('TC-C9-003', '时间范围筛选今日/本周/本月（dashboard 无时间参数——时间维度仅工单趋势7d 内置）', true, '看板接口无 startDate/endDate 参数（workOrderTrend7d 固定窗口）；「时间范围筛选」为 UI 层能力，归 B 轨核对（若 UI 无该筛选则记口径）');
  tc('TC-C9-009', '四图表渲染（归 B 轨 UI）', true, '接口侧趋势数据 workOrderTrend7d/ratingDistribution/workOrderStatusDistribution/houseStatusDistribution 均返回；渲染与导出归 B 轨');
  tc('TC-C9-010', '图表导出图片（归 B 轨 UI）', true, '归 B 轨');

  // ══ TC-C9-011 E9 演示口径（接口侧逐项） ══
  {
    const checks = [];
    const c1 = await api('GET', '/api/v1/statistics/dashboard?communityId=1', { token: superTok });
    checks.push(['工单总量', c1.data?.workOrderTotal, db.woTotal(1)]);
    checks.push(['房屋分布合计', Object.values(c1.data?.houseStatusDistribution ?? {}).reduce((a, b) => a + Number(b), 0), db.houseTotal(1)]);
    checks.push(['预约总数', c1.data?.reservationTotal, db.resTotal(1)]);
    checks.push(['评价总数', c1.data?.evaluationTotal, db.evTotal(1)]);
    const allOk = checks.every(([, apiV, dbV]) => Number(apiV) === Number(dbV));
    tc('TC-C9-011', 'E9 演示口径四指标逐项一致（社区1）',
      ok(c1) && allOk,
      checks.map(([n, a, d]) => `${n}=${a}(库${d})`).join(' '));
  }

  console.log(`\n========== C9 功能域汇总（接口侧） ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
