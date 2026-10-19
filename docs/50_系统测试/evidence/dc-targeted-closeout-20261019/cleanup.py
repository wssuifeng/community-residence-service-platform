"""只停止本批state.json明确标记的隔离进程，不删除数据库或历史文件。"""
from support import *
state=json.loads((PRIVATE/'state.json').read_text('utf-8'));assert state['database']==DB and state['ports']==[8087,5181,6387]
expected={'backendPid':['dc-final.jar','--server.port=8087'],'frontendPid':['vite','--port 5181'],'redisPid':['memurai','--port 6387']};stopped=[]
for key,parts in expected.items():
 pid=int(state[key]);cmd=subprocess.check_output(['powershell','-NoProfile','-Command',f'(Get-CimInstance Win32_Process -Filter "ProcessId={pid}").CommandLine'],text=True).strip()
 assert all(x.lower() in cmd.lower() for x in parts),(key,'进程不符合本批特征，停止清理')
 subprocess.run(['powershell','-NoProfile','-Command',f'Stop-Process -Id {pid} -ErrorAction Stop'],check=True,capture_output=True);stopped.append({'kind':key,'pid':pid,'verifiedOwnCommand':True})
import socket
ports={}
for port in [8087,5181,6387,8084,5176,6379]:
 with socket.socket() as client:ports[str(port)]=client.connect_ex(('127.0.0.1',port))==0
assert not any(ports[str(p)] for p in [8087,5181,6387]);assert all(ports[str(p)] for p in [8084,5176,6379])
save('cleanup.json',{'stopped':stopped,'ownProcessesStopped':True,'listeningAfter':ports,'databaseRetained':DB,'normalServicesStopped':False,'userBrowserTouched':False,'historicalOutputDeleted':False,'temporaryHarnessRemoved':not (ROOT/'frontend/dc-closeout.html').exists(),'rawSamplesRetainedPrivately':True})
print('own environment stopped; normal listeners preserved; synthetic database retained')
