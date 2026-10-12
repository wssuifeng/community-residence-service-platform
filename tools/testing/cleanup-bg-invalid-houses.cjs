/* 2026-10-11用户允许清理历史超层测试数据；默认只读，--apply才写。固定开发库与已核实ID，不禁用外键。 */
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const { properties } = require('./nontransaction-fixture-helpers.cjs')
const database = 'community_residence'
const mysqlRoot = 'C:/Program Files/MySQL/MySQL Server 9.6/bin'
const credentials = properties()
const env = { ...process.env, MYSQL_PWD: credentials.DB_PASSWORD }
const args = ['--protocol=TCP', '--host=127.0.0.1', '--port=3306', '--user=' + (credentials.DB_USERNAME || 'root'), '--default-character-set=utf8mb4']
function sql(statement) {
  const result = spawnSync(path.join(mysqlRoot, 'mysql.exe'), [...args, '--database=' + database, '--batch', '--skip-column-names', '--execute', statement], { env, encoding: 'utf8', windowsHide: true })
  assert.equal(result.status, 0, '定向清理SQL失败：保留本地备份，请勿重试整体清空')
  return result.stdout.trim().split(/\r?\n/).filter(Boolean).map(line => line.split('\t'))
}
const condition = 'h.is_deleted=0 AND b.is_deleted=0 AND (h.floor<1 OR h.floor>b.floors)'
const scope = `SELECT h.id FROM house h JOIN unit u ON u.id=h.unit_id JOIN building b ON b.id=u.building_id WHERE ${condition}`
assert.equal(sql('SELECT DATABASE()')[0][0], database)
const ids = sql(scope + ' ORDER BY h.id').map(row => Number(row[0]))
console.log(JSON.stringify({ mode: process.argv.includes('--apply') ? 'apply' : 'dry-run', database, invalidHouseIds: ids }))
if (!process.argv.includes('--apply')) process.exit(0)
assert.deepEqual(ids, Array.from({ length: 24 }, (_, index) => index + 25), '当前异常范围与已核实24套不同，拒绝执行')
assert.equal(sql(`SELECT COUNT(*) FROM lease_payment p JOIN lease_record l ON l.id=p.lease_id WHERE l.house_id IN (${ids}) AND p.status<>'PENDING'`)[0][0], '0', '出现非未完成支付，拒绝自动清理')
const backup = path.resolve('output/db-backups/bg-before-v31-20261011.sql')
fs.mkdirSync(path.dirname(backup), { recursive: true })
assert(!fs.existsSync(backup), '已有恢复备份，不覆盖')
const dump = spawnSync(path.join(mysqlRoot, 'mysqldump.exe'), [...args, '--single-transaction', '--no-tablespaces', '--routines', '--triggers', '--set-gtid-purged=OFF', '--result-file=' + backup, database], { env, encoding: 'utf8', windowsHide: true })
assert.equal(dump.status, 0, '备份失败，不执行清理')
assert(fs.statSync(backup).size > 1000, '备份内容不完整，不执行清理')
const before = sql('SELECT (SELECT COUNT(*) FROM house WHERE is_deleted=0),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM community)')[0]
const cleanup = `START TRANSACTION;
SELECT id FROM house WHERE id IN (${ids}) FOR UPDATE;
CREATE TEMPORARY TABLE bg_house (id BIGINT PRIMARY KEY) AS SELECT h.id FROM house h JOIN unit u ON u.id=h.unit_id JOIN building b ON b.id=u.building_id WHERE ${condition} AND h.id IN (${ids});
CREATE TEMPORARY TABLE bg_lease (id BIGINT PRIMARY KEY) AS SELECT id FROM lease_record WHERE house_id IN (SELECT id FROM bg_house);
CREATE TEMPORARY TABLE bg_app (id BIGINT PRIMARY KEY) AS SELECT id FROM residence_application WHERE house_id IN (SELECT id FROM bg_house) OR lease_id IN (SELECT id FROM bg_lease);
CREATE TEMPORARY TABLE bg_housing (id BIGINT PRIMARY KEY) AS SELECT id FROM housing WHERE house_id IN (SELECT id FROM bg_house);
CREATE TEMPORARY TABLE bg_view (id BIGINT PRIMARY KEY) AS SELECT id FROM viewing_appointment WHERE housing_id IN (SELECT id FROM bg_housing) OR lease_id IN (SELECT id FROM bg_lease);
CREATE TEMPORARY TABLE bg_agreement (id BIGINT PRIMARY KEY) AS SELECT id FROM lease_agreement WHERE lease_id IN (SELECT id FROM bg_lease);
CREATE TEMPORARY TABLE bg_conversation (id BIGINT PRIMARY KEY) AS SELECT id FROM conversation WHERE (type='LEASE_GROUP' AND related_id IN (SELECT id FROM bg_lease)) OR (type='VIEWING_GROUP' AND related_id IN (SELECT id FROM bg_view));
CREATE TEMPORARY TABLE bg_notice (id BIGINT PRIMARY KEY) AS SELECT id FROM notification WHERE (source_type='RESIDENCE_APPLICATION' AND source_id IN (SELECT id FROM bg_app)) OR (source_type='LEASE_AGREEMENT' AND source_id IN (SELECT id FROM bg_agreement)) OR (source_type IN ('LEASE_RECORD','LEASE_MOVE_OUT','LEASE_RENEWAL','LEASE_PAYMENT','LEASE_REMINDER','LEASE_FIRST_PAYMENT') AND source_id IN (SELECT id FROM bg_lease)) OR (source_type='VIEWING_APPOINTMENT' AND source_id IN (SELECT id FROM bg_view));
DELETE FROM notification_channel_log WHERE notification_id IN (SELECT id FROM bg_notice);
DELETE FROM notification WHERE id IN (SELECT id FROM bg_notice);
DELETE FROM violation_record WHERE violation_type='VIEWING_NO_SHOW' AND related_id IN (SELECT id FROM bg_view);
DELETE FROM viewing_message WHERE appointment_id IN (SELECT id FROM bg_view);
DELETE FROM viewing_appointment WHERE id IN (SELECT id FROM bg_view);
DELETE FROM conversation_message WHERE conversation_id IN (SELECT id FROM bg_conversation);
DELETE FROM conversation_participant WHERE conversation_id IN (SELECT id FROM bg_conversation);
DELETE FROM conversation WHERE id IN (SELECT id FROM bg_conversation);
DELETE FROM housing_timeslot WHERE housing_id IN (SELECT id FROM bg_housing);
DELETE FROM housing WHERE id IN (SELECT id FROM bg_housing);
DELETE FROM lease_deposit_refund WHERE lease_id IN (SELECT id FROM bg_lease);
DELETE FROM lease_payment WHERE lease_id IN (SELECT id FROM bg_lease);
DELETE FROM lease_agreement WHERE id IN (SELECT id FROM bg_agreement);
DELETE FROM lease_change_log WHERE lease_id IN (SELECT id FROM bg_lease);
DELETE FROM lease_reminder WHERE lease_id IN (SELECT id FROM bg_lease);
DELETE FROM lease_move_out_application WHERE house_id IN (SELECT id FROM bg_house) OR lease_id IN (SELECT id FROM bg_lease);
DELETE FROM residence_application WHERE id IN (SELECT id FROM bg_app);
DELETE FROM residence_relation WHERE house_id IN (SELECT id FROM bg_house);
DELETE FROM lease_record WHERE id IN (SELECT id FROM bg_lease);
DELETE FROM house_status_history WHERE house_id IN (SELECT id FROM bg_house);
DELETE FROM house WHERE id IN (SELECT id FROM bg_house);
DELETE FROM statistics_snapshot WHERE community_id=2;
COMMIT;`
sql(cleanup)
const after = sql('SELECT (SELECT COUNT(*) FROM house WHERE is_deleted=0),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM community)')[0]
assert.equal(sql('SELECT COUNT(*) FROM (' + scope + ') invalid')[0][0], '0')
assert.equal(before[3], after[3], '社区数量不得变化')
assert.equal(Number(before[0]) - Number(after[0]), 24, '不得删除未授权房屋')
const evidence = { date: '2026-10-11', database, invalidHouseIds: ids, before, after, fields: ['activeHouses','leases','payments','communities'], backupLocalOnly: true, transactionsExecuted: false, invalidFloorsRemaining: 0, foreignKeyChecksDisabled: false }
fs.writeFileSync('output/playwright/bg-development-cleanup-20261011.json', JSON.stringify(evidence, null, 2))
console.log(JSON.stringify({ success: true, removedHouses: 24, transactionsExecuted: false }))
