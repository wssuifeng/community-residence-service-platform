from support import *
import socket,hashlib
PRIVATE.mkdir(parents=True,exist_ok=True)
for port in (8087,5181,6387):
 with socket.socket() as s:assert s.connect_ex(('127.0.0.1',port))!=0,'隔离端口占用，禁止接管'
assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+DB+"'",True)=='0','专用库存在，禁止覆盖'
jar=ROOT/'output/runtime/manual-cx-release-20261018.jar'
source=json.loads((ROOT/'docs/50_系统测试/evidence/cx-current-runtime-20261018/startup-summary.json').read_text('utf-8'))
jarhash=hashlib.sha256(jar.read_bytes()).hexdigest()
assert jarhash in json.dumps(source),'JAR与已验证cx证据不一致'
assert not subprocess.check_output(['git','diff','57f26e9','HEAD','--','backend/src/main','backend/pom.xml'],cwd=ROOT),'后端源码已变化，需重新构建'
sql('CREATE DATABASE '+DB+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',True)
env={k:v for k,v in ENV.items() if not k.startswith('PAYMENT_')}
env['JWT_SECRET']='dc-isolated-only-secret-20261019-not-for-production'
env['PAYMENT_CONFIG_KEY_FILE']=str(PRIVATE/'payment/master.key')
def launch(args,name,cwd=ROOT):
 with (PRIVATE/(name+'-out.log')).open('ab') as out,(PRIVATE/(name+'-err.log')).open('ab') as err:
  return subprocess.Popen(args,cwd=cwd,env=env,stdin=subprocess.DEVNULL,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.CREATE_NEW_PROCESS_GROUP).pid
redis=launch(['D:/Tools/Memurai_Redis/memurai.exe','--port','6387','--bind','127.0.0.1','--save','','--appendonly','no','--dir',str(PRIVATE),'--logfile',str(PRIVATE/'redis.log')],'redis')
backend=launch(['C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe','-Dfile.encoding=UTF-8','-jar',str(jar),'--server.port=8087','--spring.config.import=','--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+DB+'?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true','--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=6387','--spring.data.redis.database=0','--upload.dir='+str(PRIVATE/'uploads'),'--logging.level.com.community.residence=info'],'backend')
env['VITE_PROXY_TARGET']='http://127.0.0.1:8087';env['VITE_API_BASE_URL']='/api/v1'
frontend=launch(['D:/ruanjian/nodejs/node.exe','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5181','--strictPort'],'frontend',ROOT/'frontend')
state={'database':DB,'backendPid':backend,'frontendPid':frontend,'redisPid':redis,'ports':[8087,5181,6387],'backendSource':'57f26e9','sourceHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'jarSHA256':jarhash,'normalServicesUntouched':True,'paymentCommands':0}
(PRIVATE/'state.json').write_text(json.dumps(state,indent=2),'utf-8');save('startup.json',state)
print(json.dumps(state))
