from support import *
import shutil,hashlib
state=json.loads((PRIVATE/'state.json').read_text('utf-8'))
assert state['database']==DB and state['ports']==[8087,5181,6387]
pid=state['backendPid']
cmd=subprocess.check_output(['powershell','-NoProfile','-Command',f'(Get-CimInstance Win32_Process -Filter "ProcessId={pid}").CommandLine'],text=True).strip()
assert 'manual-cx-release-20261018.jar' in cmd or str(PRIVATE/'dc-final.jar') in cmd,'拒绝停止非本批进程'
subprocess.run(['powershell','-NoProfile','-Command',f'Stop-Process -Id {pid} -ErrorAction Stop'],check=True,capture_output=True)
jar=PRIVATE/'dc-final.jar';shutil.copy2(ROOT/'backend/target/residence-backend-0.1.0-SNAPSHOT.jar',jar)
env={k:v for k,v in ENV.items() if not k.startswith('PAYMENT_')};env['JWT_SECRET']='dc-isolated-only-secret-20261019-not-for-production';env['PAYMENT_CONFIG_KEY_FILE']=str(PRIVATE/'payment/master.key')
args=['C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe','-Dfile.encoding=UTF-8','-jar',str(jar),'--server.port=8087','--spring.config.import=','--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+DB+'?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6387','--upload.dir='+str(PRIVATE/'uploads'),'--logging.level.com.community.residence=info']
with (PRIVATE/'backend-final-out.log').open('ab') as out,(PRIVATE/'backend-final-err.log').open('ab') as err:p=subprocess.Popen(args,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP)
state.update({'backendPid':p.pid,'jarSHA256':hashlib.sha256(jar.read_bytes()).hexdigest(),'backendSource':'caae28d + DEF-213/214/215 + DEF-104/106/116 regression fixes','stoppedOnlyOwnPreviousBackendPid':pid});(PRIVATE/'state.json').write_text(json.dumps(state,indent=2),'utf-8');save('final-runtime.json',state)
print('own backend restart',pid,'->',p.pid)
