# OCR 静态评审缺陷修复轮 · 实现计划

> **面向 AI 代理的工作者：** 本计划按 superpowers-zh:subagent-driven-development 模式逐任务实现，由主会话按 superpowers-zh:dispatching-parallel-agents 并发分发（同波任务文件集两两不相交）。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 修复 2026-09-23 open-code-review 全量评审工作副本发现的 1 critical + 16 high + 37 medium 缺陷（1 条 high 判误报降级），按主题分组为 13 个任务，覆盖后端并发/幂等/状态机/通知深链、迁移与种子资产、前端口径与交互、前端测试底座。

**架构：** 后端沿用项目既有三类先例修复 check-then-act 竞态——Redisson 分布式锁（押金退还/首期支付/退租申请）、MyBatis-Plus 条件更新 `update(entity, wrapper_eq_status)`（状态机终态迁移）、唯一约束 + `DuplicateKeyException` 捕获（幂等建群）；押金渠道退款拆为「事务内落 PENDING → 事务外渠道调用 → 事务内结算」三段。迁移修正以 **V27/V28 新迁移承载**（已应用 V1~V26 禁改字节，2026-09-22 用户裁决）。前端按严重项逐点修复，假后端测试底座同步对齐后端语义。

**技术栈：** Java 17 / Spring Boot 4.0.6 / MyBatis-Plus 3.5.16 / jakarta.validation / Redisson；Vue 3.5 + TS 5.9 + Vitest 4 + Element Plus 2.14.5；MySQL 9（Flyway）。

---

## 全局约束（每个子代理必须遵守）

1. **基线**：后端 `./mvnw test` 666/666 全绿、前端 `npx vitest run` 157/157 全绿（2026-09-23 主会话实测）。你的改动**不得引入任何新失败**。
2. **不跑全量测试**（多代理并行会互相干扰）：后端任务只跑**自己改动模块的单测文件**（`./mvnw test -Dtest=XxxTest`）；前端任务只跑**自己的 spec 文件**（`npx vitest run <path>`），可另跑 `npx vue-tsc --noEmit`（只关注自己文件的报错，其他并行代理的中间态报错忽略）。
3. **禁止 git add / git commit**——提交由主会话在全部任务完成并集中验证后统一进行。
4. **禁止修改已应用的 Flyway 迁移 V1~V26 任何字节**；修正一律走 V27/V28 新迁移（任务 8）。
5. **编码规范**（AGENTS.md 强制）：Entity/DTO/VO 字段注释只用 `@Schema(description="...")` 禁 `//`；Controller 用 `@Operation`；Service 复杂逻辑前用 `/* */` 块注释说明约束与意图（不写「这行做了什么」）；错误码只用 `common/constant/ErrorCode` 常量；所有接口返回 `ApiResponse<T>`；SQL 禁 `SELECT *`；日志用 `@Slf4j`。
6. **注释引用缺陷号**：本计划为每条缺陷预分配了 DEF-103~DEF-132（见附录 A 缺陷分派表），代码注释中按项目惯例引用（如「OCR DEF-106：并发建群唯一化」）。
7. **行号已漂移**：OCR 意见中的行号可能有少量漂移，以「文件 + 代码形态」定位为准。
8. 仓库根有 5 个 **0 字节残渣文件**（`WorkOrderBatch2FixTest.java` 等），与本轮无关，**不要动**（主会话统一删除）。

---

## 任务分波与依赖

```
第一波（并发 5 个）：任务 1 押金 / 任务 2 退租 / 任务 3 会话 / 任务 4 支付 / 任务 5 状态机
  · 共同依赖：任务 3 产出 V27 迁移文件 + createLeaseGroup 幂等改造（但只改 conversation 域，与 1/2/4/5 文件不相交）
第二波（并发 5 个）：任务 6 工单+入住 / 任务 7 看房 / 任务 8 迁移资产 / 任务 9 居民租约前端 / 任务 10 管理租约前端
第三波（并发 3 个）：任务 11 通知+会话前端 / 任务 12 工单+入住前端 / 任务 13 假后端底座
收尾（主会话）：全量验证 / 缺陷表登记 / 决策日志 / 文档回写 / 提交
```

同波内任务文件集两两不相交（已核对）。**跨波依赖只有一条**：任务 6/7 若引用 `ConversationService` 新增公开方法（无新增，仅内部改造），无实际编译耦合。

---

### 任务 1：押金退还并发超退与事务边界（DEF-103，critical + 3 high/medium 族）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/lease/service/DepositRefundService.java`
- 测试：`backend/src/test/java/com/community/residence/lease/service/DepositRefundServiceTest.java`

**背景**：`request()` 是 `@Transactional` 全事务：① 无锁 check-then-act，两个不同 `outRequestNo` 并发都读到 `PAID`、都算出同一可退额（`refundedAmount()` 只数 SUCCESS）→ 累计超退（critical）；② 渠道远程调用 `client.refund(...)` 在事务内——渠道成功但本地后续失败回滚 ⇒ 钱已退无 SUCCESS 行，换号重试二次退款；③ 幂等键未捕获 `DuplicateKeyException`；④ 部分退款也置 `REFUNDED` 永久堵死余额；⑤ `listByLease` 居民侧唯一防线是一行显式校验（SKIP_TABLES 已跳过数据拦截器）。项目先例：`ViewingAppointmentService` 的 `RLock` + `tryLock(5, TimeUnit.SECONDS)`、`PaymentService.settleSuccess` 的延迟结算模式。

**修复方案**（方法签名与端点契约不变，仍返回 `DepositRefundVO`）：

- [ ] **步骤 1：写失败测试（并发与幂等语义）**

在 `DepositRefundServiceTest` 追加（沿用该文件既有 Mockito 风格；mock `TransactionTemplate` 与 `RLock`）：

```java
@Test
void 并发第二请求在锁内读到REFUNDING即拒绝5703() {
    // 首次发起后 lease.deposit_status = REFUNDING；第二个并发请求进入时
    // 押金状态门禁（仅 PAID 可发起）应拒绝，不得再落第二张 PENDING 单
    LeaseRecord lease = paidLease(); // 复用/构造 deposit_status=PAID 且有 SUCCESS 首期单的租约
    when(leaseMapper.selectById(1L)).thenReturn(lease);
    // 第一笔成功退还后状态已是 REFUNDING（或并发中读到 REFUNDING）
    lease.setDepositStatus(DepositStatus.REFUNDING);
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.request(1L, refundDto("REQ-2")));
    assertEquals(ErrorCode.DEPOSIT_STATUS_NOT_REFUNDABLE.getCode(), ex.getCode());
}

@Test
void 幂等键撞库DuplicateKeyException返回既有记录() {
    LeaseRecord lease = paidLease();
    when(leaseMapper.selectById(1L)).thenReturn(lease);
    when(refundMapper.selectOne(any())).thenReturn(null); // 先查为空
    doAnswer(inv -> { throw new DuplicateKeyException("uk_deposit_out_request_no"); })
            .when(refundMapper).insert(any(LeaseDepositRefund.class));
    when(refundMapper.selectOne(any())) // 撞库后重查返回既有
            .thenReturn(null, existingRefund("REQ-1"));
    DepositRefundVO vo = service.request(1L, refundDto("REQ-1"));
    assertEquals("REQ-1", vo.getOutRequestNo());
}

@Test
void 部分退款成功后押金状态回PAID可继续退余额() {
    // 实缴 3000，退 1000 成功：refund.status=SUCCESS，lease.deposit_status 应回 PAID（非 REFUNDED）
    LeaseRecord lease = paidLease(); // deposit=3000
    LeaseDepositRefund refund = pendingRefund("REQ-1", new BigDecimal("1000"));
    DepositRefundVO vo = service.settle(lease, refund,
            ChannelRefundResult.success("CH-1", new BigDecimal("1000")), null);
    assertEquals(DepositStatus.PAID.name(), vo.getLeaseDepositStatus());
}

@Test
void 累计成功退款达实缴后押金状态置REFUNDED() {
    // 实缴 3000，已 SUCCESS 退 2000，再退 1000 成功 → 累计 3000 = 实缴 → REFUNDED
    LeaseRecord lease = paidLease();
    LeaseDepositRefund refund = pendingRefund("REQ-2", new BigDecimal("1000"));
    when(refundMapper.selectList(any())).thenReturn(List.of(successRefund("REQ-1", new BigDecimal("2000"))));
    service.settle(lease, refund, ChannelRefundResult.success("CH-2", new BigDecimal("1000")), null);
    verify(leaseMapper).update(any(LeaseRecord.class), any()); // 或按实现断言 set REFUNDED
}
```

（`paidLease/refundDto/existingRefund/pendingRefund/successRefund` 等辅助方法按该测试文件既有 fixture 风格实现；`ChannelRefundResult` 构造以实际 API 为准；`settle` 若为 private，则相关断言改经 `request()` 全链路触发或把断言落在 mock verify 上，不为此改生产代码可见性。）

- [ ] **步骤 2：跑测试验证失败**

```bash
cd backend && ./mvnw test -Dtest=DepositRefundServiceTest -q
```
预期：新增用例 FAIL（现状无锁、`settle` 无条件 REFUNDED、insert 未捕获 DuplicateKeyException）。

- [ ] **步骤 3：重构 `request()` 为「锁 + 三段式」**

```java
private final RedissonClient redissonClient;
private final TransactionTemplate transactionTemplate; // 新增依赖（构造注入）

/* 押金退还（DEF-103）：分布式锁防并发超退 + 三段式事务边界——
   锁内校验与幂等 → 事务一：落 PENDING 单 + 置 REFUNDING + 变更留痕
   → 事务外：渠道远程调用（不占数据库连接，渠道结果只落不回滚）
   → 事务二：按渠道结果结算（SUCCESS 置单据成功并按累计口径动押金状态；FAILED 回落 PAID 可重试） */
@com.community.residence.log.annotation.OperationLog(operationType = "CREATE",
        targetType = "LEASE_DEPOSIT_REFUND", targetId = "#leaseId",
        content = "'发起押金退还：请求号 ' + #dto.outRequestNo")
public DepositRefundVO request(Long leaseId, CreateDepositRefundDTO dto) {
    LeaseRecord lease = requireLease(leaseId);
    SecurityUtils.checkCommunityAccess(lease.getCommunityId());

    RLock lock = redissonClient.getLock("lock:deposit:refund:" + leaseId);
    boolean locked = false;
    try {
        locked = lock.tryLock(5, TimeUnit.SECONDS);
        if (!locked) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "押金退还正在处理中，请稍后重试");
        }
        /* —— 校验段（锁内、无事务读）：幂等 → 状态门禁 → 金额 → 渠道 —— */
        LeaseDepositRefund existing = ... // 原幂等查询；命中直接返回 toVO(existing)
        // 原有 租约状态/押金状态/金额/渠道一致性/凭据门控 校验原样保留（含原注释）

        /* —— 事务一：落 PENDING —— */
        LeaseDepositRefund refund = new LeaseDepositRefund(); // 原字段填充逻辑不变
        refund.setCreatedAt(LocalDateTime.now()); // 库端 DEFAULT 不会回填实体，先置避免响应里为 null
        try {
            transactionTemplate.executeWithoutResult(s -> refundMapper.insert(refund));
        } catch (DuplicateKeyException e) {
            /* 撞 uk_deposit_out_request_no：并发同号重查，命中即幂等返回（DEF-103） */
            LeaseDepositRefund winner = refundMapper.selectOne(new LambdaQueryWrapper<LeaseDepositRefund>()
                    .eq(LeaseDepositRefund::getOutRequestNo, dto.getOutRequestNo()).last("LIMIT 1"));
            if (winner != null) { return toVO(winner); }
            throw e;
        }
        transactionTemplate.executeWithoutResult(s -> {
            lease.setDepositStatus(DepositStatus.REFUNDING);
            leaseMapper.updateById(lease);
            changeLogService.logDepositRefund(leaseId, "发起押金退还", ...);
        });

        /* —— 事务外：渠道调用（失败仅落 FAILED，不回滚 PENDING 与 REFUNDING 前置） —— */
        ChannelRefundResult result;
        try {
            result = client.refund(paidPayment, dto.getOutRequestNo(), amount, ...);
        } catch (Exception e) {
            log.error("押金退还渠道调用异常：leaseId={}, outRequestNo={}", leaseId, dto.getOutRequestNo(), e);
            result = ChannelRefundResult.failure("渠道调用异常：" + e.getMessage());
        }

        /* —— 事务二：结算 —— */
        return settle(lease, refund, result, dto.getRemark());
    } finally {
        if (locked) { unlockQuietly(lock); }
    }
}
```

要点：**去掉方法级 `@Transactional`**；新增 `unlockQuietly(RLock)` 私有方法（仿 `ViewingAppointmentService:682`）；`settle()` / `notifyResident()` 内的 DB 写包进 `transactionTemplate.executeWithoutResult(...)`。

- [ ] **步骤 4：`settle()` 部分退款语义**

```java
/* 押金终态口径（DEF-103）：仅当累计成功退款 ≥ 实缴押金才置 REFUNDED；
   部分退款成功保持 PAID（剩余额度可再次发起），与弹窗允许部分金额一致 */
private void settle(...) {
    // SUCCESS 分支内：
    BigDecimal refundedTotal = refundedAmount(lease.getId()).add(refund.getAmount());
    boolean fullyRefunded = paidDeposit(lease).compareTo(refundedTotal) <= 0;
    lease.setDepositStatus(fullyRefunded ? DepositStatus.REFUNDED : DepositStatus.PAID);
    // FAILED 分支：回落 PAID（原逻辑保留）
}
```

- [ ] **步骤 5：`paidDeposit()` 口径改为「有成功首期单即按登记押金」**

```java
/* 实缴押金口径（DEF-103，2026-09-23 决策日志）：存在 SUCCESS 首期单即视为押金已实缴，
   实缴额以租约登记押金 lease_record.deposit 为对照基准——原「首期单金额 − 当前月租」
   推算法在月租事后被编辑时会得到错误押金部分（V25 已应用禁改，其回填判据同源受影响，
   两库当前无月租事后变更数据，口径兼容；V28 迁移不对账，仅决策日志留痕） */
private BigDecimal paidDeposit(LeaseRecord lease) {
    return paidFirstPayments(lease.getId()).isEmpty() ? BigDecimal.ZERO : lease.getDeposit();
}
```

- [ ] **步骤 6：`listByLease()` 居民侧改显式正向授权**

```java
/* 居民侧唯一数据防线（DEF-103）：lease_deposit_refund 在 SKIP_TABLES 中，
   数据级拦截器不注入条件——非本人租约必须在此显式拒绝，勿删 */
if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
    if (!lease.getTenantId().equals(SecurityUtils.getUserId())) {
        throw new ForbiddenException("无权查看他人租约的押金退还记录");
    }
} else {
    SecurityUtils.checkCommunityAccess(lease.getCommunityId());
}
```

- [ ] **步骤 7：低优先级折叠修复（同文件顺手）**

- `paidDeposit`/`refundedAmount`/`hasApprovedMoveOut`/`approvedMoveOutId` 在 `request()` 单次调用内重复查询同一批行——在方法开头各查一次复用（`List<LeasePayment> paidList = paidFirstPayments(leaseId)` 传入各辅助方法或改私有重载）。
- 「未查到实缴押金记录，无可退金额」复用 `DEPOSIT_AMOUNT_EXCEEDED` 语义失真且不可达（`paidDeposit()==0` 已被 5704 拦）——该分支改为保留但日志记录（防回归），或直接删除不可达分支（二选一，注释说明）。
- 注释「账号缺失回落空串」与实现（回落 `null`）不符——改注释为「回落 null（渠道按缺省账号处理）」。

- [ ] **步骤 8：跑测试验证通过**

```bash
cd backend && ./mvnw test -Dtest=DepositRefundServiceTest -q
```
预期：全部 PASS（含既有用例——注意既有用例若依赖方法级事务语义，需按新结构调整 mock：`when(transactionTemplate.executeWithoutResult(any()))` 用 `doAnswer` 直执行回调）。

```java
doAnswer(inv -> { ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null); return null; })
        .when(transactionTemplate).executeWithoutResult(any());
```

- [ ] **步骤 9：自查**（不 commit）：`grep -n "System.out\|// " DepositRefundService.java` 确认无行注释新增；确认未动 V22~V25 迁移。

---

### 任务 2：退租申请并发防护与通知深链（DEF-104 / DEF-105 / DEF-130）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/lease/service/LeaseMoveOutApplicationService.java`
- 修改：`backend/src/main/java/com/community/residence/lease/dto/CreateMoveOutApplicationDTO.java`
- 测试：`backend/src/test/java/com/community/residence/lease/service/LeaseMoveOutApplicationServiceTest.java`

- [ ] **步骤 1：写失败测试**

```java
@Test
void 管理员通知sourceId传租约ID而非申请ID() {
    // create()：通知深链契约——type=LEASE 的 sourceId 必须是租约 ID（DEF-105）
    service.create(1L, validDto());
    verify(notificationService).createForUser(anyLong(), anyLong(), anyString(), anyString(),
            eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(1L)); // 1L = leaseId，非 application.getId()
}

@Test
void 审核通过与驳回通知sourceId传租约ID() {
    service.approve(9L, approveDto());   // approve 通知
    verify(notificationService).createForResident(anyLong(), anyLong(), anyString(), anyString(),
            eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(1L));
    service.reject(9L, rejectDto());     // reject 通知
    verify(notificationService, times(2)).createForResident(anyLong(), anyLong(), anyString(), anyString(),
            eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(1L));
}

@Test
void 并发approve只生效一次失败方幂等返回() {
    // approve 的条件更新返回 0 行（另一并发赢家已处理）且单据已 APPROVED → 幂等返回既有 VO，不重复副作用
    when(moveOutMapper.update(any(), any())).thenReturn(0);
    LeaseMoveOutApplication approved = approvedApplication();
    when(moveOutMapper.selectById(9L)).thenReturn(approved);
    LeaseMoveOutApplicationVO vo = service.approve(9L, approveDto());
    assertEquals(MoveOutStatus.APPROVED.name(), vo.getStatus());
    verify(leaseMapper, never()).updateById(any()); // 不重复触发租约终态链
}

@Test
void 退租日期早于起租日被拒() {
    LeaseRecord lease = activeLease(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    when(leaseMapper.selectById(1L)).thenReturn(lease);
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.create(1L, dtoWithDate(LocalDate.of(2025, 12, 31))));
    assertEquals(ErrorCode.INVALID_PARAM.getCode(), ex.getCode());
}
```

- [ ] **步骤 2：跑测试验证失败**：`cd backend && ./mvnw test -Dtest=LeaseMoveOutApplicationServiceTest -q`，预期新用例 FAIL。

- [ ] **步骤 3：三处通知 sourceId 修正（DEF-105）**

`create()`（原 :123）、`approve()`（原 :230）、`reject()`（原 :260）三处：

```java
notificationService.createForUser(adminId, lease.getCommunityId(),
        "居民提交退租申请", "...",
        "LEASE", "LEASE_MOVE_OUT", application.getLeaseId());  // 原 application.getId()
```

（通知文案不变；决策依据：`frontend/src/utils/notificationNav.ts` 把 `type=LEASE` 的 sourceId 解析为租约 ID 深链 `/admin/leases/{id}` / `?tab=lease&leaseId={id}`。）

- [ ] **步骤 4：`create()` 加锁防重复 PENDING（DEF-104）**

仿任务 1 模式：`RLock lock = redissonClient.getLock("lock:moveout:" + leaseId)` + `tryLock(5, TimeUnit.SECONDS)`，锁内完成「PENDING 计数检查 + insert + 通知」，finally `unlockQuietly`。（RedissonClient 注入本服务。）

- [ ] **步骤 5：`approve()` 条件更新防并发双执行（DEF-104）**

```java
/* 终态条件更新（DEF-104）：UPDATE ... WHERE id=? AND status='PENDING'，
   0 行即并发赢家已处理——重读后已 APPROVED 视为幂等成功返回，否则按状态冲突拒绝 */
int rows = moveOutMapper.update(null, new LambdaUpdateWrapper<LeaseMoveOutApplication>()
        .eq(LeaseMoveOutApplication::getId, application.getId())
        .eq(LeaseMoveOutApplication::getStatus, MoveOutStatus.PENDING)
        .set(LeaseMoveOutApplication::getStatus, MoveOutStatus.APPROVED)
        .set(LeaseMoveOutApplication::getApprovedBy, SecurityUtils.getUserId())
        .set(LeaseMoveOutApplication::getApprovedAt, LocalDateTime.now()));
if (rows == 0) {
    LeaseMoveOutApplication fresh = moveOutMapper.selectById(application.getId());
    if (fresh != null && MoveOutStatus.APPROVED.equals(fresh.getStatus())) {
        return toVO(fresh);
    }
    throw new BusinessException(ErrorCode.MOVE_OUT_APPLICATION_PENDING,
            "退租申请已被处理，当前状态：" + (fresh == null ? "未知" : fresh.getStatus()));
}
```

（字段名以实体实际为准——`approvedBy/approvedAt` 若不存在则按实体现有审计字段；后续副作用链在条件更新成功后执行，原内存对象同步 set 新值。`reject()` 同型处理。）

- [ ] **步骤 6：退租日期范围校验（DEF-130）**

DTO 层：`@NotNull` 之外加 `@FutureOrPresent(message = "退租日期不得早于今天")`（jakarta.validation）。
Service 层（`create()` 校验段追加）：

```java
/* 退租日期须落在租期窗口内（DEF-130）：早于起租日/晚于止租日的日期无业务意义 */
if (dto.getMoveOutDate().isBefore(lease.getStartDate()) || dto.getMoveOutDate().isAfter(lease.getEndDate())) {
    throw new BusinessException(ErrorCode.INVALID_PARAM,
            "退租日期须在租期范围内：" + lease.getStartDate() + " ~ " + lease.getEndDate());
}
```

- [ ] **步骤 7：`toVO()` N+1 批量化（DEF-127 之一）**

`page()` 与 `listByResident()` 改为：先取记录列表，再按 distinct id `selectBatchIds` 批量加载 resident/community/house/unit/building/sysUser → `Map<Long, X>`，`toVO(record, maps)` 组装（参照 `ConversationService.participantProfiles` 批量模式）。`page()` 的 size 钳制改 `Math.max(1, Math.min(size, 100))`。

- [ ] **步骤 8：跑测试验证通过**：`./mvnw test -Dtest=LeaseMoveOutApplicationServiceTest -q` 全 PASS。

---

### 任务 3：会话并发建群唯一化与防御（DEF-106 / DEF-119）

**文件：**
- 创建：`backend/src/main/resources/db/migration/V27__conversation_type_related_unique.sql`
- 修改：`backend/src/main/java/com/community/residence/conversation/service/ConversationService.java`
- 修改：`backend/src/main/java/com/community/residence/conversation/controller/ConversationController.java`
- 测试：`backend/src/test/java/com/community/residence/conversation/service/ConversationServiceTest.java`

- [ ] **步骤 1：写失败测试**

```java
@Test
void 并发建群撞唯一键后重查返回既有会话() {
    when(conversationMapper.selectOne(any())).thenReturn(null);   // 先查为空
    doAnswer(inv -> { throw new DuplicateKeyException("uk_conversation_type_related"); })
            .when(conversationMapper).insert(any(Conversation.class));
    Conversation winner = existingLeaseGroup(77L);
    when(conversationMapper.selectOne(any())).thenReturn(null).thenReturn(winner); // 撞库后重查
    Conversation result = service.createLeaseGroup(3L, 1L, 5L, "租约沟通");
    assertEquals(77L, result.getId());
}

@Test
void ensureLeaseGroup回链只更新conversationId字段() {
    // 不得全字段 updateById（并发状态变更会被覆盖，DEF-119）
    LeaseRecord lease = leaseWithConversationId(null);
    when(leaseRecordMapper.selectById(3L)).thenReturn(lease);
    when(conversationMapper.insert(any(Conversation.class))).thenAnswer(inv -> {
        ((Conversation) inv.getArgument(0)).setId(77L); return 1;
    });
    service.ensureLeaseGroup(3L);
    verify(leaseRecordMapper).update(isNull(), any()); // 条件更新，非 updateById(全字段)
}

@Test
void 关闭态会话joinWorkOrderGroup拒绝() {
    Conversation closed = closedWorkOrderGroup();
    when(conversationMapper.selectById(any())).thenReturn(closed);
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.joinWorkOrderGroup(9L, 5L, NotificationOwnerType.USER));
    assertEquals(ErrorCode.CONVERSATION_CLOSED.getCode(), ex.getCode());
}
```

- [ ] **步骤 2：跑测试验证失败**：`./mvnw test -Dtest=ConversationServiceTest -q`。

- [ ] **步骤 3：创建 V27 迁移**

```sql
-- V27：会话「同类型同业务主体」唯一化（OCR 静态评审 DEF-106）
-- LEASE_GROUP / WORK_ORDER_GROUP / APPOINTMENT_GROUP 按 (type, related_id) 唯一，
-- 杜绝并发建群产生重复会话（后续 LIMIT 1 静默二选一、参与者/消息分裂）。
-- DIRECT 的 related_id 为 NULL，MySQL 唯一索引允许多行 NULL，互不影响。
ALTER TABLE conversation
    ADD UNIQUE KEY uk_conversation_type_related (type, related_id);
```

（先自查现库若已存在重复行需在迁移内先合并/清理——写一段防御性去重 DELETE 保留最小 id 行 + 诊断 SELECT 注释；两库当前无重复数据，此段为幂等保障。）

- [ ] **步骤 4：`createLeaseGroup` / `createWorkOrderGroup` 捕获 DuplicateKeyException**

```java
try {
    conversationMapper.insert(conversation);
} catch (DuplicateKeyException e) {
    /* 并发建群撞 uk_conversation_type_related（DEF-106）：重查返回既有会话 */
    Conversation winner = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getType, type)
            .eq(Conversation::getRelatedId, relatedId)
            .last("LIMIT 1"));
    if (winner != null) { return winner; }
    throw e;
}
```

- [ ] **步骤 5：`ensureLeaseGroup` 回链改条件更新（DEF-119）**

```java
if (!conversation.getId().equals(lease.getConversationId())) {
    /* 只补写回链字段（DEF-119）：全字段 updateById 会把读取时刻的整租约快照写回，
       并发的状态流转（TO_SIGN→ACTIVE 等）会被静默覆盖 */
    leaseRecordMapper.update(null, new LambdaUpdateWrapper<LeaseRecord>()
            .eq(LeaseRecord::getId, leaseId)
            .set(LeaseRecord::getConversationId, conversation.getId()));
}
```

- [ ] **步骤 6：`addParticipant` 幂等撞库吞掉**：insert 包 try/catch `DuplicateKeyException` → 忽略（对齐 Javadoc「碰撞静默忽略」的既有声明）。

- [ ] **步骤 7：`joinWorkOrderGroup` / `sendSystemMessage` 补 CLOSED 校验**（纵深防御）：取到会话后 `ConversationStatus.CLOSED` 即抛 `CONVERSATION_CLOSED`（5604），对齐 `sendMessage` 既有行为与「关闭停收停发」契约。

- [ ] **步骤 8：`ConversationController`** :34 附近内联全限定名改为 import（`ViewingAppointmentService` / `ViewingAppointmentVO` / `CreateViewingAppointmentDTO`）。

- [ ] **步骤 9：跑测试验证通过**：`./mvnw test -Dtest=ConversationServiceTest -q`。

---

### 任务 4：首期支付并发与渠道防御（DEF-107 / DEF-128）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/payment/service/PaymentService.java`
- 修改：`backend/src/main/java/com/community/residence/payment/service/channel/WechatChannelClient.java`
- 测试：`backend/src/test/java/com/community/residence/payment/service/PaymentServiceTest.java`

- [ ] **步骤 1：写失败测试**

```java
@Test
void 首期支付并发锁内仅一单() {
    // 锁 mock：tryLock 返回 true，第二个请求读到 REFUNDING 式的已存在 PENDING 单 → 原样返回
    LeaseRecord lease = activeLease();
    when(leaseMapper.selectById(1L)).thenReturn(lease);
    LeasePayment pending = pendingFirstPayment();
    when(paymentMapper.selectOne(any())).thenReturn(pending);
    LeasePaymentVO vo = service.createFirstPayment(1L, firstDto("ALIPAY"));
    assertEquals(pending.getPaymentNo(), vo.getPaymentNo());
}

@Test
void 分页列表租约批量装载不逐行查() {
    Page<LeasePayment> page = pageOf(3); // 3 条不同 leaseId
    when(paymentMapper.selectPage(any(), any())).thenReturn(page);
    when(leaseMapper.selectBatchIds(any())).thenReturn(List.of(lease(1), lease(2), lease(3)));
    service.page(1, 10, null, null);
    verify(leaseMapper, times(1)).selectBatchIds(any()); // 一次批量，非 3 次 selectById
}
```

- [ ] **步骤 2：跑测试验证失败**：`./mvnw test -Dtest=PaymentServiceTest -q`。

- [ ] **步骤 3：`createFirstPayment` 加锁**：`RLock lock = redissonClient.getLock("lock:payment:first:" + leaseId)`，锁内完成「已结清计数 + PENDING 续接 + insert」（V15 注释自认无库级约束，锁是唯一防线；DEF-107）。注入 `RedissonClient` + `unlockQuietly`。

- [ ] **步骤 4：`page()` 批量装载租约**：

```java
List<Long> leaseIds = records.stream().map(LeasePayment::getLeaseId)
        .filter(Objects::nonNull).distinct().toList();
Map<Long, LeaseRecord> leaseMap = leaseIds.isEmpty() ? Map.of()
        : leaseMapper.selectBatchIds(leaseIds).stream()
                .collect(Collectors.toMap(LeaseRecord::getId, Function.identity()));
// convert 内改调 toVO(payment, null, leaseMap.get(payment.getLeaseId()))
```

（三参 `toVO(payment, credential, lease)` 重载已存在——`listByLease` 在用；如签名不同以实际为准。）

- [ ] **步骤 5：`createFirstPayment` 金额计算 null 防御**：`lease.getMonthlyRent()` 空指针风险对齐同文件 `fillAmountBreakdown` 的防御式写法（`!= null ? : BigDecimal.ZERO`）。

- [ ] **步骤 6：`WechatChannelClient.refund` 两处防御（DEF-128）**：

```java
/* 渠道返回空状态按失败处理（DEF-128）：null 无法确认已退款，不得当成功落账 */
if (refund.getStatus() == null) {
    return ChannelRefundResult.failure("微信退款响应无状态，无法确认退款结果");
}
```
金额转分：
```java
/* 先收敛到 2 位小数再转分，避免服务端计算值超精度时 longValueExact 抛裸异常（DEF-128） */
long totalFen = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
```

- [ ] **步骤 7：跑测试验证通过**：`./mvnw test -Dtest=PaymentServiceTest -q`。

---

### 任务 5：租约状态机收口（DEF-108 / DEF-115）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/lease/dto/UpdateLeaseStatusDTO.java`
- 修改：`backend/src/main/java/com/community/residence/lease/service/LeaseService.java`
- 修改：`backend/src/main/java/com/community/residence/agreement/service/AgreementService.java`
- 测试：`backend/src/test/java/com/community/residence/lease/service/LeaseServiceTest.java`、`LeaseStateMachineFixTest.java`、`LeaseSigningClosureTest.java`、`TenancyAgreementHappyPathTest.java`、`LeaseTenancyConfirmGateTest.java`（按实际受影响面调整）

- [ ] **步骤 1：写失败测试**

```java
@Test
void 状态端点接受TO_SIGN目标值() throws Exception {
    // Bean Validation 层：UpdateLeaseStatusDTO 的 @Pattern 必须放行 TO_SIGN（DEF-108）
    UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
    dto.setStatus("TO_SIGN");
    dto.setRemark("测试");
    Set<ConstraintViolation<UpdateLeaseStatusDTO>> violations =
            Validation.buildDefaultValidatorFactory().getValidator().validate(dto);
    assertTrue(violations.isEmpty());
}

@Test
void PENDING直接ACTIVE被拒() {
    // 冻结状态机（架构设计 §6 v1.8）无 PENDING→ACTIVE 边
    LeaseRecord lease = leaseOf("PENDING");
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.applyTransition(lease, LeaseStatus.ACTIVE, "测试"));
    assertEquals(ErrorCode.STATE_TRANSITION_INVALID.getCode(), ex.getCode());
}

@Test
void 并发同目标流转幂等不重复留痕() {
    // 条件更新 0 行 + 重读已为目标状态 → 幂等返回，不重复 logStatus
    LeaseRecord lease = leaseOf("TO_SIGN");
    when(leaseMapper.update(any(), any())).thenReturn(0);
    when(leaseMapper.selectById(1L)).thenReturn(leaseOf("ACTIVE"));
    service.applyTransition(lease, LeaseStatus.ACTIVE, "测试");
    verify(changeLogService, never()).logStatus(anyLong(), anyString(), anyString(), any());
}

@Test
void 协议确认生效走统一流转内核() {
    // AgreementService.activateLeaseOnSigned 委托 leaseService.applyTransition（DEF-115）
    // 断言：非 TO_SIGN/ACTIVE 租约上双方确认 → STATE_TRANSITION_INVALID（不再 LEASE_STATUS_INVALID）
}
```

- [ ] **步骤 2：跑测试验证失败**：`./mvnw test -Dtest='LeaseServiceTest,LeaseStateMachineFixTest' -q`。

- [ ] **步骤 3：`UpdateLeaseStatusDTO`** 的 `@Pattern` 改 `^(PENDING|TO_SIGN|ACTIVE|MOVED_OUT|ARCHIVED|REJECTED)$`，`@Schema` 描述同步补「TO_SIGN-待签约」。

- [ ] **步骤 4：`ALLOWED_TRANSITIONS` 删除 `PENDING→ACTIVE` 边**（冻结状态机 §6 六态无此边；块注释同步说明「审核通过 ≠ 租约生效，生效仅经承租确认+协议确认闭环」）。

- [ ] **步骤 5：`applyTransition` 条件更新防并发双确认**

```java
/* 条件更新（DEF-115）：WHERE id=? AND status=<流转前状态>——两路并发确认只成功一路；
   0 行时重读：已为目标状态按幂等处理直接返回（不重复留痕/房态联动），否则按非法流转拒绝 */
int rows = leaseMapper.update(lease, new LambdaUpdateWrapper<LeaseRecord>()
        .eq(LeaseRecord::getId, lease.getId())
        .eq(LeaseRecord::getStatus, current));
if (rows == 0) {
    LeaseRecord fresh = leaseMapper.selectById(lease.getId());
    if (fresh != null && target.equals(fresh.getStatus())) { return; }
    throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
            String.format("租住状态不允许从 %s 流转到 %s（并发变更，当前 %s）",
                    current, target, fresh == null ? "未知" : fresh.getStatus()));
}
```

（`update(lease, wrapper)`：SET 实体非空字段 + WHERE 双条件，保留原 updateById 的全字段语义。后续 `logStatus` / `markHouseOccupied` 原样。）

- [ ] **步骤 6：`AgreementService.activateLeaseOnSigned` 委托统一内核**：删除本地复制的 TO_SIGN→ACTIVE 校验 + updateById + logStatus + 房态翻转，改调 `leaseService.applyTransition(lease, LeaseStatus.ACTIVE, "租赁协议双方确认完成，租约生效")`。**前置检查**：确认 `LeaseService` 不反向依赖 `AgreementService`（无循环依赖）；方法级 `@Transactional` 留在 AgreementService 即可（applyTransition 自带事务加入同一事务）。

- [ ] **步骤 7：受影响既有测试适配**：所有 stub `leaseMapper.updateById(any())` 返回 1 的用例改为 `leaseMapper.update(any(), any())` 返回 1（mechanical 替换，逐个文件过）。

- [ ] **步骤 8：跑测试验证通过**：`./mvnw test -Dtest='LeaseServiceTest,LeaseStateMachineFixTest,LeaseSigningClosureTest,TenancyAgreementHappyPathTest,LeaseTenancyConfirmGateTest' -q`。

---

### 任务 6：工单与入住申请状态竞态（DEF-116 / DEF-117）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/workorder/service/WorkOrderService.java`
- 修改：`backend/src/main/java/com/community/residence/resident/service/ResidenceApplicationService.java`
- 修改：`backend/src/main/java/com/community/residence/resident/entity/ResidenceApplication.java`
- 测试：`backend/src/test/java/com/community/residence/workorder/service/*.java`（`WorkOrderServiceTest` / `WorkOrderBatch2FixTest` / `WorkOrderBatch3FixTest` / `WorkOrderServiceExtraTest` / `WorkOrderStateMachineFixTest`）、`backend/src/test/java/com/community/residence/resident/service/ResidenceApplicationServiceTest.java`

- [ ] **步骤 1：`WorkOrderService.transition` 条件更新**

```java
/* 终态迁移条件更新（DEF-116）：WHERE id=? AND status=<流转前状态>；
   0 行时重读——同状态按幂等返回（不重复建群/留痕），否则 5302 */
int rows = workOrderMapper.update(order, new LambdaUpdateWrapper<WorkOrder>()
        .eq(WorkOrder::getId, order.getId())
        .eq(WorkOrder::getStatus, from));
if (rows == 0) {
    WorkOrder fresh = workOrderMapper.selectById(order.getId());
    if (fresh != null && fresh.getStatus().equals(order.getStatus())) { return; }
    throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION, ...);
}
```

（`transition` 是私有内核的话在调用点 `updateById(order)` 处统一替换；`from` 取进入 transition 前快照的状态。顺手删除 `WorkOrderService.java:10` 死导入 `ConversationType`。）

- [ ] **步骤 2：工单测试 mechanical 适配**：所有 `when(workOrderMapper.updateById(any())).thenReturn(1)` 改 `when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1)`；`verify(...updateById...)` 断言同步改。追加 2 个新用例：并发 intake 0 行 + 状态一致 → 幂等；0 行 + 状态不一致 → 5302。

- [ ] **步骤 3：`ResidenceApplicationService` withdraw/approve 条件更新（DEF-117）**

```java
/* 撤回条件更新（DEF-117）：WHERE id=? AND status='PENDING'——与并发审批互斥；
   0 行时重读，已 WITHDRAWN 幂等成功返回，已 APPROVED/REJECTED 按状态冲突拒绝 */
int rows = applicationMapper.update(null, new LambdaUpdateWrapper<ResidenceApplication>()
        .eq(ResidenceApplication::getId, id)
        .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING)
        .set(ResidenceApplication::getStatus, ApplicationStatus.WITHDRAWN));
```
（approve 同型：`PENDING→APPROVED` 条件更新 + 0 行重读幂等。）

- [ ] **步骤 4：withdraw 弱回退误关群收窄（DEF-117）**

```java
/* 弱回退关闭群聊的前置（DEF-117）：仅当弱命中租约不处于活跃态（TO_SIGN/ACTIVE）
   才关闭其群聊——活跃租约的群是现行沟通渠道，无关撤回不得误关 */
```
在调用 `closeLeaseGroup(weakLease.getId())` 前判断 `!(TO_SIGN || ACTIVE).contains(weakLease.getStatus())`，否则跳过关闭并 log.info 说明。

- [ ] **步骤 5：`ResidenceApplication` `status` 字段 `@Schema` 补 `WITHDRAWN-已撤回`**。

- [ ] **步骤 6：跑测试**：`./mvnw test -Dtest='WorkOrderServiceTest,WorkOrderBatch2FixTest,WorkOrderBatch3FixTest,WorkOrderServiceExtraTest,WorkOrderStateMachineFixTest,ResidenceApplicationServiceTest' -q` 全 PASS。

---

### 任务 7：看房预约链路三处（DEF-110 / DEF-118）

**文件：**
- 修改：`backend/src/main/java/com/community/residence/housing/service/ViewingAppointmentService.java`
- 测试：`backend/src/test/java/com/community/residence/housing/service/ViewingAssignTest.java` / `ViewingMessageAccessTest.java` / `ViewingNotificationFixTest.java`（按受影响面）

- [ ] **步骤 1：写失败测试**

```java
@Test
void 已关闭租约群聊内发起看房预约被拒5604() {
    Conversation closed = closedLeaseGroup(55L, 3L);
    when(conversationService.requireLeaseGroup(55L)).thenReturn(closed);
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.createFromConversation(55L, validDto()));
    assertEquals(ErrorCode.CONVERSATION_CLOSED.getCode(), ex.getCode());
}

@Test
void 预约传入与租约无关的housingId被拒() {
    // resolveHousingFromLease：传入 housingId 解析出的 house_id 必须等于 lease.houseId
    LeaseRecord lease = leaseOfHouse(8L);
    Housing otherHouse = housingOfHouseId(99L);
    when(housingMapper.selectById(2L)).thenReturn(otherHouse);
    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.createFromConversation(55L, dtoWithHousingId(2L)));
    assertEquals(ErrorCode.INVALID_PARAM.getCode(), ex.getCode());
}
```

- [ ] **步骤 2：跑测试验证失败**。

- [ ] **步骤 3：CLOSED 校验（DEF-110）**：`requireLeaseGroup` 返回后立即：

```java
/* 会话关闭即拒绝（接口设计 §9.19.7 错误码 5604 消费点）：退租通过/租约归档后
   该群已停收停发，不得再以其为入口发起新看房预约 */
if (ConversationStatus.CLOSED.equals(conversation.getStatus())) {
    throw new BusinessException(ErrorCode.CONVERSATION_CLOSED);
}
```

- [ ] **步骤 4：`resolveHousingFromLease` 归属校验（DEF-118）**：传入 `housingId` 早返回前，校验 `housing.getHouseId().equals(lease.getHouseId())`，不等抛 `INVALID_PARAM("预约房源与租约房屋不一致，无法在租约会话内预约该房源")`；解析路径同样校验后返回。

- [ ] **步骤 5：租约看房列表排序补 `appointment_date` 降序**（§9.19.8 契约）：`.orderByDesc(ViewingAppointment::getAppointmentDate)` 加在 `.orderByDesc(ViewingAppointment::getId)` 之前。

- [ ] **步骤 6：注释修正**：原 :119「建群在锁内落库之后」改为与实现一致（预约先落库出锁，建群与 conversation_id 回写在锁外之后）。

- [ ] **步骤 7：跑测试验证通过**：`./mvnw test -Dtest='ViewingAssignTest,ViewingMessageAccessTest,ViewingNotificationFixTest' -q`。

---

### 任务 8：迁移修正与种子/资产修复（DEF-109 / DEF-113 / DEF-114 / DEF-120 / DEF-131 / DEF-132）

**文件：**
- 创建：`backend/src/main/resources/db/migration/V28__ocr_backfill_corrections.sql`
- 修改：`docs/50_系统测试/scripts/05_clean_seed_realistic.sql`
- 修改：`docs/30_系统设计/assets/02_核心状态机/05_入住申请状态机图.html`
- 修改：`docs/30_系统设计/assets/02_核心状态机/02_租住记录状态机.lifecycle.json`

**⚠️ 本任务无编译/测试回路，改动必须逐字节谨慎；V28 不得回改 V21/V26 任何字节。**

- [ ] **步骤 1：V27 已由任务 3 创建**，本任务先确认其存在（无则报错返回，不重复创建）。

- [ ] **步骤 2：创建 V28 迁移（V21/V26 回填修正，DEF-109 / DEF-120）**

```sql
-- V28：OCR 静态评审回填修正（DEF-109 / DEF-120）
-- ① V21 修正：通知归属回填的误翻转风险——V21 白名单含 LEASE_TENANCY（仅发管理员）
--    与 LEASE_PAYMENT（双空间都发），user_id 命中 resident.id 即翻转，会把 id 撞号的
--    管理员通知错划为 RESIDENT（恰是 DEF-067 要消除的跨空间泄漏）。
--    本迁移先按「更强的身份判据」复核：owner_type 已为 RESIDENT 但 user_id 实为
--    sys_user 的行回滚为 USER（NOT EXISTS sys_user 反向兜底）。
-- ② V26 修正：入住申请强关联回填未限定 relation_type='TENANT'，OWNER/FAMILY 的
--    APPROVED 申请可能被错挂同屋租约。回滚错挂行（lease_id 置 NULL），
--    再按 TENANT 收窄重链。

-- ① 复核 V21 误翻转（幂等：仅动「RESIDENT 但 user 实为 sys_user」的行）
UPDATE notification n
   SET n.owner_type = 'USER'
 WHERE n.owner_type = 'RESIDENT'
   AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = n.user_id);

-- 诊断（只读，供人工复核，注释形式保留）：
-- SELECT n.id, n.user_id, n.source_type FROM notification n
--  WHERE n.owner_type = 'RESIDENT'
--    AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = n.user_id);

-- ② 回滚 V26 错挂的 OWNER/FAMILY 强关联
UPDATE residence_application a
   SET a.lease_id = NULL
 WHERE a.lease_id IS NOT NULL
   AND a.relation_type <> 'TENANT';

-- 按 TENANT 收窄重链（口径同运行时写路径：仅 TENANT 申请审批时创建租约）
UPDATE residence_application a
   JOIN lease_record l
     ON l.tenant_id = a.resident_id
    AND l.community_id = a.community_id
    AND l.house_id = a.house_id
   SET a.lease_id = l.id
 WHERE a.status = 'APPROVED'
   AND a.relation_type = 'TENANT'
   AND a.lease_id IS NULL
   AND l.id = (
        SELECT MAX(l2.id) FROM lease_record l2
         WHERE l2.tenant_id = a.resident_id
           AND l2.community_id = a.community_id
           AND l2.house_id = a.house_id
   );
```

（`relation_type` 列值以 V1 基线实际枚举为准——先读 `V1__*.sql` 的 `residence_application` 定义核对大小写与取值；`l.id = (SELECT MAX...)` 的 MySQL UPDATE-JOIN 子查询写法若报「不能在同一表更新中引用目标表」需改为两步：先 SELECT MAX 存临时表再 UPDATE——按实际执行环境语义写静态 SQL，两库由用户应用。）**注意**：本迁移在两库应用由用户操作，子代理只产出文件；写法必须可在 MySQL 9 直接执行（不用变量、不用存储过程）。

- [ ] **步骤 3：种子脚本 `05_clean_seed_realistic.sql`（DEF-113 / DEF-132）**

- `lease_record` 的 INSERT 列清单**补 `deposit_status`**：按脚本自身锚点（约 :690 的「4=MOVED_OUT+押金PAID」、租约 6 有 FIRST+SUCCESS 支付）置——租约 4/6 显式 `'PAID'`，其余按各自首期支付情况（有 SUCCESS 首期单的置 PAID，其余 UNPAID；以脚本「支付记录」段的实际数据为准逐条核对）。
- 文件头第 5 行 `V1~V21` 与第 19 行 `V1~V26` 矛盾——统一为 `V1~V26`。
- `staff_schedule` 社区 2 行的 `created_by = 2`（admin1 未绑社区 2）→ 改 `5`（admin2，以 `sys_admin_community` 段实际绑定为准）；§12.1 自检清单补一行 `staff_schedule.created_by` 越社区核查。

- [ ] **步骤 4：状态机资产（DEF-114 / DEF-131）**

- `05_入住申请状态机图.html:5041`：`M 154 157 L 132 157` → `M 154 157 L 286 157`（主轨正向横穿主管道）。
- 同文件 :5046：`data-composition-points` 去重 `769,479;769,479` → `769,479`；path 去掉零长尾段 `L 769 479 L 769 479` → 单个 `L 769 479`。
- `02_租住记录状态机.lifecycle.json:29`：删除 `TO_SIGN → REJECTED` 的 `withdraw` 迁移边（申请撤回不改租约状态，租约维持 TO_SIGN 仅关群聊；REJECTED 仅承接「审核驳回」PENDING→REJECTED）。保留其余边不动。

- [ ] **步骤 5：自查**：`git diff` 逐文件复核——V1~V26 零字节改动（`git diff --stat backend/src/main/resources/db/migration/` 只应出现 V27/V28 两个新文件）；种子与资产改动仅上述点位。

---

### 任务 9：居民端租约支付口径（DEF-111 / DEF-129）

**文件：**
- 修改：`frontend/src/views/resident/LeaseListView.vue`
- 修改：`frontend/src/views/resident/LeaseFirstPaymentDialog.vue`
- 测试：`frontend/src/views/resident/__tests__/LeaseListView.spec.ts`、`LeaseFirstPaymentDialog.spec.ts`

- [ ] **步骤 1：写失败测试**（按既有 spec 风格，用假后端或组件挂载）

```typescript
it('续租成功但首期未付时仍显示待支付首期入口', () => {
  // payments = [{ paymentType: 'RENEWAL', status: 'SUCCESS' }]，lease.status = 'ACTIVE'
  // 断言：入口 action === 'FIRST'（settled 只认 FIRST 口径）
})
it('TO_SIGN 租约不显示待支付首期入口', () => {
  // lease.status = 'TO_SIGN' → 入口不出 FIRST
})
it('支付列表加载失败且租约已搬出时不显示可点支付入口', () => {
  // paymentsUnknown = true + status = 'MOVED_OUT' → 无 FIRST 入口
})
it('「支付记录」tab 标签改为「租约明细」', () => { /* 快照或文本断言 */ })
```

- [ ] **步骤 2：跑测试验证失败**：`cd frontend && npx vitest run src/views/resident/__tests__/LeaseListView.spec.ts`。

- [ ] **步骤 3：三处口径修正（DEF-111）**

```typescript
// settled 只认 FIRST 口径（原「任一 SUCCESS」把续租成功也算结清，DEF-111）
const settled = !unknown && firstPaymentOf(row) !== null

// FIRST 入口收敛到 ACTIVE（后端 createFirstPayment 仅放行 ACTIVE，TO_SIGN 点了必 400）
if (!settled && !expired && row.status === 'ACTIVE') {

// unknown 回退分支同样限支付态
if (unknown && !expired && (row.status === 'ACTIVE' || row.status === 'TO_SIGN')) {
  // ↑ 该分支若保留 TO_SIGN，仅展示「支付状态未知」占位，不再给可点的 FIRST 动作；
  //   简单做法：unknown 分支直接不给 action，展示「支付记录加载失败」重试入口
```
（`paymentEntryOf` 的整体结构以现状为准——目标是：**可点的 FIRST 动作只在 `ACTIVE && !settled && !expired` 出现**；TO_SIGN 不出现可点支付入口。）

- [ ] **步骤 4：tab 标签**：`<el-radio-button value="payments">支付记录</el-radio-button>` → `租约明细`（内容已是三源明细视图）。

- [ ] **步骤 5：`LeaseFirstPaymentDialog.vue` 支付宝表单注入改 Blob URL（DEF-129，与 `LeaseRenewDialog` 同款问题本任务一并修）**

```typescript
// alipayForm 是后端生成的已签名表单 HTML；用 Blob URL 加载并自动提交，
// 替代 document.write（避免向新窗口文档流注入未转义标记）
const blob = new Blob([form], { type: 'text/html' })
const url = URL.createObjectURL(blob)
const payWindow = window.open(url, '_blank')
payWindow?.addEventListener('load', () => URL.revokeObjectURL(url))
```
（`LeaseRenewDialog.vue` 同点同修——**注意**：若 `LeaseRenewDialog.vue` 归属其他波次任务文件集冲突则只修 `LeaseFirstPaymentDialog`，Renew 的同型修复在计划尾注登记遗留。核对：`LeaseRenewDialog.vue` 不在任务 10/11/12 文件集内 → 本任务一并修，并在产出说明中注明。）

- [ ] **步骤 6：跑测试验证通过**：`npx vitest run src/views/resident/__tests__/LeaseListView.spec.ts src/views/resident/__tests__/LeaseFirstPaymentDialog.spec.ts` + `npx vue-tsc --noEmit`（只关注本任务文件报错）。

---

### 任务 10：管理端租约/退租前端（DEF-112 / DEF-123）

**文件：**
- 修改：`frontend/src/views/admin/lease/MoveOutApplicationView.vue`
- 修改：`frontend/src/views/admin/lease/DepositRefundDialog.vue`
- 修改：`frontend/src/views/admin/lease/LeaseDetailView.vue`
- 修改：`frontend/src/views/admin/lease/HousingLeaseHubView.vue`
- 测试：`frontend/src/views/admin/lease/__tests__/MoveOutApplicationView.spec.ts`、`DepositRefundDialog.spec.ts`、`LeaseDetailView.spec.ts`、`HousingLeaseHubView.spec.ts`

- [ ] **步骤 1：写失败测试**

```typescript
it('退款失败后押金状态标记清除（PAID 不再显示退还中）', () => {
  // handleRefundSuccess：fresh.depositStatus = 'PAID' → refundedLeaseIds[leaseId] 不落 PENDING
})
it('Dialog 在 FAILED 结果不 emit success', () => {
  // created.status = 'FAILED' → 不触发 success 事件
})
it('押金状态枚举全部渲染中文（REFUNDING → 退还中）', () => { /* 文本断言 */ })
it('押金退还成功后只调 fetchDetail 一次链', () => { /* mock 计数 */ })
```

- [ ] **步骤 2：`MoveOutApplicationView.vue` 三态映射（DEF-112）**

```typescript
/* 渠道失败后端回落 PAID：本地标记须按三态映射，否则失败也永远显示「退还中」（DEF-112） */
function depositMarkerOf(status: string | null | undefined): 'SUCCESS' | 'PENDING' | null {
  if (status === 'REFUNDED') return 'SUCCESS'
  if (status === 'REFUNDING') return 'PENDING'
  return null // PAID/未知：以服务端权威值为准，本地标记清除
}
```
`handleRefundSuccess` 成功分支与 catch 分支统一改用 `depositMarkerOf(fresh.depositStatus)`。

- [ ] **步骤 3：`DepositRefundDialog.vue`**：① 仅 `PENDING/SUCCESS` 才 `emit('success')`，`FAILED` 停留弹窗显示 `failMessage`（补一个「重试」按钮调原提交函数）；② :264 押金状态直出改 `depositStatusLabels[lease.depositStatus] ?? lease.depositStatus`（映射表从既有视图引入或本文件定义全五态）；③ `openRefund` 失败（lease 为 null）时弹窗体渲染明确错误文案（「租约信息加载失败，请刷新重试」）替代空白弹窗。

- [ ] **步骤 4：`LeaseDetailView.vue`**：`handleRefundSuccess` 改 `await fetchDetail()`（内部已含 fetchDeposit，去掉并发双请求乱序风险）；`handleApprove` 的 `TO_SIGN` 分支——按「列表页可置为生效、详情页补入口」口径：`canApprove` 放宽为 `status === 'PENDING' || status === 'TO_SIGN'`（TO_SIGN 时按钮文案「置为生效」，走 `TO_SIGN→ACTIVE` 流转），消除死代码分支（若产品口径不符则删除该分支并在产出说明注明——默认实现前者）。

- [ ] **步骤 5：`HousingLeaseHubView.vue` 角色门控**：`AgreementTemplateView` 面板加 `v-if="visibleTabs.some(t => t.value === 'agreements')"`（角色不可见即不挂载，避免每次 Hub 访问触发 403 报错 toast）。

- [ ] **步骤 6：跑测试验证通过**：`npx vitest run src/views/admin/lease/__tests__/` + `npx vue-tsc --noEmit`。

---

### 任务 11：通知分类与会话前端（DEF-121 / DEF-122）

**文件：**
- 修改：`frontend/src/utils/notificationNav.ts`
- 修改：`frontend/src/types/modules/notification.ts`
- 修改：`frontend/src/components/business/NotificationList.vue`
- 修改：`frontend/src/views/resident/ConversationDetailView.vue`
- 修改：`frontend/src/components/business/ConversationChat.vue`
- 修改：`frontend/src/views/admin/conversation/ConversationListView.vue`
- 测试：`frontend/src/utils/__tests__/notificationNav.spec.ts`、`frontend/src/views/admin/conversation/__tests__/ConversationListView.spec.ts`

- [ ] **步骤 1：写失败测试**

```typescript
it('LEASE_MOVE_OUT 通知归入 lease 分类并深链租约', () => {
  // NotificationList 分类 + notificationNav：type=LEASE, sourceType=LEASE_MOVE_OUT → lease 分类 + /admin/leases 或 ?tab=lease
})
it('LEASE_SIGN 死分支已删除', () => { /* 源断言或类型断言 */ })
it('会话加载失败时聊天面板降级只读', () => { /* isClosed 默认 true 断言 */ })
```

- [ ] **步骤 2：通知三处对齐（DEF-121）**：`NotificationSourceType` 联合类型 + `NotificationList.vue` 的 `CATEGORY_TYPES.lease` + `notificationNav.ts` 导航映射，统一补 `LEASE_MOVE_OUT`（居民端落 `?tab=lease&leaseId={sourceId}`、管理端落 `/admin/leases/{sourceId}`，sourceId 已在任务 2 改为租约 ID）；删除 `LEASE_SIGN` 死分支；`notificationNav.ts:76` 注释「写入 sourceType=LEASE」更正为「`LeaseExpiryReminderTask` 写入 `sourceType=LEASE_RECORD`（type 参数为 LEASE）」。

- [ ] **步骤 3：`ConversationDetailView.vue` 两处（DEF-122）**：① `getConversation` 失败且 fallback 列表未命中时 `isClosed` 判 **true**（无法确认状态即降级只读，面板顶部提示「会话状态未知，已临时锁定为只读」）；② :70 `lease.tenantId === userStore.user?.id` 比较处**加澄清注释**（居民登录态 `AuthUser.id` 即 resident 空间 ID，与 `lease.tenantId` 同空间，比较成立——OCR 误报，注释防止后人误改；不改逻辑）。

- [ ] **步骤 4：`ConversationChat.vue`**：① `closed` prop 语义收紧——在组件内部 `computed` 里对 `closed === undefined` 打 WARN 日志（dev 环境 `console.warn` 提示调用方应显式传入）；② `closedHint` 默认文案从「租约流程已结束」改中性「会话已关闭，历史消息只读留档」，租约调用方自行传入租约专属文案；③ :179 `conversationRoleLabels[member.role] ?? member.role` 兜底。

- [ ] **步骤 5：`ConversationListView.vue`**：① `typeFilter` 过滤下「发起直通」成功后：置 `typeFilter = ''` 再 `select`（避免返回的 DIRECT 会话被过滤出去导致右侧面板空白 + 误报「已建立」）；② 居民选择器按已选 `communityId` 过滤选项（`listResidents({ communityId, page: 1, size: 100 })`——后端已支持社区过滤参数则用之，不支持则前端本地过滤并注明）；③ 加载失败 `console.error` + 轻提示（不再静默空数组）。

- [ ] **步骤 6：跑测试验证通过**：`npx vitest run src/utils/__tests__/notificationNav.spec.ts src/views/admin/conversation/__tests__/ConversationListView.spec.ts` + `npx vue-tsc --noEmit`。

---

### 任务 12：工单与入住申请前端（DEF-124 / DEF-125）

**文件：**
- 修改：`frontend/src/views/admin/workorder/WorkOrderListView.vue`
- 修改：`frontend/src/views/admin/workorder/WorkOrderDetailView.vue`
- 修改：`frontend/src/views/resident/WorkOrderDetailView.vue`
- 修改：`frontend/src/views/resident/ResidenceApplyView.vue`
- 修改：`frontend/src/views/resident/ResidenceApplicationListView.vue`
- 测试：`frontend/src/views/admin/workorder/__tests__/WorkOrderListView.spec.ts`、`WorkOrderDetailView.spec.ts`、`frontend/src/views/resident/__tests__/WorkOrderDetailView.spec.ts`

- [ ] **步骤 1：写失败测试**

```typescript
it('终审确认框打开期间按钮防重入', () => { /* finalizingId 置位时序断言 */ })
it('退回工单后 toast 不再指向评价表单', () => { /* 文案断言 */ })
```

- [ ] **步骤 2：`WorkOrderListView.vue` `handleFinalize` 防重入（DEF-124）**：`finalizingId.value = order.id` 移到 `await ElMessageBox.confirm` **之前**，取消分支 `return` 前（finally 已复位）；确认框文案保持现状。

- [ ] **步骤 3：`resident/WorkOrderDetailView.vue` 退回 toast 文案**：`反馈已记录，已退回服务人员重新处理，完成后可再次确认并评价`（原「请在下方评价中打分」指向退回后不可用的表单）。

- [ ] **步骤 4：`admin/WorkOrderDetailView.vue`**：① :565 CSS 收括号修复（`}` 独立成行）；② :159 删除过时注释「仅居民确认完成（已完成）后可关闭」，保留 R74 终审注释单条。

- [ ] **步骤 5：`ResidenceApplyView.vue` 魔法数 5206**：在 `frontend/src/constants/apiCodes.ts`（新建，若已有同类文件则复用）导出 `export const APPLICATION_PENDING_EXISTS = 5206`（注释引用 `ErrorCode.APPLICATION_PENDING_EXISTS`，前端唯一来源为后端错误码契约），视图改引常量。

- [ ] **步骤 6：`ResidenceApplicationListView.vue`**：① `v-for` 与 `v-else` 同元素问题——`<template v-else>` 包裹循环；② 撤回后当前页清空回退 `if (records.value.length === 1 && page.value > 1) page.value -= 1` 再 `load()`；③ :88 注释「只认 APPROVED 的弱关联」措辞对齐类型定义（强关联/V26 口径）。

- [ ] **步骤 7：跑测试验证通过**：`npx vitest run src/views/admin/workorder/__tests__/ src/views/resident/__tests__/WorkOrderDetailView.spec.ts` + `npx vue-tsc --noEmit`。

---

### 任务 13：假后端测试底座对齐（DEF-126）

**文件：**
- 修改：`frontend/src/test/fakeBackend.ts`
- 测试：`frontend/src/__tests__/core-flows.spec.ts`、`conversation-contracts.spec.ts`（若断言需适配）

- [ ] **步骤 1：三处语义对齐（DEF-126）**

```typescript
// ① listConversations 参与者过滤改用三元组判定（与 getConversation/消息端点同口径，
//    居民与系统用户 id 撞号时列表不得串见他人会话）
const visible = all.filter((item) => this.isConversationParticipant(item, actor))

// ② 押金幂等回放基准：existing 查找前移到 refundable 计算之前，
//    重复请求与既有记录金额一致即返回既有（不再被 5706 误拒）
const existing = refunds.find((r) => r.outRequestNo === data.outRequestNo)
if (existing) { /* 一致性比较后返回既有 */ }

// ③ getMyProfile 静默回退 residents[0] 掩盖越权回归——改为 404 失败
const found = this.state.residents.find((item) => item.id === actor.id)
if (!found) fail(CODES.NOT_FOUND, '当前身份无对应居民档案')
return found
```

- [ ] **步骤 2：格式顺手修复**：:882 JSDoc 与方法声明挤行拆开；:1561 `}, listMoveOutApplications` 拆行。

- [ ] **步骤 3：跑受影响 spec**（不跑全量——同波任务 11/12 在并行修改前端，全量归主会话收尾）：`cd frontend && npx vitest run src/__tests__/core-flows.spec.ts src/__tests__/conversation-contracts.spec.ts`——既有用例 + 适配后全绿；若既有用例依赖旧的可串见语义，按新语义修正断言（产出说明中逐条列出改了哪些断言及理由）。

- [ ] **步骤 4：`npx vue-tsc --noEmit`** 无本文件新增报错。

---

## 收尾（主会话执行，不派发）

1. **集中验证**：删除根目录 5 个 0 字节残渣（`WorkOrderBatch2FixTest.java` 等）→ `cd backend && ./mvnw test`（全量，期望 ≥666 全绿 + 新增用例）→ `cd frontend && npx vitest run`（全量）+ `npm run build`。
2. **V27/V28 迁移**：告知用户在两库执行 `flyway migrate`（或重启后端自动迁移）；**不代跑**（记忆：迁移须用户亲自操作）。
3. **缺陷登记**：`docs/50_系统测试/08_缺陷跟踪与回归.md` 按附录 A 分派表登记 DEF-103~DEF-132（每条含域/根因/处置/状态「已修待回归」/回归判据），登记轮次说明引用 OCR 评审（147 文件 93 意见，2026-09-23）。
4. **决策日志**：`90_全局/决策日志.md` 一条——本轮修复引入的口径裁决：押金实缴口径改登记押金基准、`PENDING→ACTIVE` 边删除、V28 回填修正策略、（如有）其他。
5. **`_中控.md` 批次登记** + README/AGENTS.md 增量段（若能力口径有变）。
6. **提交**：代码 + V27/V28 + 测试 + 文档一次提交，提交信息按 `40_开发实施/_中控.md` §6.3 规范（`fix(全局): OCR 静态评审缺陷修复轮 DEF-103~132` 类似）。

---

## 附录 A：缺陷分派表（OCR 意见 → DEF 号 → 任务）

| DEF | OCR 意见（文件:大意） | 严重度 | 任务 |
|-----|----------------------|--------|------|
| DEF-103 | DepositRefundService 并发超退（critical）+ 渠道调用在事务内 + 幂等键未捕获 + 部分退款堵死 + 居民防线 + 低优先级折叠 | C+H+M | 1 |
| DEF-104 | LeaseMoveOutApplicationService PENDING 重复（H）+ approve 竞态（M） | H | 2 |
| DEF-105 | 退租通知 sourceId 错链 ×3（H） | H | 2 |
| DEF-130 | 退租日期无范围校验（M） | M | 2 |
| DEF-127 | 退租 toVO N+1（M，支付 page 部分归任务 4） | M | 2/4 |
| DEF-106 | ConversationService 建群竞态（H） | H | 3 |
| DEF-119 | ensureLeaseGroup 全字段回写 + closed 防御 + addParticipant 幂等声明 | M | 3 |
| DEF-107 | createFirstPayment 并发双单（H） | H | 4 |
| DEF-128 | Wechat null status 当成功 + 金额精度 + monthlyRent NPE | M | 4 |
| DEF-108 | UpdateLeaseStatusDTO @Pattern 缺 TO_SIGN（H） | H | 5 |
| DEF-115 | PENDING→ACTIVE 边残留（M）+ applyTransition 竞态（M）+ AgreementService 内核漂移（M） | M | 5 |
| DEF-116 | WorkOrderService 无条件 updateById 竞态（M） | M | 6 |
| DEF-117 | ResidenceApplication withdraw/approve 竞态 + 弱回退误关群 + WITHDRAWN Schema | M | 6 |
| DEF-110 | 租约群关闭后仍可发起看房（H，5604） | H | 7 |
| DEF-118 | housingId 归属不校验 + 排序缺日期 + 注释时序 | M | 7 |
| DEF-109 | V21 回填误翻转风险（H） | H | 8 |
| DEF-113 | 种子缺 deposit_status（H） | H | 8 |
| DEF-114 | 入住申请状态机图主轨破损（H） | H | 8 |
| DEF-120 | V26 回填 relation_type 缺口（M） | M | 8 |
| DEF-131 | lifecycle.json 撤回边失真 + html 重复点 | M | 8 |
| DEF-132 | 种子头部版本矛盾 + created_by 越社区 | L | 8 |
| DEF-111 | LeaseListView settled 口径（H）+ TO_SIGN 入口（H）+ unknown 分支（M） | H | 9 |
| DEF-129 | document.write 注入面 ×2 | M | 9 |
| DEF-112 | MoveOutApplicationView 三态映射 ×2（H） | H | 10 |
| DEF-123 | LeaseDetailView 重复请求 + TO_SIGN 激活 + Dialog emit/labels/空弹窗 + Hub 角色门控 | M | 10 |
| DEF-121 | 通知前端 LEASE_MOVE_OUT 缺席 + LEASE_SIGN 死分支 | M | 11 |
| DEF-122 | 会话前端：isClosed 降级 / typeFilter / 选择器 / closed prop / hint / role 兜底 | M | 11 |
| DEF-124 | finalizingId 时序 + 退回 toast + 注释/CSS | M | 12 |
| DEF-125 | 5206 魔法数 + v-for/v-else + 分页钳制 + 措辞 | M | 12 |
| DEF-126 | fakeBackend 参与者过滤 / 幂等基准 / profile 回退 | M | 13 |

**误报不修（留档）**：`ConversationDetailView:70` tenantId 比较——居民登录 `AuthUser.id` 即 resident 空间 ID（`AuthService.residentLogin` 用 `resident.getId()` 签发），与 `lease.tenantId` 同空间，比较成立（任务 11 加澄清注释）。

## 附录 B：不修复项（本轮明确遗留）

- `payment toVO` RENEWAL 覆盖期语义（PENDING 期显示旧周期）——展示口径问题，待产品裁决后另派（低）。
- `notifyAdminsTenancyConfirmed` 逐管理员循环通知——量级小，性能优化另派（低）。
- `frontend/src/router/admin.ts` LEASE_STAGES/HOUSING_VIEWS 常量重复——重构项，不属缺陷修复（低）。
- `resident.ts` 旧 `tab=payments` 深链兼容——Hub 合并后的历史链接边缘场景（低）。
- `ViewingAppointmentMapper:22` 看房回填按「居民+房屋+lease_id IS NULL」批量绑定而非精确到本次看房——语义收窄需产品口径（按时间窗还是更强关联），另派裁决（低）。
- `V23` 迁移头部注释「默认值不变」措辞误导——**V23 已应用，禁改任何字节**（2026-09-22 裁决），口径只能写进 docs（收尾时在 `数据库设计.md` V23 条目旁补一句澄清，不改迁移）。
- `ConversationDetailView:193` 内联三元与硬编码色值偏离 token 体系——纯样式重构项（低）。
- N1/N10 文档回归、步骤 9~12——归 50 阶段既有计划，不在本轮。
- 其余纯样式/格式类 low（fakeBackend 注释挤行已折入任务 13，其余详见 OCR 原文 `C:/Users/17841/AppData/Local/Temp/ocr_out.txt`）。
