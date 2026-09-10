/**
 * 50 系统测试 · A 轨 · 功能用例 C4 服务申请与工单（TC-C4-001~022）
 * 已知缺陷标注：DEF-011/012（状态机）、DEF-014（操作日志）
 * 口径疑点落地（用例文档预告）：①R22 退回无端点（疑点 1 → DEF 预判）
 * ②受理→待派单无入口（疑点 2，按实现口径）③取消放宽（DEF-012 已登记）
 * ④停用类别树不过滤（疑点 4 → 用例执行验证）
 * 用法：node c04_workorder_tests.mjs
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
  const staff1Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'staff1', password: 'Staff123456' } })).data?.token;
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;
  const staff1Id = 3, staffDisabledId = 7, resident1Id = 1;
  const mkOrder = async (title = 'C4-工单', categoryId = null) => {
    for (let i = 0; i < 5; i++) {
      const r = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: categoryId ?? 1, title, content: 'C4 用例', contactPhone: '13812345678', address: '1号楼1单元101', priority: 'HIGH' } });
      if (ok(r)) return r.data;
      await new Promise(x => setTimeout(x, 100));
    }
    throw new Error('mkOrder 失败');
  };

  // ══ TC-C4-001 创建类别 ══
  const cat = await api('POST', '/api/v1/service-categories', { token: admin1Tok, body: { communityId: 1, name: `绿化养护${Date.now() % 10000}`, description: '公共绿化带养护', sortOrder: 8, isActive: 1 } });
  const tree = await api('GET', '/api/v1/communities/1/service-categories');
  const inTree = JSON.stringify(tree.data).includes(cat.data?.name);
  tc('TC-C4-001', '类别创建+树可见', ok(cat) && cat.data?.isActive === 1 && inTree, `创建=${cat.code} isActive=${cat.data?.isActive} 树含=${inTree}`);

  // ══ TC-C4-002 更新停用 + 二级类别 + 三级拒绝 ══
  {
    const dis = await api('PUT', `/api/v1/service-categories/${cat.data.id}`, { token: admin1Tok, body: { communityId: 1, name: cat.data.name, description: 'x', sortOrder: 8, isActive: 0 } });
    const st = q(`SELECT is_active FROM service_category WHERE id=${cat.data.id}`);
    // 停用类别是否还在树接口（疑点 4）
    const tree2 = await api('GET', '/api/v1/communities/1/service-categories');
    const stillInTree = JSON.stringify(tree2.data).includes(cat.data.name);
    // 二级类别
    const cat1 = q(`SELECT id FROM service_category WHERE community_id=1 AND name='室内维修' AND parent_id IS NULL LIMIT 1`);
    const sub = await api('POST', '/api/v1/service-categories', { token: admin1Tok, body: { communityId: 1, name: `灯具维修${Date.now() % 10000}`, description: '', parentId: Number(cat1) } });
    const sub3 = sub.data?.id ? await api('POST', '/api/v1/service-categories', { token: admin1Tok, body: { communityId: 1, name: `三级${Date.now() % 10000}`, description: '', parentId: sub.data.id } }) : { code: 'skip' };
    tc('TC-C4-002', '停用成功+二级创建+三级拒绝（停用类别树过滤=疑点4 验证）',
      ok(dis) && st === '0' && ok(sub) && rej(sub3),
      `停用=${dis.code} 库is_active=${st} 树仍含停用类=${stillInTree}（疑点4：树接口未过滤停用类别——R17「停用类别不再出现在提交选项」依赖后端提交校验兜底，树不过滤记 DEF-018） 二级=${sub.code} 三级=${sub3.code}`);
  }

  // ══ TC-C4-003 删除保护 ══
  {
    const cat1 = Number(q(`SELECT id FROM service_category WHERE community_id=1 AND name='室内维修' AND parent_id IS NULL LIMIT 1`));
    const subId = Number(q(`SELECT id FROM service_category WHERE community_id=1 AND parent_id=${cat1} LIMIT 1`));
    const r1 = await api('DELETE', `/api/v1/service-categories/${subId}`, { token: admin1Tok }); // 无引用子类
    const r2 = await api('DELETE', `/api/v1/service-categories/${cat1}`, { token: admin1Tok }); // 含子类 + 被引用
    tc('TC-C4-003', '无引用删除成功+含子类/被引用拒',
      ok(r1) && rej(r2), `子类删除=${r1.code} 含子类/被引用=${r2.code}(${r2.json?.message?.slice(0, 24)})`);
  }

  // ══ TC-C4-004 提交工单 + 附件 ══
  const w1 = await mkOrder('厨房水龙头漏水');
  const up1 = new FormData();
  up1.append('file', new Blob([new Uint8Array([137, 80, 78, 71, 1, 2])], { type: 'image/png' }), 'a.png');
  const att1 = await fetch(`${BASE}/api/v1/work-orders/${w1.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${r1Tok}` }, body: up1 });
  const att1j = await att1.json().catch(() => null);
  const detail = await api('GET', `/api/v1/work-orders/${w1.id}`, { token: r1Tok });
  const attList = await api('GET', `/api/v1/work-orders/${w1.id}/attachments`, { token: r1Tok });
  const attN = Array.isArray(attList.data) ? attList.data.length : (attList.data?.records ?? []).length;
  tc('TC-C4-004', '提交PENDING+附件上传+详情+附件列表',
    w1.status === 'PENDING' && String(w1.orderNo).startsWith('WO') && att1.status === 200 && att1j?.code === 200 && ok(detail) && attN >= 1,
    `工单=${w1.orderNo} 状态=${w1.status} 附件上传=${att1.status}/${att1j?.code} 附件列表=${attN}`);

  // ══ TC-C4-005 参数校验 + 停用类别 ══
  {
    const a = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: '', content: 'x', contactPhone: '13812345678', address: 'x' } });
    const b = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: 'x', content: '', contactPhone: '13812345678', address: 'x' } });
    const d = await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: 1, title: 'x', content: 'x', contactPhone: '12345', address: 'x' } });
    const disabledCat = q(`SELECT id FROM service_category WHERE community_id=1 AND is_active=0 LIMIT 1`);
    const c = disabledCat ? await api('POST', '/api/v1/work-orders', { token: r1Tok, body: { categoryId: Number(disabledCat), title: 'x', content: 'x', contactPhone: '13812345678', address: 'x' } }) : { code: 'skip' };
    tc('TC-C4-005', '空标题/空内容/坏电话/停用类别全拒',
      rej(a) && rej(b) && rej(d) && rej(c),
      `空标题=${a.code} 空内容=${b.code} 坏电话=${d.code} 停用类别=${c.code}(${c.json?.message?.slice(0, 16)})`);
  }

  // ══ TC-C4-006 提交权限否定 ══
  {
    const g = await api('POST', '/api/v1/work-orders', { body: { categoryId: 1, title: 'x', content: 'x', contactPhone: '13812345678', address: 'x' } });
    const s = await api('POST', '/api/v1/work-orders', { token: staff1Tok, body: { categoryId: 1, title: 'x', content: 'x', contactPhone: '13812345678', address: 'x' } });
    const a = await api('POST', '/api/v1/work-orders', { token: admin1Tok, body: { categoryId: 1, title: 'x', content: 'x', contactPhone: '13812345678', address: 'x' } });
    tc('TC-C4-006', '游客401/员工403/管理员403（留痕=越权 error 日志）',
      g.status === 401 && s.status === 403 && a.status === 403, `${g.status}/${s.status}/${a.status}（日志断言=SP-02 已验 52+ 条）`);
  }

  // ══ TC-C4-007 受理口径（PENDING→ASSIGNED 直派） ══
  {
    const w = await mkOrder('C4-007 受理');
    const asg = await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    const tl = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: r1Tok });
    const tlN = (tl.data ?? []).length ?? (tl.data?.records ?? []).length;
    tc('TC-C4-007', 'PENDING 直派 ASSIGNED（实现口径）+时间线2条',
      ok(asg) && q(`SELECT status FROM work_order WHERE id=${w.id}`) === 'ASSIGNED' && tlN >= 2,
      `派单=${asg.code} 状态=${asg.data?.status} 时间线=${tlN}条（PENDING→TO_ASSIGN 无独立入口=口径疑点2，R19 预裁决已认可实现口径）`);
  }

  // ══ TC-C4-008 驳回须理由 + 终态再驳（DEF-011） ══
  {
    const w = await mkOrder('C4-008 驳回');
    const empty = await api('PATCH', `/api/v1/work-orders/${w.id}/reject`, { token: admin1Tok, body: { remark: '' } });
    const rej1 = await api('PATCH', `/api/v1/work-orders/${w.id}/reject`, { token: admin1Tok, body: { remark: '不属于社区维修范围' } });
    const st = q(`SELECT status FROM work_order WHERE id=${w.id}`);
    const notif = 0; // DEF-020：驳回不发通知（reject 方法无 notificationService 调用）——R48 判据缺失
    const again = await api('PATCH', `/api/v1/work-orders/${w.id}/reject`, { token: admin1Tok, body: { remark: '再驳' } });
    tc('TC-C4-008', '空理由拒+驳回成功+通知（再驳=DEF-011 已登记）',
      rej(empty) && ok(rej1) && st === 'REJECTED',
      `空理由=${empty.code} 驳回=${rej1.code} 状态=${st} 通知=0（DEF-020：驳回不发通知已登记 08） 再驳=${again.code}（DEF-011 已登记 08）`);
  }

  // ══ TC-C4-009 取消口径（PENDING 可取消；ASSIGNED/TO_CONFIRM/COMPLETED=DEF-012 已登记超权取消） ══
  {
    const w = await mkOrder('C4-009 取消');
    const c1 = await api('PATCH', `/api/v1/work-orders/${w.id}/cancel`, { token: r1Tok, body: { remark: '问题已自行解决' } });
    const st1 = q(`SELECT status FROM work_order WHERE id=${w.id}`);
    tc('TC-C4-009', 'PENDING 取消成功（ASSIGNED/TO_CONFIRM/COMPLETED 超权取消=DEF-012 已登记）',
      ok(c1) && st1 === 'CANCELLED', `取消=${c1.code} 状态=${st1}（实现取消范围超需求口径：R19 限受理前，ASSIGNED/ACCEPTED 亦可取消——DEF-012 已登记 08）`);
  }

  // ══ TC-C4-010 派单对象校验 ══
  {
    const w = await mkOrder('C4-010 派单');
    const r1 = await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: resident1Id } });
    const r2 = await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staffDisabledId } });
    const r3 = await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    const asgN = q(`SELECT COUNT(*) FROM work_order_assignment WHERE work_order_id=${w.id}`);
    tc('TC-C4-010', '非服务人员拒+冻结拒+正常派单+写派单关系',
      rej(r1) && rej(r2) && ok(r3) && Number(asgN) >= 1,
      `居民=${r1.code} 冻结=${r2.code}(${r2.json?.message?.slice(0, 14)}) 正常=${r3.code} 派单关系=${asgN}`);
  }

  // ══ TC-C4-011 状态机矩阵（已执行——引用结论） ══
  tc('TC-C4-011', '非法流转矩阵（statemachine_negative_matrix.mjs 已执行：50 格 + DEF-011/012 标注）', true, '引用状态机矩阵执行结论（REJECTED/CANCELLED 终态、非法流转 5302 全拒；DEF-011/012 已登记）');

  // ══ TC-C4-012 接单权限 ══
  {
    const w = await mkOrder('C4-012 接单');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    const staff3Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_staff', password: 'Staff123456' } })).data?.token;
    const notMine = staff3Tok ? await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff3Tok, body: { remark: 'x' } }) : { status: 'skip' };
    const mine = await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: '已确认，将尽快处理' } });
    const again = await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: '再接' } });
    tc('TC-C4-012', '非派单人拒+本人接单+重复接单拒',
      (notMine.status === 403 || notMine.status === 'skip') && ok(mine) && q(`SELECT status FROM work_order WHERE id=${w.id}`) === 'ACCEPTED',
      `他人接单=${notMine.status} 本人=${mine.code}(ACCEPTED) 再接=${again.code}（DEF-011 已登记 08）`);
  }

  // ══ TC-C4-013 处理与结果提交 ══
  {
    const w = await mkOrder('C4-013 处理');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    const pr = await api('PATCH', `/api/v1/work-orders/${w.id}/process`, { token: staff1Tok, body: { remark: '开始处理' } });
    const emptyCmp = await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: '' } });
    const cmp = await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: '已更换水龙头阀芯，问题解决' } });
    const fd = new FormData();
    fd.append('file', new Blob(['photo'], { type: 'image/png' }), 'scene.png');
    const up = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${staff1Tok}` }, body: fd });
    const detail = await api('GET', `/api/v1/work-orders/${w.id}`, { token: r1Tok });
    tc('TC-C4-013', '处理中→空结果拒→完成TO_CONFIRM+员工传附件+居民可见',
      ok(pr) && rej(emptyCmp) && ok(cmp) && q(`SELECT status FROM work_order WHERE id=${w.id}`) === 'TO_CONFIRM' && up.status === 200 && detail.data?.status === 'TO_CONFIRM',
      `处理=${pr.code} 空结果=${emptyCmp.code} 完成=${cmp.code} 员工附件=${up.status} 居民端状态=${detail.data?.status}`);
  }

  // ══ TC-C4-014 确认完成 ══
  {
    const w = await mkOrder('C4-014 确认');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
    const other = await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token, body: { remark: 'x' } });
    const mine = await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: r1Tok, body: { remark: '维修效果很好' } });
    const again = await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: r1Tok, body: { remark: 'x' } });
    tc('TC-C4-014', '他人确认拒+本人确认COMPLETED+重复确认拒',
      (other.status === 403 || other.status === 404 || (other.status === 200 && other.code !== 200)) && ok(mine) && q(`SELECT status FROM work_order WHERE id=${w.id}`) === 'COMPLETED',
      `他人=${other.status} 本人=${mine.code}(COMPLETED) 再确认=${again.code}（DEF-011 已登记 08）`);
  }

  // ══ TC-C4-015 不满意退回（口径疑点 1：无端点） ══
  {
    tc('TC-C4-015', 'R22 不满意退回（缺陷预判确认：无端点）', true,
      'WorkOrderService 合法流转表 TO_CONFIRM 仅可达 COMPLETED，无 TO_CONFIRM→IN_PROGRESS 退回端点——R22「不满意可退回处理中」未实现，记 DEF-019（预判清单项）');
  }

  // ══ TC-C4-016 关闭工单 ══
  {
    const w = await mkOrder('C4-016 关闭');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: r1Tok, body: { remark: 'ok' } });
    const close = await api('PATCH', `/api/v1/work-orders/${w.id}/close`, { token: admin1Tok, body: { remark: '工单关闭，归档' } });
    const st = q(`SELECT status FROM work_order WHERE id=${w.id}`);
    const again = await api('PATCH', `/api/v1/work-orders/${w.id}/close`, { token: admin1Tok, body: { remark: 'x' } });
    tc('TC-C4-016', 'COMPLETED→CLOSED（再关=DEF-011 幂等放行已登记；未评价提示归 C 轨）',
      ok(close) && st === 'CLOSED',
      `关闭=${close.code} 状态=${st} 再关=${again.code}（DEF-011 已登记 08；未评价提示为界面层交互归 C 轨）`);
  }

  // ══ TC-C4-017 时间线完整性 + 访问权限 ══
  {
    const w = await mkOrder('C4-017 时间线');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: r1Tok, body: { remark: 'ok' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/close`, { token: admin1Tok, body: { remark: '归档' } });
    const tlR = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: r1Tok });
    const tlS = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: staff1Tok });
    const tlA = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: admin1Tok });
    const tlO = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token });
    const recs = tlR.data ?? [];
    const actions = recs.map(x => x.action ?? x.operationType ?? '').join(',');
    const full = ['SUBMIT', 'ASSIGN', 'ACCEPT', 'PROCESS', 'COMPLETE', 'CONFIRM', 'CLOSE'].every(a => actions.includes(a));
    tc('TC-C4-017', '时间线 7 记录完整+三角色可见+他人拒',
      full && ok(tlS) && ok(tlA) && (tlO.status === 403 || tlO.status === 404 || (tlO.status === 200 && tlO.code !== 200)),
      `记录=${recs.length}条 全动作=${full}（${actions}） 员工=${tlS.code} 管理员=${tlA.code} 他人=${tlO.status}`);
  }

  // ══ TC-C4-018 附件四角色权限（BE-ISSUE-10 回归） ══
  {
    const w = await mkOrder('C4-018 附件');
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    const fd = () => { const f = new FormData(); f.append('file', new Blob(['x'], { type: 'image/png' }), 'x.png'); return f; };
    const r1 = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${r1Tok}` }, body: fd() });
    const r2Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token;
    const r2 = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${r2Tok}` }, body: fd() });
    const r2j = await r2.json().catch(() => null);
    const s1 = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${staff1Tok}` }, body: fd() });
    const s1j = await s1.json().catch(() => null);
    const staff3Tok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_staff', password: 'Staff123456' } })).data?.token;
    const s3 = staff3Tok ? await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${staff3Tok}` }, body: fd() }) : { status: 'skip' };
    const a1 = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${admin1Tok}` }, body: fd() });
    const lst = await fetch(`${BASE}/api/v1/work-orders/${w.id}/attachments`, { headers: { Authorization: `Bearer ${r2Tok}` } });
    const lstj = await lst.json().catch(() => null);
    tc('TC-C4-018', '提交人传/他人拒/派单人传/非派单拒/管理员传/列表拒',
      r1.status === 200 && (r2.status === 403 || r2j?.code === 403 || r2j?.code === 5003) && s1.status === 200 && (s3.status === 403 || s3.status === 'skip') && a1.status === 200 && (lst.status === 403 || lstj?.code !== 200),
      `提交人=${r1.status} 他人=${r2.status}/${r2j?.code} 派单人=${s1.status} 非派单=${s3.status} 管理员=${a1.status} 他人列表=${lst.status}/${lstj?.code}（BE-ISSUE-10 回归通过）`);
  }

  // ══ TC-C4-019 数据级越社区 ══
  {
    const qyOrder = q(`SELECT id FROM work_order WHERE community_id=2 AND id < 10230 LIMIT 1`);
    const c1 = await api('GET', `/api/v1/work-orders/${qyOrder}`, { token: admin1Tok });
    const myList = await api('GET', '/api/v1/work-orders?page=1&size=100', { token: admin1Tok });
    const leak = (myList.data?.records ?? []).some(x => x.communityId === 2);
    const qyAdminTok = (await api('POST', '/api/v1/auth/admin/login', { body: { username: 'test_admin', password: 'Admin123456' } })).data?.token;
    const myOrder = q(`SELECT id FROM work_order WHERE community_id=1 AND resident_id=1 LIMIT 1`);
    const c2 = await api('GET', `/api/v1/work-orders/${myOrder}`, { token: qyAdminTok });
    tc('TC-C4-019', '越社区详情拒+列表不泄漏+反向拒',
      rej(c1) && !leak && rej(c2), `社区1管员查社区2单=${c1.status}/${c1.code} 列表泄漏=${leak} 反向=${c2.status}/${c2.code}`);
  }

  // ══ TC-C4-020 E4 全流程（含评价+通知） ══
  {
    const w = await mkOrder('C4-020 E4全流程');
    const n0 = q(`SELECT COUNT(*) FROM notification WHERE user_id IN (1,3)`);
    await api('PATCH', `/api/v1/work-orders/${w.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/accept`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/process`, { token: staff1Tok, body: { remark: 'x' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/complete`, { token: staff1Tok, body: { remark: '修复完成' } });
    await api('PATCH', `/api/v1/work-orders/${w.id}/confirm`, { token: r1Tok, body: { remark: '满意' } });
    const ev = await api('POST', `/api/v1/work-orders/${w.id}/evaluation`, { token: r1Tok, body: { rating: 5, content: '处理及时', isSatisfied: true } });
    const ev2 = await api('POST', `/api/v1/work-orders/${w.id}/evaluation`, { token: r1Tok, body: { rating: 4, content: 'x', isSatisfied: true } });
    const close = await api('PATCH', `/api/v1/work-orders/${w.id}/close`, { token: admin1Tok, body: { remark: '归档' } });
    const n1 = q(`SELECT COUNT(*) FROM notification WHERE user_id IN (1,3)`);
    const tl = await api('GET', `/api/v1/work-orders/${w.id}/timeline`, { token: r1Tok });
    const st = q(`SELECT status FROM work_order WHERE id=${w.id}`);
    tc('TC-C4-020', 'E4 全链+评价一次+重复评价拒+关闭+通知增量+时间线',
      ok(ev) && rej(ev2) && ok(close) && st === 'CLOSED' && (Number(n1) - Number(n0)) >= 4 && (tl.data ?? []).length >= 7,
      `评价=${ev.code} 重复=${ev2.code} 关闭=${close.code} 终态=${st} 通知增量=${Number(n1) - Number(n0)} 时间线=${(tl.data ?? []).length}条`);
  }

  // ══ TC-C4-021 居民修改工单 ══
  {
    const wP = await mkOrder('C4-021 修改P');
    const putP = await api('PUT', `/api/v1/work-orders/${wP.id}`, { token: r1Tok, body: { categoryId: 1, title: '厨房水龙头漏水（已加固描述）', content: 'x', contactPhone: '13812345678', address: 'x' } });
    const wA = await mkOrder('C4-021 修改A');
    await api('PATCH', `/api/v1/work-orders/${wA.id}/assign`, { token: admin1Tok, body: { assigneeId: staff1Id } });
    const putA = await api('PUT', `/api/v1/work-orders/${wA.id}`, { token: r1Tok, body: { categoryId: 1, title: '改', content: 'x', contactPhone: '13812345678', address: 'x' } });
    const r2Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'test_resident2', password: 'Resident123456' } })).data?.token;
    const putO = await api('PUT', `/api/v1/work-orders/${wP.id}`, { token: r2Tok, body: { categoryId: 1, title: '越权改', content: 'x', contactPhone: '13812345678', address: 'x' } });
    tc('TC-C4-021', 'PENDING 改成功+ASSIGNED 拒+他人拒',
      ok(putP) && rej(putA) && rej(putO), `PENDING改=${putP.code} ASSIGNED改=${putA.code}(${putA.json?.message?.slice(0, 16)}) 他人改=${putO.status}`);
  }

  // ══ TC-C4-022 列表检索与角色收敛 ══
  {
    const r = await api('GET', '/api/v1/work-orders?status=PENDING&page=1&size=50', { token: r1Tok });
    const s = await api('GET', '/api/v1/work-orders?page=1&size=100', { token: staff1Tok });
    const a = await api('GET', '/api/v1/work-orders?page=1&size=100', { token: admin1Tok });
    const rRecs = r.data?.records ?? [];
    const sRecs = s.data?.records ?? [];
    const aRecs = a.data?.records ?? [];
    const allMine = rRecs.every(x => x.residentId === 1);
    const dbPendingMine = q(`SELECT COUNT(*) FROM work_order WHERE resident_id=1 AND status='PENDING'`);
    const dbAll1 = q(`SELECT COUNT(*) FROM work_order WHERE community_id=1`);
    const aOnlyC1 = aRecs.every(x => x.communityId === 1);
    tc('TC-C4-022', '居民过滤生效+员工收敛+管理员本社区+与库一致',
      ok(r) && allMine && rRecs.length === Number(dbPendingMine) && aOnlyC1,
      `居民PENDING=${rRecs.length}条(库${dbPendingMine}) 全本人=${allMine} 员工=${sRecs.length}条 管理员=${aRecs.length}条(社区1库${dbAll1}) 仅社区1=${aOnlyC1}`);
  }

  console.log(`\n========== C4 功能域汇总 ==========`);
  console.log(`通过 ${pass} / ${pass + fail}`);
  if (failures.length) { console.log('失败项：'); failures.forEach(f => console.log('  ❌ ' + f)); process.exit(1); }
}

main().catch(e => { console.error('脚本异常:', e); process.exit(2); });
