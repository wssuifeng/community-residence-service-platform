"""本批专用隔离环境；不访问正常业务或金融接口。"""
import os,json,subprocess,time
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).resolve().parent
PRIVATE=ROOT/'output/runtime/dc-closeout'
DB='community_residence_dc_20261019'
BASE='http://127.0.0.1:8087/api/v1'
WEB='http://127.0.0.1:5181'
ENV=os.environ.copy()
for line in (ROOT/'.env').read_text('utf-8-sig').splitlines():
 if '=' in line and not line.lstrip().startswith('#'):
  k,v=line.split('=',1)
  if k.strip() in ('DB_PASSWORD','DB_USERNAME'): ENV[k.strip()]=v.strip().strip('"').strip("'")
MYSQL='C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe'
def sql(query,server=False):
 assert DB=='community_residence_dc_20261019'
 if server: assert query.startswith(('CREATE DATABASE '+DB,"SELECT COUNT(*) FROM information_schema.schemata"))
 args=[MYSQL,'-h127.0.0.1','-u'+ENV.get('DB_USERNAME','root'),'-N','-B','--default-character-set=utf8mb4']
 if not server:args+=['-D'+DB]
 p=subprocess.run(args+['-e',query],env={**ENV,'MYSQL_PWD':ENV['DB_PASSWORD']},capture_output=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
 if p.returncode:raise RuntimeError('专用库SQL失败：'+p.stderr.replace(ENV['DB_PASSWORD'],'<redacted>')[:1500])
 return p.stdout.strip()
def request(path,token=None,method='GET',body=None,timeout=30):
 assert path.startswith('/') and not any(x in path.lower() for x in ('/payment','/refund','/deposit-refund')), '禁止金融请求'
 headers={'Authorization':'Bearer '+token} if token else {}
 return requests.request(method,BASE+path,headers=headers,json=body,timeout=timeout)
def api(path,token=None,method='GET',body=None,deny=False,timeout=30):
 res=request(path,token,method,body,timeout)
 try:data=res.json()
 except Exception:raise RuntimeError(path+' non-JSON '+str(res.status_code))
 if deny:return res.status_code in (400,401,403,404,409) or data.get('code')!=200
 assert res.status_code==200 and data.get('code')==200,(path,res.status_code,data.get('code'),data.get('message'))
 return data.get('data')
def login(username,password,kind='admin'):
 return api('/auth/'+kind+'/login',method='POST',body={'username':username,'password':password})
def save(name,data):
 (OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
def load_sessions():return json.loads((PRIVATE/'sessions.json').read_text('utf-8'))
