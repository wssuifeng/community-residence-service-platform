from support import *
from datetime import datetime,date,timedelta
from PIL import Image
import io
s=load_sessions();sa=s['superadmin']['token'];rt=s['resident1']['token'];staff=s['staff1']['token'];checks=[]
spec=requests.get('http://127.0.0.1:8087/v3/api-docs').json();save('openapi-final.json',spec)
def resolve(schema):
 if '$ref' in schema:return spec['components']['schemas'][schema['$ref'].split('/')[-1]]
 return schema
def record(label,method,path,realpath,token,body=None,required=None,files=None):
 op=spec['paths']['/api/v1'+path][method.lower()]
 content=op.get('requestBody',{}).get('content',{});media=next(iter(content),None)
 schema=resolve(content[media]['schema']) if media else {}
 declared=set(schema.get('required',[]));expected=set(required or [])
 if files:r=requests.request(method,BASE+realpath,headers={'Authorization':'Bearer '+token},files=files,data={'type':'IMAGE'},timeout=30)
 else:r=request(realpath,token,method,body)
 result=r.json();root=resolve(op['responses']['200']['content']['*/*']['schema'])
 value_schema=resolve(root.get('properties',{}).get('data',{}));value=result.get('data')
 declared_fields=set(value_schema.get('properties',{}));actual_fields=set(value or {}) if isinstance(value,dict) else set()
 # Optional null properties may be omitted by Jackson. Extra serialized VO getters are retained explicitly.
 required_response=set(value_schema.get('required',[]))
 ok=r.status_code==200 and result.get('code')==200 and {'code','message','data'}<=set(result) and declared==expected and required_response<=actual_fields
 row={'sample':label,'method':method,'path':'/api/v1'+path,'passed':ok,'requestRequiredExpected':sorted(expected),'requestRequiredOpenAPI':sorted(declared),'parameters':[{k:v for k,v in p.items() if k in ('name','in','required','schema')} for p in op.get('parameters',[])],'requestFields':sorted(schema.get('properties',{})),'responseType':value_schema.get('type'),'responseDeclaredFields':sorted(declared_fields),'responseActualFields':sorted(actual_fields),'requiredResponseFields':sorted(required_response),'responseUndeclaredFields':sorted(actual_fields-declared_fields),'http':r.status_code,'code':result.get('code'),'designBasis':'30接口设计对应条目；社区字段按50/14、通知数组按2026-09-09裁决，审核/首期生效按批准16/23/27'}
 checks.append(row);save('contracts-partial.json',{'checks':checks});return value
record('1 社区创建','POST','/communities','/communities',sa,{'name':'dc契约抽样社区','address':'独立测试数据'},['address','name'])
record('2 居民注册','POST','/auth/resident/register','/auth/resident/register',None,{'username':'dc_contract_resident','password':'Resident123456','realName':'隔离契约居民','phone':'13870008888'},['password','phone','realName','username'])
wo=record('3 工单创建','POST','/work-orders','/work-orders',rt,{'categoryId':1,'title':'dc契约抽样','content':'独立接口契约样本','contactPhone':'13800000001'},['categoryId','contactPhone','content','title'])
record('4 工单详情','GET','/work-orders/{id}','/work-orders/'+str(wo['id']),rt)
notice=record('5 公告创建','POST','/notices','/notices',sa,{'title':'dc契约抽样公告','content':'独立抽样正文','publishTime':datetime.now().isoformat(timespec='seconds'),'targets':[{'targetType':'COMMUNITY','targetId':1}]},['content','publishTime','title'])
record('5 公告发布（同组）','PATCH','/notices/{id}/publish','/notices/'+str(notice['id'])+'/publish',sa,{'publishTime':datetime.now().isoformat(timespec='seconds')},['publishTime'])
record('6 反馈创建','POST','/feedbacks','/feedbacks',rt,{'communityId':1,'title':'dc契约抽样反馈','content':'独立接口契约样本','category':'INQUIRY'},['category','communityId','content','title'])
record('7 资源预约','POST','/resource-reservations','/resource-reservations',rt,{'resourceId':1,'reserveDate':str(date.today()+timedelta(days=6)),'startTime':'16:00:00','endTime':'17:00:00','purpose':'dc契约抽样'},['endTime','reserveDate','resourceId','startTime'])
# Complete the already-assigned nonfinancial work order to get a real evaluation response.
api('/work-orders/1/process',staff,'PATCH',{'remark':'dc接口抽样处理'})
api('/work-orders/1/complete',staff,'PATCH',{'remark':'dc接口抽样已完成'})
api('/work-orders/1/confirm',rt,'PATCH',{})
record('8 工单评价','POST','/work-orders/{orderId}/evaluation','/work-orders/1/evaluation',rt,{'rating':5,'isSatisfied':True,'content':'隔离接口抽样'},['isSatisfied','rating'])
record('9 通知补拉','GET','/notifications/pull','/notifications/pull?sinceSeq=0',rt)
image=io.BytesIO();Image.new('RGB',(2,2)).save(image,format='JPEG')
record('10 通用上传','POST','/upload','/upload',rt,required=['file'],files={'file':('dc-contract.jpg',image.getvalue(),'image/jpeg')})
save('contracts.json',{'checks':checks,'sampleGroups':10,'operations':11,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'scope':'严格按TC-NF-016的10组抽样，不宣称全API全部字段证明'})
print('contracts',sum(x['passed'] for x in checks),'/',len(checks));print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False))
