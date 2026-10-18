"""S0 inventory and deterministic relocation. Never touches services or business data."""
from pathlib import Path
import csv,json,re,sys
ROOT=Path(__file__).resolve().parents[4]
OUT=Path(__file__).parent
BE=ROOT/'backend/src/main/java/com/community/residence'
FE=ROOT/'frontend/src'
PREFIX='com.community.residence.'

def read(p):return p.read_text(encoding='utf-8-sig')
def rel(p):return p.relative_to(ROOT).as_posix()
def mask(s):
 # Preserve positions while masking comments and string/character literals (including text blocks).
 return re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*',lambda m:''.join('\n' if c=='\n' else ' ' for c in m[0]),s)
def members(s):
 m=mask(s);match=re.search(r'\bclass\s+\w+[^\{]*\{',m)
 assert match
 start=match.end();i=start;depth=1;last=start;parts=[]
 while i<len(m):
  c=m[i]
  if c=='{':depth+=1
  if c=='}':
   depth-=1
   if depth==1:parts.append(s[last:i+1]);last=i+1
   if depth==0:
    if s[last:i].strip():parts.append(s[last:i])
    return s[:start],parts,s[i:]
  if c==';' and depth==1:parts.append(s[last:i+1]);last=i+1
  i+=1
 raise ValueError('Unbalanced class')
def methods(s):
 head,parts,tail=members(s)
 class_auth=re.search(r'@PreAuthorize\(([^\n]*)\)',head)
 result=[]
 for part in parts:
  if not re.search(r'@(?:Get|Post|Put|Patch|Delete|Request)Mapping\b',part):continue
  signature=re.search(r'\bpublic\s+[^;{]+?\s+(\w+)\s*\(',mask(part))
  assert signature,part
  auth=re.search(r'@PreAuthorize\(([^\n]*)\)',part) or class_auth
  expression=auth[1] if auth else ''
  roles=set(re.findall("'(ADMIN|SUPER_ADMIN|RESIDENT|STAFF)'",expression))
  owner='admin' if roles and roles<={'ADMIN','SUPER_ADMIN'} else 'resident' if roles=={'RESIDENT'} else 'staff' if roles=={'STAFF'} else 'common'
  result.append({'method':signature[1],'owner':owner,'authorization':expression,'declaration':part})
 return head,parts,tail,result

const_domains={
 'ApplicationStatus':'resident','ChatMessageType':'conversation','ConversationStatus':'conversation','ConversationType':'conversation',
 'DepositRefundStatus':'payment','DepositStatus':'lease','DispatchFlag':'workorder','HouseStatusConstant':'community','LeaseStatus':'lease','MoveOutStatus':'lease',
 'NoticeStatus':'notice','NotificationOwnerType':'messaging','PaymentChannel':'payment','PaymentEnvironment':'payment','PaymentStatus':'payment','PaymentType':'payment',
 'ReservationStatus':'reservation','RoleConstants':'security','ShiftType':'workorder','WorkOrderPriority':'workorder','WorkOrderProcessAction':'workorder','WorkOrderStatus':'workorder'}
def backend_target(p):
 parts=p.relative_to(BE).parts
 if len(parts)==1:return p
 if parts[0]=='common':
  section=parts[1]
  if section=='constant':return BE/'common'/const_domains.get(p.stem,'web')/'constant'/p.name
  if section=='context':return BE/'common/security/context'/p.name
  if section in ('controller','service'):return BE/'common/upload'/Path(*parts[1:])
  return BE/'common/web'/Path(*parts[1:])
 if parts[0] in ('filter','interceptor'):return BE/'common/security'/Path(*parts)
 if parts[0]=='resident' and p.stem in ('SysConfig','SysConfigService','SysConfigMapper','UpdateConfigDTO','ConfigController'):
  return BE/'common/auth'/Path(*parts[1:])
 return BE/'common'/Path(*parts)

def view_module(n):
 if n.startswith(('Lease','HousingLeaseHub')):return 'lease'
 if n.startswith(('Residence','Profile')):return 'resident'
 if n.startswith(('Housing','Viewing')):return 'housing'
 if n.startswith('WorkOrder'):return 'workorder'
 if n.startswith(('Resource','Reservation')):return 'reservation'
 if n.startswith('Feedback'):return 'feedback'
 if n.startswith('Notice'):return 'notice'
 if n.startswith('Notification'):return 'messaging'
 if n.startswith('Conversation'):return 'conversation'
 if n in ('HomeView','DashboardView'):return 'workbench'
 return 'web'
api_modules={'config':'auth','operationlog':'auth','sysuser':'auth','staff':'workorder','leaseMoveOut':'lease','paymentChannelConfig':'payment','notification':'messaging','conversationInbox':'conversation','chat':'conversation','houseImages':'community','upload':'upload'}
util_modules={'auth':'auth','onlineRenewal':'auth','username':'auth','permission':'routing','request':'web','dataFreshness':'web','websocket':'messaging','notificationNav':'messaging','housePhoto':'community','leaseDisplay':'lease','leaseHousehold':'lease','leaseTerms':'lease','noticeDocument':'notice','payment':'payment','reservationQuota':'reservation','residentImportReference':'resident','staffResidentPresentation':'resident','responsive':'web','elementPlusDefaults':'web','date':'utils','paged':'web'}
def frontend_target(p):
 parts=p.relative_to(FE).parts;n=p.stem
 if parts[0] in ('main.ts','App.vue','env.d.ts','__tests__','test'):return p
 if parts[0]=='views':
  end=parts[1]
  if end not in ('admin','resident','staff','guest','auth'):return FE/'common/web/views'/Path(*parts[1:])
  if end in ('guest','auth'):return FE/'common'/('public' if end=='guest' else 'auth')/'views'/Path(*parts[2:])
  rest=list(parts[2:]);module=rest.pop(0) if len(rest)>1 and rest[0]!='__tests__' else view_module(n)
  if rest and rest[0]=='__tests__':module=view_module(n)
  if 'Layout' in n or n=='AdminPageHeader':return FE/end/'layout'/Path(*rest)
  # Keep colocated tests adjacent to the original component category.
  target_kind='views' if n.endswith('View') or n in ('DashboardOverview','DashboardTabView') else 'components'
  if p.suffix=='.ts' and not n.endswith('.spec'):target_kind='composables' if n.startswith('use') else 'utils'
  if n.endswith('.spec'):
   base=n[:-5];target_kind='views' if base.endswith('View') else 'components'
  return FE/end/module/target_kind/Path(*rest)
 if parts[0]=='router':
  if n=='index':return FE/'router.ts'
  return FE/('common/public' if n=='guest' else n)/'routes.ts'
 if parts[0]=='api':return FE/'common'/api_modules.get(n,n)/'api'/p.name
 if parts[0]=='types':
  if len(parts)>2:return FE/'common'/api_modules.get(n,n)/'types'/p.name
  return FE/'common'/('auth' if n=='user' else 'conversation' if n=='chat' else 'web')/'types'/Path(*parts[1:])
 if parts[0]=='store':
  mod='auth' if n in ('user','index') else 'messaging' if n=='notification' else 'routing' if n=='permission' else 'statistics'
  return FE/'common'/mod/'store'/Path(*parts[1:])
 if parts[0]=='utils':return FE/'common'/util_modules.get(n,'utils')/('utils' if util_modules.get(n)!='utils' else '')/Path(*parts[1:])
 if parts[0]=='composables':
  mod='conversation' if n in ('useChatChannel','useConversationInbox') else 'web'
  if '__tests__' in parts:mod='conversation' if n[:-5] in ('useChatChannel','useConversationInbox') else 'web'
  return FE/'common'/mod/'composables'/Path(*parts[1:])
 if parts[0]=='components':
  group=parts[1];rest=parts[2:];base=n.removesuffix('.spec')
  if group=='admin':return FE/'admin/layout/components'/Path(*rest)
  if group=='layout':return FE/('admin/layout/components' if base=='AppSidebar' else 'common/web/components')/Path(*rest)
  if group=='conversation':mod='conversation'
  elif group=='business':
   mod='conversation' if any(x in base for x in ('Chat','Conversation')) else 'agreement' if 'Agreement' in base else 'community' if 'House' in base or 'CommunityDeletion' in base else 'lease' if 'Lease' in base else 'notice' if 'Notice' in base else 'messaging'
  else:mod='upload' if 'Uploader' in base else 'statistics' if base=='EChart' else 'web'
  return FE/'common'/mod/'components'/Path(*rest)
 if parts[0]=='styles':return FE/'common/styles'/Path(*parts[1:])
 if parts[0]=='directives':return FE/'common/web/directives'/Path(*parts[1:])
 if parts[0]=='constants':return FE/'common/web/constants'/Path(*parts[1:])
 raise ValueError(rel(p))

MODULE_NAMES={'community':'社区结构与房屋','resident':'居民与居住关系','lease':'租约与租期','workorder':'工单与服务人员','notice':'公告','feedback':'反馈','reservation':'公共资源预约','evaluation':'评价跟进','statistics':'运营统计','auth':'认证与系统账号配置','messaging':'消息中心与实时投递','housing':'房源与看房','payment':'支付渠道与订单','agreement':'协议','conversation':'会话','config':'技术配置','filter':'认证与请求过滤','interceptor':'数据权限','log':'操作留痕','schedule':'定时任务','common':'公共技术能力'}
def controller_destination(source,owner):
    relative=source.relative_to(BE)
    module='auth' if source.stem=='ConfigController' else relative.parts[0]
    name=source.stem
    if owner=='common':name='WorkOrderQueryController' if name=='WorkOrderController' else name
    else:
        prefix={'admin':'Admin','resident':'Resident','staff':'Staff'}[owner]
        name=prefix+name if not name.startswith(prefix) else prefix+('AccountController' if name=='ResidentController' else name)
    return BE/owner/module/'controller'/(name+'.java')

def inventory():
 rows=[];endpoints=[]
 for p in sorted(BE.rglob('*.java')):
  dest=backend_target(p);owners=[]
  if p.parent.name=='controller':
   _,_,_,items=methods(read(p))
   for item in items:
    endpoints.append({'source':rel(p),'method':item['method'],'owner':item['owner'],'authorization':item['authorization'],'target':rel(controller_destination(p,item['owner']))})
   owners=sorted(set(x['owner'] for x in items))
  rows.append({'source':rel(p),'target':';'.join(rel(controller_destination(p,o)) for o in owners) if owners else rel(dest),'transitionTarget':rel(dest),'owner':','.join(owners) or 'common','responsibility':MODULE_NAMES[p.relative_to(BE).parts[0]] if len(p.relative_to(BE).parts)>1 else '应用启动', 'split':len(owners)>1 or (owners and owners!=['common'])})
 for name,data in [('backend-ownership.csv',rows),('endpoint-ownership.csv',endpoints)]:
  with (OUT/name).open('w',encoding='utf-8',newline='') as f:
   writer=csv.DictWriter(f,fieldnames=list(data[0]));writer.writeheader();writer.writerows(data)
 front=[]
 for p in sorted(FE.rglob('*')):
  if not p.is_file():continue
  dest=frontend_target(p)
  front.append({'source':rel(p),'target':rel(dest),'owner':dest.relative_to(FE).parts[0],'responsibility':MODULE_NAMES.get(dest.relative_to(FE).parts[1],dest.relative_to(FE).parts[1]) if len(dest.relative_to(FE).parts)>2 else '应用装配与测试支撑'})
 with (OUT/'frontend-ownership.csv').open('w',encoding='utf-8',newline='') as f:
  writer=csv.DictWriter(f,fieldnames=list(front[0]));writer.writeheader();writer.writerows(front)
 assert len({x['transitionTarget'] for x in rows})==len(rows)
 assert len({x['target'] for x in front})==len(front)
 print(json.dumps({'backendFiles':len(rows),'frontendFilesIncludingTests':len(front),'httpMethods':len(endpoints),'mixedControllers':sum(',' in x['owner'] for x in rows)}))

if __name__=='__main__':inventory()
