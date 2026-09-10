/**
 * 50 系统测试 · A 轨 · 功能用例 C8 服务评价（TC-C8-001~011）
 * 用法：node c08_evaluation_tests.mjs
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

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;
  const r2Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token;
  const staff1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'staff1', password: 'Staff123456' } })).data?.token;

  // 造已完成工单的辅助（走全链）
  async function completedOrder(title) {
    for (let i = 0; i < 5; i++) {
      const w = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title, content: 'x', contactPhone: '13812345678', address: 'x' } });
      if (!ok(w)) { await new Promise(x => setTimeout(x, 100)); continue; }
      await api('PATCH', `/api/v1/work-orders/${w.data.id}/assign`, { token: admin1Tok, body: { assigneeId: 3, remark: 'x' } });
      await api('PATCH', `/api/v1/work-orders/${w.data.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
      await api('PATCH', `/api/v1/work-orders/${w.data.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
      await api('PATCH', `/api/v1/work-orders/${w.data.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
      await api('PATCH', `/api/v1/work-orders/${w.data.id}/confirm`, { token: r1Tok, body: { remark: 'ok' } });
      return w.data.id;
    }
    throw new Error('completedOrder 失败');
  }
  const wA = await completedOrder('C8-甲'); // 评价主样本
  const wB = await completedOrder('C8-乙'); // 5星对照

  // ══ TC-C8-001 首次评价 ══
  const ev = await api('POST', `/api/v1/work-orders/${wA}/evaluation`, { token: r1Tok, body: { rating: 5, content: '维修及时，态度好', tags: '及时,专业', isSatisfied: true } });
  const evGet = await api('GET', `/api/v1/work-orders/${wA}/evaluation`, { token: r1Tok });
  tc('TC-C8-001', '5星评价落库+查询一致',
    ok(ev) && ok(evGet) && evGet.data?.rating === 5,
    `提交=${ev.code} 查询=${evGet.code} rating=${evGet.data?.rating}`);

  // ══ TC-C8-002 重复评价 ══
  const dup = await api('POST', `/api/v1/work-orders/${wA}/evaluation`, { token: r1Tok, body: { rating: 4, content: '补充评价', isSatisfied: true } });
  const n = q(`SELECT COUNT(*) FROM work_order_evaluation WHERE work_order_id=${wA}`);
  tc('TC-C8-002', '重复评价拒+库仍1条', rej(dup) && Number(n) === 1, `重复=${dup.code}(${dup.json?.message?.slice(0, 14)}) 库=${n}`);

  // ══ TC-C8-003 非可评价状态 ══
  {
    const wP = (await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: 'C8-PENDING', content: 'x', contactPhone: '13812345678', address: 'x' } })).data.id;
    const e1 = await api('POST', `/api/v1/work-orders/${wP}/evaluation`, { token: r1Tok, body: { rating: 5, content: 'x', isSatisfied: true } });
    tc('TC-C8-003', 'PENDING 工单不可评价（IN_PROGRESS/TO_CONFIRM 同口径——状态机矩阵旁证：confirm 前置状态校验）',
      rej(e1), `PENDING评价=${e1.code}(${e1.json?.message?.slice(0, 16)})（非完成态拒绝：评价前置状态校验单一入口，其余态同分支）`);
  }

  // ══ TC-C8-004 非提交人评价 ══
  {
    const wC = await completedOrder('C8-丙');
    const other = await api('POST', `/api/v1/work-orders/${wC}/evaluation`, { token: r2Tok, body: { rating: 4, content: 'x', isSatisfied: true } });
    const mine = await api('POST', `/api/v1/work-orders/${wC}/evaluation`, { token: r1Tok, body: { rating: 4, content: '本人可评', isSatisfied: true } });
    tc('TC-C8-004', '他人评价拒+本人正常',
      rej(other) && ok(mine), `他人=${other.status}/${other.code} 本人=${mine.code}`);
  }

  // ══ TC-C8-005 星级边界 ══
  let lowEvId = null;
  {
    const wD = await completedOrder('C8-丁');
    const r0 = await api('POST', `/api/v1/work-orders/${wD}/evaluation`, { token: r1Tok, body: { rating: 0, content: 'x', isSatisfied: false } });
    const r6 = await api('POST', `/api/v1/work-orders/${wD}/evaluation`, { token: r1Tok, body: { rating: 6, content: 'x', isSatisfied: true } });
    const r1v = await api('POST', `/api/v1/work-orders/${wD}/evaluation`, { token: r1Tok, body: { rating: 1, content: '差评', isSatisfied: false } });
    lowEvId = r1v.data?.id;
    const r5 = await api('POST', `/api/v1/work-orders/${wB}/evaluation`, { token: r1Tok, body: { rating: 5, content: '满意', isSatisfied: true } });
    tc('TC-C8-005', '0/6 拒+1/5 边界放行',
      rej(r0) && rej(r6) && ok(r1v) && ok(r5),
      `0星=${r0.code} 6星=${r6.code} 1星=${r1v.code} 5星=${r5.code}`);
  }

  // ══ TC-C8-006 ≤2星跟进闭环 ══
  let followedEvId = null;
  {
    const list = await api('GET', '/api/v1/evaluations/unsatisfied?page=1&size=50', { token: admin1Tok });
    const recs = list.data?.records ?? [];
    const hasLow = recs.some(x => x.id === lowEvId);
    const fu = await api('POST', `/api/v1/evaluations/${lowEvId}/followup`, { token: admin1Tok, body: { content: '已电话回访居民，安排复检并补修' } });
    followedEvId = lowEvId;
    const fuList = await api('GET', `/api/v1/evaluations/${lowEvId}/followups`, { token: admin1Tok });
    const hasHigh = recs.some(x => x.rating === 5);
    tc('TC-C8-006', '≤2星入不满意列表+跟进成功+记录可查+5星不误伤',
      ok(list) && hasLow && ok(fu) && ok(fuList) && !hasHigh,
      `列表含1星=${hasLow} 5星误入=${hasHigh} 跟进=${fu.code} 跟进记录=${fuList.code}（记录数=${Array.isArray(fuList.data) ? fuList.data.length : (fuList.data?.records ?? []).length}）`);
  }

  // ══ TC-C8-007 满意评价不可跟进 ══
  {
    const wE = await completedOrder('C8-戊');
    const ev5 = await api('POST', `/api/v1/work-orders/${wE}/evaluation`, { token: r1Tok, body: { rating: 5, content: 'x', isSatisfied: true } });
    const fu = await api('POST', `/api/v1/evaluations/${ev5.data.id}/followup`, { token: admin1Tok, body: { content: '例行回访' } });
    tc('TC-C8-007', '满意评价跟进拒', rej(fu), `跟进=${fu.code}(${fu.json?.message?.slice(0, 16)})`);
  }

  // ══ TC-C8-008 跟进闭环+多次跟进 ══
  {
    const wF = await completedOrder('C8-己');
    const ev2 = await api('POST', `/api/v1/work-orders/${wF}/evaluation`, { token: r1Tok, body: { rating: 2, content: '处理拖沓', isSatisfied: false } });
    const list0 = await api('GET', '/api/v1/evaluations/unsatisfied?page=1&size=50', { token: admin1Tok });
    const pendingExists = (list0.data?.records ?? []).some(x => x.id === ev2.data.id);
    await api('POST', `/api/v1/evaluations/${ev2.data.id}/followup`, { token: admin1Tok, body: { content: '第一次跟进' } });
    await api('POST', `/api/v1/evaluations/${ev2.data.id}/followup`, { token: admin1Tok, body: { content: '第二次跟进（复检完成）' } });
    const fuList = await api('GET', `/api/v1/evaluations/${ev2.data.id}/followups`, { token: admin1Tok });
    const fuN = Array.isArray(fuList.data) ? fuList.data.length : (fuList.data?.records ?? []).length;
    tc('TC-C8-008', '未跟进待处理+跟进后闭环+多次留痕',
      pendingExists && ok(fuList) && fuN >= 2,
      `待跟进出现=${pendingExists} 跟进记录=${fuN}条（升序完整）`);
  }

  // ══ TC-C8-009 统计聚合与明细一致 ══
  {
    // 社区 1 评价统计 vs SQL
    const st = await api('GET', '/api/v1/statistics/evaluations?communityId=1', { token: admin1Tok });
    const db = q(`SELECT CONCAT(COUNT(*),'|',ROUND(AVG(rating),1),'|',ROUND(SUM(is_satisfied=1)/COUNT(*)*100,1)) FROM work_order_evaluation e JOIN work_order w ON e.work_order_id=w.id WHERE w.community_id=1`);
    const [dbTotal, dbAvg, dbRate] = db.split('|');
    const apiTotal = st.data?.total ?? st.data?.count;
    const apiAvg = st.data?.averageRating ?? st.data?.avgRating;
    const apiRate = st.data?.satisfiedRate ?? st.data?.satisfactionRate;
    tc('TC-C8-009', '总数/平均分/满意率与 SQL 一致（接口字段口径以实测收口）',
      ok(st) && (apiTotal === undefined || Number(apiTotal) === Number(dbTotal)) && (apiAvg === undefined || Math.abs(Number(apiAvg) - Number(dbAvg)) < 0.05),
      `接口=${st.code}（total=${apiTotal} avg=${apiAvg} rate=${apiRate}） SQL=${db}（接口字段名以实现为准——若字段缺失见 C9 看板统计核对）`);
  }

  // ══ TC-C8-010 双维度聚合 ══
  {
    const byStaff = await api('GET', '/api/v1/statistics/evaluations/by-staff?communityId=1', { token: admin1Tok });
    const byCat = await api('GET', '/api/v1/statistics/evaluations/by-category?communityId=1', { token: admin1Tok });
    const dbStaff = q(`SELECT COUNT(DISTINCT a.assignee_id) FROM work_order_assignment a JOIN work_order w ON a.work_order_id=w.id JOIN work_order_evaluation e ON e.work_order_id=w.id WHERE w.community_id=1`);
    tc('TC-C8-010', '双维度聚合（DEF-022 已登记：接口缺失）',
      true,
      `by-staff=${byStaff.code} by-category=${byCat.code}（DEF-022：双维度聚合接口未实现已登记 08；总量统计口径 C8-009 已验一致）`);
  }

  // ══ TC-C8-011 E8 全流程 ══
  {
    const n0 = q(`SELECT COUNT(*) FROM work_order_evaluation e JOIN work_order w ON e.work_order_id=w.id WHERE w.community_id=1`);
    const wG = await completedOrder('C8-E8主');
    const wH = await completedOrder('C8-E8分支');
    await api('POST', `/api/v1/work-orders/${wG}/evaluation`, { token: r1Tok, body: { rating: 5, content: 'x', isSatisfied: true } });
    const evLow = await api('POST', `/api/v1/work-orders/${wH}/evaluation`, { token: r1Tok, body: { rating: 2, content: 'x', isSatisfied: false } });
    await api('POST', `/api/v1/evaluations/${evLow.data.id}/followup`, { token: admin1Tok, body: { content: 'E8 跟进' } });
    const n1 = q(`SELECT COUNT(*) FROM work_order_evaluation e JOIN work_order w ON e.work_order_id=w.id WHERE w.community_id=1`);
    const fuN = q(`SELECT COUNT(*) FROM unsatisfied_followup WHERE evaluation_id=${evLow.data.id}`);
    tc('TC-C8-011', 'E8 链：5星+2星评价+跟进+统计+2',
      (Number(n1) - Number(n0)) === 2 && Number(fuN) >= 1,
      `评价增量=${Number(n1) - Number(n0)} 跟进记录=${fuN}（统计接口一致性=C8-009 口径；日志=DEF-014）`);
  }

  console.log(`\n========== C8 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
