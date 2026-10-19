"""仅核查正常进程与既有只读版本/阈值；不读取支付配置，不触发交易。"""
from support import ROOT,OUT,ENV,MYSQL,save,subprocess
import json
normalDb='community_residence';assert normalDb=='community_residence'
args=[MYSQL,'-h127.0.0.1','-u'+ENV.get('DB_USERNAME','root'),'-N','-B','--default-character-set=utf8mb4','-D'+normalDb]
queries={
 'flyway':"SELECT MAX(CAST(version AS UNSIGNED)),COUNT(*) FROM flyway_schema_history WHERE success=1",
 'businessTables':"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='community_residence' AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",
 'logRetentionDays':"SELECT config_value FROM sys_config WHERE config_key='log.retention_days'",
 'businessCounts':"SELECT (SELECT COUNT(*) FROM community),(SELECT COUNT(*) FROM house),(SELECT COUNT(*) FROM resident),(SELECT COUNT(*) FROM lease_record),(SELECT COUNT(*) FROM work_order),(SELECT COUNT(*) FROM viewing_appointment)"}
values={}
for k,q in queries.items():
 assert q.startswith('SELECT')
 r=subprocess.run(args+['-e',q],env={**ENV,'MYSQL_PWD':ENV['DB_PASSWORD']},capture_output=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW);assert r.returncode==0,'只读查询失败'
 values[k]=r.stdout.strip()
expected={8084:21936,5176:30092,6379:4868,3306:6608}
script='Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 8084,5176,6379,3306 } | Select-Object LocalPort,OwningProcess | ConvertTo-Json -Compress'
r=subprocess.check_output(['powershell','-NoProfile','-Command',script],text=True);listeners=json.loads(r);observed={int(x['LocalPort']):int(x['OwningProcess']) for x in listeners};preserved=all(observed.get(port)==pid for port,pid in expected.items())
assert preserved,'正常进程已变动，应记录实际原因而非声称未变'
assert values['flyway']=='43\t43' and values['businessTables']=='61' and values['logRetentionDays']=='730'
save('environment-after.json',{'date':'2026-10-19','normalDatabase':normalDb,'readOnly':True,'queries':values,'listeners':observed,'expectedOriginalListeners':expected,'normalProcessesPreserved':preserved,'financialConfigurationRead':False,'normalServiceRestarts':0,'financialCommands':0})
print('normal processes unchanged; V43/61; retention730; read-only business counts',values['businessCounts'])
