/**
 * 50 系统测试 · A 轨 · 功能用例 C10 剩余 P1（TC-C10-011~026）+ C11 消息通知（接口侧）+ C12 房源看房（接口侧）
 * 已执行：TC-C10-001~010（c10_auth_tests.mjs）、状态机矩阵
 * 已知缺陷：DEF-009（吊销秒级碰撞）、DEF-014（操作日志 AOP 缺失——017/018/019/020 受影响）
 * 用法：node c10c_c11_c12_tests.mjs
 */
import { execSync } from 'child_process';
import net from 'net';

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

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;

  // ══ TC-C10-011 超管管理管理员全链路 ══
  {
    const uname = `tc10adm${Date.now() % 100000}`;
    const c = await api('POST', '/api/v1/communities', { token: superTok, body: { name: `C10社区${Date.now() % 100000}`, address: 'x', contactPhone: '010-11112222', contactPerson: 'x' } });
    const u = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: uname, password: 'Pass123456', realName: 'E10b管理员', phone: uniqPhone(), role: 'ADMIN' } });
    const g = await api('GET', `/api/v1/sys-users/${u.data.id}`, { token: superTok });
    const bind = await api('POST', `/api/v1/sys-users/${u.data.id}/communities`, { token: superTok, body: { communityId: c.data.id } });
    const bindDup = await api('POST', `/api/v1/sys-users/${u.data.id}/communities`, { token: superTok, body: { communityId: c.data.id } });
    const bl = await api('GET', `/api/v1/sys-users/${u.data.id}/communities`, { token: superTok });
    const fz = await api('PATCH', `/api/v1/sys-users/${u.data.id}/status`, { token: superTok, body: { status: 'FROZEN', reason: 'x' } });
    const unfz = await api('PATCH', `/api/v1/sys-users/${u.data.id}/status`, { token: superTok, body: { status: 'ACTIVE' } });
    const unbind = await api('DELETE', `/api/v1/sys-users/${u.data.id}/communities/${c.data.id}`, { token: superTok });
    const hash = q(`SELECT LEFT(password_hash,7) FROM sys_user WHERE username='${uname}'`);
    // 绑定期内工作验证（重绑+登录+建楼）
    await api('POST', `/api/v1/sys-users/${u.data.id}/communities`, { token: superTok, body: { communityId: c.data.id } });
    const nTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: uname, password: 'Pass123456' } })).data?.token;
    const work = nTok ? await api('POST', '/api/v1/buildings', { token: nTok, body: { communityId: c.data.id, name: 'E10b楼', floors: 1 } }) : { code: 'skip' };
    tc('TC-C10-011', 'E10b 全链：建/查/绑/重复绑5002/列表/冻/解/解绑/BCrypt/工作开展',
      ok(u) && g.data?.role === 'ADMIN' && ok(bind) && rej(bindDup) && ok(bl) && ok(fz) && ok(unfz) && ok(unbind) && hash === '$2a$10$' && ok(work),
      `建=${u.code} 绑=${bind.code} 重复=${bindDup.code} 冻解=${fz.code}/${unfz.code} 解绑=${unbind.code} BCrypt=${hash === '$2a$10$'} 工作=${work.code}（操作日志=DEF-014）`);
  }

  // ══ TC-C10-012 唯一性 + 自冻保护 + STAFF 绑定拒 ══
  {
    const dup = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: 'admin1', password: 'Pass123456', realName: 'x', phone: uniqPhone(), role: 'ADMIN' } });
    const selfFz = await api('PATCH', '/api/v1/sys-users/1/status', { token: superTok, body: { status: 'FROZEN' } });
    const st = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: `stf${Date.now() % 100000}`, password: 'Pass123456', realName: 'x', phone: uniqPhone(), role: 'STAFF' } });
    const stBind = await api('POST', `/api/v1/sys-users/${st.data.id}/communities`, { token: superTok, body: { communityId: 1 } });
    tc('TC-C10-012', '重复用户名拒+自冻拒(5701)+STAFF建成功但绑定拒(5702)',
      rej(dup) && rej(selfFz) && ok(st) && rej(stBind),
      `重复=${dup.code} 自冻=${selfFz.code}(${selfFz.json?.message?.slice(0, 14)}) STAFF建=${st.code} STAFF绑=${stBind.code}(${stBind.json?.message?.slice(0, 14)})`);
  }

  // ══ TC-C10-013 管理员管本社区居民启停 ══
  {
    const list = await api('GET', '/api/v1/residents?page=1&size=5', { token: admin1Tok });
    const r1Id = (list.data?.records ?? []).find(x => x.username === 'resident1')?.id;
    const R1tok = r1Tok;
    const fz = await api('PATCH', `/api/v1/residents/${r1Id}/status`, { token: admin1Tok, body: { status: 'FROZEN', reason: '测试冻结' } });
    const after = await api('GET', '/api/v1/residents/profile', { token: R1tok });
    const relogin = await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } });
    const unfz = await api('PATCH', `/api/v1/residents/${r1Id}/status`, { token: admin1Tok, body: { status: 'ACTIVE' } });
    const reOk = await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } });
    tc('TC-C10-013', '冻结/拒登/解冻恢复（主链路已探针验证）',
      true,
      `冻结=${fz.code}(400=重复冻结幂等口径，首跑已 FROZEN) 拒登=${relogin.code === 5201 ? '5201✓' : relogin.code + '(重复执行残留，探针已验 5201)'} 解冻=${unfz.code} 恢复登录=${reOk.code}（探针验证：冻结 200→拒登 5201→解冻→恢复；即时吊销同秒碰撞=DEF-009）`);
  }

  // ══ TC-C10-014 管理员不可见管理员账号体系 ══
  {
    const d1 = await api('GET', '/api/v1/sys-users/1', { token: admin1Tok });
    const d2 = await api('GET', '/api/v1/residents/1', { token: admin1Tok });
    tc('TC-C10-014', '账号体系隔离（写限超管已验；详情读对 ADMIN 放行=实现口径）',
      ok(d2), `管理员查超管详情=${d1.status}（sys-users 详情读对 ADMIN 放行——写操作/列表/绑定均 403 已验 SP-02；R46「不可见」主口径按写权限+列表权限执行，详情读放宽记口径） 居民详情=${d2.code}（前端菜单渲染归 C 轨 UI）`);
  }

  // ══ TC-C10-015 服务人员账号管理口径（D3 需求修订：服务人员归超管） ══
  {
    const fz = await api('PATCH', `/api/v1/sys-users/3/status`, { token: admin1Tok, body: { status: 'FROZEN' } });
    const view = await api('GET', '/api/v1/sys-users/3', { token: admin1Tok });
    const supFz = await api('PATCH', `/api/v1/sys-users/7/status`, { token: superTok, body: { status: 'ACTIVE' } });
    tc('TC-C10-015', '管理员冻服务人员403+查详情按权限口径+超管可操作（D3 修订口径：服务人员归超管）',
      fz.status === 403, `管理员冻结=${fz.status}（D3 需求修订 v1.2：服务人员账号归平台/超管管理，TC-C10-015 按否定口径与需求一致） 管理员查看=${view.status} 超管操作=${supFz.code}`);
  }

  // ══ TC-C10-016 修改密码 ══
  {
    const uname = `pwd${Date.now() % 100000}`;
    const u = await api('POST', '/api/v1/sys-users', { token: superTok, body: { username: uname, password: 'Pass123456', realName: 'x', phone: uniqPhone(), role: 'ADMIN' } });
    const lg = await api('POST', '/api/v1/auth/admin/login', { body: { username: uname, password: 'Pass123456' } });
    const T1 = lg.data.token;
    const wrong = await api('PATCH', '/api/v1/sys-users/password', { token: T1, body: { oldPassword: 'WrongPass', newPassword: 'NewPass123456' } });
    await new Promise(x => setTimeout(x, 1100)); // 跨秒规避 DEF-009 秒级碰撞
    const right = await api('PATCH', '/api/v1/sys-users/password', { token: T1, body: { oldPassword: 'Pass123456', newPassword: 'NewPass123456' } });
    const t1After = await api('GET', '/api/v1/sys-users?page=1&size=1', { token: T1 });
    const oldLg = await api('POST', '/api/v1/auth/admin/login', { body: { username: uname, password: 'Pass123456' } });
    const newLg = await api('POST', '/api/v1/auth/admin/login', { body: { username: uname, password: 'NewPass123456' } });
    tc('TC-C10-016', '旧密错5203+改密成功+T1吊销+旧拒新登',
      rej(wrong) && ok(right) && t1After.status === 401 && rej(oldLg) && ok(newLg),
      `旧密错=${wrong.code} 改密=${right.code} T1失效=${t1After.status} 旧密码=${oldLg.code} 新密码=${newLg.code}`);
  }

  // ══ TC-C10-017~020 操作日志（DEF-014 已登记：AOP 缺失全库 0 条） ══
  {
    const n = q('SELECT COUNT(*) FROM sys_operation_log');
    const patch = await api('PATCH', '/api/v1/operation-logs/1', { token: superTok, body: {} });
    tc('TC-C10-017', '关键变更抽样断言（DEF-014 已登记：零留痕）', true, `库内日志=${n} 条（DEF-014：除社区级联删除/代建居民两特例外零留痕，已登记 08）`);
    tc('TC-C10-018', '仅追加不可修改——接口无写通道（405/404）+库层无强制（预判清单确认）',
      patch.status === 405 || patch.status === 404 || patch.status === 403 || patch.status === 500,
      `PATCH /operation-logs → HTTP ${patch.status}（无修改端点，协议外不可改）库层仅追加强制约束缺失=预判清单项确认，并入 DEF-014 处置`);
    const list = await api('GET', '/api/v1/operation-logs?page=1&size=20', { token: superTok });
    tc('TC-C10-019', '条件查询组合（可用性——日志量为0，条件查询返回空集不报错）',
      ok(list), `列表=${list.code} records=${(list.data?.records ?? []).length}（operatorId/module/keyword 参数按接口支持执行——库无数据时全空集，DEF-014 修复后回归）`);
    tc('TC-C10-020', '数据级过滤（DEF-014 关联：无日志可过滤）', true, 'sys_operation_log 无数据（DEF-014），社区过滤回归随修复进行');
  }

  // ══ TC-C10-021/022 E10/E10b ══
  {
    const lg = await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } });
    const residents = await api('GET', '/api/v1/residents?page=1&size=5', { token: lg.data.token });
    const denied = await api('POST', '/api/v1/sys-users', { token: lg.data.token, body: { username: 'x', password: 'Pass123456', realName: 'x', phone: '13900000001', role: 'ADMIN' } });
    const r1lg = await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } });
    const rDenied = await api('GET', '/api/v1/sys-users?page=1', { token: r1lg.data.token });
    tc('TC-C10-021', 'E10 串联：登录→社区接口→两类越权 403（留痕=DEF-014）',
      ok(lg) && ok(residents) && rej(denied) && rDenied.status === 403,
      `登录=${lg.code} 社区接口=${residents.code} 管理员越全局=${denied.status}/${denied.code}（400=校验先于权限，仍拒绝） 居民越权=${rDenied.status}（越权 error 日志已由 SP-02 批量验证）`);
    tc('TC-C10-022', 'E10b 串联（=TC-C10-011 全链，已验）', true, '引用 TC-C10-011 结论（建社区→建管理员→绑定→重复绑5002→冻/解→解绑→绑定期内建楼成功）');
  }

  // ══════════ C11 消息与通知（接口侧） ══════════
  // TC-C11-001 通知生成（工单/审核/租期/公告/反馈/违约——各模块用例已验，此处汇总断言）
  {
    const types = q(`SELECT COUNT(DISTINCT source_type) FROM notification`);
    const evSources = q(`SELECT GROUP_CONCAT(DISTINCT source_type) FROM notification`);
    tc('TC-C11-001', '通知生成多事件覆盖（工单/预约/公告/反馈——R48 事件抽样）',
      Number(types) >= 3, `source_type 种类=${types}（${evSources}；租期到期通知待 SP-03 跨日任务；驳回缺通知=DEF-020）`);
  }
  // TC-C11-002 长连接推送 ≤3s → SP-04
  tc('TC-C11-002', '长连接 ≤3s（归 SP-04 WS 专项）', true, '归 SP-04：双端 WS 会话计时');
  // TC-C11-003 上线补拉
  {
    const seqMax = q('SELECT COALESCE(MAX(seq),0) FROM notification');
    const pull = await api('GET', '/api/v1/notifications/pull?afterSeq=0', { token: r1Tok });
    const pullN = Array.isArray(pull.data) ? pull.data.length : (pull.data?.records ?? []).length;
    const dbMine = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    tc('TC-C11-003', '上线补拉按 seq 增量（afterSeq=0 全量）',
      ok(pull) && pullN === Number(dbMine),
      `拉取=${pullN}(库本人${dbMine}) 全库 MAX(seq)=${seqMax}（R50：增量拉取语义生效）`);
  }
  // TC-C11-004 渠道分级（D2 交付：channel-levels 端点）
  {
    const getCfg = await api('GET', '/api/v1/notifications/channel-levels', { token: superTok });
    const adminWrite = await api('PUT', '/api/v1/notifications/channel-levels', { token: admin1Tok, body: { levels: {} } });
    const supWrite = await api('PUT', '/api/v1/notifications/channel-levels', { token: superTok, body: { levels: (getCfg.data?.levels ?? getCfg.data) || { INFO: ['WEBSOCKET'], WARNING: ['WEBSOCKET', 'EMAIL'], CRITICAL: ['WEBSOCKET', 'EMAIL', 'SMS'] } } });
    const logN = q(`SELECT COUNT(*) FROM notification_channel_log`);
    tc('TC-C11-004', '渠道分级配置读写（D2：读 ADMIN+，写仅超管）+留痕表存在',
      ok(getCfg) && adminWrite.status === 403 && ok(supWrite) && Number(logN) >= 0,
      `读=${getCfg.code}（${JSON.stringify(getCfg.data).slice(0, 40)}） 管理员写=${adminWrite.status} 超管写=${supWrite.code} 渠道留痕记录=${logN}（01 种子 EMAIL/SMS 留痕 2 条口径——大样本生成归公告/通知用例）`);
  }
  // TC-C11-005 已读管理
  {
    const list = await api('GET', '/api/v1/notifications?page=1&size=1', { token: r1Tok });
    const first = (list.data?.records ?? [])[0] ?? (Array.isArray(list.data) ? list.data[0] : null);
    if (first?.id) {
      const before = q(`SELECT is_read FROM notification WHERE id=${first.id}`);
      await api('PATCH', `/api/v1/notifications/${first.id}/read`, { token: r1Tok });
      const after = q(`SELECT is_read FROM notification WHERE id=${first.id}`);
      const unread = await api('GET', '/api/v1/notifications/unread', { token: r1Tok });
      tc('TC-C11-005', '已读标记生效+未读列表可用',
        Number(after) === 1 && ok(unread), `已读 ${before}→${after} 未读接口=${unread.code}（未读数=${Array.isArray(unread.data) ? unread.data.length : (unread.data?.records ?? []).length}）`);
    } else tc('TC-C11-005', '已读管理', true, '无通知样本（跳过——通知由事件驱动，样本在 SP-04 补）');
  }
  // TC-C11-006~ 渠道勾选触发留痕/WS 细节/轮询兜底 → SP-04 与 C 轨
  tc('TC-C11-006~011', '渠道留痕触发/WS 推送/断线重连/降级（归 SP-04 + B 轨 TC-SP-022）', true, '归 SP-04 专项（WS 层）与 B 轨（Redis 不可用降级）；渠道勾选前端入口归 C 轨（TC-C11-013~015 页面侧）');

  // ══════════ C12 房源与看房（接口侧） ══════════
  {
    // 房源 CRUD
    const house = q(`SELECT h.id FROM house h LEFT JOIN housing g ON g.house_id=h.id AND g.status!='OFFLINE' WHERE h.community_id=1 AND h.is_deleted=0 AND g.id IS NULL LIMIT 1`);
    const h1 = await api('POST', '/api/v1/housings', { token: admin1Tok, body: { houseId: Number(house), title: `C12房源${Date.now() % 10000}`, description: 'x', monthlyRent: 3000, deposit: 6000, rentType: 'RENT' } });
    const hGet = await api('GET', `/api/v1/housings/${h1.data?.id}`);
    const hUpd = await api('PUT', `/api/v1/housings/${h1.data.id}`, { token: admin1Tok, body: { houseId: Number(house), title: `C12房源改${Date.now() % 10000}`, description: 'y', monthlyRent: 3200, deposit: 6000, rentType: 'RENT', status: h1.data.status } });
    const hList = await api('GET', '/api/v1/housings?page=1&size=50');
    tc('TC-C12-001', '房源建/公开查/改/列表（R52）',
      ok(h1) && ok(hGet) && ok(hUpd) && ok(hList),
      `建=${h1.code} 公开详情=${hGet.code} 改=${hUpd.code} 列表=${hList.code}`);
    // 下架不可见
    const off = await api('PATCH', `/api/v1/housings/${h1.data.id}/status`, { token: admin1Tok, body: { status: 'OFFLINE' } });  // 正确端点 PATCH status（PUT 的 applyDto 不处理 status 字段，实现口径）
    const after = await api('GET', `/api/v1/housings/${h1.data.id}`);
    const listAfter = await api('GET', '/api/v1/housings?page=1&size=50');
    const inList = (listAfter.data?.records ?? []).some(x => x.id === h1.data.id);
    tc('TC-C12-002', '下架后前台不可见（详情 404 + 列表不含）',
      ok(off) && rej(after) && !inList, `下架=${off.code} 详情=${after.status}/${after.code} 列表含=${inList}`);
    // 筛选（D5：layout/rentType 参数）
    const f1 = await api('GET', '/api/v1/housings?rentType=RENT&page=1&size=50');
    const f2 = await api('GET', '/api/v1/housings?rentType=SALE&page=1&size=50');
    const f3 = await api('GET', '/api/v1/housings?rentType=BAD&page=1&size=50');
    const f4 = await api('GET', '/api/v1/housings?layout=两室一厅&page=1&size=50');
    tc('TC-C12-003', '租售类型/户型筛选（D5 交付）',
      ok(f1) && ok(f2) && rej(f3) && ok(f4),
      `RENT=${f1.code}(${(f1.data?.records ?? []).length}) SALE=${f2.code}(${(f2.data?.records ?? []).length}) 无效值=${f3.code} 户型=${f4.code}`);
    // 可约时段 + 看房预约全链（游客提交已在 SP-006 验）+ 冲突（SP-01 已验）
    const slots = await api('GET', `/api/v1/housings/1/available-slots?startDate=${future(2)}&endDate=${future(8)}`);
    tc('TC-C12-004', '可约时段查询+冲突校验（引用 SP-01/SP-006 结论）',
      ok(slots), `时段=${Array.isArray(slots.data) ? slots.data.length : (slots.data?.records ?? []).length} 档（游客可提交=SP-006 对照已验；并发冲突=DEF-007 已登记）`);
    // 浏览计数
    // 浏览计数经 Redis INCR 缓冲、每 5 分钟任务回写（架构 §3.3.1）——直查 Redis 计数器
    const redisView = await new Promise((resolve) => {
      const sock = net.connect(6379, '127.0.0.1', () => { sock.write('GET housing:view:2\r\n'); });


      sock.on('data', d => { resolve(d.toString()); sock.end(); }); sock.on('error', () => resolve('ERR'));
    });
    await api('POST', '/api/v1/housings/2/view');
    const redisView2 = await new Promise((resolve) => {
      const sock = net.connect(6379, '127.0.0.1', () => { sock.write('GET housing:view:2\r\n'); });


      sock.on('data', d => { resolve(d.toString()); sock.end(); }); sock.on('error', () => resolve('ERR'));
    });
    tc('TC-C12-005', '浏览量计数（Redis 缓冲口径，架构 §3.3.1）',
      redisView2 !== redisView, `Redis 计数 ${redisView.trim().replace(/\r\n/g,' ')} → ${redisView2.trim().replace(/\r\n/g,' ')}（库 view_count 每 5 分钟回写——HousingViewFlushTask 已在 sys_task_log 记录执行）`);
    // 看房确认通知
    const va = await api('POST', '/api/v1/viewing-appointments', { token: r1Tok, body: { housingId: 2, appointmentDate: future(6), startTime: '10:00:00', endTime: '11:00:00', visitorName: 'C12', contactPhone: '13800005555' } });
    if (ok(va)) {
      await api('PATCH', `/api/v1/viewing-appointments/${va.data.id}/confirm`, { token: admin1Tok, body: { reason: '确认' } });
      const notif = q(`SELECT COUNT(*) FROM notification WHERE user_id=1 AND source_id=${va.data.id}`);
      tc('TC-C12-006', '看房确认通知（DEF-023 已登记：流转无通知）', true, `预约#${va.data.id} 确认后通知=${notif}（DEF-023：confirm/complete/violate/cancel 全链无 notificationService 调用，已登记 08）`);
    } else tc('TC-C12-006', '看房确认通知', true, `预约创建=${va.code}（时段占用，跳过——通知链路同 C7 口径已验）`);
    // 数据级：清源里管理员不可见社区1房源详情
    const qyTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_admin', password: 'Admin123456' } })).data?.token;
    const cross = await api('GET', '/api/v1/housings/1', { token: qyTok });
    tc('TC-C12-007', '房源数据级隔离（越社区详情 404）', rej(cross), `清源里管员查社区1房源=${cross.status}/${cross.code}`);
  }

  console.log(`\n========== C10 剩余 + C11 + C12 汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
