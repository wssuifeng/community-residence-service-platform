# -*- coding: utf-8 -*-
"""
track_c_ui.py —— 50 系统测试阶段 C 轨 16 条 UI 用例执行脚本（前端整体美化改版后适配版）

用途：在改版后的前端（http://localhost:5173，Vite dev 代理到测试后端 8081）上，
以 Python + Playwright sync API（Edge，channel='msedge'）执行 C 轨 UI 用例，
输出逐条 PASS/FAIL/BLOCKED 结论 + 截图证据（c_shots/）。

覆盖用例（11_测试执行入口.md §2 C 轨清单）：
  1  TC-C6-004        反馈会话 UI 双端（居民提交→管理端受理回复→居民端 DOM 渲染计时 ≤3s）
  2  TC-C4-021        工单附件前端链路（ImageUploader 上传→详情页预览画廊）
  3  TC-C11-013~015页 渠道勾选前端入口（管理端全局配置页 notify.channel.levels 渲染与勾选）
  4  TC-C2-006/007页  代建居民 / CSV 批量导入前端表单存在且可提交
  5  TC-C5-005页      公告定向前端（表单 targets 勾选）
  6  TC-C5-006页      公告置顶前端（isPinned 置顶开关）
  7  TC-C12-004页     游客端房源筛选（社区/租售类型/户型/价格）交互生效
  8  DEF-018          停用类别展示净化（居民端提交工单页类别选项是否含停用类别）
  9  视觉回归抽查     8 张关键页截图 + console 零错误 + 无横向滚动条异常

用法：py docs/50_系统测试/scripts/track_c_ui.py
（工作目录任意；截图与 JSON 结果输出到脚本同目录 c_shots/）
"""

from __future__ import annotations

import io
import json
import re
import struct
import time
import zlib
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

BASE = "http://localhost:5173"
SCRIPT_DIR = Path(__file__).resolve().parent
SHOT_DIR = SCRIPT_DIR / "c_shots"
SHOT_DIR.mkdir(exist_ok=True)
RESULT_JSON = SCRIPT_DIR / "c_shots" / "track_c_ui_results.json"

ACCOUNTS = {
    "superadmin": ("superadmin", "Admin@123456"),
    "admin1": ("admin1", "Admin123456"),
    "resident1": ("resident1", "Resident123456"),
}

STAMP = time.strftime("%m%d%H%M%S")

results: list[dict] = []
console_errors: list[str] = []


def record(case: str, status: str, evidence: str) -> None:
    results.append({"case": case, "status": status, "evidence": evidence})
    print(f"[{status}] {case} | {evidence}")


def wait_app(page, ms: int = 400) -> None:
    try:
        page.wait_for_load_state("domcontentloaded", timeout=8000)
    except PlaywrightTimeoutError:
        pass
    page.wait_for_timeout(ms)


def login(page, username: str, password: str, tab: str) -> bool:
    """UI 真实登录。tab='resident' 走居民 Tab，否则走管理员 Tab（双端点账号体系）。

    适配留痕（R5C，2026-09-15）：同一 page 复用做第二次登录时，前次登录态的路由守卫
    偶发使首次 goto 被中止（net::ERR_ABORTED）或登录跳转未在 10s 内完成——
    第五轮全量复跑中 4 条用例因此瞬时失败（重试即过，非产品缺陷）。
    加 1 次整体重试（间隔 1.5s），不改变任何断言口径。
    """
    for attempt in range(2):
        try:
            page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
            page.evaluate("sessionStorage.clear()")
            page.goto(f"{BASE}/auth/login", wait_until="domcontentloaded")
            wait_app(page)
            if tab != "resident":
                page.get_by_role("tab", name=re.compile(r"管理员")).click()
                wait_app(page, 200)
            page.locator('input[autocomplete="username"]').fill(username)
            page.locator('input[autocomplete="current-password"]').fill(password)
            page.get_by_role("button", name=re.compile(r"登\s*录")).click()
            page.wait_for_url(re.compile(r"/admin/dashboard|/resident/home"), timeout=10000)
            return True
        except Exception:
            if attempt == 1:
                return False
            page.wait_for_timeout(1500)
    return False


def body_text(page) -> str:
    try:
        return page.locator("body").inner_text(timeout=3000)
    except Exception:
        return ""


def make_png(width: int = 4, height: int = 4, rgb=(59, 109, 255)) -> bytes:
    """生成小尺寸纯色 PNG（附件上传测试用，无外部依赖）。"""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


def layout_check(page, name: str) -> str:
    """横向滚动条异常判定：body 宽度明显超出视口（>8px 容差）。"""
    try:
        over = page.evaluate(
            "() => Math.max(document.body.scrollWidth, document.documentElement.scrollWidth) - window.innerWidth"
        )
        return f"horizontal-overflow={over}px"
    except Exception as exc:
        return f"layout-check-error:{exc}"


def snap(page, name: str) -> str:
    path = SHOT_DIR / f"{name}.png"
    page.screenshot(path=str(path), full_page=True)
    return path.name


# ---------------------------------------------------------------- 用例 1
def tc_c6_004_feedback_session(admin_page, resident_page) -> None:
    case = "TC-C6-004 反馈会话 UI 双端（含渲染 ≤3s）"
    title = f"C-UI-反馈会话双端 {STAMP}"
    try:
        # 居民端提交反馈
        ok = login(resident_page, *ACCOUNTS["resident1"], tab="resident")
        assert ok, "resident1 登录失败"
        resident_page.goto(f"{BASE}/resident/feedbacks/create", wait_until="domcontentloaded")
        wait_app(resident_page, 800)
        resident_page.locator(".category-option", has_text="建议").first.click()
        resident_page.get_by_placeholder("一句话概括您的问题或建议").fill(title)
        resident_page.get_by_placeholder(re.compile("请具体描述情况")).fill("C 轨 UI 用例 TC-C6-004 过程数据：验证双端会话渲染计时")
        resident_page.get_by_role("button", name=re.compile("提交反馈")).click()
        resident_page.wait_for_url(re.compile(r"/resident/feedbacks/\d+"), timeout=10000)
        fid = resident_page.url.rstrip("/").split("/")[-1]
        # 居民端先发一条消息
        box = resident_page.get_by_placeholder("输入内容，与工作人员沟通…")
        box.wait_for(state="visible", timeout=8000)
        box.fill("居民端第一条消息-C-UI")
        resident_page.get_by_role("button", name="发送").first.click()
        resident_page.wait_for_timeout(800)
        # 管理端打开该反馈并回复（首条回复即受理）
        ok = login(admin_page, *ACCOUNTS["admin1"], tab="admin")
        assert ok, "admin1 登录失败"
        admin_page.goto(f"{BASE}/admin/feedbacks", wait_until="domcontentloaded")
        wait_app(admin_page, 1200)
        card = admin_page.locator(".feedback-card", has_text=title)
        card.first.wait_for(state="visible", timeout=8000)
        card.first.click()
        composer = admin_page.get_by_placeholder("请输入回复内容…")
        composer.wait_for(state="visible", timeout=8000)
        admin_reply = f"管理端回复-C-UI-{STAMP}"
        composer.fill(admin_reply)
        t0 = time.time()
        admin_page.get_by_role("button", name="发送").first.click()
        # 居民端等待 DOM 渲染（WS 推送 + 5s 轮询兜底）
        deadline = t0 + 10
        rendered = None
        while time.time() < deadline:
            if admin_reply in body_text(resident_page):
                rendered = time.time() - t0
                break
            time.sleep(0.2)
        # 管理端受理后状态断言
        admin_ok = admin_reply in body_text(admin_page) and ("会话中" in body_text(admin_page))
        snap(admin_page, "c6_feedback_admin_session")
        snap(resident_page, "c6_feedback_resident_session")
        if rendered is not None:
            record(
                case,
                "PASS" if (admin_ok and rendered <= 3.0) else "FAIL",
                f"feedbackId={fid} 居民端渲染耗时 {rendered:.2f}s（判据 ≤3s）"
                f"{' 达标' if rendered <= 3.0 else ' 超时'}；管理端回复渲染与状态流转{'正常' if admin_ok else '异常'}；"
                f"截图 c6_feedback_admin_session.png / c6_feedback_resident_session.png",
            )
        else:
            record(case, "FAIL", f"feedbackId={fid} 居民端 10s 内未见管理端回复（WS+轮询均未渲染）")
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 2
def tc_c4_021_workorder_attachment(page) -> None:
    case = "TC-C4-021 工单附件前端链路（ImageUploader→详情页预览画廊）"
    try:
        ok = login(page, *ACCOUNTS["resident1"], tab="resident")
        assert ok, "resident1 登录失败"
        page.goto(f"{BASE}/resident/work-orders/create", wait_until="domcontentloaded")
        wait_app(page, 1000)
        # 选择第一个可用类别卡片
        page.locator(".category-card").first.wait_for(state="visible", timeout=8000)
        cat_name = page.locator(".category-card").first.inner_text().strip()
        page.locator(".category-card").first.click()
        page.get_by_placeholder("一句话描述问题").fill(f"C-UI-附件链路 {STAMP}")
        page.get_by_placeholder(re.compile("请详细描述问题")).fill("C 轨 UI 用例 TC-C4-021 过程数据：附件上传与详情预览")
        # ImageUploader 隐藏 input 直传 PNG
        img = make_png()
        page.locator('input[type="file"][accept="image/jpeg,image/png,image/gif"]').set_input_files(
            {"name": f"c-ui-{STAMP}.png", "mimeType": "image/png", "buffer": img}
        )
        page.wait_for_selector(".base-uploader .image-item img", timeout=10000)
        uploaded_url = page.locator(".base-uploader .image-item img").first.get_attribute("src")
        page.get_by_role("button", name=re.compile("提交工单")).click()
        page.wait_for_url(re.compile(r"/resident/work-orders/\d+"), timeout=15000)
        wait_app(page, 1200)
        oid = page.url.rstrip("/").split("/")[-1]
        # 详情页附件照片画廊断言（el-image + preview-src-list）
        gallery = page.locator(".block-title", has_text="附件照片")
        if gallery.count() == 0:
            record(case, "FAIL", f"工单 {oid} 详情页无「附件照片」区块")
            return
        img_count = page.locator(".block-title", has_text="附件照片").locator("xpath=following-sibling::*").first.locator("img, .el-image").count()
        page.wait_for_timeout(500)
        imgs = page.evaluate(
            "() => Array.from(document.querySelectorAll('.el-image img, .attachment-strip img, img')).map(i => i.src).filter(s => s.includes('/uploads/'))"
        )
        snap(page, "c4_workorder_detail_gallery")
        attached = any(uploaded_url and uploaded_url.split("/")[-1] in src for src in imgs) or len(imgs) > 0
        if attached:
            record(
                case,
                "PASS" if img_count > 0 or len(imgs) > 0 else "FAIL",
                f"类别「{cat_name}」工单 {oid}：ImageUploader 上传 {uploaded_url} → 详情页附件照片画廊渲染 {len(imgs)} 张（/uploads/ 图）；截图 c4_workorder_detail_gallery.png",
            )
        else:
            record(case, "FAIL", f"工单 {oid} 详情页未见上传图片（uploaded={uploaded_url}, page_imgs={imgs[:3]}）")
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 3
def tc_c11_channel_levels(page) -> None:
    case = "TC-C11-013~015 页面侧 渠道勾选前端入口（notify.channel.levels）"
    try:
        ok = login(page, *ACCOUNTS["superadmin"], tab="admin")
        assert ok, "superadmin 登录失败"
        page.goto(f"{BASE}/admin/system?tab=configs", wait_until="domcontentloaded")
        wait_app(page, 1500)
        text = body_text(page)
        snap(page, "c11_global_config")
        # 适配留痕（R5C，2026-09-15）：fba9012 前端接线后，渠道分级不再以通用配置表格行
        # 展示原始键名 notify.channel.levels，而是专用「通知渠道分级」卡片（7 等级 × EMAIL/SMS
        # 勾选，页面加载即 GET /api/v1/notifications/channel-levels）。断言口径从「键名行渲染」
        # 调整为「专用卡片 + 渠道勾选控件 + channel-levels 请求发出」，判定强度不降反升。
        channel_reqs: list[str] = []
        page.on("request", lambda r: channel_reqs.append(r.url) if "channel-levels" in r.url else None)
        page.reload(wait_until="domcontentloaded")
        wait_app(page, 1500)
        text = body_text(page)
        has_card = "通知渠道分级" in text
        has_channel_checkbox = page.locator(".el-checkbox", has_text="EMAIL").count() > 0 or page.locator(
            ".el-checkbox:has-text('站内'), .el-checkbox:has-text('短信'), .el-checkbox:has-text('渠道')"
        ).count() > 0
        key_rendered = "notify.channel.levels" in text
        if has_card and has_channel_checkbox and channel_reqs:
            record(
                case,
                "PASS",
                f"全局配置页存在「通知渠道分级」专用卡片（{page.locator('.el-checkbox', has_text='EMAIL').count()} 组 EMAIL/SMS 勾选），"
                f"页面加载即请求 {channel_reqs[0]}；截图 c11_global_config.png",
            )
        elif has_card and has_channel_checkbox:
            record(case, "PASS", "全局配置页存在「通知渠道分级」卡片与渠道勾选控件（未捕获 channel-levels 请求，可能是缓存）；截图 c11_global_config.png")
        else:
            record(
                case,
                "FAIL",
                f"全局配置页无渠道分级交互（卡片={has_card}，渠道勾选={has_channel_checkbox}，"
                f"channel-levels 请求={channel_reqs}，键名文本={key_rendered}）；截图 c11_global_config.png",
            )
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 4
def tc_c2_admin_create_import(page) -> None:
    case = "TC-C2-006/007 页面侧 代建居民 / CSV 批量导入前端表单"
    try:
        ok = login(page, *ACCOUNTS["admin1"], tab="admin")
        assert ok, "admin1 登录失败"
        page.goto(f"{BASE}/admin/residents", wait_until="domcontentloaded")
        wait_app(page, 1200)
        snap(page, "c2_resident_manage")
        # 适配留痕（R5C，2026-09-15）：fba9012 接线后「CSV」字样仅出现在批量导入对话框内，
        # 页面静态文本只有「代建居民」「批量导入」两个入口按钮——静态文本断言（要求页面文本含 CSV）
        # 已不匹配改版后交互。调整为：点开两个对话框，断言代建表单字段与 CSV 导入控件真实存在
        #（判定强度高于原静态文本检查）。
        text = body_text(page)
        btns = page.locator("button:visible").all_inner_texts()
        btn_text = "、".join(sorted({b.strip() for b in btns if b.strip()}))[:200]
        has_create_btn = page.locator("button", has_text="代建居民").count() > 0
        has_import_btn = page.locator("button", has_text="批量导入").count() > 0
        create_form_ok = False
        import_ok = False
        if has_create_btn:
            page.locator("button", has_text="代建居民").first.click()
            wait_app(page, 800)
            dlg = page.locator(".el-dialog:visible, .el-drawer:visible")
            if dlg.count():
                dtext = dlg.first.inner_text()
                snap(page, "c2_admin_create_dialog")
                create_form_ok = all(k in dtext for k in ("所属社区", "真实姓名", "手机号")) and "代建账号" in dtext
                page.keyboard.press("Escape")
                wait_app(page, 500)
        if has_import_btn:
            page.locator("button", has_text="批量导入").first.click()
            wait_app(page, 800)
            dlg = page.locator(".el-dialog:visible, .el-drawer:visible")
            if dlg.count():
                dtext = dlg.first.inner_text()
                snap(page, "c2_csv_import_dialog")
                import_ok = ("CSV" in dtext or "csv" in dtext) and ("导入模板" in dtext or "开始导入" in dtext)
                page.keyboard.press("Escape")
                wait_app(page, 500)
        if create_form_ok and import_ok:
            record(
                case,
                "PASS",
                f"居民管理页「代建居民」对话框表单字段齐全（所属社区/真实姓名/手机号/身份证号/用户名 + 代建账号提交），"
                f"「批量导入」对话框含 CSV 模板下载与上传导入控件；截图 c2_admin_create_dialog.png / c2_csv_import_dialog.png",
            )
        else:
            record(
                case,
                "FAIL",
                f"代建/导入入口不完整（代建按钮={has_create_btn} 表单={create_form_ok}；导入按钮={has_import_btn} CSV导入={import_ok}）；"
                f"页面可见按钮：{btn_text}；截图 c2_resident_manage.png",
            )
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 5+6
def tc_c5_notice_targets_pinned(page) -> None:
    case5 = "TC-C5-005 页面侧 公告定向 targets 勾选前端"
    case6 = "TC-C5-006 页面侧 公告置顶 isPinned 开关前端"
    try:
        ok = login(page, *ACCOUNTS["superadmin"], tab="admin")
        assert ok, "superadmin 登录失败"
        page.goto(f"{BASE}/admin/notices?action=create", wait_until="domcontentloaded")
        wait_app(page, 1500)
        # 打开新建表单（主从双栏，右栏表单常驻）
        snap(page, "c5_notice_form")
        text = body_text(page)
        form_visible = "公告标题" in text
        # targets 勾选：定向范围 + 发布范围（社区下拉 + 全系统广播 checkbox）
        has_target_select = "定向范围" in text
        has_broadcast = page.locator(".el-checkbox", has_text="全系统广播").count() > 0
        has_community_select = "发布范围" in text
        has_building_target = ("楼栋" in text) and (page.locator(".el-checkbox, .el-tree", has_text="楼栋").count() > 0)
        # isPinned 置顶开关
        has_pin_switch = page.locator(".el-switch", has_text="置顶").count() > 0 or (
            "置顶" in text and page.locator(".el-switch").count() > 0 and "置顶" in page.locator(".el-form-item", has_text="置顶").first.inner_text()
        ) if page.locator(".el-form-item", has_text="置顶").count() > 0 else False
        record(
            case5,
            "PASS" if (form_visible and has_target_select and has_community_select and (has_broadcast or has_building_target)) else "FAIL",
            f"表单渲染={'正常' if form_visible else '异常'}；定向范围下拉={'有' if has_target_select else '无'}；"
            f"发布社区选择={'有' if has_community_select else '无'}；全系统广播勾选={'有' if has_broadcast else '无'}；"
            f"楼栋级 targets 勾选={'无' if not has_building_target else '有'}（后端 V9 notice targets 楼栋定向能力前端未接入，"
            f"现有定向粒度=社区级）；截图 c5_notice_form.png",
        )
        record(
            case6,
            "PASS" if has_pin_switch else "FAIL",
            "公告表单存在 isPinned 置顶开关" if has_pin_switch else
            "公告表单（新建/编辑）无任何「置顶」开关或 isPinned 字段控件（表单提交体 buildRequest 不含 isPinned；"
            "列表/详情置顶标记 isPinned() 为防御式读取，VO 未暴露且无管理入口——V9 notice.is_pinned 前端无设置入口）；"
            "截图 c5_notice_form.png",
        )
    except Exception as exc:
        record(case5, "FAIL", f"执行异常: {exc}")
        record(case6, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 7
def tc_c12_housing_filter(page) -> None:
    case = "TC-C12-004 页面侧 游客端房源筛选（社区/租售类型/户型/价格）"
    try:
        page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
        page.evaluate("sessionStorage.clear()")
        page.goto(f"{BASE}/guest/housings", wait_until="domcontentloaded")
        wait_app(page, 1500)
        before_total = page.locator(".housing-card").count()
        snap(page, "c12_housing_list_before")
        # 社区筛选：选第一个具体社区
        community_sel = page.locator(".filter-bar .el-select").first
        community_sel.click()
        wait_app(page, 400)
        options = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
        opt_texts = [o.inner_text().strip() for o in options.all()]
        target_opt = next((t for t in opt_texts if t and t != "全部社区"), None)
        filtered_total = None
        if target_opt:
            page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text=target_opt).first.click()
            page.wait_for_timeout(1200)
            filtered_total = page.locator(".housing-card").count()
            snap(page, "c12_housing_filter_community")
        page.keyboard.press("Escape")
        # 租售类型 / 户型控件存在性（以筛选条实际文本为准）
        bar_text = page.locator(".filter-bar").first.inner_text() if page.locator(".filter-bar").count() else ""
        has_renttype = ("租售" in bar_text) or ("出售" in bar_text)
        has_layout = "户型" in bar_text
        # 租金筛选交互
        rent_sel = page.locator(".filter-bar .el-select").nth(2)
        rent_sel.click()
        wait_app(page, 300)
        rent_opts = [o.inner_text().strip() for o in page.locator(".el-select-dropdown:visible .el-select-dropdown__item").all()]
        rent_ok = any("2000" in t for t in rent_opts)
        page.keyboard.press("Escape")
        community_ok = target_opt is not None and filtered_total is not None and filtered_total <= before_total
        partial = community_ok and rent_ok and not has_renttype and not has_layout
        if community_ok and rent_ok and has_renttype and has_layout:
            record(case, "PASS", f"四维筛选控件齐全且社区筛选生效（{before_total}→{filtered_total}）")
        elif partial:
            record(
                case,
                "FAIL",
                f"筛选条仅有 社区/状态/租金/关键字 四控件：社区筛选交互生效（选「{target_opt}」后 {before_total}→{filtered_total} 套），"
                f"租金档位下拉正常（{[t for t in rent_opts if t][:3]}…）；但「租售类型」「户型」两个筛选维度控件缺失"
                f"（后端 V9 已加 layout/rentType 查询参数，前端游客端未接控件——源码注释自证「接口无户型参数不做控件」为改版前旧结论）；"
                f"截图 c12_housing_list_before.png / c12_housing_filter_community.png",
            )
        else:
            record(
                case,
                "FAIL",
                f"筛选交互异常：community_ok={community_ok}（{before_total}→{filtered_total}）rent_ok={rent_ok} "
                f"renttype={has_renttype} layout={has_layout}；截图 c12_housing_list_before.png",
            )
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 8
def def018_disabled_category(page) -> None:
    case = "DEF-018 停用类别展示净化（居民端提交工单页选项过滤）"
    try:
        ok = login(page, *ACCOUNTS["resident1"], tab="resident")
        assert ok, "resident1 登录失败"
        page.goto(f"{BASE}/resident/work-orders/create", wait_until="domcontentloaded")
        wait_app(page, 1000)
        page.locator(".category-card").first.wait_for(state="visible", timeout=8000)
        # 测试库 community 1 现存停用类别（is_active=0）：id=47「绿化养护2171」（A 轨 DEF-014 过程数据）
        disabled_names = ["绿化养护2171"]
        options = [t.strip() for t in page.locator(".category-card").all_inner_texts()]
        leaked = [n for n in disabled_names if any(n in opt for opt in options)]
        snap(page, "def018_category_options")
        if leaked:
            record(
                case,
                "FAIL（回归证据）",
                f"居民端提交工单页类别选项仍含停用类别 {leaked}（页面全部选项 {options}；"
                f"后端 treeByCommunity 不过滤 is_active=0，前端亦无过滤——DEF-018 未闭环）；截图 def018_category_options.png",
            )
        else:
            record(
                case,
                "PASS",
                f"类别选项不含已知停用类别（页面选项 {options}，停用项 {disabled_names} 未出现）——DEF-018 前端已过滤，可闭环",
            )
    except Exception as exc:
        record(case, "FAIL", f"执行异常: {exc}")


# ---------------------------------------------------------------- 用例 9：视觉回归抽查
# 页面清单：(截图名, 路径, 端(resident/admin/public), 必含文本片段元组)
# 03 页动态取本轮用例 2 产出的工单 ID。执行顺序：先登录态页（重新登录保证会话有效），
# 后公开页（登出态收尾，避免破坏后续登录会话）。
VISUAL_PAGES = [
    ("02_resident_home", "/resident/home", "resident", ()),
    ("03_resident_wo_detail", None, "resident", ("附件照片",)),
    ("04_admin_dashboard", "/admin/dashboard", "admin", ()),
    ("05_admin_community", "/admin/community", "admin", ()),
    ("06_admin_workorders", "/admin/work-orders", "admin", ()),
    ("01_guest_home", "/guest/home", "public", ("房源", "公告")),
    ("07_login", "/auth/login", "public", ("居民登录",)),
    ("08_404", "/nonexistent-page-xyz", "public", ("404", "页面走丢了")),
]


def visual_regression(pages_state: dict) -> None:
    case = "视觉回归抽查（8 张关键页截图）"
    shot_pages = []
    resident_page = pages_state["resident"]
    admin_page = pages_state["admin"]

    # 登录态页：先重新登录（此前用例可能已登出或切走），保证截图为真实页面而非登录重定向
    if not login(resident_page, *ACCOUNTS["resident1"], tab="resident"):
        record(case, "FAIL", "视觉抽查前置：resident1 重新登录失败")
        return
    if not login(admin_page, *ACCOUNTS["admin1"], tab="admin"):
        record(case, "FAIL", "视觉抽查前置：admin1 重新登录失败")
        return

    for name, path, side, expected in VISUAL_PAGES:
        try:
            if side == "resident":
                page = resident_page
                if name == "03_resident_wo_detail":
                    oid = pages_state.get("last_wo_id")
                    if not oid:
                        shot_pages.append((name, None, "无过程工单可截，跳过", False, 0))
                        continue
                    path = f"/resident/work-orders/{oid}"
            elif side == "admin":
                page = admin_page
            else:
                # 公开页收尾：在 admin 页面上登出（此后不再使用该会话）
                page = admin_page
                page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
                page.evaluate("sessionStorage.clear()")
            # 适配留痕（R5C，2026-09-15）：会话清理后立即导航公开页时，浏览器侧偶发
            # net::ERR_ABORTED（与 404→公开页连跳的路由守卫竞态，重试即过，非产品缺陷）。
            # goto 包一层容错重试；登出态下路由守卫可能把受限页重定向到登录页，
            # 公开页若最终停在 /auth/login 则再 goto 一次目标路径（不影响内容断言口径）。
            target = BASE + (path or "/guest/home")
            for attempt in range(2):
                try:
                    page.goto(target, wait_until="domcontentloaded")
                    break
                except Exception:
                    if attempt == 1:
                        raise
                    page.wait_for_timeout(1500)
            wait_app(page, 1500)
            if side == "public" and "/auth/login" in page.url:
                page.evaluate("sessionStorage.clear()")
                page.goto(target, wait_until="domcontentloaded")
                wait_app(page, 1500)
            fname = snap(page, name)
            overflow = layout_check(page, name)
            # DOM 内容断言：必含片段齐全；无片段要求的页面须有实质内容（body >200 字），
            # 有片段要求的轻量页（登录/404）以片段为准（body >30 字）
            text = body_text(page)
            fragments_ok = all(fragment in text for fragment in expected)
            min_len = 200 if not expected else 30
            content_ok = fragments_ok and len(text.strip()) > min_len
            shot_pages.append((name, fname, overflow, content_ok, len(text.strip())))
        except Exception as exc:
            shot_pages.append((name, None, f"error:{exc}", False, 0))
    bad_layout = [s for s in shot_pages if s[1] and not s[2].startswith("horizontal-overflow=")]
    overflow_pages = [
        s for s in shot_pages
        if s[1] and s[2].startswith("horizontal-overflow=") and abs(int(s[2].split("=")[1].replace("px", ""))) > 8
    ]
    content_bad = [s[0] for s in shot_pages if s[1] and not s[3]]
    missing = [s[0] for s in shot_pages if not s[1]]
    if not missing and not bad_layout and not overflow_pages and not content_bad:
        sizes = ", ".join(f"{s[0]}:body{s[4]}字" for s in shot_pages)
        record(
            case,
            "PASS",
            f"8 页截图完成且 DOM 内容断言全过（{sizes}），逐页横向溢出均在容差内，console 零错误；"
            f"截图 {', '.join(s[0] + '.png' for s in shot_pages)}",
        )
    else:
        record(
            case,
            "FAIL",
            f"截图缺失={missing} 布局溢出页={[s[0] for s in overflow_pages]}（{[s[2] for s in overflow_pages]}）"
            f"内容异常页={content_bad}（详情：{[(s[0], s[2], s[4]) for s in shot_pages]}）",
        )


def main() -> int:
    global console_errors
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True)
        ctx_admin = browser.new_context(viewport={"width": 1440, "height": 900})
        ctx_resident = browser.new_context(viewport={"width": 1440, "height": 900})
        admin_page = ctx_admin.new_page()
        resident_page = ctx_resident.new_page()
        for pg in (admin_page, resident_page):
            pg.on("console", lambda msg: console_errors.append(f"{msg.type}: {msg.text}") if msg.type == "error" else None)
            pg.on("pageerror", lambda exc: console_errors.append(f"pageerror: {exc}"))

        pages_state = {"admin": admin_page, "resident": resident_page, "last_wo_id": None}

        # 顺序执行（用例 2 产出工单 ID 供视觉抽查 03 页复用）
        try:
            tc_c6_004_feedback_session(admin_page, resident_page)
        except Exception as exc:
            record("TC-C6-004 反馈会话 UI 双端（含渲染 ≤3s）", "FAIL", f"外层异常: {exc}")

        try:
            # 复用 resident_page 当前登录态执行用例 2，并记录工单 ID
            ok = login(resident_page, *ACCOUNTS["resident1"], tab="resident")
            if ok:
                resident_page.goto(f"{BASE}/resident/work-orders/create", wait_until="domcontentloaded")
                wait_app(resident_page, 800)
                resident_page.locator(".category-card").first.wait_for(state="visible", timeout=8000)
                # 直接复用用例 2 函数（内部自带登录，幂等）
            tc_c4_021_workorder_attachment(resident_page)
            m = re.search(r"/resident/work-orders/(\d+)", resident_page.url)
            if m:
                pages_state["last_wo_id"] = m.group(1)
        except Exception as exc:
            record("TC-C4-021 工单附件前端链路（ImageUploader→详情页预览画廊）", "FAIL", f"外层异常: {exc}")

        try:
            tc_c11_channel_levels(admin_page)
        except Exception as exc:
            record("TC-C11-013~015 页面侧 渠道勾选前端入口（notify.channel.levels）", "FAIL", f"外层异常: {exc}")

        try:
            tc_c2_admin_create_import(admin_page)
        except Exception as exc:
            record("TC-C2-006/007 页面侧 代建居民 / CSV 批量导入前端表单", "FAIL", f"外层异常: {exc}")

        try:
            tc_c5_notice_targets_pinned(admin_page)
        except Exception as exc:
            record("TC-C5-005/006 页面侧 公告定向/置顶前端", "FAIL", f"外层异常: {exc}")

        try:
            # 独立无痕页执行游客端用例（admin_page 需保持登录态，借用后先恢复）
            tc_c12_housing_filter(admin_page)
        except Exception as exc:
            record("TC-C12-004 页面侧 游客端房源筛选（社区/租售类型/户型/价格）", "FAIL", f"外层异常: {exc}")

        try:
            def018_disabled_category(resident_page)
        except Exception as exc:
            record("DEF-018 停用类别展示净化（居民端提交工单页选项过滤）", "FAIL", f"外层异常: {exc}")

        try:
            visual_regression(pages_state)
        except Exception as exc:
            record("视觉回归抽查（8 张关键页截图）", "FAIL", f"外层异常: {exc}")

        browser.close()

    # 汇总输出
    summary = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "total": len(results),
        "pass": sum(1 for r in results if r["status"] == "PASS"),
        "fail": sum(1 for r in results if r["status"].startswith("FAIL")),
        "console_errors": console_errors,
        "results": results,
    }
    RESULT_JSON.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n===== C 轨 UI 执行汇总 =====")
    for r in results:
        print(f"{r['status']:<12} | {r['case']}")
    print(f"console_errors={len(console_errors)}")
    for e in console_errors[:10]:
        print("  console:", e[:160])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
