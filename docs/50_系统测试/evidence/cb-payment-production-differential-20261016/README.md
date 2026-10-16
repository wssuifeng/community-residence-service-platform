# cb 首期成功与后续租金失败的生产链对照

用户要求：用成功实例对照失败租金，不再泛泛怀疑沙箱或索取错误页不存在的详情。

## 实际比较

| 字段 | 成功首期（两笔） | 失败租金（对应两笔） |
|---|---|---|
| 应用内类型 | FIRST | RENT |
| 支付金额 | 14200.00 / 21000.00 | 3600.00 / 3000.00 |
| 押金快照 | 10600.00 / 12000.00 | 均为0，不向支付宝单独传押金字段 |
| 标题 | 租约首期费用（约定首期租金+押金） | 租约租金-1个月 |
| 单号形状 | FIRST＋日期＋随机数 | RENEW＋日期＋随机数；同样满足字母数字长度限制 |
| 订单截止 | 发起当日起租边界次日0点 | 各自发起后24小时；此为真实差异，不能未经远端验证便认定是根因 |
| SDK/方法/字符集/产品码 | 共用生产AlipayChannelClient、page.pay、UTF-8、RSA2、FAST_INSTANT_TRADE_PAY | 相同 |

## 本次比旧证据多验证了什么

不是重新手写相似的SDK调用。后端测试读取只含原4笔订单业务字段的私有快照，使用合成密钥，脱离数据库把内存样本还原为原发起时间与PENDING，执行实际 `PaymentService.credentialOf → subjectOf → AlipayChannelClient.createPayment → pageExecute`。不解密真实私钥、不写订单、不做查单或结算。

再用实际 `LeaseFirstPaymentDialog.vue` / `LeaseRenewDialog.vue`、真实Vue及Axios，挂载到独立Vite测试页；所有业务API本地mock，模拟用户点击去支付，拦截新窗口发往本地空端口的POST。4个样本逐项检查商户单号、subject、金额、product_code、time_expire、method、charset、app_id、sign_type、sign存在性及timestamp完整保留。临时页及独立进程用后清理，没有接管用户浏览器。

`production-component-transport.json`为4项实际组件传输结果；`comparison-summary.json`为正常业务只读比较与边界。原始表单、签名、完整原订单号及私有快照不入库。历史012f4b4与当前生成/提交关键逻辑也已对照，相同；渠道适配器只新增耗时日志。

## 不能由此推出什么

4项本地传输正确不能证明用户当时到达支付宝的实际请求正确，也不能证明远端错误修复；本次没有重现用户的INVALID_PARAMETER，根因仍未确认，未修改网关/密钥/业务代码或原单状态。首期成功足以作为共同接入链的正对照，应继续查后续订单差异，不能再泛泛归责支付宝。

下一步需要明确允许向沙箱提交现有未付租金订单的表单、只打开收银台抓取实际请求/响应；不点击付款、不查单、不退款、不重复创建应用订单。未获授权不越过原离线边界，也不要求用户补错误页没有的详情。

## 一手接口依据

- 支付宝官方电脑网站支付：https://opendocs.alipay.com/open/270/105899 （本轮网页抓取工具无返回，未声称成功读取正文）
- 支付宝官方SDK模型：https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/src/main/java/com/alipay/api/domain/AlipayTradePagePayModel.java （本轮从官方raw成功读取；主干注释约定out_trade_no字母数字下划线且≤64字、subject不含/=&、time_expire为yyyy-MM-dd HH:mm:ss且范围1m～15d；只作字段约束参考，不把master当作项目已锁版本）
- 实际执行依赖固定为项目4.40.1006.ALL，不因调试升级SDK。
