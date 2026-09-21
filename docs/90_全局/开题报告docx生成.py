# -*- coding: utf-8 -*-
"""生成填好的开题报告及任务书：红色=待用户填，蓝色=待导师确认，其余已填"""
import copy, re
from docx import Document
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

SRC = r'D:/code/community-residence-service-platform/docs/90_全局/参考资料/开题报告/开题报告及任务书_样例1（博客网站）.docx'
DRAFT = r'D:/code/community-residence-service-platform/docs/90_全局/开题报告草稿.md'
OUT = r'D:/code/community-residence-service-platform/docs/90_全局/开题报告及任务书_社区居住服务管理系统_已填待补.docx'
RED, BLUE = 'FF0000', '0070C0'


def extract_refs():
    txt = open(DRAFT, encoding='utf-8').read()
    m = re.search(r'\| # \| 文献条目.*?\n\n', txt, re.S)
    rows = [l for l in m.group(0).split('\n') if re.match(r'\| \d+ \|', l)]
    return [l.split('|')[2].strip().replace('**', '') for l in rows]


def set_text(p_el, text, color=None, bold=None):
    rs = p_el.findall(qn('w:r'))
    if rs:
        keep = rs[0]
        for r in rs[1:]:
            p_el.remove(r)
    else:
        keep = OxmlElement('w:r')
        p_el.append(keep)
    for t in keep.findall(qn('w:t')):
        keep.remove(t)
    t = OxmlElement('w:t')
    t.set(qn('xml:space'), 'preserve')
    t.text = text
    keep.append(t)
    rpr = keep.find(qn('w:rPr'))
    if rpr is None:
        rpr = OxmlElement('w:rPr')
        keep.insert(0, rpr)
    for tag in ('w:color', 'w:b'):
        old = rpr.find(qn(tag))
        if old is not None:
            rpr.remove(old)
    if color:
        c = OxmlElement('w:color')
        c.set(qn('w:val'), color)
        rpr.append(c)
    if bold is not None:
        b = OxmlElement('w:b')
        if not bold:
            b.set(qn('w:val'), '0')
        rpr.append(b)


def clear_cell(cell):
    ps = cell.paragraphs
    pt = copy.deepcopy(ps[0]._element) if ps else None
    pb = copy.deepcopy(ps[1]._element) if len(ps) > 1 else copy.deepcopy(pt)
    for p in list(cell._tc.findall(qn('w:p'))):
        cell._tc.remove(p)
    return pt, pb


def fill_cell(cell, lines):
    pt, pb = clear_cell(cell)
    for text, kind in lines:
        p = copy.deepcopy(pt if kind in ('title', 'bold') else pb)
        color = RED if kind.startswith('red') else (BLUE if kind.startswith('blue') else None)
        bold = True if kind in ('title', 'bold') else None
        set_text(p, text, color=color, bold=bold)
        cell._tc.append(p)


def row_index(tbl, key):
    for i, tr in enumerate(tbl.findall(qn('w:tr'))):
        txt = ''.join(t.text or '' for t in tr.iter(qn('w:t')))
        if key in txt:
            return i
    return -1


refs = extract_refs()
doc = Document(SRC)
t = doc.tables[0]
tbl = t._tbl

# ---------- 表头段落：专业班级 ----------
for p in doc.paragraphs:
    if '专业班级' in p.text:
        pre = p.text.split('专业班级')[0] + '专业班级：'
        set_text(p._element, pre)
        r = OxmlElement('w:r')
        rpr = OxmlElement('w:rPr')
        c = OxmlElement('w:color')
        c.set(qn('w:val'), RED)
        rpr.append(c)
        r.append(rpr)
        tt = OxmlElement('w:t')
        tt.set(qn('xml:space'), 'preserve')
        tt.text = '【待填：专业班级（须与学籍/维普系统一致）】'
        r.append(tt)
        p._element.append(r)
        break

# ---------- 表头字段 ----------
fill_cell(t.rows[0].cells[1], [('【待填：学生姓名】', 'red')])
fill_cell(t.rows[0].cells[3], [('【待填：学号】', 'red')])
fill_cell(t.rows[1].cells[1], [('【待填：指导教师姓名】', 'red')])
fill_cell(t.rows[2].cells[1], [('基于 Spring Boot 的社区居住服务管理系统设计与实现', 'title')])

# ---------- 插入「企业教师」行 ----------
i_teacher = row_index(tbl, '指导教师')
tr_teacher = tbl.findall(qn('w:tr'))[i_teacher]
new1 = copy.deepcopy(tr_teacher)
tr_teacher.addnext(new1)
c = new1.findall(qn('w:tc'))
set_text(c[0].findall(qn('w:p'))[0], '企业教师')
set_text(c[1].findall(qn('w:p'))[0], '【待填：企业教师姓名；如无，待与导师确认后填「无」】', color=RED)

# ---------- 插入「论文课题是否在以下过程中完成」行 ----------
i_type = row_index(tbl, '选题类型')
tr_type = tbl.findall(qn('w:tr'))[i_type]
new2 = copy.deepcopy(tr_teacher)
tr_type.addnext(new2)
c2 = new2.findall(qn('w:tc'))
set_text(c2[0].findall(qn('w:p'))[0], '论文课题是否在以下过程中完成')
ps = c2[1].findall(qn('w:p'))
for p in ps[1:]:
    c2[1].remove(p)
# 复选框字符必须用 Wingdings 2 字体，否则 Word 中显示为缺字方框
for rr in ps[0].findall(qn('w:r')):
    ps[0].remove(rr)
segs = [('\uf0a3', 'Wingdings 2'), ('工程实践    ', None),
        ('\uf0a3', 'Wingdings 2'), ('实验、实习    ', None),
        ('\uf0a3', 'Wingdings 2'), ('社会调查    ', None),
        ('\uf0a3', 'Wingdings 2'), ('理论研究    ', None),
        ('\uf0a3', 'Wingdings 2'), ('否', None),
        ('　【待与导师确认后勾选；本项目建议勾「工程实践」】', None, BLUE)]
for seg in segs:
    text, font = seg[0], seg[1]
    color = seg[2] if len(seg) > 2 else None
    r = OxmlElement('w:r')
    rpr = OxmlElement('w:rPr')
    if font:
        rf = OxmlElement('w:rFonts')
        rf.set(qn('w:ascii'), font)
        rf.set(qn('w:hAnsi'), font)
        rf.set(qn('w:eastAsia'), font)
        rpr.append(rf)
    sz = OxmlElement('w:sz')
    sz.set(qn('w:val'), '24')
    rpr.append(sz)
    if color:
        cc = OxmlElement('w:color')
        cc.set(qn('w:val'), color)
        rpr.append(cc)
    r.append(rpr)
    tt = OxmlElement('w:t')
    tt.set(qn('xml:space'), 'preserve')
    tt.text = text
    r.append(tt)
    ps[0].append(r)

# ---------- 一、选题的目的和意义 ----------
SEL = ('城市居住社区类型多样，商品房小区、长租公寓、公共租赁住房、单位与园区宿舍等均由相应的物业企业、'
       '运营单位或后勤部门实施管理。目前多数社区的管理方仍以纸质台账、电子表格、电话和微信群等分散方式'
       '记录房屋信息、居住人员、报修申请和公共资源使用情况，存在信息更新滞后、居住关系不清晰、服务处理'
       '过程不可追溯、资源预约冲突频发、服务质量缺乏数据支撑等问题。本课题旨在设计并实现一个面向社区'
       '管理者的社区居住服务管理系统，基于 Spring Boot 与 Vue 前后端分离架构，将社区房屋、居民居住关系、'
       '服务工单、公告反馈、公共资源预约及运营统计纳入统一管理，以规范社区日常服务流程、提高管理效率，'
       '并通过完整的设计与实现过程锻炼综合运用软件工程方法解决实际问题的能力。')
MEAN = ('本课题的实践意义在于：一是将分散的社区管理信息集中化、结构化，通过入住审核、搬出登记和租期'
        '维护保证居住关系数据准确可查；二是将报修、隐患上报等事项统一为工单流程，处理全程留痕，居民可'
        '实时查看进度，管理方可以量化考核服务质量；三是通过公共资源预约的时间冲突校验避免资源重复占用；'
        '四是通过统计报表为管理方提供运营决策依据。本课题的理论与训练意义在于：完整实践需求分析、系统'
        '设计、数据库设计、前后端编码、系统测试的软件工程全流程，掌握 Spring Boot、Vue 3、MySQL、Redis '
        '等主流开发技术，提高独立分析问题与解决问题的能力。')
fill_cell(t.rows[row_index(tbl, '选题的目的')].cells[0], [
    ('一、选题的目的和意义', 'title'),
    ('1.选题目的', 'bold'), (SEL, 'body'),
    ('2.选题意义', 'bold'), (MEAN, 'body'),
])

# ---------- 二、主要研究内容 ----------
GOAL = ('面向具有明确服务管理方的居住社区的管理者，实现支持单/多社区管理的 Web 系统：支持多社区及其'
        '楼栋、单元、房屋层级信息管理；支持居民档案与居住关系（产权人、承租人、共同居住人等）的审核式'
        '管理，注册方式（管理员代建/自助注册+入住审核）由后台配置；支持入住、搬出与租期全过程登记；'
        '报修类事项走工单渠道全过程异步跟踪，意见建议投诉类事项走反馈渠道并以会话形式实时沟通；支持公告'
        '定向广播（含已读统计）与消息通知中心（服务端长连接实时推送，短信/微信服务号渠道可选）；支持公共'
        '资源预约与时间冲突校验；面向潜在租户/购房者提供房源信息展示与看房预约；提供服务评价与运营统计。'
        '系统设置游客、居民、服务人员、社区管理员、超级管理员五类角色，权限分全局级（仅超级管理员）与'
        '社区级（社区管理员限所绑定社区）两层，关键操作留操作日志。')
ANA = ('通过调研社区管理业务，抽象出五类角色与核心业务用例：游客（浏览公告与房源、预约看房、获取联系'
       '入口）；居民（查看居住信息与公告、提交服务申请与反馈、预约资源、参与反馈会话、确认与评价服务）；'
       '服务人员（接收处理工单、反馈进度、上传图片与文字处理结果）；社区管理员（所绑定社区的基础信息'
       '维护、居住关系审核、工单派发、公告广播与反馈会话、资源配置、房源维护、通知渠道管理、本社区统计、'
       '居民账号管理）；超级管理员（社区新建与停用、全局配置、社区管理员账号创建与社区绑定、全局统计与'
       '操作日志）。业务渠道划分为双渠道：报修类事项走工单（异步流转），答复类事项走反馈（会话式实时'
       '沟通）。对租住、工单、公共资源预约、看房预约四类业务分别建立状态模型，明确状态全集与合法流转；'
       '系统边界明确排除在线支付、房产交易环节、智能硬件接入等非管理核心功能。')
DES = ('系统采用前后端分离的单体架构：前端使用 Vue 3.5（TypeScript）构建游客端、居民端、服务人员工作台'
       '与管理员后台，基于 Vite 构建、Pinia 管理状态、Axios 调用接口、ECharts 绘制统计图表；后端使用 '
       'Spring Boot 4.0.6 提供 RESTful API，集成 Spring Security 与 JWT（jjwt）实现认证与基于角色的授权，'
       '多社区数据由 MyBatis-Plus 数据权限拦截器按社区维度统一过滤；实时通信采用 WebSocket（SockJS + '
       'STOMP）承载反馈会话与消息通知推送，推送失败时由 HTTP 轮询增量补拉兜底；数据持久层采用 MySQL 9.x '
       '存储业务数据，Redis 7 用于缓存与分布式锁（Redisson），ORM 采用 MyBatis-Plus 3.5.16；接口契约由 '
       'Springdoc 生成，作为前后端联调的唯一依据。数据库设计覆盖社区、楼栋、单元、房屋、居民、居住关系、'
       '租住记录、服务类别、工单及处理记录、公告及查看记录、反馈会话、公共资源、预约、房源、看房预约、'
       '评价、通知与操作日志等核心表，绘制 E-R 图并遵循第三范式；对租住、工单、公共资源预约、看房预约'
       '四类业务设计状态机流转约束。')
IMP = ('按「用户认证与权限 → 社区基础信息 → 居住关系与租住 → 工单 → 公告与反馈会话 → 公共资源预约 → '
       '房源与看房预约 → 消息通知中心 → 评价与统计」的顺序增量实现，并配套定时任务（租期状态判定、租期'
       '到期提醒、公告自动下线、浏览统计回写）。')
TEST = ('从需求条目逐条推导测试用例，开展功能测试、权限测试与异常流程测试；对公共资源与看房预约的时间'
        '冲突校验设计专项用例集（重叠预约拦截率要求 100%）；对五类角色越权访问与多社区数据越权设计否定'
        '用例（越权请求 100% 拒绝并记录）；对长连接通知设计「离线期间产生通知→上线补拉可见」专项用例；'
        '输出测试报告作为验收依据。')
KEYS = [
    '（1）基于 JWT 的无状态登录与接口级权限校验，配合功能权限与多社区数据权限两层控制；',
    '（2）基于 Redis 与 Redisson 的分布式锁，用于预约并发控制与定时任务防重入；',
    '（3）公共资源与看房预约的时间冲突检测（复用同一校验逻辑，重叠预约 100% 拦截）；',
    '（4）租期到期状态的日期自动判定与到期提醒（去重表保证同一租约不重复提醒）；',
    '（5）WebSocket（SockJS + STOMP）实时会话与通知推送，结合增量游标补拉保证离线消息不丢失；',
    '（6）基于 MyBatis-Plus 数据权限拦截器实现社区级数据隔离；',
    '（7）操作日志切面、附件上传（图片/文件）与 ECharts 统计可视化。',
]
METHOD = ('采用文献调查法、原型法与软件工程规范化方法相结合：通过查阅国内外文献与主流开源项目，明确'
          '前后端分离架构下管理信息系统的通用设计模式与技术选型；按「需求分析 → 系统设计 → 编码实现 → '
          '测试验证」的主线增量推进，每一阶段产出对应文档（需求规格、设计文档、测试报告），保证开发过程'
          '可追溯。技术路线为：Java 17 + Spring Boot 4.0.6（Spring MVC / Spring Security / MyBatis-Plus / '
          'JWT / Redis / Redisson），Vue 3.5 + TypeScript + Vite + Pinia + Axios + ECharts 前端，MySQL 9.x '
          '数据库，RESTful API 前后端交互，Maven 管理依赖。')
OUTLINE = ['1 绪论', '　1.1 研究背景及意义', '　1.2 国内外发展现状', '　1.3 研究目的和方法',
           '2 相关技术介绍', '　2.1 Spring Boot 框架', '　2.2 Vue 3 框架', '　2.3 MySQL 数据库与 Redis',
           '　2.4 系统开发运行环境', '3 系统分析', '　3.1 任务概述', '　3.2 可行性分析（技术/经济/运行）',
           '　3.3 功能需求分析（游客/居民/服务人员/社区管理员/超级管理员）', '　3.4 非功能需求分析',
           '4 系统详细设计', '　4.1 系统架构与功能模块设计',
           '　4.2 核心业务状态机设计（工单/租住/预约/看房预约）',
           '　4.3 数据库设计（设计原则/E-R 图/数据表设计）', '5 系统实现',
           '　5.1 用户认证与权限模块实现', '　5.2 社区基础信息与居住关系模块实现',
           '　5.3 工单与公告反馈会话模块实现', '　5.4 公共资源预约与房源看房模块实现',
           '　5.5 消息通知与统计模块实现', '6 系统测试', '　6.1 测试目的与方法', '　6.2 功能测试（按模块）',
           '　6.3 权限与冲突校验专项测试', '　6.4 测试结论', '7 总结与展望']
lines2 = [('二、主要研究内容（设计方案或论文撰写提纲）', 'title'),
          ('（一）设计目标', 'bold'), (GOAL, 'body'),
          ('（二）设计方案', 'bold'),
          ('1.系统分析', 'bold'), (ANA, 'body'),
          ('2.系统设计', 'bold'), (DES, 'body'),
          ('3.系统实现', 'bold'), (IMP, 'body'),
          ('4.系统测试', 'bold'), (TEST, 'body'),
          ('（三）关键技术', 'bold')] + [(k, 'body') for k in KEYS] + \
         [('（四）拟采取的研究方法、技术路线', 'bold'), (METHOD, 'body'),
          ('（五）论文撰写提纲', 'bold')] + [(o, 'body') for o in OUTLINE]
fill_cell(t.rows[row_index(tbl, '主要研究内容')].cells[0], lines2)

# ---------- 三、总体进度安排 ----------
fill_cell(t.rows[row_index(tbl, '总体进度安排')].cells[0], [
    ('三、总体进度安排（选题、开题、一稿、二稿、三稿、定稿等）', 'title'),
    ('【待与导师确认：以下日期按学校 2027 届通知节点拟定】', 'blue'),
    ('2026年06月23日 至 2026年08月31日　　选题', 'body'),
    ('2026年09月01日 至 2026年09月15日　　开题', 'body'),
    ('2026年09月16日 至 2026年10月15日　　一稿', 'body'),
    ('2026年10月16日 至 2026年10月31日　　二稿', 'body'),
    ('2026年11月01日 至 2026年11月30日　　三稿', 'body'),
    ('2026年12月01日 至 2026年12月30日　　定稿', 'body'),
])

# ---------- 四、选题研究准备情况 ----------
HAVE = ('（1）已修完 Java 程序设计、数据库原理、软件工程、Web 开发等课程，掌握 Spring Boot 与 Vue 的'
        '基础开发能力，独立完成过课程级前后端分离项目；（2）已确定开发技术栈并完成版本调研与验证'
        '（Java 17 / Spring Boot 4.0.6 / MyBatis-Plus 3.5.16 / MySQL 9.x / Redis 7 / Redisson / JWT / '
        'Vue 3.5 / TypeScript / Vite / ECharts）；（3）开发机器具备 JDK 17、Maven、Node.js、MySQL、'
        'Redis 等完整运行环境；（4）已建立项目文档体系并完成系统定义（目标用户、核心业务功能与验收判据、'
        '非目标边界），系统边界清晰；（5）已完成需求分析与系统设计文档的整理，具备开展后续开发与论文'
        '撰写的条件。')
LACK = ('（1）对社区管理业务的实际流程了解主要来自调研资料与文献，尚缺少一线管理方的直接经验；'
        '（2）外文文献检索渠道有限，国外相关研究与技术资料的搜集不够充分；（3）对 Spring Security '
        '细粒度权限控制与预约冲突并发校验的工程实践尚不深入。')
SOLVE = ('（1）向指导教师请教并参考开源物业管理系统与相关文献，进一步校准业务模型；（2）利用学校图书馆'
         '数据库与文献传递服务补充外文文献；（3）通过官方文档、开源案例与在线课程学习，并在系统测试阶段'
         '以专项用例集验证权限与并发校验的正确性。')
fill_cell(t.rows[row_index(tbl, '选题研究准备情况')].cells[0],
          [('四、选题研究准备情况（已查阅主要参考文献，完成任务所具备的条件等）', 'title'),
           (f'（一）主要参考文献（共 {len(refs)} 篇，其中外文 4 篇，近五年文献 {len(refs)} 篇）', 'bold')] +
          [(f'[{i + 1}] {r}', 'body') for i, r in enumerate(refs)] +
          [('（二）已具备的研究条件', 'bold'), (HAVE, 'body'),
           ('（三）尚缺少的研究条件', 'bold'), (LACK, 'body'),
           ('（四）拟解决的途径', 'bold'), (SOLVE, 'body')])

# ---------- 五、指导教师意见 / 六、学院负责人审核意见 ----------
fill_cell(t.rows[row_index(tbl, '指导教师意见')].cells[0],
          [('五、指导教师意见', 'title'),
           ('【待指导教师填写意见并签名】', 'blue'), ('　', 'body'),
           ('指导教师（签名）：', 'body'), ('　　　　年　　月　　日', 'body')])
fill_cell(t.rows[row_index(tbl, '学院负责人审核意见')].cells[0],
          [('六、学院负责人审核意见（是否同意开题）', 'title'),
           ('【待学院负责人填写审核意见并签名】', 'blue'), ('　', 'body'),
           ('学院负责人（签名）：', 'body'), ('　　　　年　　月　　日', 'body')])

doc.save(OUT)

# 清理模板携带的文档属性（样例制作者的姓名等，避免残留他人信息）
doc2 = Document(OUT)
cp = doc2.core_properties
cp.author = ''
cp.last_modified_by = ''
cp.comments = ''
cp.category = ''
cp.subject = ''
cp.title = '本科毕业设计（论文）开题报告及任务书'
doc2.save(OUT)

print('生成完成：', OUT)
print('参考文献条数：', len(refs))
