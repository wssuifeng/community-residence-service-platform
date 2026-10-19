"""基于实际结果追加最终台账；不执行业务、不修改历史db快照。"""
from pathlib import Path
from collections import Counter
import json
OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
def save(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),'utf-8')
old=OUT.parent/'db-final-reconciliation-20261019'
def load(name):return json.loads((OUT/name).read_text('utf-8'))
required=['contracts.json','browser.json','redis-fault.json','concurrency.json','permissions.json','old-anomalies.json','remaining-anomalies.json','reject-concurrency.json','realtime-browser.json','performance.json','backend-targeted.json','frontend-targeted.json']
results={n:load(n) for n in required}
for name,r in results.items():
 if 'checks' in r:assert all(c['passed'] for c in r['checks']),(name,r.get('failed'))
assert results['backend-targeted.json']['errors']==results['backend-targeted.json']['failures']==0
assert results['frontend-targeted.json']['failed']==0
assert results['realtime-browser.json']['allWithin3s']
assert results['performance.json']['allP95Within2s'] and results['performance.json']['allPushWithin3s']
assert results['performance.json']['functionalErrors']==0
assert len(results['performance.json']['pushSamples'])==6
for phase in ['base','bulk']:
 r=load('performance-'+phase+'.json')
 assert r['setupLogins']==200 and r['setupFailed']==0 and r['maxHTTPThreads']==50
 assert r['wsConnectedAtEnd']==50 and not r['wsErrors'] and r['steadyDurationSeconds']>=599
 assert len(r['labels'])==7 and not r['failedSamples']
updates={
 'TC-C6-004':('真实Edge双通道各10次DOM计时','realtime-browser.json','补齐时延子判据；原反馈流程证据继续采用'),
 'TC-C11-008':('在线通知DOM与负载下STOMP计时','realtime-browser.json','真实终端，不将在线线程数当WS数量'),
 'TC-C11-017':('事件通知、在线呈现、补拉、已读及两态留痕','realtime-browser.json','与原E11流程记录及browser/old-anomalies共同采用，不声明全部故障分支重放'),
 'TC-C11-014':('真实UI保存两态、公告发布和SQL留痕','browser.json','站内必达，站外按勾选留痕；实际金融请求0'),
 'TC-NF-016':('10组契约/11操作及sinceSeq非零边界','contracts.json','抽样口径；不是211条path全部接口穷举'),
 'TC-SP-022':('独立Redis真实五分钟断开、轮询、退出与恢复','redis-fault.json','正常6379保持；故障响应变慢保留为观察'),
 'TC-SP-025':('真实Edge图片/文档上传组件异常矩阵','browser.json','图片用业务表单，文档用原组件独立挂载，不宣称已验所有业务入口')}
for n in [2,3,4,11]:updates[f'TC-NF-{n:03}']=('基础/N7规模各50HTTP＋50WS，稳态600秒','performance.json','N7前后差值如实保留；03绝对P95门槛未改，04的30%建议单独评估')
updates['TC-NF-005']=('通知与反馈各10个真实DOM计时','realtime-browser.json','HTTP发出到DOM出现为DB提交后时延上界')
updates['TC-SP-019']=updates['TC-NF-005']
for n in range(1,5):updates[f'TC-SP-{n:03}']=('两类预约各3轮10路竞争及有效边界矩阵','concurrency.json','原非法大段看房组合按现行栅格/单次上限调整，未冒称已穿透前置校验')
for n in range(5,11):updates[f'TC-SP-{n:03}']=('五角色、本人与社区、身份空间、附件与反馈WS矩阵','permissions.json','分母为实际113检查；不是所有HTTP方法与角色任意组合全集')
rows=json.loads((old/'case-ledger.json').read_text('utf-8'));assert len(rows)==305
supp=[]
for r in rows:
 u=updates.get(r['id']);supp.append({'id':r['id'],'priority':r['priority'],'priorDisposition':r['disposition'],'currentSupplement':{'method':u[0],'evidence':u[1],'boundary':u[2]} if u else None})
save('case-supplement.json',{'caseCount':305,'supplementedCases':len(updates),'rows':supp,'claimBoundary':'累计证据与本轮定向补证；未将脚本断言/单测总数换算为305 TC最终源码整册执行率'})
text=['# 305 TC本轮追加证据','', '原逐项归并快照保持；本表只追加本批验证及其边界。','', '| TC | 原归并 | 本轮追加 | 证据 | 边界 |','|---|---|---|---|---|']
for r in supp:
 u=r['currentSupplement'];text.append('| '+r['id']+' | '+r['priorDisposition']+' | '+(u['method'] if u else '沿用原逐项证据')+' | '+('['+u['evidence']+']('+u['evidence']+')' if u else 'db原台账')+' | '+(u['boundary'] if u else '原证据基线与限制不扩大')+' |')
(OUT/'case-supplement.md').write_text('\n'.join(text)+'\n','utf-8')
finance={103,107,112,123,129}
methods={104:('old-anomalies.json','真实十路提交、十路审核，只一单/一条状态日志/一条居民通知且重复请求幂等；本批补锁后最新终态读取，并以reject-concurrency.json追加十路驳回/通知一次验证'),106:('old-anomalies.json','真实十路租约建群全部返回同一ID；唯一组1；本批修复旧快照重查及撞键锁升级'),108:('old-anomalies.json','实际状态DTO接受TO_SIGN，PENDING→TO_SIGN成功'),110:('old-anomalies.json','关闭租约群发起看房5604，预约零新增'),115:('remaining-anomalies.json','非交易同目标状态竞争、仅一条留痕；首期生效按现行FIRST设计，未执行付款/协议激活交易'),116:('remaining-anomalies.json','真实十路受理群/留痕/通知各一次；修复CAS输家旧快照误拒，当前读返回赢家群，合法重复状态仍5302；结合工程分支验证'),117:('remaining-anomalies.json','真实撤回与审批三轮竞争仅一方终态；弱/强关联关闭边界采用本轮ResidenceApplicationLeaseLinkTest'),118:('remaining-anomalies.json','真实会话无关房源400/零新增及日期倒序；结合7项归属工程回归'),119:('old-anomalies.json','真实补回链不覆盖TO_SIGN且不重开CLOSED群；条件更新和重复参与者采用本轮工程回归'),122:('frontend-targeted.json','本轮会话组件/列表定向回归，失败只读、直通后选中等原判据'),124:('frontend-targeted.json','本轮管理/居民工单组件定向回归，确认框未决防重与退回提示'),127:('backend-targeted.json','新增双行分页断言，六种关联selectBatchIds各一次且不逐行查关联；符合原spec层判据'),130:('old-anomalies.json','真实早于起租/晚于止租均业务码400且无申请；HTTP200统一响应不误作未拒绝')}
defs=json.loads((old/'defect-ledger.json').read_text('utf-8'));dispositions=[]
for r in defs:
 n=int(r['id'][4:]);row={'id':r['id'],'severity':r['severity'],'priorDisposition':r['disposition'],'currentDisposition':r['disposition'],'evidence':r['evidence'],'boundary':r['conclusion']}
 if n in methods:
  proof,desc=methods[n];row.update(currentDisposition='本轮补证处置',evidence=[proof]+(['reject-concurrency.json'] if n==104 else []),boundary=desc)
 if n in finance:
  row.update(currentDisposition='沿用工程证据与用户金融确认',evidence=['../db-final-reconciliation-20261019/defect-ledger.json'],boundary='用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。'+(' 手动激活的旧预期已由FIRST支付生效取代，本轮只验证手动路径被拒。' if n==123 else ''))
 dispositions.append(row)
for n,title in [(213,'直通会话并发重复'),(214,'Redis包装连接异常未命中降级'),(215,'Redis恢复后通知序号回退导致增量补拉遗漏')]:
 dispositions.append({'id':f'DEF-{n}','severity':'严重','title':title,'priorDisposition':'本轮新发现','currentDisposition':'修复定向验证','evidence':['old-anomalies.json' if n==213 else 'redis-fault.json','backend-targeted.json'],'boundary':'当前独立库/Redis/终端定向验证；不推定为所有业务全量回归'})
assert len(dispositions)==215
save('defect-supplement.json',{'defectCount':215,'newDefects':3,'original18Disposed':18,'nonFinancialTargeted':13,'financialEvidenceAdopted':5,'rows':dispositions,'finalExitApproved':False})
text=['# 18项原异常专项的逐项处置','', '| DEF | 严重度 | 本轮处置 | 证据与范围 |','|---|---|---|---|']
for r in dispositions:
 if int(r['id'][4:]) in finance|set(methods):text.append('| '+r['id']+' | '+r['severity']+' | '+r['currentDisposition']+' | '+r['boundary']+'；'+', '.join(r['evidence'])+' |')
text+=['','DEF-073与DEF-128两个微信一般保留沿用既有裁决，不新增微信交易；其余历史缺陷继续采用db逐项依据。本批新登记213～215另见08。','', '本轮将原18项逐条落到实际验证/现行设计替代/用户授权的金融证据采用，不再留一个未分解的“18项待整理”。工程层、真实HTTP/SQL与用户确认分开记载；未声称每条原异常均有真实渠道执行。']
(OUT/'18-defect-dispositions.md').write_text('\n'.join(text)+'\n','utf-8')
save('summary.json',{'date':'2026-10-19','sourceBase':'caae28d','caseCount':305,'supplementedCases':len(updates),'defectCount':215,'newDefects':[213,214,215],'original18Disposed':18,'checks':{n:{'passed':r.get('passed'),'count':len(r.get('checks',[]))} for n,r in results.items() if 'checks' in r},'financialCommands':0,'normalServicesReplaced':False,'phaseExitApproved':False})
print('evidence complete:305TC,215DEF,',len(updates),'TC supplements')
