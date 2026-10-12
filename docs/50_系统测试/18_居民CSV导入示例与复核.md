# 居民CSV导入示例与复核

## 只看这三步

1. 管理员进入「居民 → 批量导入」，下载模板；用Excel编辑时另存为 **CSV UTF-8（逗号分隔）**，不要上传.xlsx或仅更改后缀。
2. 参考[samples/居民导入十列样例.csv](samples/居民导入十列样例.csv)：先替换`{COMMUNITY_ID}`为真实社区主键；仅涉及关系的行再替换`{HOUSE_OWNER}`/`{HOUSE_TENANT}`/`{HOUSE_FAMILY}`为实际房屋主键。样例手机号/账号均为合成值，正常系统使用前自行改成不重复的测试值。
3. 选择CSV → 开始导入 → 核对成功行账号/关系与失败行原因。重复上传成功行应拒绝，不应重复建账号。仅修正失败行后重试。

## 实际模型和文件格式

| 列 | 约束 |
|---|---|
| 社区ID | 必填，实际community.id，且在当前管理员权限内 |
| 姓名、手机号 | 必填；手机号用于联系和初始密码；重复手机号拒绝 |
| 证件号 | 选填，本样例留空，不使用真人证件 |
| 用户名、邮箱 | 选填；用户名重复拒绝，邮箱写入居民档案 |
| 房屋ID、关系类型、入住日期 | 三列全有或全无；房屋是house.id不是门牌号；类型OWNER/TENANT/FAMILY；日期YYYY-MM-DD |
| 备注 | 居住关系备注，仅关系行可填；含逗号/双引号时使用标准CSV引号转义（示例第12行姓名演示逗号/引号） |

十列表头必须按样例顺序保留；旧四列`社区ID,姓名,手机号,证件号`仍可用，但只建账号，不登记关系。单文件上限5MB，UTF-8，逐行独立事务，部分成功时已成功行不会因其他行失败回滚。管理员直接登记关系是台账录入，**不会自动创建租约或支付单**；租住申请正式关系仍遵循首期成功生效规则。

**现有能力并非“Excel导入导出”**：运行OpenAPI与ResidentController只有CSV导入，前端“下载模板”在浏览器生成CSV；目前没有居民导出接口。本次不新增.xlsx解析或导出功能。

## 本轮复核证据

执行脚本：`tools/testing/nontransaction-button-regression.cjs`；实际ID替换后的12行CSV、重复导入/混合失败/旧四列结果与浏览器截图放`evidence/nontransaction-buttons-20261012/`。该目录中的执行CSV引用的是已回收的隔离夹具ID，不可原样上传正常库；可移植样例使用上方占位符文件。

DEF-142本轮按用户指定的**当前格式和可重建数据**复核，不再等待原Excel文件。DEF-145本轮实测居民提交工单表单类别卡片显示、选择与提交，不再等待旧类别资料。详细执行结果以08及中控为准，不在此重复阶段状态。

导入/类别的独立复核摘要见[evidence/nontransaction-buttons-20261012/resident-import-category-verification.json](evidence/nontransaction-buttons-20261012/resident-import-category-verification.json)，该子集不与完整按钮矩阵的计数相加。
