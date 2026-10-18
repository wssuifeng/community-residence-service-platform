# S0兼容与离线测试基线

2026-10-18，源码起点c53c523。测试不启动正常服务、不连接业务库/Redis或交易渠道；唯一MySQL专项测试保持显式禁用。

- 后端原全量：1175项，1失败、1跳过。失败来自PublicResourceImagesMigrationTest把迁移总数固定42，实际43。修正为版本不重号、从1至最新连续且至少43，保留V29结构约束。
- 前端原全量：71文件、418项，7失败。4项AdminLayout替身缺route.path；3项工单详情测试仍断言内嵌群聊，与已采纳统一会话入口不符。同步为入口/来源/返回路径与关闭记录可达检查，不恢复旧UI。
- 基线修正定向：后端迁移守卫3项和新增真实HTTP映射注册1项通过；前端原失败3文件与新增路由契约共4文件10项通过。前端类型检查与生产构建通过。
- HTTP契约由ControllerMappingCompatibilityTest离线注册Spring真实映射生成，覆盖路径/方法/条件、方法或类角色、参数注解与返回类型；无重复handler。它不替代实际HTTP权限执行测试。
- 路由契约由portal-route-compatibility.spec.ts导出，覆盖三端及公开页面的路径、角色、名称、嵌套路由与重定向/深链样例；不导出页面实现。
- backend-ownership.csv共464生产Java文件，含过渡路径及最终拆分去向；endpoint-ownership.csv共263个方法、29个混合Controller，逐方法保留授权表达式。frontend-ownership.csv共319个文件（含测试/夹具，非功能页计数）。

后续迁移后将运行完整离线后端/前端测试，记录实际结果；本基线不预填迁移通过。
