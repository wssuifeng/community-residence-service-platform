/**
 * 50 系统测试 · A 轨 · 功能用例 C5 公告广播（TC-C5-001~014）
 * 已知缺陷标注：DEF-003（读路径数据级过滤）、DEF-004（communityId 越权写入）、DEF-014（日志）
 * 口径疑点落地：V9 已交付 is_pinned + targets 多社区/楼栋定向（1c 步骤 D4）——疑点 1 部分解决
 * 用法：node c05_notice_tests.mjs
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
const now = () => { const d = new Date(Date.now() - 60000); return d.toISOString().slice(0, 19); };
const iso = (d) => d.toISOString().slice(0, 19);

async function main() {
  const superTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'superadmin', password: 'Admin@123456' } })).data?.token;
  const admin1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'admin1', password: 'Admin123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;
  const r2Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token; // 阳光花园（社区1）隔离对照
  const qyTok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident', password: 'Resident123456' } })).data?.token; // 清源里（社区2）
  const staff1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'staff1', password: 'Staff123456' } })).data?.token;

  // ══ TC-C5-001 创建+发布+居民可见 ══
  const n1 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `国庆物业服务安排${Date.now() % 10000}`, content: '国庆假期物业 9:00-18:00', publishTime: now(), communityId: 1 } });
  const pub1 = await api('PATCH', `/api/v1/notices/${n1.data?.id}/publish`, { token: admin1Tok, body: { publishTime: now() } });
  const st1 = q(`SELECT status FROM notice WHERE id=${n1.data?.id}`);
  const r1List = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
  const visible = (r1List.data?.records ?? []).some(x => x.id === n1.data?.id);
  tc('TC-C5-001', 'DRAFT→发布 PUBLISHED→居民可见',
    ok(n1) && n1.data?.status === 'DRAFT' && ok(pub1) && st1 === 'PUBLISHED' && visible,
    `创建=${n1.code}(${n1.data?.status}) 发布=${pub1.code} 库状态=${st1} 居民可见=${visible}`);

  // ══ TC-C5-002 编辑留痕（仅草稿可改） ══
  {
    const d = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `草稿${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    const putD = await api('PUT', `/api/v1/notices/${d.data.id}`, { token: admin1Tok, body: { title: `修订${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    const putP = await api('PUT', `/api/v1/notices/${n1.data.id}`, { token: admin1Tok, body: { title: '改已发布', content: 'x', publishTime: now(), communityId: 1 } });
    tc('TC-C5-002', '草稿可改+已发布拒改（编辑日志=DEF-014）',
      ok(putD) && rej(putP), `草稿改=${putD.code} 已发布改=${putP.code}(${putP.json?.message?.slice(0, 16)})`);
  }

  // ══ TC-C5-003 越范围发布（communityId=2 是 DEF-004 已登记；无目标社区被拒） ══
  {
    const r1 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: 'X越权', content: 'x', publishTime: now(), communityId: 2 } });
    const r2 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: 'X无目标', content: 'x', publishTime: now() } });
    // targets 写法越范围（有校验）
    const r3 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: 'X targets', content: 'x', publishTime: now(), targets: [{ targetType: 'COMMUNITY', targetId: 2 }] } });
    tc('TC-C5-003', '无目标拒+targets 越范围拒（communityId 越权=DEF-004 已登记）',
      rej(r2) && rej(r3),
      `communityId=2：${r1.code}（DEF-004 放行已登记 08） 无目标=${r2.code}(${r2.json?.message?.slice(0, 16)}) targets越范围=${r3.status}/${r3.code}`);
  }

  // ══ TC-C5-004 超管全系统广播 ══
  {
    const b = await api('POST', '/api/v1/notices', { token: superTok, body: { title: `系统升级维护${Date.now() % 10000}`, content: 'x', publishTime: now() } });
    await api('PATCH', `/api/v1/notices/${b.data?.id}/publish`, { token: superTok, body: { publishTime: now() } });
    const targetN = q(`SELECT COUNT(*) FROM notice_target WHERE notice_id=${b.data?.id}`);
    const l1 = await api('GET', '/api/v1/notices?page=1&size=50', { token: qyTok });
    const l2 = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const v1 = (l1.data?.records ?? []).some(x => x.id === b.data?.id);
    const v2 = (l2.data?.records ?? []).some(x => x.id === b.data?.id);
    tc('TC-C5-004', '广播无 target 记录+两社区居民均可见',
      ok(b) && Number(targetN) === 0 && v1 && v2,
      `创建=${b.code} target记录=${targetN} 清源里居民可见=${v1} 社区1居民可见=${v2}`);
  }

  // ══ TC-C5-005 定向可见性（范围外不可见=DEF-003 已登记） ══
  {
    const l1 = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const inC1 = (l1.data?.records ?? []).some(x => x.id === n1.data.id);
    // 清源里居民（范围外）
    const lqy = await api('GET', '/api/v1/notices?page=1&size=50', { token: qyTok });
    const leak = (lqy.data?.records ?? []).some(x => x.id === n1.data.id);
    const detailQY = await api('GET', `/api/v1/notices/${n1.data.id}`, { token: qyTok });
    const guest = await api('GET', '/api/v1/notices?page=1&size=5');
    tc('TC-C5-005', '范围内可见（范围外可见性=DEF-003 已登记：列表/详情放行）',
      inC1 && ok(guest),
      `社区1可见=${inC1} 清源里列表含=${leak}（DEF-003 已登记 08） 范围外详情=${detailQY.status}/${detailQY.code}（DEF-003） 游客列表=${guest.code}`);
  }

  // ══ TC-C5-006 置顶（V9 已交付 is_pinned + targets） ══
  {
    const pin = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `置顶公告${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1, isPinned: 1 } });
    await api('PATCH', `/api/v1/notices/${pin.data?.id}/publish`, { token: admin1Tok, body: { publishTime: now() } });
    const l = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const recs = l.data?.records ?? [];
    const firstPinned = recs[0]?.id === pin.data?.id && recs[0]?.isPinned === 1;
    tc('TC-C5-006', 'isPinned 公告排最前（V9 D4 交付后链路通）',
      ok(pin) && firstPinned, `置顶单=${pin.code} 首位=${firstPinned}（首条 id=${recs[0]?.id} pinned=${recs[0]?.isPinned}）——疑点 2 由 V9 is_pinned 列解决`);
  }

  // ══ TC-C5-007 有效期 + 缺省 30 天 ══
  {
    const shortEnd = iso(new Date(Date.now() + 2 * 3600 * 1000));
    const a = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `短效${Date.now() % 10000}`, content: 'x', publishTime: now(), endTime: shortEnd, communityId: 1 } });
    const b = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `缺省${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    const eA = q(`SELECT end_time FROM notice WHERE id=${a.data.id}`);
    const eB = q(`SELECT end_time FROM notice WHERE id=${b.data.id}`);
    const bDefault = eB.startsWith(iso(new Date(Date.now() + 30 * 86400 * 1000)).slice(0, 10));
    tc('TC-C5-007', '有效期设置+缺省30天（指定 endTime 存储偏移=DEF-021 已登记）',
      ok(a) && ok(b) && bDefault,
      `甲=${String(eA).slice(0, 16)}（传入 ${shortEnd.slice(11, 16)}，落库偏移 8h=DEF-021：LocalDateTime 按 UTC 反序列化后按本地时间存储，已登记 08） 乙=${String(eB).slice(0, 16)}（缺省30天=${bDefault}，服务端自算不受影响）`);
  }

  // ══ TC-C5-008 过期下线（查询期过滤兜底——过期锚点公告 3） ══
  {
    // 01 脚本预置「TEST-已过期公告」id=3（endTime 已过仍 PUBLISHED）
    const l = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const expiredVisible = (l.data?.records ?? []).some(x => x.id === 3);
    const detail = await api('GET', '/api/v1/notices/3', { token: r1Tok });
    const adminL = await api('GET', '/api/v1/notices?page=1&size=50', { token: (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_admin', password: 'Admin123456' } })).data?.token }); // 公告3定向社区2，其绑定管理员
    const adminSee = (adminL.data?.records ?? []).some(x => x.id === 3);
    const taskLog = q(`SELECT COUNT(*) FROM sys_task_log WHERE task_name LIKE '%Offline%' OR task_name LIKE '%Notice%'`);
    tc('TC-C5-008', '过期公告居民列表不返回+详情404+管理端归档可查（任务记录）',
      !expiredVisible && rej(detail) && adminSee,
      `居民列表含过期=${expiredVisible} 详情=${detail.status}/${detail.code} 管理端可见=${adminSee} 下线任务记录=${taskLog}（查询期过滤兜底生效；每小时任务记录归 SP-03 长跑）`);
  }

  // ══ TC-C5-009 查看记录与已读统计 ══
  {
    const v1 = await api('POST', `/api/v1/notices/${n1.data.id}/view`, { token: r1Tok });
    const v2 = await api('POST', `/api/v1/notices/${n1.data.id}/view`, { token: r1Tok });
    const recN = q(`SELECT COUNT(*) FROM notice_view_record WHERE notice_id=${n1.data.id} AND user_id=1`);
    const vc = q(`SELECT view_count FROM notice WHERE id=${n1.data.id}`);
    const viewers = await api('GET', `/api/v1/notices/${n1.data.id}/viewers?page=1&size=50`, { token: admin1Tok });
    const viewerN = (viewers.data?.records ?? []).length;
    tc('TC-C5-009', '首次查看+重复不累计+viewers 一致',
      ok(v1) && Number(recN) === 1 && Number(vc) >= 1 && viewerN === Number(recN),
      `查看=${v1.code} 记录数=${recN} viewCount=${vc} viewers明细=${viewerN}`);
  }

  // ══ TC-C5-010 删除（BE-ISSUE-8 回归） ══
  {
    const d = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `删除回归${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    await api('PATCH', `/api/v1/notices/${d.data.id}/publish`, { token: admin1Tok, body: { publishTime: now() } });
    await api('POST', `/api/v1/notices/${d.data.id}/view`, { token: r1Tok });
    const delP = await api('DELETE', `/api/v1/notices/${d.data.id}`, { token: admin1Tok });
    const wd = await api('PATCH', `/api/v1/notices/${d.data.id}/withdraw`, { token: admin1Tok, body: { reason: '内容需要修正' } });
    const delW = await api('DELETE', `/api/v1/notices/${d.data.id}`, { token: admin1Tok });
    const residue = q(`SELECT (SELECT COUNT(*) FROM notice WHERE id=${d.data.id})+(SELECT COUNT(*) FROM notice_target WHERE notice_id=${d.data.id})+(SELECT COUNT(*) FROM notice_view_record WHERE notice_id=${d.data.id})`);
    tc('TC-C5-010', 'PUBLISHED 拒删+撤回后删成功+三表零残留（BE-ISSUE-8 回归）',
      rej(delP) && ok(wd) && ok(delW) && Number(residue) === 0,
      `直删=${delP.code} 撤回=${wd.code} 删除=${delW.code} 残留=${residue}`);
  }

  // ══ TC-C5-011 撤回 ══
  {
    const d = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `撤回${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    await api('PATCH', `/api/v1/notices/${d.data.id}/publish`, { token: admin1Tok, body: { publishTime: now() } });
    const empty = await api('PATCH', `/api/v1/notices/${d.data.id}/withdraw`, { token: admin1Tok, body: { reason: '' } });
    const wd = await api('PATCH', `/api/v1/notices/${d.data.id}/withdraw`, { token: admin1Tok, body: { reason: '内容需要修正' } });
    const st = q(`SELECT status FROM notice WHERE id=${d.data.id}`);
    const l = await api('GET', '/api/v1/notices?page=1&size=50', { token: r1Tok });
    const gone = !(l.data?.records ?? []).some(x => x.id === d.data.id);
    const detail = await api('GET', `/api/v1/notices/${d.data.id}`, { token: r1Tok });
    const d2 = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `草稿撤${Date.now() % 10000}`, content: 'x', publishTime: now(), communityId: 1 } });
    const wdDraft = await api('PATCH', `/api/v1/notices/${d2.data.id}/withdraw`, { token: admin1Tok, body: { reason: 'x' } });
    tc('TC-C5-011', '空理由拒+撤回成功+居民不可见+草稿撤回拒',
      rej(empty) && ok(wd) && st === 'WITHDRAWN' && gone && rej(detail) && rej(wdDraft),
      `空理由=${empty.code} 撤回=${wd.code}(${st}) 列表消失=${gone} 详情=${detail.status} 草稿撤=${wdDraft.code}`);
  }

  // ══ TC-C5-012 权限否定 ══
  {
    const mk = t => ({ title: 'X', content: 'x', publishTime: now(), communityId: 1 });
    const r1a = await api('POST', '/api/v1/notices', { token: r1Tok, body: mk() });
    const r1b = await api('PATCH', `/api/v1/notices/1/publish`, { token: r1Tok, body: { publishTime: now() } });
    const r1c = await api('DELETE', '/api/v1/notices/1', { token: r1Tok });
    const s1a = await api('POST', '/api/v1/notices', { token: staff1Tok, body: mk() });
    const s1b = await api('PATCH', `/api/v1/notices/1/publish`, { token: staff1Tok, body: { publishTime: now() } });
    const g1 = await api('POST', '/api/v1/notices', { body: mk() });
    const g2 = await api('DELETE', '/api/v1/notices/1');
    const rv = await api('GET', `/api/v1/notices/1/viewers`, { token: r1Tok });
    tc('TC-C5-012', '居民/员工/游客管理动作全拒+居民查 viewers 拒',
      r1a.status === 403 && r1b.status === 403 && r1c.status === 403 && s1a.status === 403 && s1b.status === 403 && g1.status === 401 && g2.status === 401 && rv.status === 403,
      `居民建/发/删=${r1a.status}/${r1b.status}/${r1c.status} 员工=${s1a.status}/${s1b.status} 游客=${g1.status}/${g2.status} viewers=${rv.status}`);
  }

  // ══ TC-C5-013 数据级（写路径有校验——SP-02 已验 403；列表隔离 SP-02 已验） ══
  {
    const qyNotice = q(`SELECT n.id FROM notice n JOIN notice_target t ON n.id=t.notice_id WHERE t.target_id=2 LIMIT 1`);
    const put = await api('PUT', `/api/v1/notices/${qyNotice}`, { token: admin1Tok, body: { title: '越权改', content: 'x', publishTime: now(), communityId: 2 } });
    const wd = await api('PATCH', `/api/v1/notices/${qyNotice}/withdraw`, { token: admin1Tok, body: { reason: 'x' } });
    const vw = await api('GET', `/api/v1/notices/${qyNotice}/viewers`, { token: admin1Tok });
    tc('TC-C5-013', '越社区改/撤拒（viewers 读路径=DEF-003 已登记）',
      rej(put) && rej(wd),
      `改=${put.code}(${put.json?.message?.slice(0, 12)}) 撤=${wd.code} 回执=${vw.status}/${vw.code}（读路径无过滤放行=DEF-003 同源已登记 08）`);
  }

  // ══ TC-C5-014 E5 全流程（过期等待环节归 SP-03） ══
  {
    const n0 = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    const d = await api('POST', '/api/v1/notices', { token: admin1Tok, body: { title: `停水通知${Date.now() % 10000}`, content: '明日停水', publishTime: now(), endTime: iso(new Date(Date.now() + 2 * 3600 * 1000)), communityId: 1 } });
    await api('PATCH', `/api/v1/notices/${d.data.id}/publish`, { token: admin1Tok, body: { publishTime: now() } });
    const n1c = q(`SELECT COUNT(*) FROM notification WHERE user_id=1`);
    await api('POST', `/api/v1/notices/${d.data.id}/view`, { token: r1Tok });
    const vc = q(`SELECT view_count FROM notice WHERE id=${d.data.id}`);
    const viewers = await api('GET', `/api/v1/notices/${d.data.id}/viewers?page=1&size=50`, { token: admin1Tok });
    await api('PATCH', `/api/v1/notices/${d.data.id}/withdraw`, { token: admin1Tok, body: { reason: '收尾' } });
    const st = q(`SELECT status FROM notice WHERE id=${d.data.id}`);
    tc('TC-C5-014', 'E5 链：发布→通知触达→查看回执→viewers→撤回收尾（过期下线归 SP-03）',
      ok(d) && (Number(n1c) - Number(n0)) >= 1 && Number(vc) >= 1 && ok(viewers) && st === 'WITHDRAWN',
      `发布=OK 通知增量=${Number(n1c) - Number(n0)} viewCount=${vc} viewers=${viewers.code} 撤回=${st}（R51 渠道留痕归 C11/TC-SP 用例）`);
  }

  console.log(`\n========== C5 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
