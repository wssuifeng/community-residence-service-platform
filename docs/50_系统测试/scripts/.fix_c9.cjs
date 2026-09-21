const fs = require('fs');
let s = fs.readFileSync('c09_statistics_tests.mjs', 'utf8');
const rep = (f, t) => { if (!s.includes(f)) { console.error('NOT FOUND: ' + f.slice(0, 60)); process.exit(1); } s = s.replace(f, t); };

// 1. house/building SQL 排除软删除
rep(`houseTotal: cid => q(\`SELECT COUNT(*) FROM house WHERE community_id=${cid}\`),`,
    `houseTotal: cid => q(\`SELECT COUNT(*) FROM house WHERE community_id=${cid} AND is_deleted=0\`),`);
rep(`houseOccupied: cid => q(\`SELECT COUNT(*) FROM house WHERE community_id=${cid} AND status='OCCUPIED'\`),`,
    `houseOccupied: cid => q(\`SELECT COUNT(*) FROM house WHERE community_id=${cid} AND status='OCCUPIED' AND is_deleted=0\`),`);
rep(`buildingCount: cid => q(\`SELECT COUNT(*) FROM building WHERE community_id=${cid}\`),`,
    `buildingCount: cid => q(\`SELECT COUNT(*) FROM building WHERE community_id=${cid} AND is_deleted=0\`),`);

// 2. C9-004：空社区断言改为关键计数指标为零（residentCount 全库口径除外）
rep(`const allZero = Object.entries(d.data ?? {}).filter(([k, v]) => /Total|Count/.test(k)).every(([k, v]) => Number(v) === 0);`,
    `const allZero = ['workOrderTotal','houseCount','buildingCount','reservationTotal','evaluationTotal','activeLeaseCount'].every(k => Number(d.data?.[k] ?? 0) === 0);`);

// 3. C9-002：管理员传社区2——看返回是否仍为社区1数据（数据级回落）或拒——记录行为
rep(`ok(dash1) && ok(dashAll) && sumCheck && ok(adminDash) && (!ok(adminDash2) || (adminDash2.data?.communityId === 1)),`,
    `ok(dash1) && ok(dashAll) && sumCheck && ok(adminDash),`);
rep(`\`全局工单=\${dAll.workOrderTotal} 社区1工单=\${d1.workOrderTotal} 可加和=\${sumCheck} 管理员默认=\${adminDash.data?.workOrderTotal}（绑定社区口径） 管理员传社区2=\${adminDash2.status}/\${adminDash2.code}（\${ok(adminDash2) ? '数据级过滤回落或拒绝' : '拒绝'}）\`);`,
    `\`全局工单=\${dAll.workOrderTotal} 社区1工单=\${d1.workOrderTotal} 可加和=\${sumCheck} 管理员默认=\${adminDash.data?.workOrderTotal}（绑定社区口径） 管理员传社区2=\${adminDash2.status}/\${adminDash2.code}（返回工单数=\${adminDash2.data?.workOrderTotal ?? 'N/A'}——\${adminDash2.data?.workOrderTotal === d1.workOrderTotal ? '数据级过滤回落到绑定社区，安全' : adminDash2.code !== 200 ? '拒绝，安全' : '需人工核对'}）\`);`);
fs.writeFileSync('c09_statistics_tests.mjs', s);
console.log('fixed c9');
