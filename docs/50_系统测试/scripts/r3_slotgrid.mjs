/**
 * 50 系统测试 · 第三轮 · C2 可约时段栅格化（Slot Grid）专项回归
 * 覆盖 08 缺陷跟踪与回归 §3.7 设计定案 + §3.8 实施记录（第三批第 13 项）：
 *   SG-01 slot_unit 三粒度配置（15/30/60 合法、45 服务层枚举拒、10/0 @Min 拒、缺省 30、VO 暴露）
 *   SG-02 模板对齐校验正反例（30/15/60 粒度 + 存量非对齐模板保留不清洗）
 *   SG-03 available-slots 栅格结构（栅格展开/timeslotId 标识/升序/booked 与库内分桶一致）
 *   SG-04 创建预约对齐校验 + 锁内逐格容量（满员拒/跨格请求整单拒/落库逐格 ≤ capacity）
 *   SG-05 并发容量不超卖（P0：容量 3 同槽 5 路并发恰 3 成功 2 拒，多轮稳定）
 *   SG-06 V11 用户级唯一约束（本人 10 路并发仅 1 成功 + 多人同槽可达 + 索引形状）
 *   SG-07 存量非对齐预约宽松计入（覆盖即占用：展示计入 + 新预约容量判定计入）
 *   SG-08 confirm 逐格口径（confirm 后仍占用、取消后释放、排除自身逻辑）
 *   SG-09 C12 看房口径不回归（uk_viewing_slot 维持全局唯一，与容量模型相反）
 * 用法：TEST_BASE=http://localhost:8081 node r3_slotgrid.mjs
 */
import { execSync } from 'child_process';

const BASE = process.env.TEST_BASE || 'http://localhost:8080';
let pass = 0, fail = 0;
const results = {}; // sgId -> {ok, notes[]}
function tc(sgId, item, expectDesc, ok_, actual) {
  ok_ ? pass++ : fail++;
  results[sgId] = results[sgId] ?? { ok: true, notes: [] };
  if (!ok_) results[sgId].ok = false;
  results[sgId].notes.push(item);
  console.log(`${ok_ ? '✅' : '❌'} [${sgId}] ${item} → ${actual}${ok_ ? '' : '（期望' + expectDesc + '）'}`);
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
const toMin = t => { const p = t.split(':'); return Number(p[0]) * 60 + Number(p[1]); };
const fromMin = m => `${P(Math.floor(m / 60))}:${P(m % 60)}:00`;
/* java dow：1=周一…7=周日（resource_timeslot.day_of_week 口径）；
   未来 30~90 天内该周几的全部日期 */
function datesWithDow(javaDow) {
  const out = [];
  for (let off = 30; off <= 90; off++) {
    const d = new Date(); d.setDate(d.getDate() + off);
    const jd = d.getDay() === 0 ? 7 : d.getDay();
    if (jd === javaDow) out.push(`${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`);
  }
  return out;
}
/* 该资源在该周几上的空闲日期（无占用态预约），取 need 个 */
function freeDates(resourceId, javaDow, need = 1) {
  const found = [];
  for (const c of datesWithDow(javaDow)) {
    const busy = Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${resourceId} AND reserve_date='${c}' AND status IN ('PENDING','RESERVED')`));
    if (busy === 0) { found.push(c); if (found.length === need) return found; }
  }
  throw new Error(`资源 ${resourceId} 在 dow=${javaDow} 无空闲日期`);
}
/* 库内某格占用数（覆盖即占用口径，与后端 SlotGrids 分桶一致） */
const gridBookedDb = (rid, date, gridStartMin, slotUnit) =>
  Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${rid} AND reserve_date='${date}' AND status IN ('PENDING','RESERVED') AND start_time < '${fromMin(gridStartMin + slotUnit)}' AND end_time > '${fromMin(gridStartMin)}'`));

async function main() {
  const login = async (u, p) => (await api('POST', '/api/v1/auth/resident/login', { body: { username: u, password: p } })).data?.token
    ?? (await api('POST', '/api/v1/auth/admin/login', { body: { username: u, password: p } })).data?.token;
  const loginResident = async (u, p) => (await api('POST', '/api/v1/auth/resident/login', { body: { username: u, password: p } })).data?.token;
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin2Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_admin', password: 'Admin123456' } })).data?.token; // 社区 2 清源里
  const r1Tok = await loginResident('resident1', 'Resident123456');      // 社区 1
  const qyTok = await loginResident('test_resident', 'Resident123456');  // 社区 2 清源里
  const perfTok = {};
  for (let i = 1; i <= 10; i++) perfTok[i] = await loginResident(`perf_resident_${String(i).padStart(3, '0')}`, 'Resident123456');
  const TAG = Date.now() % 100000;

  /* 自建资源/模板/直插行登记，收尾统一清理 */
  const createdResources = [];   // { id }
  const sqlRows = [];            // { table, id }
  const mkResource = async (name, { capacity, slotUnit } = {}) => {
    const body = { communityId: 2, name: `${name}-${TAG}`, type: 'MEETING_ROOM', location: 'R3SG', capacity };
    if (slotUnit !== undefined) body.slotUnit = slotUnit;
    const r = await api('POST', '/api/v1/resources', { token: admin2Tok, body });
    if (ok(r)) createdResources.push({ id: r.data.id });
    return r;
  };
  const mkTemplate = (rid, dow, start, end) =>
    api('POST', `/api/v1/resources/${rid}/timeslots`, { token: admin2Tok, body: { dayOfWeek: dow, startTime: start, endTime: end, isAvailable: 1 } });

  try {
    /* ══ SG-01 slot_unit 三粒度配置 ══ */
    {
      const r15 = await mkResource('SG01资源15', { capacity: 3, slotUnit: 15 });
      const r30 = await mkResource('SG01资源30', { capacity: 3, slotUnit: 30 });
      const r60 = await mkResource('SG01资源60', { capacity: 3, slotUnit: 60 });
      const bad45 = await api('POST', '/api/v1/resources', { token: admin2Tok, body: { communityId: 2, name: `SG01非法45-${TAG}`, type: 'MEETING_ROOM', capacity: 3, slotUnit: 45 } });
      const bad10 = await api('POST', '/api/v1/resources', { token: admin2Tok, body: { communityId: 2, name: `SG01非法10-${TAG}`, type: 'MEETING_ROOM', capacity: 3, slotUnit: 10 } });
      const bad0 = await api('POST', '/api/v1/resources', { token: admin2Tok, body: { communityId: 2, name: `SG01非法0-${TAG}`, type: 'MEETING_ROOM', capacity: 3, slotUnit: 0 } });
      const def = await mkResource('SG01缺省', { capacity: 3 }); // 不传 slotUnit
      const vo15 = r15.data?.slotUnit, vo30 = r30.data?.slotUnit, vo60 = r60.data?.slotUnit, voDef = def.data?.slotUnit;
      tc('SG-01', 'slot_unit ∈ {15,30,60} 创建成功且 VO 回显', '三粒度均成功，VO.slotUnit 与入参一致',
        ok(r15) && ok(r30) && ok(r60) && vo15 === 15 && vo30 === 30 && vo60 === 60,
        `15=${r15.code}/${vo15} 30=${r30.code}/${vo30} 60=${r60.code}/${vo60}`);
      tc('SG-01', '非法值 45 拒绝（服务层枚举，@Min/@Max 放行后收敛）', '400 + 业务提示',
        rej(bad45) && bad45.code === 400 && (bad45.msg || '').includes('最小单位'),
        `45=${bad45.status}/${bad45.code}(${(bad45.msg || '').slice(0, 20)})`);
      tc('SG-01', '越界值 10/0 拒绝（@Min(15)/@Max(60)）', '400',
        rej(bad10) && bad10.code === 400 && rej(bad0) && bad0.code === 400,
        `10=${bad10.status}/${bad10.code} 0=${bad0.status}/${bad0.code}`);
      tc('SG-01', '缺省不传 = 30', 'VO.slotUnit=30',
        ok(def) && voDef === 30, `缺省=${def.code}/${voDef}`);
    }

    /* ══ SG-02 模板对齐校验正反例 ══ */
    {
      const r30 = await mkResource('SG02资源30', { capacity: 3, slotUnit: 30 });
      const r15 = await mkResource('SG02资源15', { capacity: 3, slotUnit: 15 });
      const r60 = await mkResource('SG02资源60', { capacity: 3, slotUnit: 60 });
      const rid = r30.data?.id;
      const good = await mkTemplate(rid, 1, '10:00:00', '11:30:00');       // 90min = 30×3 对齐
      const badStart = await mkTemplate(rid, 2, '10:10:00', '11:00:00');   // 起非对齐
      const badEnd = await mkTemplate(rid, 3, '10:00:00', '11:10:00');     // 止非对齐
      const ok15 = await mkTemplate(r15.data?.id, 1, '10:00:00', '10:15:00'); // 15 粒度
      const bad60 = await mkTemplate(r60.data?.id, 1, '10:00:00', '10:30:00'); // 30 对齐但非 60 倍数
      tc('SG-02', '30 粒度：10:00-11:30 放行、10:10-11:00 / 10:00-11:10 拒', '对齐放行 + 非对齐 400（提示含整数倍）',
        ok(good) && rej(badStart) && badStart.code === 400 && (badStart.msg || '').includes('整数倍')
        && rej(badEnd) && badEnd.code === 400 && (badEnd.msg || '').includes('整数倍'),
        `对齐=${good.code} 起非对齐=${badStart.code}(${(badStart.msg || '').slice(0, 16)}) 止非对齐=${badEnd.code}`);
      tc('SG-02', '15 粒度：10:00-10:15 放行', '成功',
        ok(ok15), `15粒度=${ok15.code}`);
      tc('SG-02', '60 粒度：10:00-10:30（30 对齐但非 60 倍数）拒', '400',
        rej(bad60) && bad60.code === 400 && (bad60.msg || '').includes('整数倍'),
        `60粒度=${bad60.code}(${(bad60.msg || '').slice(0, 16)})`);
      /* 存量非对齐模板保留：SQL 直插 10:10-10:50 后 GET 模板列表可见（不清洗） */
      q(`INSERT INTO resource_timeslot (resource_id, community_id, day_of_week, start_time, end_time, is_available) VALUES (${rid}, 2, 4, '10:10:00', '10:50:00', 1)`);
      const legacyId = Number(q(`SELECT id FROM resource_timeslot WHERE resource_id=${rid} AND day_of_week=4 AND start_time='10:10:00'`));
      sqlRows.push({ table: 'resource_timeslot', id: legacyId });
      const list = await api('GET', `/api/v1/resources/${rid}/timeslots?page=1&size=100`, { token: admin2Tok });
      const seen = (list.data?.records ?? []).some(t => t.id === legacyId && t.startTime === '10:10:00' && t.endTime === '10:50:00');
      tc('SG-02', '存量非对齐模板保留（直插 10:10-10:50 列表可见不清洗）', 'GET 列表含该模板',
        ok(list) && seen, `列表可见=${seen}（legacyId=${legacyId}）`);
    }

    /* ══ SG-03 available-slots 栅格结构 ══ */
    {
      const r = await mkResource('SG03栅格', { capacity: 3, slotUnit: 30 });
      const rid = r.data?.id;
      const tpl = await mkTemplate(rid, 1, '09:00:00', '11:00:00');
      const tplId = tpl.data?.id;
      const [date] = freeDates(rid, 1);
      const slots0 = await api('GET', `/api/v1/resources/${rid}/available-slots?startDate=${date}&endDate=${date}`);
      const arr0 = slots0.data ?? [];
      const starts = arr0.map(s => s.startTime);
      const gridOk = arr0.length === 4
        && JSON.stringify(starts) === JSON.stringify(['09:00:00', '09:30:00', '10:00:00', '10:30:00'])
        && arr0.every(s => toMin(s.endTime) - toMin(s.startTime) === 30)
        && arr0.every(s => s.timeslotId === tplId * 10000 + toMin(s.startTime))
        && arr0.every(s => s.maxBookings === 3 && s.currentBookings === 0 && s.status === 'AVAILABLE');
      tc('SG-03', '模板 09:00-11:00 展开为 4 格（每格 30 分钟，升序）', '4 格 09:00/09:30/10:00/10:30，startTime 升序',
        ok(slots0) && gridOk,
        `格数=${arr0.length} 起点=${starts.join(',')} 格标识核对=${arr0.every(s => s.timeslotId === tplId * 10000 + toMin(s.startTime))}`);
      /* 经接口造 2 条占用态预约（不同居民）→ 该格 currentBookings=2、余量 1 */
      const b1 = await api('POST', '/api/v1/resource-reservations', { token: qyTok, body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG03-A${TAG}`, contactPhone: '13800003333' } });
      const b2 = await api('POST', '/api/v1/resource-reservations', { token: perfTok[1], body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG03-B${TAG}`, contactPhone: '13800003334' } });
      const slots1 = await api('GET', `/api/v1/resources/${rid}/available-slots?startDate=${date}&endDate=${date}`);
      const arr1 = (slots1.data ?? []).filter(s => s.date === date);
      const g0 = arr1.find(s => s.startTime === '09:00:00');
      const bookedOk = g0 && g0.currentBookings === 2 && g0.maxBookings === 3 && (g0.maxBookings - g0.currentBookings) === 1
        && arr1.filter(s => s.startTime !== '09:00:00').every(s => s.currentBookings === 0);
      tc('SG-03', '2 条占用态预约 → 该格 booked=2、余量 1、其余格 0', '09:00 格 currentBookings=2、maxBookings-currentBookings=1',
        ok(b1) && ok(b2) && ok(slots1) && bookedOk,
        `预约=${b1.code}/${b2.code} 09:00格=${g0 ? `${g0.currentBookings}/${g0.maxBookings}` : '缺失'} 余量=${g0 ? g0.maxBookings - g0.currentBookings : '-'}`);
      /* 直查库比对：占用态预约按覆盖即占用分桶的期望值 vs 接口返回逐格 */
      const grids = [540, 570, 600, 630]; // 09:00/09:30/10:00/10:30
      const mismatch = grids.filter(g => gridBookedDb(rid, date, g, 30) !== (arr1.find(s => toMin(s.startTime) === g)?.currentBookings ?? -1));
      tc('SG-03', 'booked 与库内明细一致（覆盖即占用分桶逐格比对）', '接口 currentBookings = 库内期望值',
        mismatch.length === 0,
        mismatch.length === 0 ? '4/4 格一致' : `不一致格=${mismatch.map(g => fromMin(g)).join(',')}`);
    }

    /* ══ SG-04 创建预约对齐校验 + 锁内逐格容量 ══ */
    {
      const r = await mkResource('SG04容量', { capacity: 3, slotUnit: 30 });
      const rid = r.data?.id;
      await mkTemplate(rid, 2, '09:00:00', '12:00:00');
      const [date] = freeDates(rid, 2);
      const book = (token, s, e, who) => api('POST', '/api/v1/resource-reservations', { token, body: { resourceId: rid, reserveDate: date, startTime: s, endTime: e, purpose: `SG04-${who}${TAG}`, contactPhone: '13800004444' } });
      const unaligned = await book(qyTok, '10:15:00', '10:45:00', '非对齐');
      tc('SG-04', '非对齐预约请求（10:15-10:45）拒', '400 + 提示含整数倍',
        rej(unaligned) && unaligned.code === 400 && (unaligned.msg || '').includes('整数倍'),
        `非对齐=${unaligned.code}(${(unaligned.msg || '').slice(0, 18)})`);
      const A = await book(qyTok, '09:00:00', '09:30:00', 'A');
      const B = await book(perfTok[1], '09:00:00', '09:30:00', 'B');
      const C = await book(perfTok[2], '09:00:00', '09:30:00', 'C');
      const D = await book(perfTok[3], '09:00:00', '09:30:00', 'D');
      tc('SG-04', '容量 3 同槽 A/B/C 成功、D 拒（冲突码 + 已满语义）', 'A/B/C 200；D 5401 且消息含已约满',
        ok(A) && ok(B) && ok(C) && rej(D) && D.code === 5401 && (D.msg || '').includes('已约满'),
        `A=${A.code} B=${B.code} C=${C.code} D=${D.code}/${D.status}(${(D.msg || '').slice(0, 18)})`);
      /* 跨格请求 09:00-10:30 覆盖 3 格，其中 09:00 格已满 → 整单拒绝 */
      const cross = await book(perfTok[4], '09:00:00', '10:30:00', '跨格');
      tc('SG-04', '跨格请求（09:00-10:30 覆盖 3 格，09:00 格已满）整单拒', '5401',
        rej(cross) && cross.code === 5401,
        `跨格=${cross.code}(${(cross.msg || '').slice(0, 22)})`);
      /* 落库断言：每格占用数 ≤ capacity（09:00~11:30 六格逐格） */
      const over = [];
      for (let g = 540; g < 690; g += 30) if (gridBookedDb(rid, date, g, 30) > 3) over.push(fromMin(g));
      tc('SG-04', '落库逐格占用 ≤ capacity', '6 格全部 ≤3',
        over.length === 0, over.length === 0 ? '6/6 格 ≤3' : `超卖格=${over.join(',')}`);
    }

    /* ══ SG-05 并发容量不超卖（P0） ══ */
    {
      const r = await mkResource('SG05并发', { capacity: 3, slotUnit: 30 });
      const rid = r.data?.id;
      await mkTemplate(rid, 3, '09:00:00', '11:00:00');
      const [date1, date2] = freeDates(rid, 3, 2);
      const run5 = async (date, slot) => {
        const rs = await Promise.all([1, 2, 3, 4, 5].map(i =>
          api('POST', '/api/v1/resource-reservations', { token: perfTok[i], body: { resourceId: rid, reserveDate: date, startTime: slot, endTime: fromMin(toMin(slot) + 30), purpose: `SG05-${i}${TAG}`, contactPhone: '13800005555' } })));
        return { succ: rs.filter(ok).length, failCodes: rs.filter(rej).map(x => x.code), msgs: rs.filter(rej).map(x => (x.msg || '').slice(0, 10)) };
      };
      const round1 = await run5(date1, '10:00:00');
      const db1 = Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${rid} AND reserve_date='${date1}' AND start_time='10:00:00' AND status IN ('PENDING','RESERVED')`));
      tc('SG-05', '轮1：容量 3 同槽 5 路并发恰 3 成功 2 拒', '3 成功 + 2 个 5401',
        round1.succ === 3 && round1.failCodes.length === 2 && round1.failCodes.every(c => c === 5401),
        `成功=${round1.succ} 拒=${JSON.stringify(round1.failCodes)}(${round1.msgs[0] || ''})`);
      tc('SG-05', '轮1：落库恰 3 条占用态', 'DB=3',
        db1 === 3, `落库=${db1}`);
      const round2 = await run5(date2, '09:30:00');
      const db2 = Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${rid} AND reserve_date='${date2}' AND start_time='09:30:00' AND status IN ('PENDING','RESERVED')`));
      tc('SG-05', '轮2（不同日期/时段）：5 路并发仍恰 3 成功 2 拒、落库 3', '3 成功 + 2 拒 + DB=3',
        round2.succ === 3 && round2.failCodes.length === 2 && round2.failCodes.every(c => c === 5401) && db2 === 3,
        `成功=${round2.succ} 拒=${JSON.stringify(round2.failCodes)} 落库=${db2}`);
    }

    /* ══ SG-06 V11 用户级唯一约束 ══ */
    {
      const r = await mkResource('SG06唯一', { capacity: 2, slotUnit: 30 });
      const rid = r.data?.id;
      await mkTemplate(rid, 4, '09:00:00', '11:00:00');
      const [date] = freeDates(rid, 4);
      /* 同一居民同资源同日期同起止 10 路并发重复提交：仅 1 成功 */
      const dup = await Promise.all(Array.from({ length: 10 }, () =>
        api('POST', '/api/v1/resource-reservations', { token: perfTok[10], body: { resourceId: rid, reserveDate: date, startTime: '10:00:00', endTime: '10:30:00', purpose: `SG06本人${TAG}`, contactPhone: '13800006666' } })));
      const dupSucc = dup.filter(ok).length;
      const dupDb = Number(q(`SELECT COUNT(*) FROM resource_reservation WHERE resource_id=${rid} AND reserve_date='${date}' AND start_time='10:00:00' AND status IN ('PENDING','RESERVED')`));
      tc('SG-06', '同一居民同槽 10 路并发：仅 1 成功（锁内本人拦截为主、约束兜底）', '1 成功 + 9 拒（5002/5401）+ DB=1',
        dupSucc === 1 && dupDb === 1 && dup.filter(rej).every(x => x.code === 5002 || x.code === 5401),
        `成功=${dupSucc} 拒码=${JSON.stringify([...new Set(dup.filter(rej).map(x => x.code))])} 落库=${dupDb}`);
      /* V10 降级正向验证：容量 ≥2 资源不同居民同槽均成功（若仍全局唯一会误拒） */
      const m1 = await api('POST', '/api/v1/resource-reservations', { token: qyTok, body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG06多人A${TAG}`, contactPhone: '13800007777' } });
      const m2 = await api('POST', '/api/v1/resource-reservations', { token: perfTok[9], body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG06多人B${TAG}`, contactPhone: '13800007778' } });
      tc('SG-06', '多人同槽可达（V10 全局唯一降级为用户级的关键回归）', '两居民同槽均成功',
        ok(m1) && ok(m2), `A=${m1.code} B=${m2.code}`);
      /* 索引形状：uk_reservation_user_slot 为用户级、旧 uk_reservation_slot 已撤 */
      const userSlotCols = q(`SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics WHERE TABLE_SCHEMA='community_residence_test' AND TABLE_NAME='resource_reservation' AND INDEX_NAME='uk_reservation_user_slot'`);
      const oldGlobalCnt = Number(q(`SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics WHERE TABLE_SCHEMA='community_residence_test' AND TABLE_NAME='resource_reservation' AND INDEX_NAME='uk_reservation_slot'`));
      tc('SG-06', 'V11 索引形状：用户级约束在、全局旧约束撤', 'uk_reservation_user_slot=(user_id,resource_id,reserve_date,start_time,slot_occupied) 且 uk_reservation_slot 不存在',
        userSlotCols === 'user_id,resource_id,reserve_date,start_time,slot_occupied' && oldGlobalCnt === 0,
        `user_slot=(${userSlotCols}) 旧全局约束存在=${oldGlobalCnt}`);
    }

    /* ══ SG-07 存量非对齐预约宽松计入 ══ */
    {
      const r = await mkResource('SG07存量', { capacity: 1, slotUnit: 30 });
      const rid = r.data?.id;
      await mkTemplate(rid, 5, '09:00:00', '11:00:00');
      const [date] = freeDates(rid, 5);
      const qyUid = Number(q(`SELECT id FROM resident WHERE username='test_resident'`));
      /* SQL 直插非对齐占用态预约 10:10-10:50（远期日期，TAG 标记） */
      q(`INSERT INTO resource_reservation (user_id, resource_id, community_id, reserve_date, start_time, end_time, status, purpose, contact_phone) VALUES (${qyUid}, ${rid}, 2, '${date}', '10:10:00', '10:50:00', 'PENDING', 'R3SG07存量非对齐${TAG}', '13800008888')`);
      const legacyId = Number(q(`SELECT id FROM resource_reservation WHERE resource_id=${rid} AND reserve_date='${date}' AND start_time='10:10:00'`));
      sqlRows.push({ table: 'resource_reservation', id: legacyId });
      const slots = await api('GET', `/api/v1/resources/${rid}/available-slots?startDate=${date}&endDate=${date}`);
      const arr = (slots.data ?? []).filter(s => s.date === date);
      const g10 = arr.find(s => s.startTime === '10:00:00');
      const g1030 = arr.find(s => s.startTime === '10:30:00');
      const gFree = arr.filter(s => ['09:00:00', '09:30:00'].includes(s.startTime));
      tc('SG-07', '非对齐预约 10:10-10:50 计入 10:00 与 10:30 两格（覆盖即占用）', '两格 currentBookings=1 且 FULL（capacity=1），其余格 0',
        ok(slots) && g10 && g10.currentBookings === 1 && g10.status === 'FULL'
        && g1030 && g1030.currentBookings === 1 && g1030.status === 'FULL'
        && gFree.every(s => s.currentBookings === 0 && s.status === 'AVAILABLE'),
        `10:00格=${g10 ? `${g10.currentBookings}/${g10.status}` : '缺失'} 10:30格=${g1030 ? `${g1030.currentBookings}/${g1030.status}` : '缺失'} 空闲格=${gFree.map(s => `${s.startTime}:${s.currentBookings}`).join(',')}`);
      /* 新预约容量判定同样计入：被该格占满后新预约拒（对照：空闲格放行） */
      const blocked = await api('POST', '/api/v1/resource-reservations', { token: perfTok[8], body: { resourceId: rid, reserveDate: date, startTime: '10:00:00', endTime: '10:30:00', purpose: `SG07拒${TAG}`, contactPhone: '13800009999' } });
      const control = await api('POST', '/api/v1/resource-reservations', { token: perfTok[8], body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG07对照${TAG}`, contactPhone: '13800009999' } });
      tc('SG-07', '新预约容量判定计入存量非对齐（占满拒 + 空闲格对照放行）', '10:00 格新预约 5401、09:00 格放行',
        rej(blocked) && blocked.code === 5401 && ok(control),
        `占满格=${blocked.code} 空闲格=${control.code}`);
    }

    /* ══ SG-08 confirm 逐格口径 ══ */
    {
      const r = await mkResource('SG08确认', { capacity: 1, slotUnit: 30 });
      const rid = r.data?.id;
      await mkTemplate(rid, 6, '09:00:00', '11:00:00');
      const [date] = freeDates(rid, 6);
      const book = (token, who) => api('POST', '/api/v1/resource-reservations', { token, body: { resourceId: rid, reserveDate: date, startTime: '09:00:00', endTime: '09:30:00', purpose: `SG08-${who}${TAG}`, contactPhone: '13800011111' } });
      const A = await book(qyTok, 'A');
      const B1 = await book(perfTok[7], 'B1');
      const conf = await api('PATCH', `/api/v1/resource-reservations/${A.data?.id}/confirm`, { token: admin2Tok, body: { reason: 'SG08确认' } });
      const aStatus = q(`SELECT status FROM resource_reservation WHERE id=${A.data?.id}`);
      const B2 = await book(perfTok[7], 'B2');
      const cancel = await api('PATCH', `/api/v1/resource-reservations/${A.data?.id}/cancel`, { token: qyTok, body: { reason: 'SG08释放' } });
      const B3 = await book(perfTok[7], 'B3');
      tc('SG-08', '容量 1：A 预约后 B 同槽拒', 'B1 5401',
        ok(A) && rej(B1) && B1.code === 5401, `A=${A.code} B1=${B1.code}`);
      tc('SG-08', 'confirm A（→RESERVED）后 B 仍拒（A 仍占用）', 'confirm 200 + B2 5401',
        ok(conf) && aStatus === 'RESERVED' && rej(B2) && B2.code === 5401,
        `confirm=${conf.code} A状态=${aStatus} B2=${B2.code}`);
      tc('SG-08', 'cancel A 后 B 可约（排除自身 + 占用态集合口径）', 'cancel 200 + B3 200',
        ok(cancel) && ok(B3), `cancel=${cancel.code} B3=${B3.code}`);
    }

    /* ══ SG-09 C12 看房口径不回归 ══ */
    {
      /* V10 uk_viewing_slot 维持全局唯一（V11 未动） */
      const viewCols = q(`SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics WHERE TABLE_SCHEMA='community_residence_test' AND TABLE_NAME='viewing_appointment' AND INDEX_NAME='uk_viewing_slot'`);
      const viewNonUniq = q(`SELECT NON_UNIQUE FROM information_schema.statistics WHERE TABLE_SCHEMA='community_residence_test' AND TABLE_NAME='viewing_appointment' AND INDEX_NAME='uk_viewing_slot' LIMIT 1`);
      tc('SG-09', 'V11 后 uk_viewing_slot 未变（全局唯一约束在）', '(housing_id,appointment_date,start_time,end_time,slot_occupied) 且 UNIQUE',
        viewCols === 'housing_id,appointment_date,start_time,end_time,slot_occupied' && viewNonUniq === '0',
        `列=(${viewCols}) 唯一=${viewNonUniq === '0'}`);
      /* 同房源同日期同时段：A 成功后 B 被拒（capacity=1 语义，与资源预约容量模型相反） */
      const housingId = 1; // 种子房源（模板周一~周日 09:00-12:00/14:00-18:00）
      let date = null;
      for (let off = 30; off <= 90; off++) {
        const d = new Date(); d.setDate(d.getDate() + off);
        const c = `${d.getFullYear()}-${P(d.getMonth() + 1)}-${P(d.getDate())}`;
        const busy = Number(q(`SELECT COUNT(*) FROM viewing_appointment WHERE housing_id=${housingId} AND appointment_date='${c}' AND status IN ('TO_CONFIRM','RESERVED')`));
        if (busy === 0) { date = c; break; }
      }
      const mkView = token => api('POST', '/api/v1/viewing-appointments', { token, body: { housingId, appointmentDate: date, startTime: '10:00:00', endTime: '11:00:00', visitorName: `SG09访客${TAG}`, contactPhone: '13800022222' } });
      const A = await mkView(r1Tok);
      const B = await mkView(qyTok);
      tc('SG-09', '同房源同槽：A 看房成功后 B 被拒（与资源预约容量模型相反）', 'A 200；B 5401 重叠/已约',
        ok(A) && rej(B) && B.code === 5401,
        `日期=${date} A=${A.code} B=${B.code}/${(B.msg || '').slice(0, 16)}`);
      /* 清理：A 本人取消 */
      if (A.data?.id) await api('PATCH', `/api/v1/viewing-appointments/${A.data.id}/cancel`, { token: r1Tok, body: { reason: `SG09清理${TAG}` } });
    }

    console.log(`\n══ Slot Grid 专项（SG-01~SG-09）：${pass} 通过 / ${fail} 失败 ══`);
    console.log('\n按 SG 组汇总：');
    for (const [id, rr] of Object.entries(results)) console.log(`${rr.ok ? '✅' : '❌'} ${id}（${rr.notes.length} 项）`);
  } finally {
    /* 数据自清理：直插行物理删除 → 自建资源占用态预约置 CANCELLED → 资源软删 */
    for (const row of sqlRows) {
      try { q(`DELETE FROM ${row.table} WHERE id=${row.id}`); } catch (e) { console.error(`清理直插行失败 ${row.table}#${row.id}:`, e.message); }
    }
    for (const { id } of createdResources) {
      try {
        q(`UPDATE resource_reservation SET status='CANCELLED', remark=CONCAT('[R3SG清理]', IFNULL(remark,'')) WHERE resource_id=${id} AND status IN ('PENDING','RESERVED')`);
      } catch (e) { console.error(`清理预约失败 资源${id}:`, e.message); }
      try { await api('DELETE', `/api/v1/resources/${id}`, { token: admin2Tok }); } catch (e) { console.error(`删除资源失败 ${id}:`, e.message); }
    }
    console.log(`\n[清理] 直插行 ${sqlRows.length} 条已物理删除；自建资源 ${createdResources.length} 个占用态预约已置 CANCELLED 并软删`);
  }
  process.exit(fail ? 1 : 0);
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2); });
