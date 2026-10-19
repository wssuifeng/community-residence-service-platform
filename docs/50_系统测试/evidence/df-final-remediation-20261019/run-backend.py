from pathlib import Path
import hashlib,json,os,subprocess,xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
PRIVATE=ROOT/'output/runtime/df-closeout'
previous=json.loads((ROOT/'docs/50_系统测试/evidence/dc-targeted-closeout-20261019/backend-targeted.json').read_text('utf-8'))
classes=[s['class'] for s in previous['suites']]
for path in (ROOT/'backend/src/test/java').rglob('*Test.java'):
 if any(part in path.as_posix() for part in ['/config/', '/reservation/', '/housing/', '/messaging/', '/feedback/']) or path.name in ['TokenBlacklistRedisOutageTest.java']:
  classes.append('.'.join(path.relative_to(ROOT/'backend/src/test/java').with_suffix('').parts))
classes=sorted(s for s in set(classes) if not any(x in s.lower() for x in ['payment', 'refund']))
PRIVATE.mkdir(parents=True,exist_ok=True)
assert not any('payment' in s.lower() or 'refund' in s.lower() for s in classes)
env=dict(os.environ)
env['JAVA_HOME']='C:/Program Files/Java/jdk-17.0.4.1'
command=['cmd.exe','/d','/c',str(ROOT/'backend/mvnw.cmd'),'--batch-mode','--no-transfer-progress','-Dtest='+','.join(classes),'package']
log=PRIVATE/'build-final.log'
with log.open('wb') as stream:
 result=subprocess.run(command,cwd=ROOT/'backend',env=env,stdout=stream,stderr=subprocess.STDOUT)
if result.returncode:
 print('Maven failed; private log:',log)
 print('\n'.join(log.read_text('utf-8',errors='replace').splitlines()[-60:]))
 raise SystemExit(result.returncode)
suites=[]
for name in classes:
 source=ROOT/'backend/target/surefire-reports'/('TEST-'+name+'.xml')
 suite=ET.parse(source).getroot()
 suites.append({'class':name,**{k:int(suite.get(k,'0')) for k in ['tests','failures','errors','skipped']},'cases':[n.get('name') for n in suite.findall('testcase')]})
summary={'suites':suites,**{k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']},'scope':'受影响非金融定向套件；覆盖Redis、认证、通知、预约、房源、退租，不代表全部写入口组合。','buildSucceeded':True,'command':command,'buildLogSHA256':hashlib.sha256(log.read_bytes()).hexdigest(),'jarSHA256':hashlib.sha256((ROOT/'backend/target/residence-backend-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()}
assert summary['failures']==summary['errors']==summary['skipped']==0
(OUT/'backend-targeted.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps({k:summary[k] for k in ['tests','failures','errors','skipped','buildSucceeded']},ensure_ascii=False))
