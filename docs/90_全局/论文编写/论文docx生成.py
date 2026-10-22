# -*- coding: utf-8 -*-
"""论文初稿 docx 生成器

输入：论文初稿.md（内容稿）
输出：论文初稿_小区居住服务管理系统.docx

格式依据：2027届本科毕业论文（设计）格式模版（标注版）的批注规范
"""
import re, json, os
from docx import Document
from docx.shared import Pt, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

BASE = 'D:/code/community-residence-service-platform/docs/90_全局/论文编写'
MD = BASE + '/论文初稿.md'
OUT = BASE + '/论文初稿_小区居住服务管理系统.docx'
TITLE = '基于 Spring Boot 的小区居住服务管理系统设计与实现'

# ---------------- 基础工具 ----------------
def set_run(run, cn='宋体', en='Times New Roman', size=12, bold=False, color=None):
    run.font.size = Pt(size)
    run.bold = bold
    if color:
        run.font.color.rgb = color
    rPr = run._element.get_or_add_rPr()
    rFonts = rPr.find(qn('w:rFonts'))
    if rFonts is None:
        rFonts = OxmlElement('w:rFonts')
        rPr.insert(0, rFonts)
    rFonts.set(qn('w:ascii'), en)
    rFonts.set(qn('w:hAnsi'), en)
    rFonts.set(qn('w:eastAsia'), cn)


def set_ind(p, first_chars=0, left_chars=0):
    pPr = p._p.get_or_add_pPr()
    ind = pPr.find(qn('w:ind'))
    if ind is None:
        ind = OxmlElement('w:ind')
        pPr.append(ind)
    if first_chars:
        ind.set(qn('w:firstLineChars'), str(int(first_chars * 100)))
        ind.set(qn('w:firstLine'), str(int(first_chars * 240)))
    if left_chars:
        ind.set(qn('w:leftChars'), str(int(left_chars * 100)))
        ind.set(qn('w:left'), str(int(left_chars * 240)))


def set_spacing(p, line_pt=22, before=0, after=0, exact=True):
    pPr = p._p.get_or_add_pPr()
    sp = pPr.find(qn('w:spacing'))
    if sp is None:
        sp = OxmlElement('w:spacing')
        pPr.append(sp)
    sp.set(qn('w:line'), str(int(line_pt * 20)))
    sp.set(qn('w:lineRule'), 'exact' if exact else 'auto')
    sp.set(qn('w:before'), str(int(before * 20)))
    sp.set(qn('w:after'), str(int(after * 20)))


def set_page_break_before(p):
    pPr = p._p.get_or_add_pPr()
    if pPr.find(qn('w:pageBreakBefore')) is None:
        pPr.append(OxmlElement('w:pageBreakBefore'))


def set_align(p, align='justify'):
    pPr = p._p.get_or_add_pPr()
    jc = pPr.find(qn('w:jc'))
    if jc is None:
        jc = OxmlElement('w:jc')
        pPr.append(jc)
    jc.set(qn('w:val'), align)


def set_bottom_border(p, sz=4):
    """页眉横线：黑色单横线。"""
    pPr = p._p.get_or_add_pPr()
    pBdr = pPr.find(qn('w:pBdr'))
    if pBdr is None:
        pBdr = OxmlElement('w:pBdr')
        pPr.append(pBdr)
    bottom = pBdr.find(qn('w:bottom'))
    if bottom is None:
        bottom = OxmlElement('w:bottom')
        pBdr.append(bottom)
    bottom.set(qn('w:val'), 'single')
    bottom.set(qn('w:color'), 'auto')
    bottom.set(qn('w:sz'), str(sz))
    bottom.set(qn('w:space'), '1')


def add_page_field(p, instr='PAGE \\* MERGEFORMAT', size=10.5):
    """在段落中插入 PAGE 域。"""
    p.text = ''
    set_align(p, 'center')
    set_spacing(p, 22, 0, 0)
    r = p.add_run()
    f1 = OxmlElement('w:fldChar'); f1.set(qn('w:fldCharType'), 'begin')
    it = OxmlElement('w:instrText'); it.set(qn('xml:space'), 'preserve'); it.text = instr
    f2 = OxmlElement('w:fldChar'); f2.set(qn('w:fldCharType'), 'separate')
    tt = OxmlElement('w:t'); tt.text = '1'
    f3 = OxmlElement('w:fldChar'); f3.set(qn('w:fldCharType'), 'end')
    r._r.append(f1); r._r.append(it); r._r.append(f2); r._r.append(tt); r._r.append(f3)
    set_run(r, '宋体', 'Times New Roman', size)


def set_header_paragraph(p, text, border=True):
    p.text = ''
    set_align(p, 'center')
    set_spacing(p, 22, 0, 0)
    if text:
        add_text(p, text, '宋体', 'Times New Roman', 12)
    if border:
        set_bottom_border(p)


def clear_header(section):
    for part in (section.header, section.even_page_header):
        part.is_linked_to_previous = False
        for p in part.paragraphs:
            p.text = ''


def set_body_header(section, chapter_title):
    clear_header(section)
    set_header_paragraph(section.header.paragraphs[0], TITLE, True)
    set_header_paragraph(section.even_page_header.paragraphs[0], chapter_title, True)
    f = section.footer
    f.is_linked_to_previous = False
    add_page_field(f.paragraphs[0], 'PAGE \\* MERGEFORMAT', 10.5)


def clear_footer(section):
    f = section.footer
    f.is_linked_to_previous = False
    for p in f.paragraphs:
        p.text = ''


def outline_level(p, level):
    pPr = p._p.get_or_add_pPr()
    ol = pPr.find(qn('w:outlineLvl'))
    if ol is None:
        ol = OxmlElement('w:outlineLvl')
        pPr.append(ol)
    ol.set(qn('w:val'), str(level))


def add_text(p, text, cn='宋体', en='Times New Roman', size=12, bold=False):
    r = p.add_run(text)
    set_run(r, cn, en, size, bold)
    return r


# ---------------- 段落构造 ----------------
def para_body(doc, text):
    """正文：宋体小四，固定行距 22pt，首行缩进 2 字符，两端对齐。"""
    p = doc.add_paragraph()
    set_align(p, 'both')
    set_ind(p, first_chars=2)
    set_spacing(p, 22, 0, 0)
    add_text(p, text, '宋体', 'Times New Roman', 12)
    return p


def para_h1(doc, text, size=15):
    """一级标题：默认小三号(15pt)宋体加粗居中；参考文献/致谢用三号(16pt)。"""
    p = doc.add_paragraph(style='Heading 1')
    set_align(p, 'center')
    set_ind(p, 0)
    set_spacing(p, 22, 6, 6)
    add_text(p, text, '宋体', 'Times New Roman', size, True)
    outline_level(p, 0)
    return p


def para_h2(doc, text):
    """二级标题：四号(14pt)宋体加粗，左对齐，缩进 2 字符。"""
    p = doc.add_paragraph(style='Heading 2')
    set_align(p, 'left')
    set_ind(p, left_chars=2)
    set_spacing(p, 22, 3, 3)
    add_text(p, text, '宋体', 'Times New Roman', 14, True)
    outline_level(p, 1)
    return p


def para_h3(doc, text):
    """三级标题：小四(12pt)宋体加粗，左对齐，缩进 2 字符。"""
    p = doc.add_paragraph(style='Heading 3')
    set_align(p, 'left')
    set_ind(p, left_chars=2)
    set_spacing(p, 22, 2, 2)
    add_text(p, text, '宋体', 'Times New Roman', 12, True)
    outline_level(p, 2)
    return p


def para_caption(doc, text):
    """表题/图题：宋体小五(9pt)，居中，行距 22pt。"""
    p = doc.add_paragraph()
    set_align(p, 'center')
    set_ind(p, 0)
    set_spacing(p, 22, 3, 3)
    add_text(p, text, '宋体', 'Times New Roman', 9)
    return p


def para_image(doc, path, width_cm=14.0):
    """插图：居中，无首行缩进，与正文留白。"""
    from docx.shared import Cm as _Cm
    p = doc.add_paragraph()
    set_align(p, 'center')
    set_ind(p, 0)
    # 注意：不能设固定行距，否则图片会被裁成一行高度
    set_spacing(p, 22, 6, 3, exact=False)
    p.paragraph_format.line_spacing = 1.0
    run = p.add_run()
    run.add_picture(path, width=_Cm(width_cm))
    return p


# ---------------- 三线表 ----------------
def _border(el, edge, sz, val='single'):
    tcPr = el
    borders = tcPr.find(qn('w:tcBorders'))
    if borders is None:
        borders = OxmlElement('w:tcBorders')
        tcPr.append(borders)
    e = borders.find(qn('w:' + edge))
    if e is None:
        e = OxmlElement('w:' + edge)
        borders.append(e)
    e.set(qn('w:val'), val)
    e.set(qn('w:sz'), str(sz))
    e.set(qn('w:space'), '0')
    e.set(qn('w:color'), '000000')


def three_line_table(doc, rows):
    """rows: 二维字符串数组，第一行为表头。生成学术三线表。"""
    ncol = len(rows[0])
    table = doc.add_table(rows=len(rows), cols=ncol)
    table.alignment = 1  # center
    tbl = table._tbl
    tblPr = tbl.tblPr

    # 去掉默认边框
    borders = OxmlElement('w:tblBorders')
    for edge in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'):
        e = OxmlElement('w:' + edge)
        e.set(qn('w:val'), 'none')
        e.set(qn('w:sz'), '0')
        borders.append(e)
    tblPr.append(borders)

    # 列宽：首列（角色/字段名）固定，其余按内容长度分配，总宽等于正文宽度 15cm
    _avail = 15.0
    if ncol == 3:
        _fixed = [2.4]
    elif ncol == 2:
        _fixed = [4.0]
    else:
        _fixed = [max(1.8, _avail * 0.16)]
    _rest = _avail - sum(_fixed)
    _wts = []
    for _j in range(len(_fixed), ncol):
        _lens = [len(r[_j]) if _j < len(r) else 0 for r in rows]
        _wts.append(max(4, sum(_lens) / max(1, len(_lens))))
    _sw = sum(_wts) or 1
    _widths = [Cm(w) for w in _fixed] + [Cm(_rest * w / _sw) for w in _wts]
    for _j, _w in enumerate(_widths):
        for _r in table.rows:
            _r.cells[_j].width = _w

    # 表头跨页重复
    tr0 = table.rows[0]._tr
    trPr0 = tr0.get_or_add_trPr()
    th = OxmlElement('w:tblHeader')
    trPr0.append(th)

    for i, row in enumerate(rows):
        for j, val in enumerate(row):
            cell = table.cell(i, j)
            cell.text = ''
            p = cell.paragraphs[0]
            set_align(p, 'center')
            set_ind(p, 0)
            set_spacing(p, 18, 1, 1)
            add_text(p, val, '宋体', 'Times New Roman', 10.5, i == 0)
            tcPr = cell._tc.get_or_add_tcPr()
            # 顶线与底线 2.25 磅（sz 以 1/8 磅计 => 18）
            if i == 0:
                _border(tcPr, 'top', 18)
            if i == len(rows) - 1:
                _border(tcPr, 'bottom', 18)
            # 表头下栏目线 1 磅 => sz 8
            if i == 0:
                _border(tcPr, 'bottom', 8)
    return table


# ---------------- 页眉页脚 ----------------
def set_pgnum(section, fmt='decimal', start=1):
    sectPr = section._sectPr
    pg = sectPr.find(qn('w:pgNumType'))
    if pg is None:
        pg = OxmlElement('w:pgNumType')
        sectPr.append(pg)
    pg.set(qn('w:fmt'), fmt)
    pg.set(qn('w:start'), str(start))


def add_header_text(section, text, odd=True):
    hdr = section.header
    p = hdr.paragraphs[0]
    p.text = ''
    set_align(p, 'center')
    add_text(p, text, '宋体', 'Times New Roman', 12)


print('生成器已就绪')


# ==================== 解析 Markdown ====================
raw = open(MD, encoding='utf-8').read().replace('\r\n', '\n')
lines = raw.split('\n')

# 引用编号：按正文首次出现顺序
CITE_ORDER = []
for m in re.finditer(r'\{c(\d+)\}', raw):
    k = int(m.group(1))
    if k not in CITE_ORDER:
        CITE_ORDER.append(k)
CITE_NUM = {k: i + 1 for i, k in enumerate(CITE_ORDER)}

REFS = json.load(open(BASE + '/_refs21.json', encoding='utf-8'))
REF_LIST = [REFS[k - 1] for k in CITE_ORDER]

def add_text_with_cites(p, text, cn='宋体', en='Times New Roman', size=12):
    """把 {cN} 转成右上标 [n]。"""
    pos = 0
    for m in re.finditer(r'\{c(\d+)\}', text):
        if m.start() > pos:
            add_text(p, text[pos:m.start()], cn, en, size)
        r = p.add_run('[%d]' % CITE_NUM[int(m.group(1))])
        set_run(r, cn, en, size)
        r.font.superscript = True
        pos = m.end()
    if pos < len(text):
        add_text(p, text[pos:], cn, en, size)


# ==================== 文档骨架 ====================
doc = Document()

# 默认样式：正文宋体小四
normal = doc.styles['Normal']
normal.font.name = 'Times New Roman'
normal.font.size = Pt(12)
normal.element.rPr.rFonts.set(qn('w:eastAsia'), '宋体')

sec = doc.sections[0]
sec.page_width = Cm(21.0)
sec.page_height = Cm(29.7)
sec.top_margin = Cm(2.5)
sec.bottom_margin = Cm(2.5)
sec.left_margin = Cm(3.0)
sec.right_margin = Cm(3.0)
sec.header_distance = Cm(2.0)
sec.footer_distance = Cm(2.0)

# ---------- 封面 ----------
def blank(doc, n=1, size=12):
    for _ in range(n):
        p = doc.add_paragraph()
        set_spacing(p, 22, 0, 0)
        add_text(p, '', '宋体', 'Times New Roman', size)

blank(doc, 1)
p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 50, 0, 0)
add_text(p, '本科毕业设计', '黑体', 'Times New Roman', 42, False)
blank(doc, 1)
p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 24, 0, 0)
add_text(p, '题目：', '宋体', 'Times New Roman', 16, True)
p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 26, 0, 0)
add_text(p, TITLE, '宋体', 'Times New Roman', 16, True)
blank(doc, 2)
for label, value in (('学　　院：', '软件工程学院'), ('专业班级：', '25Z250102'),
                     ('学生姓名：', '余荣艺'), ('学　　号：', '2025101010276'),
                     ('指导教师：', '印丽娅'), ('企业教师：', '暂无')):
    p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 26, 0, 0)
    add_text(p, label + value, '宋体', 'Times New Roman', 16)
blank(doc, 2)
p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 24, 0, 0)
add_text(p, '二〇二七年五月', '宋体', 'Times New Roman', 16, False)

# ---------- 原创性声明（与封面同节，无页码无页眉） ----------
s1 = doc.sections[0]
s1.different_first_page_header_footer = False
for _part in (s1.header, s1.even_page_header, s1.footer):
    _part.is_linked_to_previous = False
    for _p in _part.paragraphs:
        _p.text = ''

p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 26, 0, 0)
set_page_break_before(p)
add_text(p, '江西软件职业技术大学毕业设计', '宋体', 'Times New Roman', 16, True)
p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 26, 0, 0)
add_text(p, '原创性声明和使用授权声明', '宋体', 'Times New Roman', 16, True)
blank(doc, 1)

p = doc.add_paragraph(); set_align(p, 'left'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
add_text(p, '1.毕业设计原创性声明', '宋体', 'Times New Roman', 14, True)
for t in ('本人声明所呈交的毕业设计，是本人在指导教师指导下进行的研究工作及取得的研究成果。除文中已经注明引用的内容外，本设计不包含其他个人或集体已经发表或撰写的作品。对本文的研究做出重要贡献的个人和集体，均已在文中标明。',
          '本声明的法律后果由本人承担。'):
    p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
    add_text(p, t, '宋体', 'Times New Roman', 12)
blank(doc, 2)
p = doc.add_paragraph(); set_align(p, 'right'); set_spacing(p, 22, 0, 0)
add_text(p, '学生签名：　　　　　　　　　　', '宋体', 'Times New Roman', 12)
p = doc.add_paragraph(); set_align(p, 'right'); set_spacing(p, 22, 0, 0)
add_text(p, '2027年　　月　　日　　　　', '宋体', 'Times New Roman', 12)
blank(doc, 2)

p = doc.add_paragraph(); set_align(p, 'left'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
add_text(p, '2.毕业设计使用授权声明', '宋体', 'Times New Roman', 14, True)
for t in ('本人完全了解江西软件职业技术大学有关收集、保存、使用毕业设计的规定。',
          '本人愿意按照学校要求提交毕业设计的印刷本和电子版，同意毕业设计的印刷本和电子版采用影印、缩印、数字化或其它复制手段保存；同意学校在不以营利为目的的前提下，建立目录检索与阅览服务系统，公布毕业设计的部分或全部内容，允许他人依法合理使用。',
          '注：保密的毕业设计在解密后遵守此规定。'):
    p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
    add_text(p, t, '宋体', 'Times New Roman', 12)
blank(doc, 2)
p = doc.add_paragraph(); set_align(p, 'right'); set_spacing(p, 22, 0, 0)
add_text(p, '学生签名：　　　　　　　　　　', '宋体', 'Times New Roman', 12)
p = doc.add_paragraph(); set_align(p, 'right'); set_spacing(p, 22, 0, 0)
add_text(p, '2027年　　月　　日　　　　', '宋体', 'Times New Roman', 12)

print('封面与声明完成')


# ---------- 摘要 / Abstract / 目录（罗马页码，无页眉） ----------
sec2 = doc.add_section(WD_SECTION.NEW_PAGE)
sec2.page_width = Cm(21.0); sec2.page_height = Cm(29.7)
sec2.top_margin = Cm(2.5); sec2.bottom_margin = Cm(2.5)
sec2.left_margin = Cm(3.0); sec2.right_margin = Cm(3.0)
sec2.header_distance = Cm(2.0); sec2.footer_distance = Cm(2.0)
sec2.different_first_page_header_footer = False
set_pgnum(sec2, 'upperRoman', 1)
clear_header(sec2)
_f2 = sec2.footer; _f2.is_linked_to_previous = False
add_page_field(_f2.paragraphs[0], 'PAGE \\* ROMAN', 10.5)

def front_title(doc, text, brk=True, font='黑体', size=15):
    p = doc.add_paragraph(); set_align(p, 'center'); set_spacing(p, 22, 6, 6)
    if brk:
        set_page_break_before(p)
    add_text(p, text, font, 'Times New Roman', size, False)

# 中文摘要
front_title(doc, '摘　　要', brk=False)
ABS = [
 '针对小区管理方在房屋、居住关系、服务事项与公共资源管理中信息分散、流程不可追溯、预约易冲突的问题，本文设计并实现了一个基于 Spring Boot 的小区居住服务管理系统。系统采用前后端分离的单体架构，后端以 Spring Boot 提供 RESTful 接口，前端以 Vue 3 与 TypeScript 构建居民、服务人员与管理员三类入口，持久层采用 MySQL 与 MyBatis-Plus，并以 Redis 与 Redisson 承担缓存与分布式锁。系统面向居民、服务人员、小区管理员与超级管理员四类角色，实现小区与房屋层级管理、居民与居住关系管理、租住管理、工单、公告、反馈会话、公共资源预约、服务评价、运营统计、权限管理与消息通知等功能。设计上以功能权限与小区数据权限两层模型实现多小区数据隔离，以六类业务状态机约束流转，以时段唯一约束与分布式锁保证预约并发正确性，以长连接推送结合增量补拉保证通知不丢失。测试覆盖功能、权限、异常流程与非功能四类，越权访问与预约冲突用例全部被拦截，并完成容器化部署验证。结果表明该系统能够支撑单小区与多小区场景下的日常居住服务管理。',
]
for t in ABS:
    p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
    add_text(p, t, '楷体', 'Times New Roman', 12)
p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
add_text(p, '【关键词】', '黑体', 'Times New Roman', 12)
add_text(p, '小区管理；居住服务；Spring Boot；Vue 3；权限控制', '楷体', 'Times New Roman', 12)

# 英文摘要
front_title(doc, 'Abstract', font='Times New Roman')
ABSE = [
 'To address the problems of scattered information, untraceable service processes and frequent reservation conflicts in the management of housing, residence relationships, service items and public resources, this thesis designs and implements a residential community residence service management system based on Spring Boot. The system adopts a front-end and back-end separated monolithic architecture. The back end provides RESTful interfaces with Spring Boot; the front end builds three entries for residents, service staff and administrators with Vue 3 and TypeScript; the persistence layer uses MySQL and MyBatis-Plus; and Redis with Redisson provides caching and distributed locks. Facing four roles, namely residents, service staff, community administrators and super administrators, the system implements community and housing hierarchy management, resident and residence relationship management, lease management, work orders, notices, feedback conversations, public resource reservation, service evaluation, operation statistics, permission management and message notification. A two-level permission model of functional permission and community-scoped data permission realizes multi-community data isolation; six business state machines constrain state transitions; time-slot unique constraints and distributed locks guarantee reservation concurrency correctness; long-connection push combined with incremental pull ensures that notifications are not lost. Tests cover functional, permission, exception-flow and non-functional categories; unauthorized access and reservation conflict cases are all intercepted, and containerized deployment verification is completed. The results show that the system can support daily residence service management in both single-community and multi-community scenarios.',
]
for t in ABSE:
    p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
    add_text(p, t, 'Times New Roman', 'Times New Roman', 12)
p = doc.add_paragraph(); set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
add_text(p, '【Key words】', 'Times New Roman', 'Times New Roman', 12)
add_text(p, 'Community Management; Residence Service; Spring Boot; Vue 3; Access Control', 'Times New Roman', 'Times New Roman', 12)

# 目录
front_title(doc, '目　　录')
p = doc.add_paragraph(); set_spacing(p, 22, 0, 0); set_align(p, 'left')

def _fld_run(par, kind, text=None):
    r = par.add_run(); set_run(r, '宋体', 'Times New Roman', 12)
    if kind == 'instr':
        el = OxmlElement('w:instrText'); el.set(qn('xml:space'), 'preserve'); el.text = text
    elif kind == 'text':
        el = OxmlElement('w:t'); el.text = text
    else:
        el = OxmlElement('w:fldChar'); el.set(qn('w:fldCharType'), kind)
    r._r.append(el); return r

_fld_run(p, 'begin')
_fld_run(p, 'instr', 'TOC \\o "1-3" \\h \\z \\u')
_fld_run(p, 'separate')
_fld_run(p, 'text', '（在此右键选择"更新域"生成目录）')
_fld_run(p, 'end')

print('摘要与目录完成')


# ---------- 正文（新节，阿拉伯页码 + 页眉） ----------
def body_section(sec, first=False):
    sec.page_width = Cm(21.0); sec.page_height = Cm(29.7)
    sec.top_margin = Cm(2.5); sec.bottom_margin = Cm(2.5)
    sec.left_margin = Cm(3.0); sec.right_margin = Cm(3.0)
    sec.header_distance = Cm(2.0); sec.footer_distance = Cm(2.0)
    sec.different_first_page_header_footer = False
    if first:
        set_pgnum(sec, 'decimal', 1)
    else:
        _pn = sec._sectPr.find(qn('w:pgNumType'))
        if _pn is not None:
            _pn.attrib.pop(qn('w:start'), None)
            _pn.set(qn('w:fmt'), 'decimal')

_settings = doc.settings.element
if _settings.find(qn('w:evenAndOddHeaders')) is None:
    _settings.append(OxmlElement('w:evenAndOddHeaders'))

sec3 = doc.add_section(WD_SECTION.NEW_PAGE)
body_section(sec3, first=True)
set_body_header(sec3, '1 绪论')

CHAPTERS = []

def style_header(sec, chapter):
    """奇数页页眉=论文题目，偶数页页眉=本章标题（静态文本，避免 STYLEREF 在 WPS 解析失败）。"""
    h = sec.header
    h.is_linked_to_previous = False
    p = h.paragraphs[0]; p.text = ''
    set_align(p, 'center'); set_spacing(p, 22, 0, 0)
    add_text(p, TITLE, '宋体', 'Times New Roman', 12)

    he = sec.even_page_header
    he.is_linked_to_previous = False
    p = he.paragraphs[0]; p.text = ''
    set_align(p, 'center'); set_spacing(p, 22, 0, 0)
    add_text(p, chapter, '宋体', 'Times New Roman', 12)

    f = sec.footer
    f.is_linked_to_previous = False
    p = f.paragraphs[0]; p.text = ''
    set_align(p, 'center')
    r = p.add_run()
    f1 = OxmlElement('w:fldChar'); f1.set(qn('w:fldCharType'), 'begin')
    it = OxmlElement('w:instrText'); it.set(qn('xml:space'), 'preserve')
    it.text = 'PAGE \\* MERGEFORMAT'
    f2 = OxmlElement('w:fldChar'); f2.set(qn('w:fldCharType'), 'separate')
    tt = OxmlElement('w:t'); tt.text = '1'
    f3 = OxmlElement('w:fldChar'); f3.set(qn('w:fldCharType'), 'end')
    r._r.append(f1); r._r.append(it); r._r.append(f2); r._r.append(tt); r._r.append(f3)
    set_run(r, '宋体', 'Times New Roman', 10.5)


# ---------- 逐块渲染 ----------
start = raw.index('# 1 绪论')
body_md = raw[start:]
blines = body_md.split('\n')

i = 0
n = len(blines)
first_chapter = True
while i < n:
    line = blines[i].rstrip()
    s_ = line.strip()
    if not s_ or s_ == '---' or s_.startswith('>'):
        i += 1
        continue

    if s_.startswith('### '):
        para_h3(doc, s_[4:].strip()); i += 1; continue
    if s_.startswith('## '):
        para_h2(doc, s_[3:].strip()); i += 1; continue
    if s_.startswith('# '):
        txt = s_[2:].strip()
        if first_chapter:
            first_chapter = False
        else:
            new_sec = doc.add_section(WD_SECTION.NEW_PAGE)
            body_section(new_sec, first=False)
            set_body_header(new_sec, txt)
        hp = para_h1(doc, txt, size=16 if txt in ('参考文献', '致谢') else 15)
        CHAPTERS.append(txt)
        i += 1
        continue

    if s_.startswith('|'):
        rows = []
        while i < n and blines[i].strip().startswith('|'):
            row = blines[i].strip()
            if not re.match(r'^\|[\s:\-|]+\|$', row):
                cells = [c.strip() for c in row.strip('|').split('|')]
                rows.append(cells)
            i += 1
        if rows:
            three_line_table(doc, rows)
        continue

    if re.match(r'^表\s*\d+-\d+', s_):
        para_caption(doc, s_); i += 1; continue

    # 插图：![alt](相对路径)
    if s_.startswith('![') and '](' in s_ and s_.endswith(')'):
        _img = s_[s_.index('](') + 2:-1]
        _full = os.path.join(BASE, _img.replace('/', os.sep))
        if os.path.exists(_full):
            para_image(doc, _full, 15.0)
        else:
            print('  [缺图]', _full)
        i += 1; continue

    # 图题
    if re.match(r'^图\s*\d+-\d+', s_):
        para_caption(doc, s_); i += 1; continue

    p = doc.add_paragraph()
    set_align(p, 'both'); set_ind(p, 2); set_spacing(p, 22, 0, 0)
    add_text_with_cites(p, s_, '宋体', 'Times New Roman', 12)
    i += 1

# 页眉：统一显示论文题目（章节级/前置页差异需分节，而分节在 WPS 导出时会产生空白页，
# 故本版统一处理；见交付说明中的待改进项）
print('正文渲染完成，共', len(CHAPTERS), '章')

# ---------- 参考文献：替换占位符为实际条目 ----------
# 找到“参考文献”标题之后插入条目
ref_h1_idx = None
for idx, p in enumerate(doc.paragraphs):
    if p.text.strip() == '参考文献':
        ref_h1_idx = idx
        break

if ref_h1_idx is not None:
    anchor = doc.paragraphs[ref_h1_idx]._p
    prev = anchor
    for num, item in enumerate(REF_LIST, 1):
        np_ = doc.add_paragraph()
        set_align(np_, 'both'); set_ind(np_, 2); set_spacing(np_, 18, 0, 0)
        add_text(np_, '[%d] %s' % (num, item), '宋体', 'Times New Roman', 10.5)
        prev.addnext(np_._p)
        prev = np_._p
    # 删除原占位说明段
    for p in list(doc.paragraphs):
        if p.text.strip().startswith('（按正文首次引用顺序排列'):
            p._p.getparent().remove(p._p)

# 清理目录与正文之间的空段落（避免多出空白页）
_paras = doc.paragraphs
for _i, _p in enumerate(_paras):
    if _p.text.strip() == '1 绪论':
        _k = _i - 1
        while _k >= 0 and _paras[_k].text.strip() == '':
            _paras[_k]._p.getparent().remove(_paras[_k]._p)
            _k -= 1
        break

doc.save(OUT)
print('已保存：', OUT)
print('参考文献：', len(REF_LIST), '条')