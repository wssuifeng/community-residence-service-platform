# df · 缺陷整改与测试出口

用户授权修复已知缺陷、完成50收尾并转60。实际修复和隔离验证完成后回填结果；金融沿用户确认，不执行交易、查单、退款。

## 最终结果（df）

- 最终隔离运行：`community_residence_df_20261019`，后端8087、前端5181、Redis6387；正常8084/5176/6379未在本批测试过程中操作。
- 后端受影响回归449/449，前端定向38/38；`vue-tsc --noEmit`与Vite build通过。见`backend-targeted.json`、`frontend-targeted.json`、`frontend-build-summary.json`。
- 后端代码层全量 Maven 回归 `1231` 项，失败/错误 `0`，跳过 `1`；该数字不替代305 TC系统测试分母。见`full-backend-test-summary.json`。
- Redis故障12/12：断开后真实前端30.8ms，无响应Redis 1027.3ms，150次故障期写请求全部成功，最长910.97ms。
- 状态竞态7/7、跨资源/跨域与退租13/13、通知提交乱序2/2；见`state-fixed.json`、`concurrency.json`、`notification-order.json`。
- 本批金融请求0；`final-summary.json`与`final-defect-ledger.json`为当前最终结构化台账。
