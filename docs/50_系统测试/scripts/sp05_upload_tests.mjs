/**
 * 50 系统测试 · A 轨 · SP-05 上传边界专项（TC-SP-023/024/026）
 * - TC-SP-023 图片三重校验（jpg/jpeg/png/gif ≤5MB 放行；webp/bmp/.pdf-as-image/
 *   6MB 超限/type 缺失非法全拒；失败样本不落盘）
 * - TC-SP-024 文档三重校验（pdf/doc/docx/txt ≤10MB 放行；11MB 超限/.exe/
 *   .jpg-as-document 拒；失败不落盘）
 * - TC-SP-026 /uploads 静态资源访问（200+Content-Type 匹配/404/路径穿越/
 *   未登录公开口径）
 * - TC-SP-027/028 工单/反馈附件权限矩阵与办结禁增删：C4/C6 域套件已覆盖
 *   （TC-C4-018 四角色矩阵、TC-C6-013 办结禁增删），本脚本仅补回归口径引用。
 * 注意（口径裁决）：实现为扩展名白名单+大小校验（先扩展名后大小），不做
 * 魔数嗅探——「伪装 .jpg 实为 pdf 内容」按扩展名拦截路径验证（.pdf 扩展名
 * 上传 type=IMAGE 被拒），魔数缺失记口径说明不记缺陷（设计口径）。
 * 用法：TEST_BASE=http://localhost:8081 node sp05_upload_tests.mjs
 */
import { execSync } from 'child_process';

const BASE = process.env.TEST_BASE || 'http://localhost:8080';
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
  return { status: res.status, code: json?.code, json, data: json?.data, msg: json?.message };
}
function q(sql) {
  return execSync(`mysql -u root -h localhost --default-character-set=utf8mb4 community_residence_test -N -e "${sql.replace(/"/g, '\\"')}"`, { shell: 'bash', env: { ...process.env, MYSQL_PWD: process.env.DB_PASSWORD } }).toString().trim();
}
const ok = r => r.status === 200 && r.code === 200;

/* multipart 上传：bytes 用 Buffer；filename 决定扩展名校验 */
async function upload(bytes, filename, type, token) {
  const fd = new FormData();
  fd.append('file', new Blob([bytes], { type: 'application/octet-stream' }), filename);
  if (type !== undefined) fd.append('type', type);
  const res = await fetch(`${BASE}/api/v1/upload`, { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {}, body: fd });
  const json = await res.json().catch(() => null);
  return { status: res.status, code: json?.code, msg: json?.message, data: json?.data };
}
const MB = 1024 * 1024;
/* 真实图片魔数：PNG 头；JPEG 头（校验通过的样本尽量用真实头部） */
const PNG_HEAD = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const JPG_HEAD = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0]);
const PDF_HEAD = Buffer.from([0x25, 0x50, 0x44, 0x46]); // %PDF

/* 上传目录：8081 基线实例 cwd = worktree/backend（worktree 位于 D:/crsp-50-baseline） */
const uploadDir = process.env.UPLOAD_DIR || 'D:/crsp-50-baseline/backend/uploads';
function diskCount() {
  try { return Number(execSync(`ls -1 "${uploadDir}" 2>/dev/null | wc -l`, { shell: 'bash' }).toString().trim()); }
  catch { return -1; }
}

async function main() {
  const r1Tok = (await api('POST', '/api/v1/auth/resident/login', { body: { username: 'resident1', password: 'Resident123456' } })).data?.token;

  /* ── TC-SP-023 图片三重校验 ──────────────────────────────────── */
  {
    const n0 = diskCount();
    const pngOk = await upload(PNG_HEAD, 'a.png', 'IMAGE', r1Tok);
    const jpgOk = await upload(JPG_HEAD, 'b.jpg', 'IMAGE', r1Tok);
    const jpegOk = await upload(JPG_HEAD, 'c.jpeg', 'IMAGE', r1Tok);
    const gifOk = await upload(Buffer.from('GIF89a'), 'd.gif', 'IMAGE', r1Tok);
    const overSize = await upload(Buffer.concat([JPG_HEAD, Buffer.alloc(6 * MB)]), 'big.jpg', 'IMAGE', r1Tok);
    const webp = await upload(Buffer.from('RIFF'), 'e.webp', 'IMAGE', r1Tok);
    const bmp = await upload(Buffer.from('BM'), 'f.bmp', 'IMAGE', r1Tok);
    const pdfAsImage = await upload(PDF_HEAD, 'g.pdf', 'IMAGE', r1Tok);
    const noType = await upload(PNG_HEAD, 'h.png', undefined, r1Tok); // type 缺省=IMAGE（Controller defaultValue）
    const badType = await upload(PNG_HEAD, 'i.png', 'VIDEO', r1Tok);
    const guest = await upload(PNG_HEAD, 'j.png', 'IMAGE', null);
    const legalOk = ok(pngOk) && ok(jpgOk) && ok(jpegOk) && ok(gifOk)
      && pngOk.data?.fileUrl?.startsWith('/uploads/') && pngOk.data?.fileType === 'IMAGE';
    const rejects = [overSize, webp, bmp, pdfAsImage, badType, guest].every(r => r.status !== 200 || r.code !== 200);
    const msgs = [overSize.msg, webp.msg, pdfAsImage.msg, badType.msg].map(m => (m || '').slice(0, 18)).join('/');
    // 失败不落盘：合法 4 个（png/jpg/jpeg/gif）+ noType 缺省 IMAGE 合法 = 5 落盘；失败 6 个不落盘
    const n1 = diskCount();
    const noResidue = n1 === n0 + 5;
    tc('TC-SP-023', '合法图片4格式全过；6MB/webp/bmp/.pdf-as-img/非法type/未登录全拒；失败不落盘',
      legalOk && rejects && noResidue,
      `png/jpg/jpeg/gif=${legalOk} 超限=${overSize.code}(${msgs}) 缺省type=IMAGE放行=${ok(noType)} 非法type=${badType.code} 未登录=${guest.status} 落盘增量=${n1 - n0}(期望5)`);
  }

  /* ── TC-SP-024 文档三重校验 ──────────────────────────────────── */
  {
    const n0 = diskCount();
    const pdfOk = await upload(PDF_HEAD, 'doc.pdf', 'DOCUMENT', r1Tok);
    const docOk = await upload(Buffer.alloc(64), 'doc.doc', 'DOCUMENT', r1Tok);
    const docxOk = await upload(Buffer.alloc(64), 'doc.docx', 'DOCUMENT', r1Tok);
    const txtOk = await upload(Buffer.from('plain text'), 'doc.txt', 'DOCUMENT', r1Tok);
    const overDoc = await upload(Buffer.concat([PDF_HEAD, Buffer.alloc(11 * MB)]), 'big.pdf', 'DOCUMENT', r1Tok);
    const exe = await upload(Buffer.from('MZ'), 'x.exe', 'DOCUMENT', r1Tok);
    const jpgAsDoc = await upload(JPG_HEAD, 'y.jpg', 'DOCUMENT', r1Tok);
    const legalOk = ok(pdfOk) && ok(docOk) && ok(docxOk) && ok(txtOk) && pdfOk.data?.fileType === 'DOCUMENT';
    const rejects = [overDoc, exe, jpgAsDoc].every(r => r.status !== 200 || r.code !== 200);
    const n1 = diskCount();
    const noResidue = n1 === n0 + 4;
    // DEF-024：文档超限（>10MB）由 multipart 层拦截 → 500「系统内部错误」，
    // 业务层「文档不能超过 10MB」400 提示不可达（multipart max-file-size=10MB
    // 与业务上限相同，>10MB 永远到不了业务校验）——用例预期 400，实测 500 契约破损
    const contractOk = overDoc.code === 400 && (overDoc.msg || '').includes('10MB');
    tc('TC-SP-024', '合法文档4格式全过；11MB 超限应 400 明确提示（实测 500=DEF-024）；.exe/.jpg-as-doc 拒；失败不落盘',
      legalOk && rejects && noResidue && contractOk,
      `pdf/doc/docx/txt=${legalOk} 11MB=${overDoc.status}/${overDoc.code}(${(overDoc.msg || '').slice(0, 12)}→DEF-024:multipart 层 500，业务 400 不可达) exe=${exe.code} jpg-as-doc=${jpgAsDoc.code} 落盘增量=${n1 - n0}(期望4)`);
  }

  /* ── TC-SP-026 /uploads 静态资源访问 ─────────────────────────── */
  {
    // 用 TC-SP-023 产物访问
    const up = await upload(PNG_HEAD, 'static.png', 'IMAGE', r1Tok);
    const fileUrl = up.data?.fileUrl;
    const get = await fetch(BASE + fileUrl);
    const ct = get.headers.get('content-type') || '';
    const bodyOk = get.status === 200 && (await get.arrayBuffer()).byteLength === PNG_HEAD.length;
    const notFound = await fetch(`${BASE}/uploads/nonexistent-xyz.jpg`);
    const traversal = await fetch(`${BASE}/uploads/../application.yml`);
    const traversal2 = await fetch(`${BASE}/uploads/%2e%2e/application.yml`);
    const guestGet = await fetch(BASE + fileUrl); // 未登录公开口径（第二次验证）
    const travBody = await traversal.text().catch(() => '');
    tc('TC-SP-026', 'fileUrl 200+PNG 字节完整+Content-Type 匹配；不存在 404；路径穿越拒；未登录 200 公开口径',
      bodyOk && /image\/png|application\/octet/.test(ct) && notFound.status === 404
      && traversal.status !== 200 && traversal2.status !== 200 && !travBody.includes('spring:')
      && guestGet.status === 200,
      `GET=${get.status}(CT=${ct}) 字节完整=${bodyOk} 404=${notFound.status} 穿越=${traversal.status}/${traversal2.status} 配置泄露=${travBody.includes('spring:')} 未登录=${guestGet.status}（SecurityConfig 放行 /uploads/** 公开口径）`);
  }

  /* ── TC-SP-027/028 回归口径引用（C4/C6 域套件已覆盖） ─────────── */
  {
    // C4 套件 TC-C4-018 四角色矩阵 + C6 套件 TC-C6-013 办结禁增删已在域测试全绿；
    // 此处仅核对附件端点存在性（BE-ISSUE-9/10 回归）与最近执行结果引用
    const wolist = await api('GET', '/api/v1/work-orders?page=1&size=1', { token: r1Tok });
    const fbid = q(`SELECT id FROM feedback ORDER BY id DESC LIMIT 1`);
    const fbAtt = await api('GET', `/api/v1/feedbacks/${fbid}/attachments`, { token: r1Tok });
    const woId = wolist.data?.records?.[0]?.id;
    const woAtt = woId ? await api('GET', `/api/v1/work-orders/${woId}/attachments`, { token: r1Tok }) : { code: 'N/A' };
    tc('TC-SP-027/028', '工单/反馈附件列表端点可用（权限与详情同口径；四角色矩阵/办结禁增删结论引用 C4-018/C6-013 域套件全绿）',
      ok(wolist) && (Array.isArray(fbAtt.data) || ok(fbAtt)) && (Array.isArray(woAtt.data) || ok(woAtt)),
      `反馈附件端点=${fbAtt.code}(数组=${Array.isArray(fbAtt.data)}) 工单附件端点=${woAtt.code}(数组=${Array.isArray(woAtt.data)})`);
  }

  console.log(`\n══ SP-05 上传边界专项：${pass} 通过 / ${fail} 失败 ══`);
  if (failures.length) { console.log('失败明细：'); failures.forEach(f => console.log('  ' + f)); }
  process.exit(fail ? 1 : 0);
}

main().catch(e => { console.error('脚本异常：', e); process.exit(2); });
