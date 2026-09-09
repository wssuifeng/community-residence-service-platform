# 管理端后台界面冗余清理计划

> 状态：✅ 已完成（2026-09-09 实施，vue-tsc 零错误 + vite 构建通过，提交见 git log）
> 目标：减少侧边栏条目数量，消除重复页面，降低认知负担
> 实施结果：管理端 39 视图 → 35 视图（删 5 增 1），侧边栏 35 → 30 条目

---

## 一、问题诊断

当前管理端共 39 个视图、9 个侧边栏分组、35 个侧边栏条目。主要冗余点：

### 1. 统计模块（5页）：3页可合并/删除

| 页面 | 问题 |
|------|------|
| `statistics/EvaluationStatisticsView.vue` | **100% 重复**：调 `getDashboardStats()` 与 Dashboard 完全同一个接口，数据无差异，只是重排了布分 |
| `statistics/WorkOrderStatisticsView.vue` | 内容单薄（1张卡片 + 2图），可作为 Dashboard 的补充详情，不值得独立一页 |
| `statistics/ResidentStatisticsView.vue` | 同上，1张卡片 + 1图 |
| `statistics/ResourceStatisticsView.vue` | 同上，1张卡片 + 1图 |

### 2. 租住管理（2页）：即将到期是全部记录的子集

| 页面 | 问题 |
|------|------|
| `lease/ExpiringLeaseListView.vue` | 表格列与 `LeaseListView.vue` 完全相同，仅多一个"天数窗口"筛选，完全可以用 Tab 或筛选器合并 |

---

## 二、改造方案

### 方案 A：统计模块 5页 → 2页

#### A1. 删除 `EvaluationStatisticsView.vue`（整个文件删除）

将其独有计算（avgRating、satisfactionPercent）合并进 `DashboardView.vue`，在"预约与评价"卡片组追加两张卡片：
- 平均评分（原页面已有）
- 满意率 ≥4星（原页面已有）

数据来源不变：`getDashboardStats()` 看板聚合接口本就返回 `ratingDistribution`，零额外请求。

#### A2. 将 Work Order / Resident / Resource 三个独立统计页合并为一个 `StatisticsView.vue`

新页面使用 `<el-tabs>` 三个 Tab（工单 / 居民 / 资源预约），各 Tab 懒加载（切换时才发请求）。

文件变化：
- 新建 `frontend/src/views/admin/statistics/StatisticsView.vue`
- 删除 `WorkOrderStatisticsView.vue`、`ResidentStatisticsView.vue`、`ResourceStatisticsView.vue`

### 方案 B：租住管理 2页 → 1页

在 `LeaseListView.vue` 顶部加 `<el-tabs>`（全部 / 即将到期）：
- "全部" Tab：保留现有表格和新建/编辑/终止功能
- "即将到期" Tab：复用 `ExpiringLeaseListView` 的逻辑（天数窗口筛选 + 剩余天数着色），切换时懒加载

文件变化：
- 修改 `LeaseListView.vue`（合入到期逻辑）
- 删除 `ExpiringLeaseListView.vue`

---

## 三、路由变更

```diff
// frontend/src/router/admin.ts

- { path: 'statistics/work-orders', name: 'AdminWorkOrderStatistics', ... }
- { path: 'statistics/residents',   name: 'AdminResidentStatistics',  ... }
- { path: 'statistics/resources',   name: 'AdminResourceStatistics',  ... }
- { path: 'statistics/evaluations', name: 'AdminEvaluationStatistics', ... }
+ { path: 'statistics/details',     name: 'AdminStatisticsDetails',   component: StatisticsView, meta: { title: '详细统计' } }

- { path: 'leases/expiring', name: 'AdminExpiringLeases', ... }
// LeaseListView 内部 Tab 处理，不再需要独立路由
```

---

## 四、侧边栏变更（AppSidebar.vue）

```diff
// 运营统计分组
  { path: '/admin/statistics/dashboard', title: '运营看板' },
- { path: '/admin/statistics/work-orders', title: '工单统计' },
- { path: '/admin/statistics/residents',   title: '居民统计' },
- { path: '/admin/statistics/resources',   title: '资源统计' },
- { path: '/admin/statistics/evaluations', title: '评价统计' },
+ { path: '/admin/statistics/details',     title: '详细统计' },

// 租住管理分组
  { path: '/admin/leases', title: '租住记录' },
- { path: '/admin/leases/expiring', title: '即将到期' },
```

侧边栏条目：35 → 30（减少 5 条）

---

## 五、文件清单

### 新增
- `frontend/src/views/admin/statistics/StatisticsView.vue`（合并三个统计页）

### 修改
- `frontend/src/views/admin/statistics/DashboardView.vue`（追加评价摘要卡片）
- `frontend/src/views/admin/lease/LeaseListView.vue`（合入即将到期 Tab）
- `frontend/src/router/admin.ts`（删旧路由、加新路由）
- `frontend/src/components/layout/AppSidebar.vue`（精简菜单）

### 删除
- `frontend/src/views/admin/statistics/WorkOrderStatisticsView.vue`
- `frontend/src/views/admin/statistics/ResidentStatisticsView.vue`
- `frontend/src/views/admin/statistics/ResourceStatisticsView.vue`
- `frontend/src/views/admin/statistics/EvaluationStatisticsView.vue`
- `frontend/src/views/admin/lease/ExpiringLeaseListView.vue`

---

## 六、注意事项（已有预备性改动）

本次计划制定前已对以下文件做了预备性改动，实施时需在此基础上继续而非从头来：

1. **`DashboardView.vue`**：已追加 `avgRating`、`satisfactionPercent` computed 属性，以及模板中"预约与评价"卡片组的两张新卡片。✅ 这部分改动已完成，实施时无需重做。

2. **`LeaseListView.vue`**：已修改 import（加了 `computed`、`watch`、`getExpiringLeaseList`），追加了即将到期相关的响应式状态和辅助函数。⚠️ 模板部分（Tab 结构）尚未改动，实施时需继续。

3. **`StatisticsView.vue`**：已新建完整文件，内容为三 Tab 合并统计页。✅ 已完成，实施时直接使用。

---

## 七、实施顺序

1. 完成 `LeaseListView.vue` 模板改造（加 `<el-tabs>`，包入现有表格，追加即将到期 Tab 内容）
2. 更新 `admin.ts` 路由
3. 更新 `AppSidebar.vue` 菜单
4. 删除 5 个废弃文件
5. 本地构建验证（`npm run build`，vue-tsc 类型检查通过即可）
