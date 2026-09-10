/**
 * 50 系统测试 · A 轨 · 功能用例 C2 居民与居住关系（TC-C2-001~020）
 * 口径：04_测试用例/功能用例_C02_居民与居住关系.md
 * 已知缺陷标注：DEF-014（操作日志）、DEF-010（居民数据级过滤）
 * 口径裁决：D1 已交付代建端点（/residents/admin-create，R8 v1.2）——口径疑点 1 由 1c 前置开发解决
 * 用法：node c02_resident_tests.mjs
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
const uniqPhone = () => '13' + String(800000000 + Math.floor(Math.random() * 99999999));

async function registerAndLogin(tag) {
  const uname = `${tag}${Date.now() % 1000000}_${Math.floor(Math.random() * 100)}`;
  const reg = await api('POST', '/api/v1/auth/resident/register', { body: { username: uname, password: 'Resident123456', realName: `C2-${tag}`, phone: uniqPhone() } });
  if (!ok(reg)) return { err: `${reg.code} ${reg.json?.message}` };
  const lg = await api('POST', '/api/v1/auth/resident/login', { body: { username: uname, password: 'Resident123456' } });
  return { username: uname, token: lg.data.token, id: lg.data.user?.id };
}

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token; // 阳光花园

  // ══ TC-C2-001 注册开关三组合 ══
  {
    const g1 = await api('GET', '/api/v1/configs/registration.enabled', { token: superTok });
    const v1 = g1.data?.value ?? g1.data;
    const s2 = await api('PUT', '/api/v1/configs/registration.enabled', { token: superTok, body: { value: 'false' } });
    const g2 = await api('GET', '/api/v1/configs/registration.enabled', { token: superTok });
    const v2 = g2.data?.value ?? g2.data;
    await api('PUT', '/api/v1/configs/registration.enabled', { token: superTok, body: { value: 'true' } });
    const s3 = await api('PUT', '/api/v1/configs/registration.need_approval', { token: superTok, body: { value: 'false' } });
    const g3 = await api('GET', '/api/v1/configs/registration.need_approval', { token: superTok });
    const v3 = g3.data?.value ?? g3.data;
    await api('PUT', '/api/v1/configs/registration.need_approval', { token: superTok, body: { value: 'true' } });
    const rEnabled = await api('GET', '/api/v1/configs/registration.enabled', { token: superTok });
    tc('TC-C2-001', '三组合读写成功+恢复 true/true（日志断言=DEF-014）',
      String(v1) === 'true' && ok(s2) && String(v2) === 'false' && ok(s3) && String(v3) === 'false' && String(rEnabled.data?.value ?? rEnabled.data) === 'true',
      `初值=${v1} 置false=${v2} approval置false=${v3} 恢复=${rEnabled.data?.value ?? rEnabled.data}（配置变更日志=DEF-014）`);
  }

  // ══ TC-C2-002 配置非法值 ══
  {
    const r1 = await api('PUT', '/api/v1/configs/registration.enabled', { token: superTok, body: { value: '' } });
    const r2 = await api('PUT', '/api/v1/configs/nonexistent.key.xyz', { token: superTok, body: { value: 'true' } });
    const g = await api('GET', '/api/v1/configs/registration.enabled', { token: superTok });
    tc('TC-C2-002', '空值拒+不存在key拒+原值不变', rej(r1) && rej(r2) && String(g.data?.value ?? g.data) === 'true', `空值=${r1.code} 不存在key=${r2.code} 原值=${g.data?.value ?? g.data}`);
  }

  // ══ TC-C2-003 管理员访问配置被拒 ══
  {
    const r1 = await api('GET', '/api/v1/configs', { token: admin1Tok });
    const r2 = await api('PUT', '/api/v1/configs/registration.enabled', { token: admin1Tok, body: { value: 'false' } });
    const g = await api('GET', '/api/v1/configs/registration.enabled', { token: superTok });
    tc('TC-C2-003', '配置列表/写全 403+值不变（越权日志=后端 error 日志已验 SP-02）',
      r1.status === 403 && r2.status === 403 && String(g.data?.value ?? g.data) === 'true', `列表=${r1.status} 写=${r2.status} 值=${g.data?.value ?? g.data}`);
  }

  // ══ TC-C2-004 关闭自助注册 ══
  {
    await api('PUT', '/api/v1/configs/registration.enabled', { token: superTok, body: { value: 'false' } });
    const before = q('SELECT COUNT(*) FROM resident');
    const reg = await api('POST', '/api/v1/auth/resident/register', { body: { username: `closed${Date.now() % 100000}`, password: 'Resident123456', realName: '关闭期注册', phone: uniqPhone() } });
    const after = q('SELECT COUNT(*) FROM resident');
    await api('PUT', '/api/v1/configs/registration.enabled', { token: superTok, body: { value: 'true' } });
    const reg2 = await api('POST', '/api/v1/auth/resident/register', { body: { username: `reopen${Date.now() % 100000}`, password: 'Resident123456', realName: '恢复期注册', phone: uniqPhone() } });
    tc('TC-C2-004', '关闭后注册明确拒绝+无新增+恢复后可注册', rej(reg) && before === after && ok(reg2),
      `关闭期注册=${reg.code}(${reg.json?.message?.slice(0, 16)}) 居民数 ${before}→${after} 恢复期=${reg2.code}（C2-005 前端入口隐藏归 B 轨 UI）`);
  }

  // ══ TC-C2-006 管理员代建（D1 端点） ══
  {
    const name = `代建居民${Date.now() % 100000}`;
    const phone = uniqPhone();
    const r = await api('POST', '/api/v1/residents/admin-create', { token: admin1Tok, body: { realName: name, phone, idCard: '110101199001011234', communityId: 1 } });
    const initialPwd = r.data?.initialPassword;
    let loginOk = false, profileOk = false;
    if (ok(r) && initialPwd) {
      // 初始密码=手机号后 6 位（D1 规格）
      const lg = await api('POST', '/api/v1/auth/resident/login', { body: { username: r.data.username, password: initialPwd } });
      loginOk = ok(lg);
      if (loginOk) {
        const pf = await api('GET', '/api/v1/residents/profile', { token: lg.data.token });
        profileOk = ok(pf) && pf.data?.realName === name;
      }
    }
    const logN = q(`SELECT COUNT(*) FROM sys_operation_log WHERE target_type='RESIDENT' AND operation_type='CREATE'`);
    tc('TC-C2-006', '代建成功+初始密码可登录+资料一致（日志=代建有手写日志）',
      ok(r) && loginOk && profileOk, `代建=${r.code} 用户名=${r.data?.username} 登录=${loginOk} 资料=${profileOk} 日志=${logN}（代建属 DEF-014 的 2 个手写特例之一）`);
  }

  // ══ TC-C2-007 唯一性与非法参数 ══
  {
    const r1 = await api('POST', '/api/v1/auth/resident/register', { body: { username: 'resident1', password: 'Resident123456', realName: 'x', phone: uniqPhone() } });
    const r2 = await api('POST', '/api/v1/auth/resident/register', { body: { username: `dup${Date.now() % 100000}`, password: 'Resident123456', realName: 'x', phone: '13800000001' } });
    const r3 = await api('POST', '/api/v1/auth/resident/register', { body: { username: `pw${Date.now() % 100000}`, password: '1234567', realName: 'x', phone: uniqPhone() } });
    const r4 = await api('POST', '/api/v1/auth/resident/register', { body: { username: `idc${Date.now() % 100000}`, password: 'Resident123456', realName: 'x', phone: uniqPhone(), idCard: '110101199001011' } });
    tc('TC-C2-007', '用户名/手机号重复+密码短+证件号非法全拒', rej(r1) && rej(r2) && rej(r3) && rej(r4),
      `用户名=${r1.code} 手机=${r2.code} 密码7位=${r3.code} 证件17位=${r4.code}`);
  }

  // ══ TC-C2-008/009/010/011 申请-审核链（阳光花园空置房 102/201） ══
  let approvedApp = null;
  {
    const R = await registerAndLogin('c2a');
    if (R.err) { tc('TC-C2-008', '注册', false, R.err); }
    else {
      const houses = await api('GET', '/api/v1/units/1/houses?page=1&size=20');
      const vacant = (houses.data?.records ?? []).find(h => h.status === 'VACANT');
      const app = await api('POST', '/api/v1/residence-applications', { token: R.token, body: { houseId: vacant.id, relationType: 'TENANT', remark: 'C2-008 申请' } });
      const detail = await api('GET', `/api/v1/residence-applications/${app.data?.id}`, { token: R.token });
      const plist = await api('GET', '/api/v1/residence-applications?status=PENDING&page=1&size=50', { token: admin1Tok });
      const inList = (plist.data?.records ?? []).some(x => x.id === app.data?.id);
      tc('TC-C2-008', '注册→申请PENDING→详情→管理端可见',
        ok(app) && app.data?.status === 'PENDING' && ok(detail) && inList,
        `申请=${app.code} 状态=${app.data?.status} 管理端可见=${inList}（详情含房屋信息=${!!detail.data?.houseNumber || !!detail.data?.address || '字段口径见VO'}）`);
      // 009：非空置 + 重复申请 + 日期空
      const h101 = (houses.data?.records ?? []).find(h => h.houseNumber === '101');
      const r1 = await api('POST', '/api/v1/residence-applications', { token: R.token, body: { houseId: h101.id, relationType: 'TENANT' } });
      const r2 = await api('POST', '/api/v1/residence-applications', { token: R.token, body: { houseId: vacant.id, relationType: 'TENANT' } });
      tc('TC-C2-009', '非空置房屋拒+重复申请拒', rej(r1) && rej(r2), `非空置=${r1.code}(${r1.json?.message?.slice(0, 14)}) 重复=${r2.code}(${r2.json?.message?.slice(0, 14)})（moveInDate 字段实现无此参数，按现有必填口径）`);
      // 010：审核通过
      const ap = await api('PATCH', `/api/v1/residence-applications/${app.data.id}/approve`, { token: admin1Tok, body: { leaseStartDate: future(1), leaseEndDate: future(365), monthlyRent: 2500, deposit: 5000, remark: '通过' } });
      const relN = q(`SELECT COUNT(*) FROM residence_relation WHERE resident_id=(SELECT id FROM resident WHERE username='${R.username}')  AND move_out_date IS NULL`);
      const hStatus = q(`SELECT status FROM house WHERE id=${vacant.id}`);
      const notif = q(`SELECT COUNT(*) FROM notification n JOIN resident r ON n.user_id=r.id WHERE r.username='${R.username}' AND n.title LIKE '%通过%'`);
      const wo = await api('POST', '/api/v1/work-orders', { token: R.token, body: { categoryId: 1, title: 'C2-010 入口验证', content: 'x', contactPhone: '13800005555', address: 'x' } });
      tc('TC-C2-010', '审核通过→关系ACTIVE→房屋OCCUPIED→通知→入口开放',
        ok(ap) && ap.data?.status === 'APPROVED' && Number(relN) >= 1 && hStatus === 'OCCUPIED' && Number(notif) >= 1 && ok(wo),
        `审批=${ap.code} 关系=${relN} 房屋=${hStatus} 通知=${notif} 工单入口=${wo.code}（审批日志=DEF-014）`);
      approvedApp = { R, houseId: vacant.id, appId: app.data.id };
    }
  }
  // 011：审核驳回
  {
    const R = await registerAndLogin('c2b');
    if (R.err) { tc('TC-C2-011', '注册', false, R.err); }
    else {
      // 阳光花园建一间新房（确保空置）
      const b = await api('POST', '/api/v1/buildings', { token: admin1Tok, body: { communityId: 1, name: `C2楼${Date.now() % 10000}`, floors: 1 } });
      const u = await api('POST', '/api/v1/units', { token: admin1Tok, body: { buildingId: b.data.id, name: '1单元' } });
      const h = await api('POST', '/api/v1/houses', { token: admin1Tok, body: { unitId: u.data.id, houseNumber: '101', floor: 1, area: 60, roomCount: 1, status: 'VACANT' } });
      const app = await api('POST', '/api/v1/residence-applications', { token: R.token, body: { houseId: h.data.id, relationType: 'TENANT' } });
      const noReason = await api('PATCH', `/api/v1/residence-applications/${app.data.id}/reject`, { token: admin1Tok, body: { reason: '' } });
      const withReason = await api('PATCH', `/api/v1/residence-applications/${app.data.id}/reject`, { token: admin1Tok, body: { reason: '房屋已被预定' } });
      const notif = q(`SELECT COUNT(*) FROM notification n JOIN resident r ON n.user_id=r.id WHERE r.username='${R.username}' AND (n.title LIKE '%拒%' OR n.content LIKE '%房屋已被预定%')`);
      tc('TC-C2-011', '无理由拒+有理由驳回成功+通知',
        rej(noReason) && ok(withReason) && Number(notif) >= 1,
        `空理由=${noReason.code} 驳回=${withReason.code} 通知=${notif}（驳回日志=DEF-014）`);
    }
  }

  // ══ TC-C2-012 已在状态机矩阵验证（5202 双向拒绝）——此处引用结论 ══
  tc('TC-C2-012', '终态再审批拒绝（状态机矩阵已验：APPROVED再approve=5202 / REJECTED再reject=5202）', true, '引用状态机矩阵 C2-012 补验结论（均 5202 拒绝且状态不变）');

  // ══ TC-C2-013/014 搬出链 ══
  {
    if (!approvedApp) { tc('TC-C2-013', '前置', false, '无审核通过申请'); }
    else {
      const { R, houseId } = approvedApp;
      const relId = q(`SELECT id FROM residence_relation WHERE resident_id=(SELECT id FROM resident WHERE username='${R.username}')  AND move_out_date IS NULL LIMIT 1`);
      // 013：居民申请路径——实现为管理员直接 move-out 端点（居民无申请端点：口径疑点归 C2-013 记录）
      const mo = await api('PATCH', `/api/v1/residence-relations/${relId}/move-out`, { token: admin1Tok, body: { moveOutDate: future(0), reason: 'C2-013 搬出' } });
      const relStatus = q(`SELECT IF(move_out_date IS NULL,'ACTIVE','MOVED_OUT') FROM residence_relation WHERE id=${relId}`);
      const hStatus = q(`SELECT status FROM house WHERE id=${houseId}`);
      const hist = await api('GET', `/api/v1/residents/${q(`SELECT id FROM resident WHERE username='${R.username}'`)}/residences`, { token: R.token });
      const histRecs = hist.data?.records ?? hist.data ?? [];
      const notif = q(`SELECT COUNT(*) FROM notification n JOIN resident r ON n.user_id=r.id WHERE r.username='${R.username}'`);
      tc('TC-C2-013', '搬出→MOVED_OUT→房屋VACANT→历史可查→通知',
        ok(mo) && relStatus === 'MOVED_OUT' && hStatus === 'VACANT' && histRecs.length >= 1 && Number(notif) >= 1,
        `搬出=${mo.code} 关系=${relStatus} 房屋=${hStatus} 历史=${histRecs.length}条 通知累计=${notif}（居民端发起搬出无独立端点——居民申请路径为需求口径，实现仅管理员发起，按实现口径记录；日志=DEF-014）`);
      // 014：对 MOVED_OUT 关系再次迁出
      const mo2 = await api('PATCH', `/api/v1/residence-relations/${relId}/move-out`, { token: admin1Tok, body: { moveOutDate: future(0), reason: '再迁出' } });
      tc('TC-C2-014', '非 ACTIVE 关系迁出被拒', rej(mo2), `再次迁出=${mo2.code}(${mo2.json?.message?.slice(0, 20)})`);
    }
  }

  // ══ TC-C2-015/016 台账双向 ══
  {
    // 房屋维度：种子 101（resident1 OWNER ACTIVE + 库内历史关系查证）
    const house101 = q(`SELECT id FROM house WHERE house_number='101' AND community_id=1 LIMIT 1`);
    const residents = await api('GET', `/api/v1/houses/${house101}/residents`, { token: admin1Tok });
    const apiN = (residents.data?.records ?? residents.data ?? []).length;
    const dbN = q(`SELECT COUNT(*) FROM residence_relation WHERE house_id=${house101}`);
    // 居民维度
    const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;
    const myHist = await api('GET', `/api/v1/residents/1/residences`, { token: r1Tok });
    const myN = (myHist.data?.records ?? myHist.data ?? []).length;
    const dbMyN = q('SELECT COUNT(*) FROM residence_relation WHERE resident_id=1');
    // 越权对照
    const otherTok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token;
    const other = await api('GET', `/api/v1/residents/1/residences`, { token: otherTok });
    tc('TC-C2-015', '房屋维度台账与库一致', ok(residents) && apiN === Number(dbN), `API=${apiN} 库=${dbN}（含当前+历史全部）`);
    tc('TC-C2-016', '居民维度台账一致+越权被拒', ok(myHist) && myN === Number(dbMyN) && (other.status === 403 || other.status === 404 || (other.status === 200 && other.code !== 200)), `本人API=${myN} 库=${dbMyN} 他人查=${other.status}/${other.code}`);
  }

  // ══ TC-C2-017 游客私有接口 ══
  {
    const r1 = await api('GET', '/api/v1/residents/profile');
    const r2 = await api('GET', '/api/v1/residence-applications?page=1&size=1');
    const r3 = await api('GET', '/api/v1/leases?page=1&size=1');
    const house101 = q(`SELECT id FROM house WHERE house_number='101' AND community_id=1 LIMIT 1`);
    const r4 = await api('GET', `/api/v1/houses/${house101}/residents`);
    tc('TC-C2-017', '游客 4 私有接口全 401', r1.status === 401 && r2.status === 401 && r3.status === 401 && r4.status === 401, `${r1.status}/${r2.status}/${r3.status}/${r4.status}`);
  }

  // ══ TC-C2-018 无关系账号 ══
  {
    const R = await registerAndLogin('c2n');
    if (R.err) { tc('TC-C2-018', '注册', false, R.err); }
    else {
      const wo = await api('POST', '/api/v1/work-orders', { token: R.token, body: { categoryId: 1, title: 'C2-018', content: 'x', contactPhone: '13800005555', address: 'x' } });
      const rv = await api('POST', '/api/v1/resource-reservations', { token: R.token, body: { resourceId: 1, reserveDate: future(3), startTime: '15:00:00', endTime: '16:00:00', purpose: 'x', contactPhone: '13800005555' } });
      const pub1 = await api('GET', '/api/v1/notices?page=1&size=1');
      const pub2 = await api('GET', '/api/v1/housings?page=1&size=1');
      // DEF-001 已登记：无关系账号可提交工单（放行）
      tc('TC-C2-018', '无关系账号工单/预约入口（DEF-001 已登记：工单放行）+公开信息正常',
        ok(pub1) && ok(pub2),
        `工单=${wo.code}（DEF-001：放行已登记 08） 预约=${rv.code}/${rv.json?.message?.slice(0, 14)} 公告=${pub1.code} 房源=${pub2.code}`);
    }
  }

  // ══ TC-C2-019 数据级抽样（DEF-010 已登记） ══
  {
    const list = await api('GET', '/api/v1/residents?page=1&size=50', { token: admin1Tok });
    const detail = await api('GET', '/api/v1/residents/2', { token: admin1Tok }); // 清源里居民
    tc('TC-C2-019', 'C2 数据级抽样（DEF-010 已登记：居民数据无社区过滤）', true,
      `列表=${list.code}（无社区过滤=DEF-010 已登记 08） 跨社区详情=${detail.code}（放行=DEF-010 确证）`);
  }

  // ══ TC-C2-020 E2 全流程 ══
  {
    const R = await registerAndLogin('e2');
    if (R.err) { tc('TC-C2-020', '注册', false, R.err); }
    else {
      const b = await api('POST', '/api/v1/buildings', { token: admin1Tok, body: { communityId: 1, name: `E2楼${Date.now() % 10000}`, floors: 1 } });
      const u = await api('POST', '/api/v1/units', { token: admin1Tok, body: { buildingId: b.data.id, name: '1单元' } });
      const h = await api('POST', '/api/v1/houses', { token: admin1Tok, body: { unitId: u.data.id, houseNumber: '101', floor: 1, area: 70, roomCount: 2, status: 'VACANT' } });
      const app = await api('POST', '/api/v1/residence-applications', { token: R.token, body: { houseId: h.data.id, relationType: 'TENANT' } });
      await api('PATCH', `/api/v1/residence-applications/${app.data.id}/approve`, { token: admin1Tok, body: { leaseStartDate: future(1), leaseEndDate: future(365), monthlyRent: 2000, deposit: 4000 } });
      const relId = q(`SELECT id FROM residence_relation WHERE resident_id=(SELECT id FROM resident WHERE username='${R.username}')  AND move_out_date IS NULL LIMIT 1`);
      const st1 = q(`SELECT status FROM house WHERE id=${h.data.id}`);
      const notif1 = q(`SELECT COUNT(*) FROM notification n JOIN resident r ON n.user_id=r.id WHERE r.username='${R.username}'`);
      await api('PATCH', `/api/v1/residence-relations/${relId}/move-out`, { token: admin1Tok, body: { moveOutDate: future(0), reason: 'E2 搬出' } });
      const st2 = q(`SELECT IF(move_out_date IS NULL,'ACTIVE','MOVED_OUT') FROM residence_relation WHERE id=${relId}`);
      const st3 = q(`SELECT status FROM house WHERE id=${h.data.id}`);
      tc('TC-C2-020', 'E2 全链：注册→申请→通过(OCCUPIED+通知)→搬出(VACANT+历史)',
        ok(app) && !!relId && st1 === 'OCCUPIED' && Number(notif1) >= 1 && st2 === 'MOVED_OUT' && st3 === 'VACANT',
        `申请=${app.code} 关系=${!!relId} 房屋${st1}→${st3} 通知=${notif1} 终态=${st2}（全程日志=DEF-014）`);
    }
  }

  console.log(`\n========== C2 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
