# 独立支付宝沙箱验证组件

目的：区分项目业务/前端问题与当前沙箱接入环境的外部阻塞。独立 Maven 项目，只在运行时依赖官方 Java SDK；JDK 17 内置 HTTP 服务原样返回 SDK 表单，不连接数据库、不调用业务接口、不复用旧单。金额固定 0.01，单号 ISO 前缀，拒绝正式网关。

## 官方依据

- 电脑网站支付快速接入：https://opendocs.alipay.com/open/270/105899 （原样将 pageExecute 的 form 输出到 HTTP response；product_code=FAST_INSTANT_TRADE_PAY）
- 沙箱快速接入：https://opendocs.alipay.com/common/02kkv7
- 官方 Java SDK README：https://github.com/alipay/alipay-sdk-java-all/blob/master/v2/README.md （本组件默认采用其示例版本 4.40.1033.ALL；不是声称此版本为最新发布）

## 编译与测试

在本目录执行（需 JDK 17、Maven）：

```text
mvn test dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/sdk1033.classpath
mvn test dependency:build-classpath -DincludeScope=runtime -Dalipay.sdk.version=4.40.1006.ALL -Dmdep.outputFile=target/sdk1006.classpath
```

## 运行

手工原生页面：环境变量 ALIPAY_PROBE_APP_ID、ALIPAY_PROBE_PRIVATE_KEY、ALIPAY_PROBE_PUBLIC_KEY，classpath 为 target/classes 加上述 classpath 文件内的 SDK 依赖，执行 diagnostic.AlipaySandboxProbe，默认端口 8095，绑定 127.0.0.1。/pay/post 直接返回官方 SDK 表单；/pay/get 用 SDK 完整 GET URL 重定向；/query 只查本进程发出的 ISO 订单。不要把 SDK 签名 URL 或完整表单存档。

自动复验：Node.js + Playwright/Edge，通过 PLAYWRIGHT_MODULE_PATH 指定已安装 Playwright 模块（例如通过 npm 本地安装）。JAVA_HOME 指向 JDK 17。

```text
node run-probe.mjs --help
node run-probe.mjs --classpath target/sdk1033.classpath --output <脱敏证据.json>
```

可显式传 --env-file <本机被忽略的.env>，仅从中读取已核验的支付宝沙箱凭据作为输入，不使用项目支付配置/网关、业务代码或数据。也支持直接传入上述独立环境变量。不要把凭据写入源码、命令参数或提交。脚本启动自己的临时端口服务和独立无头浏览器，不附着或关闭用户浏览器；所有服务/浏览器在 finally 中收束。

记录出站方法、字段名、RSA2 校验、HTTP 状态与脱敏跳转链；不记录私钥、Cookie、完整签名或完整响应体。每次复验会创建新的沙箱渠道请求，不能作为“完全只读”使用。自动脚本不登录买家、不点击付款，不调用退款/关单。

独立复现收银台异常可支持暂缓本项目沙箱测试，不能证明所有沙箱用户均不可用、支付宝内部根因或正式环境已验证。权威执行结果只回写到现有 50 阶段测试记录与中控。
