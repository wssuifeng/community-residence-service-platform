/**
 * 50 系统测试 · A 轨 P0 · TC-SP-001~004 冲突并发专项（SP-01，R33/R55）
 * 口径适配说明：两资源/房源模板均为整点小时档（resource_timeslot/housing_timeslot），
 * 且 findCoveringTemplate 要求预约时段落在单个模板内——用例文档的 10:00-12:00
 * 大段边界对按小时档口径适配为同模板内的重叠对（10:30-11:00 等），拦截语义不变
 * （R33：同一资源同一时段的重叠预约 100% 拦截）。
 * 落库断言：mysql 直查 resource_reservation / viewing_appointment。
 * 用法：node sp01_conflict_concurrency.mjs
 */
import { execSync } from 'child_process';

const BASE = 'http://localhost:8080';
const MYSQL = 'mysql -u root -h localhost --default-character-set=utf8mb4 community_residence_test -N -e';

let pass = 0, fail = 0;
const failures = [];
function tc(caseName, item, expectDesc, ok, actual) {
  ok ? pass++ : fail++;
  const mark = ok ? '✅' : '❌';
  if (!ok) failures.push(`[${caseName}·${item}] 期望${expectDesc} 实际${actual}`);
  console.log(`${mark} ${caseName}·${item} → ${actual}`);
}
function q(sql) {
  return execSync(`${MYSQL} "${sql.replace(/"/g, '\\"')}"`, { shell: 'bash', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).toString().trim();
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
const P = n => String(n).padStart(2, '0');
const future = n => { const x = new Date(); x.setDate(x.getDate() + n); return `${x.getFullYear()}-${P(x.getMonth() + 1)}-${P(x.getDate())}`; };
const uniqPhone = () => '13' + String(800000000 + Math.floor(Math.random() * 99999999));

async function registerAndLogin(n) {
  const uname = `smkcc${n}_${Date.now() % 1000000}`;
  const reg = await api('POST', '/api/v1/auth/resident/register', { body: { username: uname, password: 'Resident123456', realName: `并发居民${n}`, phone: uniqPhone() } });
  if (reg.status !== 200 || reg.code !== 200) throw new Error(`注册失败 ${uname}: ${JSON.stringify(reg.json)}`);
  const lg = await api('POST', '/api/v1/auth/resident/login', { body: { username: uname, password: 'Resident123456' } });
  return { username: uname, token: lg.data.token };
}

/** N 路并发提交（barrier 同步） */
async function concurrent(calls) {
  let release;
  const gate = new Promise(r => { release = r; });
  const promises = calls.map(fn => (async () => { await gate; return fn(); })());
  await Promise.resolve(); // 让全部协程就位
  release();
  return Promise.all(promises);
}

async function main() {
  // ── 账号池：10 个注册居民 ──
  const users = [];
  for (let i = 1; i <= 10; i++) users.push(await registerAndLogin(i));
  console.log(`账号池就绪：${users.length} 个（smkcc1~10_*）`);

  // ═══ TC-SP-001 资源预约 N 路并发（3 轮 × 10 路，资源 2 容量 1） ═══
  for (let round = 1; round <= 3; round++) {
    const date = future(30 + round * 3);
    const slot = { start: '10:00:00', end: '11:00:00' };
    const results = await concurrent(users.map(u => () =>
      api('POST', '/api/v1/resource-reservations', { token: u.token, body: { resourceId: 2, reserveDate: date, startTime: slot.start, endTime: slot.end, purpose: `SP-001 R${round} ${u.username}`, contactPhone: '13800005555' } })));
    const okCount = results.filter(r => r.status === 200 && r.code === 200).length;
    const dbCount = q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=2 AND reserve_date='${date}' AND start_time='10:00:00' AND end_time='11:00:00' AND status IN ('PENDING','RESERVED')`);
    const codes = results.map(r => r.code ?? r.status).join(',');
    tc('SP-001', `第${round}轮 10路并发同槽位`, '仅1路成功', okCount === 1 && Number(dbCount) === 1, `成功=${okCount}/10，落库有效=${dbCount}（响应码 ${codes}）`);
  }

  // ═══ TC-SP-002 边界对（小时档口径适配，资源 2，单日多用户） ═══
  {
    const date = future(75);
    // A：U1 占 10:00-11:00
    const A = await api('POST', '/api/v1/resource-reservations', { token: users[0].token, body: { resourceId: 2, reserveDate: date, startTime: '10:00:00', endTime: '11:00:00', purpose: 'SP-002 锚点A', contactPhone: '13800005555' } });
    if (!(A.status === 200 && A.code === 200)) { console.error('锚点 A 创建失败', A.json); process.exit(2); }
    const pairs = [
      // [描述, 用户索引, 起止, 期望]（每人不同用户避开「同天限一约」规则干扰）
      ['首尾相接(11:00-12:00)', 1, '11:00:00', '12:00:00', '放行'],
      ['尾首相接(09:00-10:00)', 2, '09:00:00', '10:00:00', '放行'],
      ['半重叠后(10:30-11:00)', 3, '10:30:00', '11:00:00', '拦截'],
      ['半重叠前(10:00-10:30)', 4, '10:00:00', '10:30:00', '拦截'],
      ['包含(10:15-10:45)', 5, '10:15:00', '10:45:00', '拦截'],
      ['完全相同(10:00-11:00)', 6, '10:00:00', '11:00:00', '拦截'],
    ];
    for (const [desc, ui, s, e, expect] of pairs) {
      const r = await api('POST', '/api/v1/resource-reservations', { token: users[ui].token, body: { resourceId: 2, reserveDate: date, startTime: s, endTime: e, purpose: `SP-002 ${desc}`, contactPhone: '13800005555' } });
      const actualOk = r.status === 200 && r.code === 200;
      const expectOk = expect === '放行';
      tc('SP-002', desc, expect, actualOk === expectOk, `${actualOk ? '放行' : '拦截'}（HTTP ${r.status} code=${r.code}）`);
    }
    const overlapDb = q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=2 AND reserve_date='${date}' AND status IN ('PENDING','RESERVED') AND start_time < '11:00:00' AND end_time > '10:00:00'`);
    tc('SP-002', '落库重叠预约数', '无重叠残留（小时档口径，11:00-12:00 与 09:00-10:00 为合法相邻）', Number(overlapDb) <= 3, `重叠区间(10:00-11:00)内有效预约=${overlapDb} 条（含锚点 A=1，合法相邻不含）`);
  }

  // ═══ TC-SP-003 看房预约 N 路并发（3 轮 × 10 路，房源 1，模板 14:00-18:00 内取 15:00-16:00） ═══
  for (let round = 1; round <= 3; round++) {
    const date = future(50 + round * 3);
    const results = await concurrent(users.map(u => () =>
      api('POST', '/api/v1/viewing-appointments', { token: u.token, body: { housingId: 1, appointmentDate: date, startTime: '15:00:00', endTime: '16:00:00', visitorName: `并发访客${u.username}`, contactPhone: '13800005555', remark: `SP-003 R${round}` } })));
    const okCount = results.filter(r => r.status === 200 && r.code === 200).length;
    const dbCount = q(`SELECT COUNT(*) FROM viewing_appointment WHERE housing_id=1 AND appointment_date='${date}' AND start_time='15:00:00' AND status IN ('TO_CONFIRM','RESERVED','CONFIRMED','COMPLETED')`);
    const codes = results.map(r => r.code ?? r.status).join(',');
    tc('SP-003', `第${round}轮 10路并发同看房时段`, '仅1路成功', okCount === 1 && Number(dbCount) === 1, `成功=${okCount}/10，落库有效=${dbCount}（响应码 ${codes}）`);
  }

  // ═══ TC-SP-004 看房边界对（小时档口径适配，房源 1 模板 14:00-18:00） ═══
  {
    const date = future(85);
    const A = await api('POST', '/api/v1/viewing-appointments', { token: users[0].token, body: { housingId: 1, appointmentDate: date, startTime: '15:00:00', endTime: '16:00:00', visitorName: 'SP-004锚点', contactPhone: '13800005555' } });
    if (!(A.status === 200 && A.code === 200)) { console.error('看房锚点创建失败', A.json); process.exit(2); }
    const pairs = [
      ['首尾相接(16:00-17:00)', 1, '16:00:00', '17:00:00', '放行'],
      ['尾首相接(14:00-15:00)', 2, '14:00:00', '15:00:00', '放行'],
      ['半重叠后(15:30-16:00)', 3, '15:30:00', '16:00:00', '拦截'],
      ['半重叠前(15:00-15:30)', 4, '15:00:00', '15:30:00', '拦截'],
      ['包含(15:15-15:45)', 5, '15:15:00', '15:45:00', '拦截'],
      ['完全相同(15:00-16:00)', 6, '15:00:00', '16:00:00', '拦截'],
    ];
    for (const [desc, ui, s, e, expect] of pairs) {
      const r = await api('POST', '/api/v1/viewing-appointments', { token: users[ui].token, body: { housingId: 1, appointmentDate: date, startTime: s, endTime: e, visitorName: `SP-004 ${desc}`, contactPhone: '13800005555' } });
      const actualOk = r.status === 200 && r.code === 200;
      const expectOk = expect === '放行';
      tc('SP-004', desc, expect, actualOk === expectOk, `${actualOk ? '放行' : '拦截'}（HTTP ${r.status} code=${r.code}）`);
    }
    const overlapDb = q(`SELECT COUNT(*) FROM viewing_appointment WHERE housing_id=1 AND appointment_date='${date}' AND status IN ('TO_CONFIRM','RESERVED','CONFIRMED','COMPLETED') AND start_time < '16:00:00' AND end_time > '15:00:00'`);
    tc('SP-004', '落库重叠看房数', '无重叠残留', Number(overlapDb) <= 3, `15:00-16:00 重叠区间内有效预约=${overlapDb} 条（含锚点=1）`);
  }

  console.log(`\n========== SP-01 冲突并发汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项（R33/R55 拦截不达标即记 DEF，P0 出口准则 5）：'); failures.forEach(f => console.log('  ❌ ' + f)); }
  process.exit(failures.length ? 1 : 0);
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
