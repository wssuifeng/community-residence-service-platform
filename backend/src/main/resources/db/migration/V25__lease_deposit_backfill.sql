-- =============================================================================
-- Flyway Migration V25: 存量租约押金状态回填（R72 数据修补）
-- =============================================================================
-- 背景：V22 只给 lease_record.deposit_status 设了列默认值 'UNPAID'，**未回填存量**；
--       而「首期费用支付成功」（R71，V20 之后上线）的租约其押金实缴其实已完成
--       （成功首期单金额 = 首月租金 + 押金），状态却仍是 UNPAID，
--       导致 R72 押金退还第一步就被门禁挡死（接口设计 §9.20.1 押金状态门禁），
--       用户按 CF-E3-F 手测时在租约 4 上直接受阻。
-- 回填规则（与 DepositRefundService 的运行时口径**完全一致**）：
--   ① 该租约存在 `payment_type='FIRST'` 且 `status='SUCCESS'` 的支付单，
--      且该单金额 **严格大于** 租约月租（即押金部分 = 单额 − 月租 > 0）；
--      —— 押金为 0 / 未收押金的租约不回填，避免出现「可退还 0 元」的误导状态；
--   ② 仅当 `deposit_status='UNPAID'` 时才回填（**幂等**：PAID/REFUNDING/REFUNDED/OFFSET 一律不动，
--      已进入退还流程的租约不被本脚本回退）。
-- 依据：数据库设计 v1.5 §3.3.1「押金状态流转——首期费用支付成功，押金部分入账 → PAID」
--       + 系统定义 v1.6 §3（平台不托管押金资金，本列只表达「实缴与退还进度」）。
-- 放迁移而非人工补数的理由：结构变更与存量修补同属「库到达目标状态」的一部分，
--       放迁移可保证所有环境（开发/测试/演示库）升级时一次性补齐，且**语句幂等**——
--       Flyway 只执行一次，人工重跑亦无副作用（本批已按此口径对运行中的测试库执行过一次）。
-- 表数不变（仍 53）；本脚本只改数据、不改结构。
-- =============================================================================

UPDATE lease_record l
SET l.deposit_status = 'PAID'
WHERE l.deposit_status = 'UNPAID'
  AND EXISTS (SELECT 1
              FROM lease_payment p
              WHERE p.lease_id = l.id
                AND p.payment_type = 'FIRST'
                AND p.status = 'SUCCESS'
                AND p.amount > l.monthly_rent);

-- =============================================================================
-- 校验（只读，人工核对）
-- =============================================================================
-- SELECT id, status, monthly_rent, deposit, deposit_status FROM lease_record ORDER BY id;
-- SELECT id, lease_id, payment_type, status, amount FROM lease_payment ORDER BY id;
