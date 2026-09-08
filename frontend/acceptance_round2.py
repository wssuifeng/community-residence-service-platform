from __future__ import annotations

"""P1 前端验收 · 第二阶段（修复后复验 + E4/E6 UI 全流程）。

覆盖：
- E5 复验：admin1 建公告草稿（修复 boundCommunities 默认社区）→ 发布 →
  resident1 查看 → 回执统计
- E2 补验：superadmin 审批 fe_test_user4 的入住申请（社区8）→
  居民个人中心居住关系/租约 → 房屋状态翻转
- E4：resident1 提交工单（UI）→ superadmin 派单（UI）
- E6：resident1 提交反馈（UI）→ superadmin 回复（UI）→ 状态自动受理 → 办结
"""

import json
import re
import time
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE = "http://localhost:5173"
OUT = Path("acceptance-round2-results.json")
SHOTS = Path("acceptance-shots")
SHOTS.mkdir(exist_ok=True)

results: dict = {"started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "checks": [], "issues": [], "api_errors": []}


def check(name: str, passed: bool, detail: str = "") -> None:
    results["checks"].append({"name": name, "passed": bool(passed), "detail": detail})
    print(f"{'PASS' if passed else 'FAIL'} | {name} | {detail[:140]}")


def issue(title: str, detail: str, priority: str = "P1") -> None:
    results["issues"].append({"title": title, "detail": detail, "priority": priority})
    print(f"ISSUE[{priority}] {title}: {detail[:140]}")


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


def select_form_item(page, label: str, option_text: str, scope: str = ".el-dialog:visible") -> None:
    item = page.locator(f"{scope} .el-form-item:has(.el-form-item__label:text-is('{label}'))").first
    item.locator(".el-select").first.click()
    page.wait_for_timeout(400)
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text=option_text).first.click()
    page.wait_for_timeout(200)


def main() -> int:
    ts = time.strftime("%H%M%S")
    api_errors: list[str] = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()
        page.on(
            "response",
            lambda res: api_errors.append(f"{res.request.method} {res.url.split('5173')[-1]} -> {res.status}")
            if "/api/" in res.url and res.status >= 400
            else None,
        )

        # ================= E5 复验（boundCommunities 修复后）=================
        ok = login(page, "admin1", "Admin123456")
        check("E5r admin1 login", ok, page.url)
        page.goto(f"{BASE}/admin/notices/create", wait_until="domcontentloaded")
        wait_app(page, 900)
        page.locator('input[placeholder*="标题"]').fill("前端测试公告R")
        page.locator("textarea").first.fill("修复后的复验公告")
        # 社区下拉应有默认选中（修复点）
        default_community = page.locator(".el-form-item:has(.el-form-item__label:text-is('发布社区')) .el-select input").input_value() \
            if page.locator(".el-form-item:has(.el-form-item__label:text-is('发布社区'))").count() > 0 else ""
        check("E5r community default selected", default_community != "", f"default={default_community!r}")
        page.get_by_role("button", name="存草稿").click()
        wait_app(page, 1200)
        draft_saved = "/admin/notices" in page.url and page.get_by_text("前端测试公告R", exact=False).count() > 0
        check("E5r draft saved and in list", draft_saved, page.url)
        page.screenshot(path=str(SHOTS / f"e5r-draft-{ts}.png"))

        # 列表页发布
        row = page.locator("tr", has_text="前端测试公告R").first
        pub = row.get_by_role("button", name=re.compile("发布"))
        if pub.count() > 0:
            pub.first.click()
            wait_app(page, 1000)
            if page.locator(".el-dialog:visible").count() > 0:
                page.locator(".el-dialog:visible .el-dialog__footer button", has_text="确").first.click()
                wait_app(page, 1000)
        row_after = page.locator("tr", has_text="前端测试公告R").first
        published = row_after.count() > 0 and "已发布" in row_after.inner_text()
        check("E5r notice published", published, row_after.inner_text()[:120] if row_after.count() else "row not found")
        page.screenshot(path=str(SHOTS / f"e5r-published-{ts}.png"))

        # resident1 查看公告详情（回执）
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E5r resident login", ok, page.url)
        page.goto(f"{BASE}/resident/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        body = page.locator("body").inner_text()
        check("E5r resident sees notice", "前端测试公告R" in body, body[:150])
        card = page.locator("a, .notice-card, .card, li, article", has_text="前端测试公告R").first
        if card.count() > 0:
            card.click()
            wait_app(page, 900)
            check("E5r notice detail renders", "前端测试公告R" in page.locator("body").inner_text(), page.url)
            page.screenshot(path=str(SHOTS / f"e5r-resident-detail-{ts}.png"))
        else:
            check("E5r notice detail renders", False, "未找到公告卡片")

        # superadmin 查看回执统计
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        row = page.locator("tr", has_text="前端测试公告R").first
        receipt = row.get_by_role("button", name=re.compile("回执|查看记录")) if row.count() else None
        if receipt and receipt.count() > 0:
            receipt.first.click()
            wait_app(page, 900)
            b = page.locator("body").inner_text()
            check("E5r receipt stats visible", "已读" in b or "回执" in b, b[:150])
            page.screenshot(path=str(SHOTS / f"e5r-receipt-{ts}.png"))
        else:
            check("E5r receipt stats visible", False, "公告列表无查看回执入口")

        # ================= E2 补验：superadmin 审批社区8的申请 =================
        page.goto(f"{BASE}/admin/residence-applications", wait_until="domcontentloaded")
        wait_app(page, 900)
        row = page.locator("tr", has_text="前端测试用户D").first
        found = row.count() > 0
        check("E2r application visible to superadmin", found, "superadmin 应见社区8申请")
        if found:
            row.get_by_role("button", name=re.compile("通过|审核|审批")).first.click()
            wait_app(page, 600)
            labels = page.locator(".el-dialog:visible .el-form-item__label").all_inner_texts()
            print("approve labels:", labels)
            for lt in labels:
                if "开始" in lt:
                    it = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{lt}'))").first
                    it.locator("input").first.fill("2026-09-10")
                elif "结束" in lt:
                    it = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{lt}'))").first
                    it.locator("input").first.fill("2027-09-10")
                elif "租金" in lt or "押金" in lt:
                    it = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{lt}'))").first
                    it.locator("input").first.fill("2000")
                elif "意见" in lt:
                    it = page.locator(f".el-dialog:visible .el-form-item:has(.el-form-item__label:text-is('{lt}'))").first
                    it.locator("textarea, input").first.fill("同意入住")
            page.locator(".el-dialog:visible .el-dialog__footer button", has_text="确").first.click()
            wait_app(page, 1400)
            ok_msg = page.locator(".el-message--success").count() > 0
            check("E2r approve succeeds", ok_msg, "审批成功提示")
            page.screenshot(path=str(SHOTS / f"e2r-approved-{ts}.png"))

        # 居民个人中心验证
        ok = login(page, "fe_test_user4", "Test123456", tab="resident")
        check("E2r new resident relogin", ok, page.url)
        page.goto(f"{BASE}/resident/profile", wait_until="domcontentloaded")
        wait_app(page, 1000)
        body = page.locator("body").inner_text()
        check("E2r profile shows active relation", "在住" in body, body[:200])
        check("E2r profile shows lease", "生效" in body or "租住" in body, body[:250])
        page.screenshot(path=str(SHOTS / f"e2r-profile-{ts}.png"))

        # 房屋状态翻转（superadmin 查社区8房屋）
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/houses", wait_until="domcontentloaded")
        wait_app(page, 700)
        selects = page.locator(".filter-panel .el-select")
        selects.nth(0).click(); page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="前端测试社区C").first.click()
        wait_app(page, 400)
        selects.nth(1).click(); page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="A号楼").first.click()
        wait_app(page, 400)
        selects.nth(2).click(); page.wait_for_timeout(300)
        page.locator(".el-select-dropdown:visible .el-select-dropdown__item", has_text="1单元").first.click()
        wait_app(page, 700)
        hrow = page.locator("tr", has_text="101").first
        htext = hrow.inner_text() if hrow.count() else ""
        check("E2r house flipped to occupied", "已入住" in htext or "居住" in htext, htext[:120])
        page.screenshot(path=str(SHOTS / f"e2r-house-{ts}.png"))

        # ================= E4：居民提交工单（UI）=================
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E4 resident login", ok, page.url)
        page.goto(f"{BASE}/resident/work-orders/create", wait_until="domcontentloaded")
        wait_app(page, 1000)
        # 服务类别（分组下拉）
        page.locator(".el-select").first.click()
        page.wait_for_timeout(500)
        opt = page.locator(".el-select-dropdown:visible .el-select-dropdown__item").first
        opt_text = opt.inner_text() if opt.count() else ""
        if opt.count() > 0:
            opt.click()
        page.wait_for_timeout(300)
        check("E4 category options loaded", opt_text != "", f"first={opt_text!r}")
        page.locator('input[placeholder*="一句话概括"]').fill("前端测试工单")
        page.locator("textarea").first.fill("厨房水管漏水，请尽快处理")
        page.locator('input[placeholder*="方便服务人员联系"]').fill("13900000003")
        page.get_by_role("button", name=re.compile("提交|创建|立即提交")).first.click()
        wait_app(page, 1500)
        wo_created = page.url.endswith("/resident/work-orders") or page.locator(".el-message--success").count() > 0
        check("E4 work order submitted", wo_created, page.url)
        page.screenshot(path=str(SHOTS / f"e4-created-{ts}.png"))

        # 找到新工单并取 id（列表第一行）
        page.goto(f"{BASE}/resident/work-orders", wait_until="domcontentloaded")
        wait_app(page, 900)
        wo_row = page.locator("tr", has_text="前端测试工单").first
        if wo_row.count() == 0:
            # 卡片式列表兜底
            wo_row = page.locator(".card, li, article", has_text="前端测试工单").first
        check("E4 work order in my list", wo_row.count() > 0, "我的工单列表应出现新工单")
        row_text = wo_row.inner_text() if wo_row.count() else ""
        check("E4 status pending", "待" in row_text, row_text[:100])

        # superadmin 派单
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/work-orders", wait_until="domcontentloaded")
        wait_app(page, 900)
        arow = page.locator("tr", has_text="前端测试工单").first
        check("E4 admin sees work order", arow.count() > 0, "管理端工单列表")
        detail_btn = arow.get_by_role("button", name=re.compile("详情|查看"))
        if detail_btn.count() > 0:
            detail_btn.first.click()
            wait_app(page, 1000)
        else:
            arow.first.click()
            wait_app(page, 1000)
        page.screenshot(path=str(SHOTS / f"e4-admin-detail-{ts}.png"))
        assign_btn = page.get_by_role("button", name=re.compile("派单"))
        if assign_btn.count() > 0:
            assign_btn.first.click()
            wait_app(page, 600)
            # 选服务人员（staff1）
            sel = page.locator(".el-dialog:visible .el-select").first
            if sel.count() > 0:
                sel.click()
                page.wait_for_timeout(500)
                dd = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
                if dd.count() > 0:
                    dd.first.click()
                    page.wait_for_timeout(200)
            rem = page.locator(".el-dialog:visible textarea")
            if rem.count() > 0:
                rem.first.fill("请尽快联系居民处理")
            page.locator(".el-dialog:visible .el-dialog__footer button", has_text="确").first.click()
            wait_app(page, 1400)
            assigned = page.locator(".el-message--success").count() > 0 or "已派单" in page.locator("body").inner_text()
            check("E4 assign succeeds", assigned, "派单成功")
            page.screenshot(path=str(SHOTS / f"e4-assigned-{ts}.png"))
        else:
            check("E4 assign succeeds", False, "工单详情无派单按钮")

        # ================= E6：反馈会话全流程（UI）=================
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E6 resident login", ok, page.url)
        page.goto(f"{BASE}/resident/feedbacks/create", wait_until="domcontentloaded")
        wait_app(page, 900)
        # 反馈类别下拉
        cat_sel = page.locator(".el-select").first
        if cat_sel.count() > 0:
            cat_sel.click()
            page.wait_for_timeout(400)
            c = page.locator(".el-select-dropdown:visible .el-select-dropdown__item").first
            if c.count() > 0:
                c.click()
        page.locator('input[placeholder*="一句话概括"]').fill("前端测试反馈")
        page.locator("textarea").first.fill("楼道照明损坏，望维修")
        page.get_by_role("button", name=re.compile("提交|创建")).first.click()
        wait_app(page, 1500)
        fb_created = page.url.endswith("/resident/feedbacks") or page.locator(".el-message--success").count() > 0
        check("E6 feedback submitted", fb_created, page.url)
        page.screenshot(path=str(SHOTS / f"e6-created-{ts}.png"))

        # superadmin 打开反馈详情并回复
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/feedbacks", wait_until="domcontentloaded")
        wait_app(page, 900)
        frow = page.locator("tr", has_text="前端测试反馈").first
        check("E6 admin sees feedback", frow.count() > 0, "管理端反馈列表")
        if frow.count() > 0:
            fbtn = frow.get_by_role("button", name=re.compile("详情|查看|处理"))
            if fbtn.count() > 0:
                fbtn.first.click()
            else:
                frow.first.click()
            wait_app(page, 1000)
            page.screenshot(path=str(SHOTS / f"e6-admin-detail-{ts}.png"))
            # 回复消息
            reply_box = page.locator("textarea").last
            if reply_box.count() > 0:
                reply_box.fill("已收到反馈，正在安排处理")
                page.get_by_role("button", name=re.compile("发送|回复")).first.click()
                wait_app(page, 1500)
                body = page.locator("body").inner_text()
                check("E6 admin reply sent", "已收到反馈" in body, body[:200])
                check("E6 status auto in-session", "会话中" in body or "处理中" in body, body[:200])
                page.screenshot(path=str(SHOTS / f"e6-replied-{ts}.png"))
                # 办结
                close_btn = page.get_by_role("button", name=re.compile("办结"))
                if close_btn.count() > 0:
                    close_btn.first.click()
                    wait_app(page, 600)
                    # 办结原因弹窗（ElMessageBox prompt）
                    ta = page.locator(".el-message-box textarea, .el-message-box input")
                    if ta.count() > 0:
                        ta.first.fill("已修复完毕")
                    page.locator(".el-message-box__btns button", has_text="确认").first.click()
                    wait_app(page, 1400)
                    body2 = page.locator("body").inner_text()
                    check("E6 feedback closed", "已办结" in body2, body2[:200])
                    page.screenshot(path=str(SHOTS / f"e6-closed-{ts}.png"))
                else:
                    check("E6 feedback closed", False, "详情页无办结按钮")
            else:
                check("E6 admin reply sent", False, "详情页无回复输入框")

        browser.close()

    results["api_errors"] = api_errors
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
