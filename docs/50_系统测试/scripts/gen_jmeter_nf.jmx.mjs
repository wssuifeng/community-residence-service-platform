#!/usr/bin/env node
/**
 * 生成 TC-NF-002/003/011 压测 JMX（JMeter 5.6.3）：
 * - setUp 线程组：perf_resident_001~200 逐一登录换取 JWT → 写 tokens.csv
 * - 压测线程组：50 并发 / Ramp-up 60s / 持续时长（CLI 参数，稳态 10min）
 *   取样器（7 接口，每接口独立标签）：
 *   GET /communities、GET /units/1/houses、GET /notices（公开）
 *   GET /work-orders?page=1&size=20、GET /notifications（需令牌）
 *   GET /work-orders/{id}（需令牌）、GET /housings/{id}（公开）
 * - 每取样器：HTTP 200 断言 + JSON code=200 断言
 * - 聚合报告 + JTL 落盘
 * 用法：node gen_jmeter_nf.jmx.mjs   （产物 nf_load.jmx 同目录）
 */
import fs from 'fs';
import path from 'path';

const dir = import.meta.dirname;
const accounts = [];
for (let i = 1; i <= 200; i++) accounts.push(`perf_resident_${String(i).padStart(3, '0')},Resident123456`);

const jmx = `<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="50-系统测试 NF 压测（TC-NF-002/003/011）" enabled="true">
      <stringProp name="TestPlan.comments">7 核心接口 50 并发稳态；P95≤2s 判据（03 计划 §5 出口准则 4）</stringProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments">
        <collectionProp name="Arguments.arguments">
          <elementProp name="HOST" elementType="Argument">
            <stringProp name="Argument.name">HOST</stringProp>
            <stringProp name="Argument.value">\${__P(host,localhost)}</stringProp>
          </elementProp>
          <elementProp name="PORT" elementType="Argument">
            <stringProp name="Argument.name">PORT</stringProp>
            <stringProp name="Argument.value">\${__P(port,8081)}</stringProp>
          </elementProp>
          <elementProp name="DURATION" elementType="Argument">
            <stringProp name="Argument.name">DURATION</stringProp>
            <stringProp name="Argument.value">\${__P(duration,600)}</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    <hashTree>
      <!-- setUp：200 账号池登录 → tokens.csv -->
      <SetupThreadGroup guiclass="SetupThreadGroupGui" testclass="SetupThreadGroup" testname="setUp-登录池" enabled="true">
        <intProp name="ThreadGroup.num_threads">1</intProp>
        <intProp name="ThreadGroup.ramp_time">1</intProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController">
          <stringProp name="LoopController.loops">200</stringProp>
        </elementProp>
      </SetupThreadGroup>
      <hashTree>
        <CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="账号池 CSV" enabled="true">
          <stringProp name="filename">${dir.replace(/\\/g, '/')}/nf_accounts.csv</stringProp>
          <stringProp name="variableNames">username,password</stringProp>
          <boolProp name="recycle">false</boolProp>
          <boolProp name="stopThread">true</boolProp>
          <stringProp name="shareMode">shareMode.all</stringProp>
        </CSVDataSet>
        <hashTree/>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="setUp-登录" enabled="true">
          <stringProp name="HTTPSampler.domain">\${HOST}</stringProp>
          <stringProp name="HTTPSampler.port">\${PORT}</stringProp>
          <stringProp name="HTTPSampler.path">/api/v1/auth/resident/login</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">{"username":"\${username}","password":"\${password}"}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
        </HTTPSamplerProxy>
        <hashTree>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="JSON头" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Content-Type</stringProp>
                <stringProp name="Header.value">application/json</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
          <JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="提取 token" enabled="true">
            <stringProp name="JSONPostProcessor.referenceNames">token</stringProp>
            <stringProp name="JSONPostProcessor.jsonPathExprs">$.data.token</stringProp>
            <stringProp name="JSONPostProcessor.match_numbers">1</stringProp>
            <stringProp name="JSONPostProcessor.default_values">TOKEN_NOT_FOUND</stringProp>
          </JSONPostProcessor>
          <hashTree/>
          <JSR223PostProcessor guiclass="TestBeanGUI" testclass="JSR223PostProcessor" testname="写 tokens.csv" enabled="true">
            <stringProp name="scriptLanguage">groovy</stringProp>
            <stringProp name="script">def f = new File("${dir.replace(/\\/g, '/')}/tokens.csv"); f.append(vars.get("username") + "," + vars.get("token") + System.getProperty("line.separator"));
if (props.get("AMTOKEN") == null) {
  def c2 = new URL("http://localhost:" + vars.get("PORT") + "/api/v1/auth/admin/login").openConnection()
  c2.setRequestProperty("Content-Type", "application/json"); c2.setDoOutput(true)
  c2.outputStream.withWriter{ it.write('{"username":"admin1","password":"Admin123456"}') }
  def t2 = c2.inputStream.text; def m2 = (t2 =~ /"token":"([^"]+)"/)
  props.put("AMTOKEN", m2 ? m2[0][1] : "NONE")
}</stringProp>
          </JSR223PostProcessor>
          <hashTree/>
        </hashTree>
      </hashTree>
      <!-- 压测线程组：50 并发稳态 -->
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="50并发-7接口" enabled="true">
        <intProp name="ThreadGroup.num_threads">50</intProp>
        <intProp name="ThreadGroup.ramp_time">60</intProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController">
          <boolProp name="LoopController.continue_forever">true</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
        <stringProp name="ThreadGroup.duration">\${__P(duration,600)}</stringProp>
        <stringProp name="ThreadGroup.delay">0</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <OnceOnlyController guiclass="OnceOnlyControllerGui" testclass="OnceOnlyController" testname="仅一次" enabled="true"/>
        <hashTree/>
        <CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="令牌 CSV" enabled="true">
          <stringProp name="filename">${dir.replace(/\\/g, '/')}/tokens.csv</stringProp>
          <stringProp name="variableNames">tuser,token</stringProp>
          <boolProp name="recycle">true</boolProp>
          <stringProp name="shareMode">shareMode.all</stringProp>
        </CSVDataSet>
        <hashTree/>
        <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="认证头" enabled="true">
          <collectionProp name="HeaderManager.headers">
            <elementProp name="" elementType="Header">
              <stringProp name="Header.name">Authorization</stringProp>
              <stringProp name="Header.value">Bearer \${token}</stringProp>
            </elementProp>
          </collectionProp>
        </HeaderManager>
        <hashTree/>
${['GET /api/v1/communities|L1-社区列表(公开)',
    'GET /api/v1/units/1/houses?page=1&size=20|L2-房屋列表(公开)',
    'GET /api/v1/notices?page=1&size=20|L3-公告列表(公开)',
    'GET /api/v1/work-orders?page=1&size=20|L4-工单列表(令牌)',
    'GET /api/v1/notifications?page=1&size=20|L5-通知列表(令牌)',
    'GET /api/v1/work-orders/1|L6-工单详情(令牌)',
    'GET /api/v1/housings/1|L7-房源详情(公开)'].map(([s]) => s).join('\n')}
${[['/api/v1/communities', 'L1-社区列表(公开)', ''],
  ['/api/v1/units/1/houses?page=1&size=20', 'L2-房屋列表(公开)', ''],
  ['/api/v1/notices?page=1&size=20', 'L3-公告列表(公开)', ''],
  ['/api/v1/work-orders?page=1&size=20', 'L4-工单列表(令牌)', ''],
  ['/api/v1/notifications?page=1&size=20', 'L5-通知列表(令牌)', ''],
  ['/api/v1/work-orders/11189', 'L6-工单详情(admin1令牌)', 'AM'],
  ['/api/v1/housings/1', 'L7-房源详情(公开)', '']].map(([p, n, am]) => `        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="${n}" enabled="true">
          <stringProp name="HTTPSampler.domain">\${HOST}</stringProp>
          <stringProp name="HTTPSampler.port">\${PORT}</stringProp>
          <stringProp name="HTTPSampler.path">${p}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
        </HTTPSamplerProxy>
        <hashTree>${am === 'AM' ? `
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="L6专用admin头" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">Bearer \${__P(AMTOKEN,)}</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>` : ''}
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="HTTP 200" enabled="true">
            <collectionProp name="Asserion.test_strings">
              <stringProp name="49586">200</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
          <JSONPathAssertion guiclass="JSONPathAssertionGui" testclass="JSONPathAssertion" testname="code=200" enabled="true">
            <stringProp name="JSON_PATH">$.code</stringProp>
            <stringProp name="EXPECTED_VALUE">200</stringProp>
            <boolProp name="JSONVALIDATION">true</boolProp>
          </JSONPathAssertion>
          <hashTree/>
        </hashTree>`).join('\n')}
        <ResultCollector guiclass="AggregateReport" testclass="ResultCollector" testname="聚合报告" enabled="true">
          <boolProp name="ResultCollector.error_logging">false</boolProp>
          <objProp>
            <name>saveConfig</name>
            <value class="SampleSaveConfiguration">
              <time>true</time>
              <latency>true</latency>
              <timestamp>true</timestamp>
              <success>true</success>
              <label>true</label>
              <code>true</code>
              <message>true</message>
              <threadName>true</threadName>
              <dataType>true</dataType>
              <encoding>false</encoding>
              <assertions>true</assertions>
              <subresults>true</subresults>
              <responseData>false</responseData>
              <samplerData>false</samplerData>
              <xml>false</xml>
              <fieldNames>true</fieldNames>
              <responseHeaders>false</responseHeaders>
              <requestHeaders>false</requestHeaders>
              <responseDataOnError>false</responseDataOnError>
              <saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
            </value>
          </objProp>
          <stringProp name="filename">\${__P(jtl,${dir.replace(/\\/g, '/')}/nf_result.jtl)}</stringProp>
        </ResultCollector>
        <hashTree/>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
`;
fs.writeFileSync(path.join(dir, 'nf_accounts.csv'), accounts.join('\n') + '\n');
fs.writeFileSync(path.join(dir, 'nf_load.jmx'), jmx.replace(/&(?!(amp|lt|gt|quot|apos);)/g, '&amp;'));
console.log('生成 nf_load.jmx + nf_accounts.csv（200 账号）');
