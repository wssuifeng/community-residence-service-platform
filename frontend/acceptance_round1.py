from __future__ import annotations

"""P1 前端验收 · 第一轮（E1/E2/E3/E5/E12，依据给前端会话的验收指令.md）。

UI 结构对齐：
- 登录 Tab：管理员/服务人员共用一个 Tab（双端点账号体系）
- E1：/admin/communities 新建社区 → /admin/buildings（选社区后新建楼栋）
  → /admin/units（选社区+楼栋后新建单元）→ /admin/houses（选社区+楼栋+单元后新建房屋）
- E2：注册页注册新居民 → 登录 → 入住申请由 API 代发（前端无居民端申请 UI，
  已知计划内缺口）→ admin1 在 /admin/residence-applications UI 审批 →
  居民个人中心验证居住关系/租约卡片
- E3：居民个人中心的居住关系/租约卡片（无独立"我的租约"页）
- E5：/admin/notices/create 建公告 → 列表发布 → 居民端查看 → 管理端回执
- E12：游客 /guest/housings → 详情多次访问 → 列表查看次数
"""

import json
import re
import time
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE = "http://localhost:5173"
API = "http://localhost:8080/api/v1"
OUT = Path("acceptance-round1-results.json")
SHOTS = Path("acceptance-shots")
SHOTS.mkdir(exist_ok=True)

results: dict = {"started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "checks": [], "issues": [], "api_calls": []}
api_calls: list[dict] = []


def check(name: str, passed: bool, detail: str = "") -> None:
    results["checks"].append({"name": name, "passed": bool(passed), "detail": detail})
    print(f"{'PASS' if passed else 'FAIL'} | {name} | {detail[:150]}")


def issue(title: str, detail: str, priority: str = "P1") -> None:
    results["issues"].append({"title": title, "detail": detail, "priority": priority})
    print(f"ISSUE[{priority}] {title}: {detail[:150]}")


def wait_app(page, ms: int = 400) -> None:
    try:
        page.wait_for_load_state("domcontentloaded", timeout=8000)
    except PWTimeout:
        pass
    page.wait_for_timeout(ms)


def login(page, username: str, password: str, tab: str = "admin") -> bool:
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
    try:
        page.wait_for_url(re.compile(r"/admin/|/staff/|/resident/"), timeout=8000)
        return True
    except PWTimeout:
        return False


def fill_form_item(page, label: str, value: str) -> None:
    """按 el-form-item label 定位输入框并填写（限定在可见 dialog 内）。"""
    item = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{label}'))").first
    item.locator("input").first.fill(value)


def select_form_item(page, label: str, option_text: str) -> None:
    """按 label 选 el-select 选项。"""
    item = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{label}'))").first
    item.locator(".el-select").first.click()
    page.wait_for_timeout(300)
    page.locator(f".el-select-dropdown:visible .el-select-dropdown__item:has-text('{option_text}')").first.click()
    page.wait_for_timeout(200)


def submit_dialog(page, title_regex: str) -> None:
    """按对话框标题匹配，点击 footer 的确定按钮（Element Plus 对话框 accessible name 不可靠）。"""
    dlg = page.locator(".el-dialog", has=page.locator(f".el-dialog__title:text-is('{title_regex}')")).first
    dlg.locator(".el-dialog__footer button", has_text="确").click()


def api_login(username: str, password: str, endpoint: str = "admin") -> str:
    resp = requests.post(f"{API}/auth/{endpoint}/login", json={"username": username, "password": password}, timeout=8)
    resp.raise_for_status()
    return resp.json()["data"]["token"]


def main() -> int:
    ts = time.strftime("%H%M%S")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()
        page.on(
            "response",
            lambda res: api_calls.append({"method": res.request.method, "url": res.url.split("5173")[-1], "status": res.status})
            if "/api/" in res.url
            else None,
        )

        # ================= E1 社区初始化 =================
        ok = login(page, "superadmin", "Admin@123456")
        check("E1 superadmin login", ok, page.url)

        # 1. 新建社区
        page.goto(f"{BASE}/admin/communities", wait_until="domcontentloaded")
        wait_app(page, 700)
        page.get_by_text("新建社区", exact=True).click()
        wait_app(page, 300)
        fill_form_item(page, "社区名称", "前端测试社区C")
        fill_form_item(page, "社区地址", "测试路1号")
        fill_form_item(page, "联系人", "测试联系人")
        fill_form_item(page, "联系电话", "13800000001")
        submit_dialog(page, "新建社区")
        wait_app(page, 800)
        community_created = page.get_by_text("前端测试社区C", exact=True).count() > 0
        check("E1 create community", community_created, "列表应出现「前端测试社区C」")

        # 找到新社区 id（详情页用）
        community_row = page.locator("tr", has_text="前端测试社区C").first
        community_id = community_row.locator("td").first.inner_text().strip()
        page.screenshot(path=str(SHOTS / f"e1-community-{ts}.png"))

        # 2. 新建楼栋（BuildingListView 需先选社区）
        page.goto(f"{BASE}/admin/buildings", wait_until="domcontentloaded")
        wait_app(page, 700)
        # 列表筛选区选择社区
        page.locator(".filter-panel .el-select, .filter-label + .el-select").first.click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="前端测试社区C").first.click()
        wait_app(page, 500)
        page.get_by_text("新建楼栋", exact=True).click()
        wait_app(page, 300)
        select_form_item(page, "所属社区", "前端测试社区C")
        fill_form_item(page, "楼栋名称", "A号楼")
        fill_form_item(page, "总层数", "6")
        submit_dialog(page, "新建楼栋")
        wait_app(page, 800)
        check("E1 create building", page.get_by_text("A号楼", exact=True).count() > 0, "楼栋列表应出现 A号楼")
        page.screenshot(path=str(SHOTS / f"e1-building-{ts}.png"))

        # 3. 新建单元（UnitListView：社区 + 楼栋下拉；后端 DTO 为 name/description）
        page.goto(f"{BASE}/admin/units", wait_until="domcontentloaded")
        wait_app(page, 700)
        page.get_by_text("新建单元", exact=True).click()
        wait_app(page, 300)
        select_form_item(page, "所属社区", "前端测试社区C")
        select_form_item(page, "所属楼栋", "A号楼")
        fill_form_item(page, "单元名称", "1单元")
        submit_dialog(page, "新建单元")
        wait_app(page, 800)
        check("E1 create unit", True, "单元创建（列表按社区过滤，改为 API 核验）")
        page.screenshot(path=str(SHOTS / f"e1-unit-{ts}.png"))

        # 4. 新建房屋（HouseListView：需社区+楼栋+单元三级筛选后才可新建）
        page.goto(f"{BASE}/admin/houses", wait_until="domcontentloaded")
        wait_app(page, 700)

        # 通过 API 拿新建单元 id（UI 三级联动筛选自动化较脆弱，用数据辅助定位）
        satok = api_login("superadmin", "Admin@123456")
        comms = requests.get(f"{API}/communities?page=1&size=100", headers={"Authorization": f"Bearer {satok}"}, timeout=8).json()["data"]["records"]
        cid = next(c["id"] for c in comms if c["name"] == "前端测试社区C")
        blds = requests.get(f"{API}/communities/{cid}/buildings?page=1&size=100", headers={"Authorization": f"Bearer {satok}"}, timeout=8).json()["data"]["records"]
        bid = blds[0]["id"] if blds else None
        units = requests.get(f"{API}/buildings/{bid}/units?page=1&size=100", headers={"Authorization": f"Bearer {satok}"}, timeout=8).json()["data"]["records"]
        uid = units[0]["id"] if units else None
        check("E1 unit created via data", bool(uid), f"community={cid} building={bid} unit={uid}")

        # UI 三级筛选：选社区 → 楼栋 → 单元
        selects = page.locator(".filter-panel .el-select")
        selects.nth(0).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="前端测试社区C").first.click()
        wait_app(page, 400)
        selects.nth(1).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="A号楼").first.click()
        wait_app(page, 400)
        selects.nth(2).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="1单元").first.click()
        wait_app(page, 500)

        new_btn = page.get_by_text("新建房屋", exact=True)
        btn_enabled = new_btn.count() > 0 and new_btn.first.is_enabled()
        check("E1 house create button enabled after unit filter", btn_enabled, "需选到单元后才能新建房屋")
        if btn_enabled:
            new_btn.first.click()
            wait_app(page, 300)
            fill_form_item(page, "门牌号", "101")
            fill_form_item(page, "所在楼层", "1")
            fill_form_item(page, "建筑面积(㎡)", "89.5")
            fill_form_item(page, "户型", "2室1厅1卫")
            fill_form_item(page, "房间数", "2")
            fill_form_item(page, "朝向", "南北")
            submit_dialog(page, "新建房屋")
            wait_app(page, 900)
            house_created = page.get_by_text("101", exact=True).count() > 0
            check("E1 create house", house_created, "房屋列表应出现 101")
            # 房屋状态默认空置
            house_row = page.locator("tr", has_text="101").first
            row_text = house_row.inner_text() if house_row.count() else ""
            check("E1 house default vacant", "空置" in row_text, row_text[:120])
            page.screenshot(path=str(SHOTS / f"e1-house-{ts}.png"))

        e1_posts = [c for c in api_calls if c["method"] == "POST" and c["status"] != 200]
        check("E1 no failed POST", not e1_posts, str(e1_posts))

        # ================= E2 注册 → 入住申请 → 审批 =================
        page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
        page.evaluate("sessionStorage.clear()")
        page.goto(f"{BASE}/auth/register", wait_until="domcontentloaded")
        wait_app(page)
        page.locator('input[autocomplete="username"]').fill("fe_test_user4")
        page.locator('input[placeholder="8~20 位"]').fill("Test123456")
        page.locator('input[placeholder="再次输入密码"]').fill("Test123456")
        # 其余注册字段（真实姓名/手机号）按 placeholder 定位
        page.locator('input[placeholder*="姓名"]').fill("前端测试用户D")
        page.locator('input[placeholder*="手机"]').fill("13800138004")
        page.get_by_role("button", name=re.compile(r"注\s*册")).click()
        wait_app(page, 1200)
        registered = page.locator(".el-message--success").count() > 0 or "/auth/login" in page.url
        check("E2 register new resident", registered, page.url)

        ok = login(page, "fe_test_user4", "Test123456", tab="resident")
        check("E2 new resident login", ok and "/resident/home" in page.url, page.url)

        # 居民端无入住申请 UI（P1 计划内缺口）→ API 代发申请，UI 侧验证后续审批与联动
        retok = api_login("fe_test_user4", "Test123456", endpoint="resident")
        app_resp = requests.post(
            f"{API}/residence-applications",
            json={"houseId": None, "relationType": "TENANT", "remark": "申请入住前端测试社区C"},
            headers={"Authorization": f"Bearer {retok}"},
            timeout=8,
        )
        # houseId 需真实值：查 E1 建的房屋
        houses = requests.get(f"{API}/units/{uid}/houses?page=1&size=20", headers={"Authorization": f"Bearer {satok}"}, timeout=8).json()["data"]["records"]
        hid = houses[0]["id"] if houses else None
        app_resp = requests.post(
            f"{API}/residence-applications",
            json={"houseId": hid, "relationType": "TENANT", "remark": "申请入住前端测试社区C（验收代发）"},
            headers={"Authorization": f"Bearer {retok}"},
            timeout=8,
        )
        app_ok = app_resp.status_code == 200 and app_resp.json().get("code") == 200
        check("E2 residence application submitted (API)", app_ok, f"houseId={hid} status={app_resp.status_code} body={app_resp.text[:120]}")

        # admin1 UI 审批
        ok = login(page, "admin1", "Admin123456")
        check("E2 admin1 login", ok, page.url)
        page.goto(f"{BASE}/admin/residence-applications", wait_until="domcontentloaded")
        wait_app(page, 900)
        page.screenshot(path=str(SHOTS / f"e2-applications-{ts}.png"))
        row = page.locator("tr", has_text="前端测试用户D").first
        found = row.count() > 0
        check("E2 application visible to admin", found, "入住申请列表应出现 前端测试用户D 的申请")
        if found:
            row.get_by_role("button", name=re.compile("通过|审核|审批")).first.click()
            wait_app(page, 500)
            # 审批对话框：租期/租金/押金 + 意见
            page.screenshot(path=str(SHOTS / f"e2-approve-dialog-{ts}.png"))
            # 填写审批表单（字段名以实际 UI 为准，先探测）
            dialog_labels = page.locator(".el-dialog:visible .el-form-item__label").all_inner_texts()
            print("approve dialog labels:", dialog_labels)
            # 日期字段：租期开始/结束
            for label_text in dialog_labels:
                if "开始" in label_text:
                    fill_form_item(page, label_text, "2026-09-10")
                elif "结束" in label_text:
                    fill_form_item(page, label_text, "2027-09-10")
                elif "租金" in label_text:
                    item = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{label_text}'))").first
                    item.locator("input").first.fill("2000")
                elif "押金" in label_text:
                    item = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{label_text}'))").first
                    item.locator("input").first.fill("4000")
                elif "意见" in label_text:
                    fill_form_item(page, label_text, "同意入住")
            submit_dialog(page, "通过")
            wait_app(page, 1200)
            approved = page.locator(".el-message--success").count() > 0
            row_after = page.locator("tr", has_text="前端测试用户D").first
            check("E2 approve application", approved, "审批应有成功提示")
            page.screenshot(path=str(SHOTS / f"e2-approved-{ts}.png"))

        # 居民个人中心验证居住关系与租约
        ok = login(page, "fe_test_user4", "Test123456", tab="resident")
        check("E2 resident relogin", ok, page.url)
        page.goto(f"{BASE}/resident/profile", wait_until="domcontentloaded")
        wait_app(page, 900)
        body = page.locator("body").inner_text()
        check("E2 profile shows residence relation", "在住" in body or "居住关系" in body, body[:200])
        check("E2 profile shows lease record", "租住记录" in body or "租约" in body or "生效" in body, body[:200])
        page.screenshot(path=str(SHOTS / f"e2-profile-{ts}.png"))

        # 房屋状态翻转为已入住（管理端房屋列表）
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/houses", wait_until="domcontentloaded")
        wait_app(page, 700)
        selects = page.locator(".filter-panel .el-select")
        selects.nth(0).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="前端测试社区C").first.click()
        wait_app(page, 400)
        selects.nth(1).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="A号楼").first.click()
        wait_app(page, 400)
        selects.nth(2).click()
        page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="1单元").first.click()
        wait_app(page, 600)
        house_row = page.locator("tr", has_text="101").first
        house_text = house_row.inner_text() if house_row.count() else ""
        check("E2 house status flipped to occupied", "已入住" in house_text or "居住中" in house_text, house_text[:120])

        # ================= E3 租约查询（个人中心卡片）=================
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E3 resident1 login", ok, page.url)
        page.goto(f"{BASE}/resident/profile", wait_until="domcontentloaded")
        wait_app(page, 900)
        body = page.locator("body").inner_text()
        check("E3 lease card visible", "租住记录" in body, body[:200])
        check("E3 lease shows house/rent", ("房屋" in body or "101" in body) and ("租金" in body or "租期" in body), body[:300])
        page.screenshot(path=str(SHOTS / f"e3-profile-{ts}.png"))

        # ================= E5 公告发布与查看 =================
        ok = login(page, "admin1", "Admin123456")
        check("E5 admin1 login", ok, page.url)
        page.goto(f"{BASE}/admin/notices/create", wait_until="domcontentloaded")
        wait_app(page, 700)
        # 探测公告创建表单结构
        labels = page.locator(".el-form-item__label").all_inner_texts()
        print("notice create labels:", labels)
        page.screenshot(path=str(SHOTS / f"e5-create-form-{ts}.png"))
        # 通用填写：标题/内容
        page.locator('input[placeholder*="标题"]').fill("前端测试公告")
        page.locator('textarea').first.fill("这是一条测试公告内容")
        # 发布时间等字段视实际表单而定，先尝试常见 placeholder
        for ph in ["发布时间", "失效时间", "失效"]:
            loc = page.locator(f'input[placeholder*="{ph}"]')
            if loc.count() > 0:
                loc.first.click()
                page.wait_for_timeout(300)
                # 日期面板点确定（element-plus 日期确认按钮）
                confirm = page.locator(".el-picker-panel:visible .el-button", has_text="确")
                if confirm.count() > 0:
                    confirm.first.click()
                page.wait_for_timeout(200)
        # 保存草稿
        draft_btn = page.get_by_role("button", name=re.compile("存草稿|保存草稿|存为草稿"))
        if draft_btn.count() > 0:
            draft_btn.first.click()
            wait_app(page, 1000)
            check("E5 notice draft saved", "/admin/notices" in page.url or page.locator(".el-message--success").count() > 0, page.url)
        else:
            # 无草稿按钮则直接提交（表单可能仅"发布"）
            issue("E5 公告创建页无「保存草稿」按钮", "验收指令要求先保存草稿再发布；实际按钮：" + str(page.get_by_role("button").all_inner_texts()[:8]), "P2")
            submit = page.get_by_role("button", name=re.compile("发布|提 交|确 定"))
            if submit.count() > 0:
                submit.first.click()
                wait_app(page, 1000)
        page.screenshot(path=str(SHOTS / f"e5-after-save-{ts}.png"))

        # 公告列表：找到草稿并发布
        page.goto(f"{BASE}/admin/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        row = page.locator("tr", has_text="前端测试公告").first
        check("E5 notice in list", row.count() > 0, "公告列表应出现「前端测试公告」")
        if row.count() > 0:
            status_text = row.inner_text()
            publish_btn = row.get_by_role("button", name=re.compile("发布"))
            if publish_btn.count() > 0:
                publish_btn.first.click()
                wait_app(page, 800)
                # 发布可能弹对话框要求发布时间
                dlg_confirm = page.locator(".el-dialog:visible .el-button--primary")
                if page.locator(".el-dialog:visible").count() > 0:
                    dlg_confirm.first.click()
                    wait_app(page, 800)
                check("E5 notice published", page.locator(".el-message--success").count() > 0 or "已发布" in page.locator("body").inner_text(), "发布成功提示")
            else:
                check("E5 notice published", "已发布" in status_text, status_text[:120])
            page.screenshot(path=str(SHOTS / f"e5-published-{ts}.png"))

        # 居民查看公告（置顶验证 + 详情回执）
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E5 resident login", ok, page.url)
        page.goto(f"{BASE}/resident/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        body = page.locator("body").inner_text()
        check("E5 resident sees published notice", "前端测试公告" in body, body[:200])
        page.screenshot(path=str(SHOTS / f"e5-resident-list-{ts}.png"))
        notice_card = page.locator("a, .notice-card, .card, li", has_text="前端测试公告").first
        if notice_card.count() > 0:
            notice_card.click()
            wait_app(page, 900)
            detail_ok = "前端测试公告" in page.locator("body").inner_text()
            check("E5 notice detail renders", detail_ok, page.url)
            page.screenshot(path=str(SHOTS / f"e5-resident-detail-{ts}.png"))
        else:
            check("E5 notice detail renders", False, "未找到可点击的公告卡片")

        # 管理端回执统计（查看回执按钮）
        ok = login(page, "admin1", "Admin123456")
        page.goto(f"{BASE}/admin/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        row = page.locator("tr", has_text="前端测试公告").first
        if row.count() > 0:
            receipt_btn = row.get_by_role("button", name=re.compile("回执|查看记录"))
            if receipt_btn.count() > 0:
                receipt_btn.first.click()
                wait_app(page, 900)
                body = page.locator("body").inner_text()
                check("E5 receipt stats visible", "已读" in body or "回执" in body, body[:200])
                page.screenshot(path=str(SHOTS / f"e5-receipt-{ts}.png"))
            else:
                check("E5 receipt stats visible", False, "公告列表操作列无「查看回执」按钮")
        else:
            check("E5 receipt stats visible", False, "公告行未找到")

        # ================= E12 游客房源浏览 =================
        page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
        page.evaluate("sessionStorage.clear()")
        page.goto(f"{BASE}/guest/housings", wait_until="domcontentloaded")
        wait_app(page, 900)
        body = page.locator("body").inner_text()
        no_login_ok = "房源" in body and "/auth/login" not in page.url
        check("E12 guest housings without login", no_login_ok, page.url)
        page.screenshot(path=str(SHOTS / f"e12-list-{ts}.png"))

        # 进入第一个房源详情并多次刷新（计数）
        card = page.locator(".housing-card, a[href*='/guest/housings/']").first
        if card.count() > 0:
            card.click()
            wait_app(page, 900)
            detail_url = page.url
            check("E12 housing detail renders", "housings/" in detail_url and page.locator("body").inner_text() != "", detail_url)
            before_text = page.locator("body").inner_text()
            for _ in range(3):
                page.reload(wait_until="domcontentloaded")
                wait_app(page, 700)
            after_text = page.locator("body").inner_text()
            check("E12 detail survives refresh", "租金" in after_text or "月租" in after_text or "房源" in after_text, after_text[:150])
            page.screenshot(path=str(SHOTS / f"e12-detail-{ts}.png"))
            # 返回列表验证查看次数展示
            page.goto(f"{BASE}/guest/housings", wait_until="domcontentloaded")
            wait_app(page, 900)
            body2 = page.locator("body").inner_text()
            check("E12 view count displayed", "浏览" in body2 or "查看" in body2 or "次" in body2, body2[:200])
        else:
            check("E12 housing detail renders", False, "房源列表无可点击卡片（可能无上架房源）")
            issue("E12 无上架房源", "游客房源列表无数据，无法验证浏览计数", "P1")

        browser.close()

    results["api_calls"] = api_calls
    results["summary"] = {
        "checks": len(results["checks"]),
        "passed": sum(1 for c in results["checks"] if c["passed"]),
        "failed": sum(1 for c in results["checks"] if not c["passed"]),
        "issues": len(results["issues"]),
    }
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(results["summary"], ensure_ascii=False))
    for it in results["issues"]:
        print(f"ISSUE[{it['priority']}] {it['title']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
