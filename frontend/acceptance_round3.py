from __future__ import annotations

"""P1 前端验收 · 第三阶段（E5 发布链复验 + E4 工单 + E6 反馈，全 UI）。

修正点（对照第二轮失败归因）：
- 发布确认走 ElMessageBox（.el-message-box），非 el-dialog
- E4 工单表单：description→content、urgency→priority 已适配后端
- E2r 审批链路已验证（申请5 APPROVED + 房屋6 OCCUPIED + 关系/租约 ACTIVE），不再重跑
"""

import json
import re
import time
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE = "http://localhost:5173"
OUT = Path("acceptance-round3-results.json")
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

        # ================= E5 发布链（草稿6 已存在，UI 发布）=================
        ok = login(page, "admin1", "Admin123456")
        check("E5p admin1 login", ok, page.url)
        page.goto(f"{BASE}/admin/notices", wait_until="domcontentloaded")
        wait_app(page, 900)
        rows = page.locator("tr", has_text="前端测试公告R")
        check("E5p drafts visible", rows.count() >= 1, f"rows={rows.count()}")
        row = rows.first
        pub = row.get_by_role("button", name=re.compile("^发布$"))
        check("E5p publish button exists", pub.count() > 0, "行内发布按钮")
        if pub.count() > 0:
            pub.first.click()
            wait_app(page, 600)
            # ElMessageBox 确认
            box = page.locator(".el-message-box")
            if box.count() > 0:
                box.get_by_role("button", name="发布").click()
                wait_app(page, 1200)
            row_after = page.locator("tr", has_text="前端测试公告R").first
            published = row_after.count() > 0 and "已发布" in row_after.inner_text()
            check("E5p notice published", published, row_after.inner_text()[:100] if row_after.count() else "no row")
            page.screenshot(path=str(SHOTS / f"e5p-published-{ts}.png"))
            notice_id = 6  # 草稿6

            # resident1 查看公告
            ok = login(page, "resident1", "Resident123456", tab="resident")
            check("E5p resident login", ok, page.url)
            page.goto(f"{BASE}/resident/notices", wait_until="domcontentloaded")
            wait_app(page, 900)
            body = page.locator("body").inner_text()
            check("E5p resident sees published notice", "前端测试公告R" in body, body[:150])
            card = page.locator(".notice-card", has_text="前端测试公告R").first
            if card.count() > 0:
                card.click()
                wait_app(page, 900)
                check("E5p notice detail renders", "前端测试公告R" in page.locator("body").inner_text(), page.url)
                page.screenshot(path=str(SHOTS / f"e5p-resident-detail-{ts}.png"))
            else:
                check("E5p notice detail renders", False, "公告卡片未找到")

            # superadmin 回执统计（详情页 viewers）
            ok = login(page, "superadmin", "Admin@123456")
            page.goto(f"{BASE}/admin/notices/{notice_id}", wait_until="domcontentloaded")
            wait_app(page, 1000)
            body = page.locator("body").inner_text()
            check("E5p receipt stats visible", "已读" in body or "查看" in body, body[:200])
            page.screenshot(path=str(SHOTS / f"e5p-receipt-{ts}.png"))

        # ================= E4：居民提交工单 → 管理端派单 =================
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E4 resident login", ok, page.url)
        page.goto(f"{BASE}/resident/work-orders/create", wait_until="domcontentloaded")
        wait_app(page, 1200)
        page.locator(".el-select").first.click()
        page.wait_for_timeout(600)
        dd = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
        cat_ok = dd.count() > 0
        if cat_ok:
            dd.first.click()
        page.wait_for_timeout(300)
        check("E4 category options loaded", cat_ok, f"count={dd.count()}")
        page.locator('input[placeholder*="一句话概括"]').fill("验收工单Z")
        page.locator("textarea").first.fill("厨房水管漏水，请尽快处理")
        page.locator('input[placeholder*="方便服务人员联系"]').fill("13900000003")
        page.get_by_role("button", name=re.compile("提交|创建")).first.click()
        wait_app(page, 1800)
        wo_ok = "/resident/work-orders/" in page.url and "/create" not in page.url
        check("E4 work order submitted (redirects to detail)", wo_ok, page.url)
        page.screenshot(path=str(SHOTS / f"e4-detail-{ts}.png"))
        body = page.locator("body").inner_text()
        check("E4 initial status", "待" in body, body[:150])
        wo_id = page.url.rstrip("/").split("/")[-1]

        # superadmin 派单
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/work-orders/{wo_id}", wait_until="domcontentloaded")
        wait_app(page, 1000)
        assign_btn = page.get_by_role("button", name=re.compile("派单"))
        check("E4 assign button visible", assign_btn.count() > 0, "详情页派单按钮")
        if assign_btn.count() > 0:
            assign_btn.first.click()
            wait_app(page, 700)
            sel = page.locator(".el-dialog:visible .el-select").first
            if sel.count() > 0:
                sel.click()
                page.wait_for_timeout(600)
                dd = page.locator(".el-select-dropdown:visible .el-select-dropdown__item")
                staff_ok = dd.count() > 0
                if staff_ok:
                    dd.first.click()
                check("E4 staff options loaded", staff_ok, f"count={dd.count()}")
            rem = page.locator(".el-dialog:visible textarea")
            if rem.count() > 0:
                rem.first.fill("请尽快联系居民处理")
            page.locator(".el-dialog:visible .el-dialog__footer button", has_text="确").first.click()
            wait_app(page, 1500)
            body = page.locator("body").inner_text()
            assigned = "已派单" in body or "已接单" in body
            check("E4 assigned", assigned, body[:150])
            page.screenshot(path=str(SHOTS / f"e4-assigned-{ts}.png"))

        # ================= E6：反馈会话全流程 =================
        ok = login(page, "resident1", "Resident123456", tab="resident")
        check("E6 resident login", ok, page.url)
        page.goto(f"{BASE}/resident/feedbacks/create", wait_until="domcontentloaded")
        wait_app(page, 1000)
        cat_sel = page.locator(".el-select").first
        if cat_sel.count() > 0:
            cat_sel.click()
            page.wait_for_timeout(500)
            c = page.locator(".el-select-dropdown:visible .el-select-dropdown__item").first
            if c.count() > 0:
                c.click()
        page.locator('input[placeholder*="一句话概括"]').fill("验收反馈Z")
        page.locator("textarea").first.fill("楼道照明损坏，望维修")
        page.get_by_role("button", name=re.compile("提交|创建")).first.click()
        wait_app(page, 1800)
        fb_ok = "/resident/feedbacks/" in page.url and "/create" not in page.url
        check("E6 feedback submitted (redirects to detail)", fb_ok, page.url)
        page.screenshot(path=str(SHOTS / f"e6-created-{ts}.png"))
        fb_id = page.url.rstrip("/").split("/")[-1]

        # superadmin 回复
        ok = login(page, "superadmin", "Admin@123456")
        page.goto(f"{BASE}/admin/feedbacks/{fb_id}", wait_until="domcontentloaded")
        wait_app(page, 1200)
        reply_box = page.locator("textarea").last
        check("E6 reply box visible", reply_box.count() > 0, "详情页回复输入框")
        if reply_box.count() > 0:
            reply_box.fill("已收到反馈，正在安排处理")
            page.get_by_role("button", name=re.compile("发送|回复")).first.click()
            wait_app(page, 1800)
            body = page.locator("body").inner_text()
            check("E6 admin reply sent", "已收到反馈" in body, body[:200])
            check("E6 status auto in-session", "会话中" in body or "受理" in body, body[:200])
            page.screenshot(path=str(SHOTS / f"e6-replied-{ts}.png"))

            # 办结
            close_btn = page.get_by_role("button", name=re.compile("办结"))
            if close_btn.count() > 0:
                close_btn.first.click()
                wait_app(page, 700)
                ta = page.locator(".el-message-box textarea, .el-message-box input")
                if ta.count() > 0:
                    ta.first.fill("已修复完毕")
                page.locator(".el-message-box__btns button", has_text="确认").first.click()
                wait_app(page, 1600)
                body2 = page.locator("body").inner_text()
                check("E6 feedback closed", "已办结" in body2, body2[:200])
                page.screenshot(path=str(SHOTS / f"e6-closed-{ts}.png"))
            else:
                check("E6 feedback closed", False, "无办结按钮")
        else:
            check("E6 admin reply sent", False, "无回复框")

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
    for e in api_errors[:10]:
        print("API-ERR:", e)
    for it in results["issues"]:
        print(f"ISSUE[{it['priority']}] {it['title']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
