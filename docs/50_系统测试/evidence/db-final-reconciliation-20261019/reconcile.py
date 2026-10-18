"""Reconcile saved project evidence. No network, database, browser or test execution."""
import csv, hashlib, json, re
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
BASE=ROOT/'docs/50_系统测试'
OUT=Path(__file__).resolve().parent
SNAP=BASE/'evidence/cz-test-closeout-20261018'
def read(p): return p.read_text(encoding='utf-8-sig')
def load(p): return json.loads(read(p))
def write_json(name,obj): (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def cells(l):
    s=re.sub(r'(`+).*?\1',lambda m:m[0].replace('|','¦'),l)
    return [x.strip().replace('¦','|') for x in s.strip('|').split('|')]
def clean(s): return re.sub(r'[*`]', '', s).replace('|','／').replace('\n',' ')
def line_of(p,needle):
    ls=read(p).splitlines()
    return next((i for i,l in enumerate(ls,1) if needle in l),1)
def loc(p,needle=''):
    p=p.resolve()
    return {'path':p.relative_to(ROOT).as_posix(),'line':line_of(p,needle) if needle else 1}
registry={}
def ev(k,name,filename,needle=''):
    p=BASE/filename
    assert p.is_file(),p
    registry[k]={'name':name,**loc(p,needle)}
def sec(k,name,number): ev(k,name,'08_缺陷跟踪与回归.md','### '+number+' ')
ev('A5','第五轮各业务套件执行','07_测试执行记录.md','### 6.1 ')
ev('D5','第五轮逐缺陷复验','07_测试执行记录.md','### 6.2 ')
ev('UI5','第五轮页面与接线复验','07_测试执行记录.md','### 6.4 ')
ev('NF5','第五轮量化性能','07_测试执行记录.md','### 6.3 ')
ev('WS1','双通道22样本及鉴权','07_测试执行记录.md','### 1.6 ')
ev('JOB','定时任务与到期提醒','07_测试执行记录.md','### 2.5 ')
ev('COLD','历史冷启动与依赖检查','07_测试执行记录.md','### 2.4 ')
ev('B3','第三批逐项专项执行','07_测试执行记录.md','### 4.1 ')
ev('CONTRACT','历史HTTP契约抽样','07_测试执行记录.md','### 6.5 ')
for k,name,n in [('BE','首批非交易复核','3.75'),('BF','权限/身份/按钮真实复验','3.76'),('BG','结构/责任人','3.77'),('BH1','居民录入与关系','3.78'),('BH2','首期门禁与占用','3.80'),('BH3','公共资源真实图片','3.79'),('BI','四域数据权限','3.81'),('BK','类别与CSV原现场','3.85'),('BL','服务人员完成看房','3.88'),('BM','非交易异常单测','3.89'),('BN','账号/导入/挂牌修正','3.90'),('BO','责任人/主关系/编号','3.91'),('BP','HT01～06修正','3.92'),('BQ','公告图片/身份/会话图片/匿名','3.93'),('BU','删除确认/合同/相册','3.94'),('BX','未读/子表权限/配置开关','3.95'),('CE','金融用户主路径及订单截止','3.100'),('CF','铃铛/首页/工单群','3.101'),('CM','既有回归用户确认','3.103'),('CP','三端会话基础用户确认','3.105'),('BC','协议意愿去重','3.73'),('BD','支付宝配置管理','3.74')]: sec(k,name,n)
ev('BJ','工单派单权限与侧栏','08_缺陷跟踪与回归.md','## 3.82 ')
sec('CA','单套挂牌与列表实时刷新','3.97')
sec('MOVEOUT','退租通知与实际三端深链','3.62')
sec('RESUME','首期PENDING重开真实续接','3.54')
ev('CV','最终结构整理及离线验证','evidence/cv-architecture-implementation-20261018/final-validation.json')
ev('CV3','结构轮1183项，DB专项跳过1','evidence/cv-architecture-implementation-20261018/s3-validation.json')
ev('CVP','事务提交后推送验证','evidence/cv-architecture-implementation-20261018/o3-validation.json')
ev('CY','顶栏与三端账号菜单','evidence/cy-header-navigation-20261018/README.md')
ev('CZ','用户确认与最后运行事实','evidence/cz-test-closeout-20261018/evidence-summary.json')
ev('SYSDETAIL','30设计9.10.1.5允许ADMIN读详情','../30_系统设计/接口设计.md','##### 9.10.1.5 ')
ev('CHLEVEL','渠道分级未勾选否定测试','../../backend/src/test/java/com/community/residence/common/messaging/service/ChannelLevelsTest.java','void create_withoutConfiguredChannels')
ev('EVENTS','工单六类通知与身份','08_缺陷跟踪与回归.md','### 3.58 ')
ev('BMTEST','964项逐套件历史单测结果','evidence/autonomous-closeout-20261013/backend-results.json')
ev('CFTEST','后续1163项单测汇总','evidence/cf-notification-home-workorder-20261016/backend-tests.json')
# Case definitions are snapshotted before db maintenance; keep historical priorities separately.
catalog=load(SNAP/'test-case-catalog.json');rtm=load(SNAP/'rtm-snapshot.json')
priority_add={3:'P0',17:'P0',18:'P0',19:'P0',20:'P0',21:'P0',22:'P0',23:'P0',24:'P1',25:'P1',26:'P0',27:'P1',28:'P1',29:'P0',30:'P0'}
scripts={}
for p in (BASE/'scripts').iterdir():
    if p.suffix not in ('.mjs','.py'): continue
    for i,l in enumerate(read(p).splitlines(),1):
        for cid in re.findall(r'TC-(?:C\d+|NF|SP)-\d{3}',l):
            scripts.setdefault(cid,[]).append({'path':p.relative_to(ROOT).as_posix(),'line':i,'text':l.strip()[:240]})
# Explicitly reviewed semantic overrides; never infer PASS from the last occurrence of an ID.
case_overrides={}
def co(ids,result,keys,note):
    for cid in ids.split(): case_overrides[cid]=(result,keys.split(),note)
co('TC-C1-010 TC-C1-011 TC-C1-013 TC-C1-028','现行定向证据','BG BO','楼栋floors是单栋边界；不再使用固定99/200层或roomCount，每层户数/生成规模的技术限制不等同业务上限。')
co('TC-C1-019 TC-C1-020 TC-C1-021 TC-C1-024 TC-C1-027','现行定向证据','BG BP BU BX','按现行依赖保护、真实删除影响、名称/版本/双确认及外层事务审阅；旧“一律有引用拒绝”不能覆盖超管经批准的级联删除。未追加删除正常数据。')
co('TC-C2-010 TC-C2-020 TC-C2-021','现行定向证据','BH1 BH2 BO','TENANT审批仅到待签约；协议SIGNED且FIRST成功才激活正式关系。OWNER/依赖成员按各自关系规则，直建关系不代建租约或付款义务。')
co('TC-C3-003','现行定向证据','BC BH2','状态端点可审核到TO_SIGN；禁止直接ACTIVE。首期成功内核与交易成功事实分别留证。')
co('TC-C3-010','用户弃置','D5','DEF-016用户裁决不实施独立手动重发。不得把其脚本引用占位计作已执行通过，也不能静默从305分母扣除。')
co('TC-C3-014','批准预期替代','BU CE','旧续租缴费顺延止期已被v1.10/27取代：居民申请延长、管理员批准即生效；普通RENT按确认范围余月，不延长合同。当前实现及人工金融主路径可用，不回填旧语义通过。')
co('TC-C3-016','人工主路径确认','BC BH2 CE CZ','协议后待首期、FIRST成功激活；普通租金成功用户确认。完整退款/迟到/notify/微信异常不由该确认推出，AI不复测。')
co('TC-C4-007 TC-C4-009 TC-C4-011 TC-C4-012 TC-C4-014 TC-C4-016 TC-C4-019 TC-C4-020 TC-C4-022','现行定向证据','BJ CF BF','按当前受理/直接派单/最新被派人权限/确认终审及群生命周期；旧取消或跳级预期先对照现行状态机，不以同编号旧断言替代新分支。')
co('TC-C5-005 TC-C5-009 TC-C5-013','现行定向证据','BI BQ CF','居民按有效关系匹配真实社区/楼栋；阅读者与回执均有范围校验，旧主表通过后新增越权已由BI修复实测。')
co('TC-C5-002','现行定向证据','BQ CE','旧已发布仅下线后编辑预期被批准28的发布修订/版本阅读取代；历史草稿行为与新修订行为分开。')
co('TC-C6-004','量化适用性保留','WS1 BF CVP CP','历史22样本及BF单样本12ms有效；后续事务提交后推送与统一会话改动，最新版本没有相同负载下≤3s的量化样本。')
co('TC-C6-005 TC-C6-006 TC-C6-007 TC-C6-008 TC-C6-010 TC-C6-012 TC-C6-013','现行定向证据','BI BQ CP','会话/图片/匿名/办结/参与者权限有增量实测；用户三端基础确认有效。实时≤3s量化子判据见TC-C6-004，非全套时延通过。')
co('TC-C7-004','现行定向证据','A5 BF','DEF-002已纠正为同居民同资源同一重叠时段拒绝；同日不重叠可约，原标题“同日重复”不作按天限次。')
co('TC-C8-003','现行定向证据','BF BI','按当前COMPLETED/CLOSED可评价边界，CLOSED补评已实测；不沿旧单一COMPLETED硬限制。')
co('TC-C8-004','现行定向证据','BI','提交人身份/评价读取权限都需核验，后续DEF-175修复包括非提交人及非被派人员读评价。')
co('TC-C9-001 TC-C9-003 TC-C9-004 TC-C9-009 TC-C9-010 TC-C9-011','人工基础确认','UI5 BL CM CZ','历史接口/页面观察与用户整册确认保留，不能据此补造16卡/4图/全部时间段/导出逐项原始结果；旧脚本存在接口替代或占位，出口严格统计保留。')
co('TC-C10-001 TC-C10-004 TC-C10-005 TC-C10-006 TC-C10-010 TC-C10-016','现行定向证据','BF BO BP BQ','36h签发/在线续期、令牌族撤销、权限代次和同秒重登有增量证据；不是36h耐久实测。')
co('TC-C10-014 TC-C10-015','按30设计勘误','SYSDETAIL A5 BF','30接口9.10.1.5明确GET用户详情允许ADMIN/SUPER_ADMIN；列表/写/绑定仅超管。原TC把详情预期为403系从20不可见直接推导，按50职责改正；需求与设计的差异交60，不因此修改既有权限。')
co('TC-C10-017 TC-C10-018 TC-C10-019 TC-C10-020','现行定向证据','D5 BF BM','早期脚本存在true占位；用DEF-034逐服务落库、BF安全白名单日志与BM参数/分页/身份回归承接，历史未采集请求不补造。')
# C11 script IDs were historical shorthand and do not match 04 definitions: use semantic evidence.
for n,key in {1:'BF CF',2:'BF BH2',3:'BI',4:'BF JOB',5:'BI BQ',6:'BQ CP',9:'WS1 BF',10:'WS1 BF',11:'CF',12:'BF CF',13:'UI5 BF',15:'BF BD',16:'BF CF'}.items():
    co(f'TC-C11-{n:03}','现行定向证据',key,'按本用例语义关联通知事件/收件身份/已读或渠道权限；旧c10c脚本的同号标题不一致，不作同号即覆盖。')
co('TC-C11-007','现行定向证据','EVENTS BF BI BC BQ','六类事件按08§3.58～3.64及BF/BI/BC/BQ逐语义归并：工单、入住/退租/资源/看房审核、到期、公告、反馈、违约；不扩成全部后增金融异常/每种接收人组合穷尽。')
co('TC-C11-014','部分执行证据','CHLEVEL BMTEST UI5 BF','ChannelLevelsTest 9项已执行，含未勾选仅WEBSOCKET一条留痕的明确否定断言；真实配置读写也通过，但原TC要求的UI发布→SQL直查两态未找到完整逐项对应，不写完全无测试或真实SQL全过。')
co('TC-C11-008 TC-C11-017','量化适用性保留','WS1 BF CVP CF CP','功能、补拉、身份与菜单已有证据；最新提交后推送及统一会话的≤3s子判据无新量化记录。')
# C12 equivalent cases are located by behavior, not the old seven shorthand IDs.
for n,keys in {1:'BN BP',2:'BG BH2',3:'BN BX',4:'BN',5:'BN BU',6:'A5 BF',7:'BF',8:'BF',9:'BF',10:'BF',11:'BF BL',12:'BF BP',13:'BF BL',14:'BF',17:'BE',18:'BF BP',19:'BF',20:'A5 BL',21:'BF BL',22:'BF BL'}.items():
    co(f'TC-C12-{n:03}','现行定向证据',keys,'按注册居民/本人预约/当前被分配服务人员及真实房屋来源核对；旧脚本七个简写编号不等于本页23个完整定义，保留各专项执行层次。')
co('TC-C12-015 TC-C12-016','批准预期替代','BF BL','方案B：游客只浏览；创建/查询/取消和账号违约处置收归注册居民，旧visitor_name/visitor_phone匿名预约不恢复、不作为当前失败。')
co('TC-C12-023','批准预期替代','BF','旧标题“看房不套用栅格”已被R60/DEF-061的60分钟切片取代。看房与资源容量/唯一约束仍不同，不能继续期待整段模板不切片。')
co('TC-NF-001','历史执行证据','NF5','50并发/200账号/600秒/500ms pacing的压测蓝图和JTL留存；本次仅复核已有材料。')
co('TC-NF-002 TC-NF-003 TC-NF-004 TC-NF-005 TC-NF-011','量化适用性保留','NF5 WS1 BF CVP','历史P95/并发/WS样本有效；源码查询、模型和推送时机有实质变化，不能继承为最终代码相同负载实测。N7造数前后未单独采集种子基线。')
co('TC-NF-006 TC-NF-007 TC-NF-008 TC-NF-009 TC-NF-010','现行定向证据','BF BP BQ BM CZ','安全/日志有历史及增量证据；仅N6阈值730在2026-10-18真实只读确认，本日未查询。')
co('TC-NF-012','历史执行证据','UI5 CY','历史Edge自动化与后续独立浏览器验证分开；新浏览器结果不冒称所有浏览器兼容全量通过。')
co('TC-NF-013','人工基础确认','CM CP CZ','用户已确认业务、最新版本及未发现受影响客户端；未记录其浏览器精确版本，不追加巡检。')
co('TC-NF-014','现行定向证据','COLD BU CV','历史冷启动＋后续独立库迁移和JDK17运行包加载承接；不宣称本日再次冷启动。')
co('TC-NF-015','批准预期替代','BD CE','“不存在支付SDK/真实支付”已被R61/R71/R72批准引入双渠道取代；凭据门控和非交易离线可启动仍成立。')
co('TC-NF-016','执行证据缺项','CONTRACT CV','历史抽样10/10与结构迁移HTTP映射保持有效；02/03/04与部分旧接口文档存在语义漂移，不能等同现行全契约逐项一致。')
for n in range(1,5): co(f'TC-SP-{n:03}','专项适用性保留','A5 BF BI BL','原冲突20/20含重复/重叠/首尾相接；后续栅格/日程/身份/私有预约有定向证据，但不是最新源码重放原三轮10路完整矩阵。')
for n in range(5,11): co(f'TC-SP-{n:03}','专项适用性保留','A5 BF BI BJ BO BX CV','原权限84/84与后续实际拒绝有效；后续新入口、角色边界、身份及表结构变化已单独验证，缺少映射到最终全集的同口径100%分母。')
for n in range(11,17): co(f'TC-SP-{n:03}','历史执行证据','WS1 JOB','按07跨日任务、锁/任务日志及直调集成逐项保留；停止窗口内任务采用历史已说明的补观察，不新造自然调度结果。')
co('TC-SP-017 TC-SP-018 TC-SP-020 TC-SP-021','现行定向证据','WS1 BF BQ CP','历史CONNECT/订阅/重连/补拉与增量身份隔离/用户会话基础确认分别留证；不把人工确认扩成故障注入。')
co('TC-SP-019','量化适用性保留','WS1 BF CVP CP','双通道≤3s历史实测有效；最新推送时机和会话改造之后无同口径量化复测。')
co('TC-SP-022','执行证据缺项','WS1 CZ','原B轨Redis故障注入没有专门执行记录；用户普通可用性确认不代表其停过Redis。本次保留服务、不执行停机，不要求重复业务。')
co('TC-SP-023 TC-SP-024 TC-SP-026 TC-SP-027 TC-SP-028','现行定向证据','WS1 BF BH3 BQ BU','按三重文件校验、失败零落盘、真实图片与各业务附件权限保留专项证据；不把不同上传业务新增上限合并成一个断言。')
co('TC-SP-025','执行证据缺项','UI5 BH3 BQ BU','各真实上传入口/组件已有实测，但本条列出的两类上传组件前端全部格式/扩展名/大小矩阵没有逐项执行对照。')
co('TC-SP-029 TC-SP-030 TC-SP-031 TC-SP-032 TC-SP-033 TC-SP-034 TC-SP-035 TC-SP-036 TC-SP-037 TC-SP-038 TC-SP-039','历史执行证据','A5 D5 WS1 UI5','按BE-ISSUE逐条语义承接已运行的业务/上传/状态/错误契约；覆盖边界以原专项为准，不计为新执行。')
for n in range(17,25): co(f'TC-C3-{n:03}','现行定向证据','BC BH2','协议发出/同意/重复/撤回/并发/布局有原专项与单测；018已由付费生效门禁修正，SIGNED仍TO_SIGN，不执行金融交易。')
for n in range(25,31): co(f'TC-C3-{n:03}','现行定向证据','BD BX','合成凭据的配置角色/密钥/版本/清除/界面已有离线及真实隔离证据；后续显式开关取代保存必停用，不读取或重新测试用户真实凭据。')
# Remaining supplemental definitions have explicit B3/slot-grid execution.
for x in catalog:
    cid=x['id']; domain,num=cid.split('-')[1:]; n=int(num)
    if cid in case_overrides: continue
    if (domain=='C1' and n>=27) or (domain=='C2' and n==21) or (domain=='C4' and n>=23) or (domain=='C5' and n>=15) or (domain=='C6' and n>=14) or (domain=='C8' and n==12) or (domain=='C9' and n==12) or (domain=='C10' and n>=23):
        co(cid,'历史执行证据','B3 D5 WS1','第三批逐项/日志任务专项已执行；只引用其实际断言，不累计成最新全量覆盖。')
    elif domain=='C7' and n>=16:
        co(cid,'历史执行证据','A5 B3','r3_slotgrid的栅格/容量/唯一约束专项已纳入第五轮；后续容量与日程规则的适用性另见39专项评估。')
    elif cid=='TC-C2-005': co(cid,'人工基础确认','UI5 CM','旧接口脚本未包含此页面入口；用户业务确认及既有UI证据保留，不伪造注册开关所有浏览器组合。')
    else: co(cid,'历史执行证据','A5','对应功能域的历史执行及缺陷修复复验保留；最新源码没有逐TC整套重放，本项不是新增测试。')
def expanded_ids(value):
    ids=set(re.findall(r'TC-[A-Z0-9]+-\d{3}',value))
    for m in re.finditer(r'TC-([A-Z0-9]+)-(\d{3})[~～](?:TC-[A-Z0-9]+-)?(\d{3})',value):
        ids.update(f'TC-{m[1]}-{n:03}' for n in range(int(m[2]),int(m[3])+1))
    return sorted(ids)
extra_rtm={
'R59':('TC-C12-011 TC-C12-018 TC-C12-021','BF BL','带看分配/本人完成/群入口有专项；不把看房简写7例当整域全量。'),
'R60':('TC-C7-017 TC-C7-019 TC-C7-024 TC-C12-008 TC-C12-009','BF BL','连续时段/上限由原增量专项承接，现有TC部分覆盖，不宣称单条TC囊括全部组合。'),
'R61':('TC-C3-016 '+' '.join(f'TC-C3-{n:03}' for n in range(25,31)),'BD BX CE CZ','配置子集与用户普通RENT成功分开；不回填微信/退款/迟到异常。'),
'R62':('TC-C1-023 TC-C1-028 TC-C12-001 TC-C12-004','BG BH2 BP','结构/批量挂牌/占用/范围有真实增量证据，引用为等价范围。'),
'R63':('TC-C3-016 TC-C12-021','BF BX CP','看房/租约流程含会话子集；直通/三类群/身份隔离另以08及组件/真实专项承接，04缺独立完整R63编号，不伪称两TC已穷尽。'),
'R64':(' '.join(f'TC-C3-{n:03}' for n in range(17,25)),'BC BH2','现行协议一次同意、SIGNED仍待FIRST，不恢复旧承租门槛。'),
'R65':('TC-C4-023','BF BJ BMTEST','候选人员为TC子集，排班/专长/常驻/调度由原R65接口及实际专项承接；04无独立完整排班TC，不伪称候选一例即全覆盖。'),
'R66':('TC-C2-010 TC-C2-020 TC-C3-003 TC-C3-018','BH2 BO CE','审核TO_SIGN，协议与FIRST后激活；主关系/依赖成员分开。'),
'R67':('TC-C3-017 TC-C3-018 TC-C3-019 TC-C3-020 TC-C3-021 TC-C3-022 TC-C3-023 TC-C3-024','BC BH2','协议角色/幂等/竞争/正文确认有原专项。'),
'R68':('TC-C3-021 TC-C12-012','BF BP CP','撤回/取消/终态关群等价子集及后续专项；不恢复旧未关闭群。'),
'R69':('TC-C12-021','BF BP','精确租约/房屋来源预约、参与者及父群保留由BP真实专项承接。'),
'R70':('TC-C3-011 TC-C3-016','BF BMTEST','退租申请/审核/通知/日期/关群由独立13项套件与真实链路承接，现有TC是流程子集。'),
'R71':('TC-C3-016 TC-C3-018','BH2 CE CZ','FIRST成功激活内核与用户FIRST成功分开；金融不重测。'),
'R72':('TC-C3-016','BMTEST CE CZ','流程TC仅承接租约范围，退款专门证据见08§3.57及DepositRefundServiceTest；用户金融接受可用，DEF-073/128限制保留，不标所有退款异常通过。'),
'R73':('TC-C3-016','BF BP CE','详情/时间线/金额/范围已有组件和真实子集，旧未知历史不补造。'),
'R74':('TC-C4-007 TC-C4-020','BF BJ CF CP','工单群创建/派单加入/身份/终审回收有真实回归及基础确认。'),
'R75':('TC-C1-017 TC-SP-023 TC-SP-025','BH3 BQ','资源图上传/预览/清空有真实专项；前端通用异常矩阵仍按SP025限制。')}
rtm_rows=[]
for r in rtm:
    rid=r['id']; ids=expanded_ids(r['testCase']); keys=[];note='按关联TC逐项处置读取，不由历史总数或当前可用性推导全部步骤通过。'
    if rid in extra_rtm:
        idtext,keytext,note=extra_rtm[rid];ids=idtext.split();keys=keytext.split()
    if rid in ['N1','N2','N3','N5','R33','R44','R45','R55']:
        note='历史专项和后续定向有效；最终负载/时延或安全全集100%证明范围见39，不能继承为最新量化实测。'
    rtm_rows.append({'id':rid,'caseIds':ids,'additionalEvidence':keys,'conclusion':note,'historicalTestCase':r['testCase'],'historicalResult':r['recordedResult']})
assert len(rtm_rows)==85 and all(r['caseIds'] for r in rtm_rows)
case_rows=[]
for x in catalog:
    cid=x['id'];dpath=ROOT/x['path'];definition=loc(dpath,cid)
    result,keys,note=case_overrides[cid]
    req=[r['id'] for r in rtm_rows if cid in r['caseIds']]
    if cid.startswith('TC-C3-') and int(cid.rsplit('-',1)[1])>=17:
        req=['R64','R66','R67','R71'] if int(cid[-3:])<25 else ['R61']
    priority=x['priority'];effective=priority_add.get(int(cid[-3:]),priority) if cid.startswith('TC-C3-') else priority
    case_rows.append({'id':cid,'title':x['title'],'definition':definition,'historicalPriority':priority,'priority':effective,'priorityBasis':'db按核心状态/安全P0、一般管理配置/界面P1补注' if priority=='unspecified' else '原04定义','requirements':req,'disposition':result,'evidence':keys,'scriptMentions':scripts.get(cid,[]),'conclusion':note,'newExecution':False})
# Read every full historical defect row, including the six full rows outside section 2.
defdoc=BASE/'08_缺陷跟踪与回归.md';dlines=read(defdoc).splitlines();records={}
for i,l in enumerate(dlines,1):
    if not re.match(r'^\| DEF-\d+ \|',l): continue
    c=cells(l)
    if len(c)!=8: continue
    records.setdefault(c[0],{'id':c[0],'registration':{'path':defdoc.relative_to(ROOT).as_posix(),'line':i},'title':clean(c[3]).split('：',1)[0][:105],'originalSeverity':c[4],'historicalState':c[5],'historicalFix':c[6],'historicalRegression':c[7]})
# Missing prose-only and later concise registrations: preserve their lack of historical severity.
late={64:('浏览计数只读DB而滞后','一般'),65:('可约时段跨午夜死循环/OOM','致命'),155:('周日=0导致看房时段无法保存','一般'),156:('取消看房后群未关闭','一般'),168:('协议意愿重复采集及生效边界','严重'),189:('社区单删缺真实影响和确认保护','严重'),190:('主关系成员及整户迁出确认不清','一般'),191:('挂牌/占用状态混淆','一般'),192:('会话有效期与36h裁决不一致','一般'),193:('本人租约会话私有来源预约被拒','一般'),194:('SIGNED未付仍显示待签约','一般'),195:('取消子看房误关父租约群','严重'),196:('新社区缺服务类别和空态','一般'),197:('公告示例图片和新建残留','一般'),198:('在线无续期及迟到401误清新会话','一般'),199:('聊天图片未形成共享消息','一般'),200:('匿名反馈泄露姓名及账号ID','严重'),201:('资源时段额度分子/分母不同源','一般'),202:('支付配置保存误停用/配置误报','一般'),203:('群聊已读未持久化和自发消息误计','一般'),204:('房屋相册/合同子表权限注入导致500','严重'),205:('普通租金收银台INVALID_PARAMETER','一般'),206:('单套挂牌缺看房时段配置','一般'),207:('红点已更新而缓存列表陈旧','一般'),208:('缓存支付表单过期仍可重开','严重'),209:('铃铛未读漏列/混入已读','一般'),210:('首页横向溢出','一般'),211:('公告ALL单值无效范围控件','一般'),212:('直接派单漏建群与会话筛选缺失','严重')}
for n,(title,sev) in late.items():
    cid=f'DEF-{n:03}'
    if cid in records: continue
    candidates=[(i,l) for i,l in enumerate(dlines,1) if cid in l and (l.startswith('- **'+cid) or l.startswith('### ') or l.startswith('| **'+cid) or l.startswith('| '+cid))]
    if not candidates: candidates=[(i,l) for i,l in enumerate(dlines,1) if cid in l and (l.startswith('| '+cid) or l.startswith('### '))]
    assert candidates,cid
    records[cid]={'id':cid,'registration':{'path':defdoc.relative_to(ROOT).as_posix(),'line':candidates[0][0]},'title':title,'originalSeverity':None,'historicalState':'见专项原登记，未单列统一状态','historicalFix':None,'historicalRegression':None}
assert set(records)=={f'DEF-{i:03}' for i in range(1,213)}, sorted(set(range(1,213))-{int(i[4:]) for i in records})
# Intentional explicit dispositions. Other old closed records retain their recorded regression scope.
overrides={}
def do(nums,result,keys,conclusion,layer='原专项所列层次'):
    for n in nums: overrides[n]=(result,keys.split(),conclusion,layer)
do([16],'用户弃置','D5','用户2026-09-11明确不实施独立重发；不是修复通过。','用户裁决')
do([18,41],'修复回归关闭','D5 UI5','原延期后已由后端树过滤正式修复，双向显隐回归通过；不继续延期。')
do([21],'拒绝','D5','原时区误判，非产品缺陷；按原用户采纳的拒绝保留，不算修复成功。','测试口径裁决')
do([51,53],'修复回归关闭','BF BI','日期窗口/过去时段/7天边界有原单测及后续真实预约回归。')
do([52,54,55,56,57,58,59,63],'人工确认收口','BF BK CM CY','历史UI实现/定向观察与用户此前业务完成确认承接；不扩写为各筛选/动画/视口分支全部自动实测。','界面证据及用户基础确认')
do([60,61,62,64,65],'修复回归关闭','BF BL','分别按原修复专项及后续会话消息、60分钟切片、重叠/首尾相接真实回归承接；DEF-065 C7同型循环观察不冒称已有真实OOM压力复现。')
do([67,75,98],'修复回归关闭','BF BI BX CF','通知/会话身份空间、同ID双向不可见及未参与者拒绝已有真实隔离回归；原主表待批次验收已过时。')
do([69,93],'人工确认收口','BH2 CE CZ','支付门禁/入口与用户FIRST/普通RENT成功承接；不补造所有重复下单/期限/渠道异常实测。','离线门禁与用户主路径')
do([68],'修复回归关闭','RESUME BMTEST CFTEST CV CE','首期组件明确覆盖FIRST+PENDING重开续接、SUCCESS回查和5904兜底；PaymentServiceTest覆盖PENDING重用。对应既有组件/单元套件已执行，用户当前付款可用另记；不称真实中断支付或微信异常重验。','已执行组件/单元回归')
do([70,71,72,94,97],'修复回归关闭','BF BJ CF','工单受理/派单/处理/确认/终审/群生命周期有后续真实回归，旧待回归不保留为当前阻塞。')
do([73],'用户暂缓','CE CZ','原用户2026-09-22明确微信未支持先不管；金融本轮接受可用但不证明微信二维码异常修复，保留一般遗留，不追加金融测试。','用户范围裁决')
do([74,100,101,102],'文档或测试资产修正','BF BM','错误码文档/随机测试顺序/工单收件矩阵已按对应原记录修正；不是产品修复通过数量。')
do([87,90,91,92],'修复回归关闭','BF BH2 BP CP','管理端直通/申请确定关联/详情错误态/真实群入口已有定向实测与后续会话基础确认；不恢复旧目录路径。')
do([88,89,111],'批准设计替代','BC BH2 CE','旧独立承租确认、复用旧承租时间、TO_SIGN一律禁付均被后续协议去重及首期激活设计取代；按现行规则留证，不按旧预期判失败。')
do([95,96],'修复回归关闭','BH2 BI','退款表权限白名单及deposit_status回填已有原真实验证；后续内核/权限单测承接，不等同新渠道退款实测。')
do([103,104,105,106,107,108,110,115,116,117,118,119,127,130],'工程回归有据，专项待评审','BMTEST CFTEST CV3','逐缺陷对应测试类及历史已执行套件见testBindings；锁/条件更新/幂等/归属/日期均按测试实际模拟层次读取。未把Mock竞争断言称为真实数据库并发或渠道交易。','已执行单元/组件套件；实际并发另看专项')
do([109,113,114,120,125,126,131,132],'文档或测试资产修正','BMTEST BH2','存量/种子/图/前端模板或测试后端资产按原修订保留；迁移实际执行与当前V43来源分开。历史正常数据不重置，不把静态修订算业务执行通过。')
do([112,121,122,123,124,129],'工程回归有据，专项待评审','CFTEST CV','对应前端组件/导航/并发防重测试已有全量运行记录，精确文件见testBindings。退款组件仅合成状态，不触发渠道。','已执行前端组件/导航测试')
do([128],'金融异常证据保留','CE CZ','微信退款null状态和金额转换修复有原记录，但未找到对应逐项已执行专项结果；用户接受金融可用不扩写成微信退款异常通过。','修复记录；无该异常渠道实测结论')
do([133,135,136],'修复回归关闭','BG BO','批准设计后结构边界/责任人/户型完整落地且真实隔离回归；旧手填联系人和roomCount模型不恢复。')
do([134,137,147,158],'按现行设计终结','BE BF BO','分别为仅超管新建、既有删除入口、非ACTIVE不可再次迁出、注册居民预约方案B；可操作性/体验观察交60，不开越权或非法流转入口。','设计核对及原用户裁决')
do([138],'修复回归关闭','BH3','真实上传/清空/图片校验已替代URL手填，后续用户基础确认有效。')
do([139,140,146,154,161,169,170,171,172,173],'修复回归关闭','BE BF','原现场与后续真实隔离HTTP/日志/分页/冻结/迁出/布局证据逐项承接；DEF-139安全白名单补齐而历史原始请求不补造。')
do([141,143],'修复回归关闭','BH1 BO','账号资料与具体居住关系录入真实隔离通过；依赖成员后续按主关系规则。')
do([142,145],'修复回归关闭','BK CM','已按用户新CSV/服务类别口径复核，旧现场等待不继续要求复验。')
do([144,148,149],'修复回归关闭','BH2 BP CE','挂牌来源/占用/价格与首期成功激活内核定向回归；交易主路径用户已确认，异常范围独立保留。')
do([150,151,152,153,155,156,157,159,160],'修复回归关闭','BF BJ BP','08§3.33/3.34/3.42对应申请唯一、群、时段、ISO星期、取消只读、工单前态和CLOSED补评真实回归；主表旧待修不作为当前未修。')
do([162,174,175,176],'修复回归关闭','BI','真实公告范围/回执/评价参与者/选填电话回归已通过，跨社区不可见的断言按BI保存。')
do([163,164,165,166,167],'修复回归关闭','BF BC','原独立真实专项/界面几何均有实际回归，后续协议通知与去重按现行规则；退款失败留痕不扩大到全渠道异常。')
do([168],'修复回归关闭','BC BH2','协议一次同意、角色拒绝、幂等、撤回与竞争原专项通过；SIGNED仍待首期，金融不执行。')
do([177,178],'修复回归关闭','BJ','最新被派人员列表/详情权限及768/1024侧栏真实回归。')
do([179,180,181,182],'修复回归关闭','BK BL','重复导入文件、真实状态、错误空态及本人STAFF完成权限均有具体新增测试和实际按钮回归。')
do(range(183,189),'修复回归关闭','BN BO CM','bn原现场真实隔离修复，bo后续模型修正及用户此前回归文档已测完承接；不再保留“用户复测待填”为待办。')
do([189],'批准设计替代','BP BU BX','bp有效关系/资金一律禁止已由用户bu批准的真实影响、名称、版本、双确认取代；不得恢复原硬禁止或恢复用户主动删除资料。')
do(range(190,196),'修复回归关闭','BP BO CM','按bp各项真实API/浏览器与用户业务确认收口；DEF-195取消子看房不关闭父租约群有独立红绿及隔离回归。')
do(range(196,202),'修复回归关闭','BQ CM CP','类别/公告真图/在线续期/共享图片/匿名身份/时段额度逐项专项已执行；36h不是耐久实测。')
do(range(202,205),'修复回归关闭','BX CM CP','显式配置开关、身份空间已读游标与父表权限均有真实API/浏览器/并发定向证据；保存不代表交易。')
do([205],'用户确认主路径','CE CZ','新FIRST/普通RENT成功已确认，当前租金不阻塞；原INVALID_PARAMETER确切根因仍未知，不填“定位并修复”。','用户真实支付成功；无AI渠道复测')
do([206],'修复回归关闭','CA CM','ca单套挂牌默认/自定义时段复用批量解析且同事务；非法配置不留半成品，隔离API与浏览器入口已验，后续用户此前回归确认承接。')
do([207],'修复回归关闭','CA CM','ca的LivePanel/useLiveRefresh在激活/通知/前台恢复与30秒补读，隐藏停止，保留筛选/表单并防旧响应覆盖；真实新申请自动出现/切回更新已验。')
do([208],'工程回归关闭','CE','缓存表单打开前和系统截止相等保护有失败先行/修复后定向19及后端1157记录；不是远端404根因确证。','固定时钟/真实组件测试')
do(range(209,213),'修复回归关闭','CF CP CY','cf/ch实际未读/滚动/首页/范围/三端工单群和筛选通过；CF-02用户确认与后续整册、三端及最新运行确认承接，不要求重复。')
do([105,121],'修复回归关闭','MOVEOUT BF','08§3.62真实通过/驳回两分支，三通知均sourceId=leaseId，管理员及居民消息中心实际落到正确租约。单测只作补充，不仅凭单测关闭。','真实隔离API/浏览器深链及配套单测')
# Explicit regression test bindings, with executed historical suite evidence rather than source-existence-as-pass.
bindings={68:['PaymentServiceTest','LeaseFirstPaymentDialog.spec.ts'],103:['DepositRefundServiceTest'],104:['LeaseMoveOutApplicationServiceTest'],105:['LeaseMoveOutApplicationServiceTest'],106:['ConversationServiceTest'],107:['PaymentServiceTest'],108:['LeaseStateMachineFixTest','LeaseServiceTest'],110:['ViewingConversationDefectFixTest'],112:['MoveOutApplicationView.spec.ts','DepositRefundDialog.spec.ts'],115:['LeaseServiceTest','AgreementConsentBoundaryTest'],116:['WorkOrderR74IntakeTest','WorkOrderServiceTest'],117:['ResidenceApplicationServiceTest','ResidenceApplicationLeaseLinkTest'],118:['ViewingConversationDefectFixTest','ViewingLeaseLinkGuardTest'],119:['ConversationServiceTest','ConversationLeaseGroupEnsureTest'],121:['notificationNav.spec.ts'],122:['ConversationChat.spec.ts','ConversationListView.spec.ts'],123:['LeaseDetailView.spec.ts','DepositRefundDialog.spec.ts'],124:['WorkOrderListView.spec.ts','WorkOrderDetailView.spec.ts'],127:['LeaseMoveOutApplicationServiceTest'],129:['LeaseFirstPaymentDialog.spec.ts'],130:['LeaseMoveOutApplicationServiceTest']}
alltests=list((ROOT/'backend/src/test').rglob('*.java'))+list((ROOT/'frontend/src').rglob('*.spec.ts'))
bm=load(BASE/'evidence/autonomous-closeout-20261013/backend-results.json')['suites']
def_rows=[]
for cid,r in sorted(records.items()):
    n=int(cid[4:]);m=re.search(r'(致命|严重|一般|轻微)',(r['originalSeverity'] or '').split('（',1)[0].split('→')[-1])
    sev=m[1] if m else late[n][1]
    if n in overrides: result,keys,note,layer=overrides[n]
    else:
        result='修复回归关闭';keys=['D5' if n<45 else 'BF'];note='保留原逐缺陷修复/回归结果：'+clean(r['historicalRegression'] or '')
        layer='原登记中的实际测试层次；无新增执行'
    ts=[]
    for name in bindings.get(n,[]):
        fs=[p for p in alltests if p.name==name or p.stem==name]
        assert fs,(cid,name)
        for p in fs:
            suite=next((s for s in bm if s['name'].endswith('.'+p.stem)),None)
            ts.append({**loc(p,cid),'classOrFile':name,'historicalExecutedSuite':suite,'subsequentFullSuiteEvidence':['CFTEST','CV3'] if p.suffix=='.java' else ['CV']})
    r.update({'severity':sev,'severityBasis':'原专项原定级，未降级' if m else 'db补定级：按原登记影响归类，非历史追认；一般展示/局部阻断，严重身份泄露/核心链/数据或截止保护，致命OOM','disposition':result,'evidence':keys,'conclusion':note,'verificationLayer':layer,'testBindings':ts,'newExecution':False})
    def_rows.append(r)
# Identify every later exact mention for human navigation, without deriving a disposition from keywords.
for r in def_rows:
    r['mentionLines']=[i for i,l in enumerate(dlines,1) if r['id'] in re.findall(r'DEF-\d{3}',l)]
summary={'date':'2026-10-19','businessBaseline':'def6f90','backendRuntimeSource':'57f26e9','newBusinessTests':0,'newFinancialCommands':0,'lastRuntimeVerificationDate':'2026-10-18','caseCount':len(case_rows),'casePriority':dict(Counter(r['priority'] for r in case_rows)),'casePriorityAdded':len(priority_add),'caseDisposition':dict(Counter(r['disposition'] for r in case_rows)),'defectCount':len(def_rows),'primaryHistoricalRows':177,'laterRegistrationsAdded':len(def_rows)-177,'defectSeverity':dict(Counter(r['severity'] for r in def_rows)),'defectSeverityAdded':sum(r['originalSeverity'] is None for r in def_rows),'defectDisposition':dict(Counter(r['disposition'] for r in def_rows)),'rates':{},'claimBoundary':'分布是证据归并结果，不是305项在最终源码全量新执行；保存明确缺证，不以汇总单测数代替TC分母。'}
fixed={'修复回归关闭','工程回归关闭'}
for sev in ['致命','严重','一般','轻微']:
    rows=[r for r in def_rows if r['severity']==sev]
    repaired=sum(r['disposition'] in fixed for r in rows)
    retained=sum(r['disposition'] in {'用户暂缓','金融异常证据保留'} for r in rows)
    summary['rates'][sev]={'registered':len(rows),'repairedWithRegression':repaired,'repairRateOfAllRegistrations':round(100*repaired/len(rows),2) if rows else None,'otherDispositions':len(rows)-repaired-retained,'retained':retained,'engineeringEvidenceAwaitingReview':sum(r['disposition']=='工程回归有据，专项待评审' for r in rows),'note':'修复率分母含拒绝/设计替代/资产/人工接受等，故不等于产品缺陷关闭率；原03出口采用全量有效产品缺陷仍需评审。'}
excluded={'用户弃置','拒绝','文档或测试资产修正','批准设计替代','按现行设计终结'}
accepted={'人工确认收口','用户确认主路径'}
for sev in ['致命','严重','一般','轻微']:
    rows=[r for r in def_rows if r['severity']==sev and r['disposition'] not in excluded]
    repaired=sum(r['disposition'] in fixed for r in rows); acc=sum(r['disposition'] in accepted for r in rows)
    summary['rates'][sev]['effectiveProductDenominator']=len(rows)
    summary['rates'][sev]['excludedWithReason']=sum(r['severity']==sev and r['disposition'] in excluded for r in def_rows)
    summary['rates'][sev]['acceptedByUser']=acc
    summary['rates'][sev]['engineeringClosureRate']=round(100*repaired/len(rows),2)
    summary['rates'][sev]['closureIncludingUserAcceptance']=round(100*(repaired+acc)/len(rows),2)
    summary['rates'][sev]['note']='全登记修复比例与有效产品关闭率分别列示；排除项逐条保留理由，用户接受不计为工程修复，工程回归层次不扩大为真实DB/渠道。'
write_json('rtm-ledger.json',rtm_rows);write_json('case-ledger.json',case_rows);write_json('defect-ledger.json',def_rows);write_json('evidence-registry.json',registry);write_json('summary.json',summary)
for name,rows,fields in [('case-ledger.csv',case_rows,['id','title','historicalPriority','priority','disposition','evidence','conclusion']),('defect-ledger.csv',def_rows,['id','title','originalSeverity','severity','disposition','evidence','verificationLayer','conclusion'])]:
    with (OUT/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');w.writeheader()
        for row in rows: w.writerow({k:';'.join(row[k]) if isinstance(row[k],list) else row[k] for k in fields})
def relative_link(path,line=None):
    import os
    target=Path(os.path.relpath(ROOT/path,OUT)).as_posix()
    return target+(f'#L{line}' if line else '')
def footnotes():
    return '\n'.join(f'- <a id="{k}"></a> **{k}**：[{v["name"]}]({relative_link(v["path"])})，第{v["line"]}行起。' for k,v in registry.items())
def keylinks(keys): return '、'.join(f'[{k}](#{k})' for k in keys)
md=['# 305项用例证据逐项台账','', '> 2026-10-19归并。每行均保留原定义、原优先级和现行预期的关系；“现行定向证据”仅表示所列专项覆盖了相应变更范围，不能等同该TC所有步骤在最终源码完整重放。人工基础确认不补造异常/故障/时延执行。','', '| 编号及原定义 | 优先级 | 归并处置 | 执行证据 | 现行预期与限制 |','|---|---|---|---|---|']
for r in case_rows:
    md.append(f'| [{r["id"]}]({relative_link(r["definition"]["path"])}) {clean(r["title"])} | {r["priority"]}'+('（db补注）' if r['historicalPriority']=='unspecified' else '')+f' | {r["disposition"]} | {keylinks(r["evidence"])} | {clean(r["conclusion"])} |')
md+=['','## 证据索引','',footnotes()];(OUT/'case-ledger.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
md=['# DEF-001～212最终处置逐项台账','','> 2026-10-19归并。主表177项之外新增收录35项已有登记，没有新报35个缺陷。29项原无显式严重度，db按原影响补定级，保留来源字段。不把设计替代、用户接受、文档/测试资产修正算作“已修复通过”。工程回归有据而专项待评审的18项不关闭；避免违反08的历史专项不得仅凭单测/构建关闭约束。少数后续明确真实专项已对应的项另行关闭。','','| 编号及原登记 | 严重度 | 最终处置 | 证据 | 依据与边界 |','|---|---|---|---|---|']
for r in def_rows:
    md.append(f'| [{r["id"]}]({relative_link(r["registration"]["path"])}) {clean(r["title"])} | {r["severity"]}'+('（db补定级）' if r['originalSeverity'] is None else '')+f' | {r["disposition"]} | {keylinks(r["evidence"])} | {clean(r["conclusion"])} |')
md+=['','## 工程回归对应的测试文件','','完整类/文件、原已执行套件和后续汇总来源均在JSON的testBindings中；源文件存在本身不算执行证据。']
for r in def_rows:
    if r['testBindings']:
        md+=['',f'- **{r["id"]}**：'+'；'.join(f'[{t["classOrFile"]}]({relative_link(t["path"])})'+(f'（BM套件{t["historicalExecutedSuite"]["tests"]}项、失败/错误/跳过均0）' if t['historicalExecutedSuite'] else '（执行来源按CF/CV全量记录，非本日重跑）') for t in r['testBindings'])]
md+=['','## 证据索引','',footnotes()];(OUT/'defect-ledger.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
