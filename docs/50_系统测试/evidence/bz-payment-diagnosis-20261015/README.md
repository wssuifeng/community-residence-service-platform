# bz用户实测与离线排查

- verification-summary.json：用户报告范围、正常库只读快照及明确未完成事项。
- user-contract-after-first-payment.png：用户本轮提供的合同面板原图，无支付密钥/渠道流水。
- offline-transport.json：官方SDK4.40.1006.ALL、全新合成RSA密钥、伪造APPID及本机虚拟网关；独立headless Edge拦截全部请求，4种FIRST/RENT＋是否load后释放Blob组合均保留必需POST字段，没有访问支付宝或正常业务API。

错误根因尚未确认，不声称已修复INVALID_PARAMETER。实际失败页的业务参数并未从本地日志恢复；已有查询日志不是page.pay错误原请求，不能用查不到交易来判支付宝整体故障。未调用正常项目支付/查单/退款，未修改已成功账目、现有待支付单或渠道配置，未重启服务或操作用户浏览器。

官方对照：
- https://opendocs.alipay.com/open/270/105899 （电脑网站支付快速接入，动态页面）
- https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/AbstractAlipayClient.java （pageExecute与POST表单生成）
- https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/domain/AlipayTradePagePayModel.java （out_trade_no、total_amount、product_code、time_expire等字段）

在线SDK源码仅作官方实现说明，不能把master版本当成本项目依赖；本地实测固定使用既有4.40.1006.ALL。诊断脚本及合成表单在本机output/runtime/BzOfflineFormProbe.java、bz-offline-transport.cjs及bz-offline-forms中，合成密钥仅内存生成，不输出/发布签名表单。
