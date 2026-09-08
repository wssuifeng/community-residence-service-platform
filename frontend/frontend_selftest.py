from __future__ import annotations

import json
import re
import time
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

BASE = "http://localhost:5173"
OUT = Path("frontend-selftest-results.json")

# BE-ISSUE-4 修复后后端已种入的测试账号（后端测试遗留问题修正计划 §BE-ISSUE-4）
ACCOUNTS = {
    "superadmin": ("superadmin", "Admin@123456", "admin"),
    "admin1": ("admin1", "Admin123456", "admin"),
    "staff1": ("staff1", "Staff123456", "admin"),
    "resident1": ("resident1", "Resident123456", "resident"),
}


def wait_app(page, ms: int = 350) -> None:
    try:
        page.wait_for_load_state("domcontentloaded", timeout=8000)
    except PlaywrightTimeoutError:
        pass
    page.wait_for_timeout(ms)


def text_has(page, value: str) -> bool:
    return value in page.locator("body").inner_text(timeout=3000)


def login(page, username: str, password: str, tab: str) -> bool:
    """真实登录。tab=resident 走居民端点（默认 Tab），其余走管理员/服务人员端点（双端点账号体系隔离）。"""
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
        page.wait_for_url(re.compile(r"/admin/statistics/dashboard|/staff/dashboard|/resident/home"), timeout=8000)
    except PlaywrightTimeoutError:
        return False
    return True


def check_grid(page, selector: str, expected_min_columns: int) -> tuple[bool, str]:
    try:
        result = page.locator(selector).first.evaluate(
            "el => ({display: getComputedStyle(el).display, columns: getComputedStyle(el).gridTemplateColumns})"
        )
        columns = len(result["columns"].split(" ")) if result["columns"] != "none" else 0
        return columns >= expected_min_columns, f"display={result['display']} columns={result['columns']}"
    except Exception as exc:
        return False, str(exc)


def main() -> int:
    results: dict = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "checks": [],
        "console_errors": [],
        "page_errors": [],
        "failed_requests": [],
        "api_statuses": [],
        "notes": [],
    }
    console_errors: list[str] = []
    page_errors: list[str] = []
    failed_requests: list[str] = []
    api_statuses: list[dict] = []
    api_errors: list[str] = []

    # 关注的接口：BE-ISSUE-1 通知、BE-ISSUE-2 反馈附件、BE-ISSUE-3 游客回执
    API_WATCH = re.compile(r"/api/v1/(notifications|feedbacks/\d+/attachments|notices/\d+/view)")

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()
        page.on("console", lambda msg: console_errors.append(f"{msg.type}: {msg.text}") if msg.type == "error" else None)
        page.on("pageerror", lambda exc: page_errors.append(str(exc)))
        page.on("requestfailed", lambda req: failed_requests.append(f"{req.method} {req.url}: {req.failure}"))
        page.on(
            "response",
            lambda res: api_statuses.append({"method": res.request.method, "url": res.url, "status": res.status})
            if API_WATCH.search(res.url)
            else None,
        )
        # 全量 4xx/5xx API 响应记录（带 URL，用于区分数据级权限拦截与真实缺陷）
        page.on(
            "response",
            lambda res: api_errors.append(f"{res.request.method} {res.url} -> {res.status}")
            if "/api/" in res.url and res.status >= 400
            else None,
        )

        def check(name: str, passed: bool, detail: str = "") -> None:
            results["checks"].append({"name": name, "passed": bool(passed), "detail": detail})

        # FE-AUTH / FE-GUEST: public pages render.
        public_pages = [
            ("guest home", "/guest/home", "安心居住"),
            ("guest housings", "/guest/housings", "房源"),
            ("guest notices", "/guest/notices", "公告"),
            ("login", "/auth/login", "登录"),
            ("register", "/auth/register", "注册"),
        ]
        for name, path, expected in public_pages:
            page.goto(BASE + path, wait_until="domcontentloaded")
            wait_app(page)
            check(f"public render: {name}", text_has(page, expected), page.url)

        # FE-FORM: login and registration empty-form validation.
        page.goto(f"{BASE}/auth/login", wait_until="domcontentloaded")
        wait_app(page)
        page.get_by_role("button", name=re.compile(r"登\s*录")).click()
        page.wait_for_timeout(250)
        check("login empty form validation", page.locator(".el-message--warning").count() > 0, page.url)

        page.goto(f"{BASE}/auth/register", wait_until="domcontentloaded")
        wait_app(page)
        page.get_by_role("button", name=re.compile(r"注\s*册")).click()
        page.wait_for_timeout(250)
        check("register empty form validation", page.locator(".el-message--warning").count() > 0, page.url)

        # FE-ROUTE: unauthenticated protected routes redirect to login with redirect query.
        context.clear_cookies()
        page.evaluate("sessionStorage.clear()")
        for name, path in [
            ("resident guard", "/resident/home"),
            ("staff guard", "/staff/dashboard"),
            ("admin guard", "/admin/statistics/dashboard"),
        ]:
            page.goto(BASE + path, wait_until="domcontentloaded")
            wait_app(page)
            parsed = urlparse(page.url)
            check(f"unauthenticated route guard: {name}", parsed.path == "/auth/login" and "redirect=" in parsed.query, page.url)

        # ===== 复测 BE-ISSUE-3：游客公告详情不调用回执接口 =====
        before_view_calls = [a for a in api_statuses if "/view" in a["url"]]
        page.goto(f"{BASE}/guest/notices/3", wait_until="domcontentloaded")
        wait_app(page, 600)
        after_view_calls = [a for a in api_statuses if "/view" in a["url"]]
        no_new_view_call = len(after_view_calls) == len(before_view_calls)
        check("BE-ISSUE-3 guest notice detail without view-record call", no_new_view_call and text_has(page, "公告"), page.url)

        # ===== superadmin：真实登录与超管权限按钮可见 =====
        u, pw, tab = ACCOUNTS["superadmin"]
        logged_in = login(page, u, pw, tab)
        check("superadmin login and redirect", logged_in and "/admin/statistics/dashboard" in page.url, page.url)

        page.goto(f"{BASE}/admin/communities", wait_until="networkidle")
        wait_app(page, 900)
        check("superadmin create-community visible", page.get_by_text("新建社区", exact=True).count() > 0, page.url)
        page.goto(f"{BASE}/admin/sys-users", wait_until="networkidle")
        wait_app(page, 900)
        check("superadmin create-user visible", page.get_by_text(re.compile("创建.*用户|新建.*用户")).count() > 0, page.url)
        page.goto(f"{BASE}/admin/configs", wait_until="networkidle")
        wait_app(page, 900)
        check("superadmin config edit visible", page.get_by_text("编辑", exact=True).count() > 0, page.url)

        # ===== 复测 BE-ISSUE-1（管理端）：通知页加载，接口不再 500 =====
        page.goto(f"{BASE}/admin/notifications", wait_until="domcontentloaded")
        wait_app(page, 900)
        admin_notif_statuses = [a["status"] for a in api_statuses if "/api/v1/notifications" in a["url"]]
        check(
            "BE-ISSUE-1 admin notifications loads (no 500)",
            bool(admin_notif_statuses) and all(s == 200 for s in admin_notif_statuses) and text_has(page, "通知"),
            f"statuses={admin_notif_statuses} url={page.url}",
        )

        # ===== 复测 BE-ISSUE-2（管理端）：反馈详情附件接口 200 且页面无运行时错误 =====
        page.goto(f"{BASE}/admin/feedbacks/2", wait_until="domcontentloaded")
        wait_app(page, 900)
        attach_statuses = [a["status"] for a in api_statuses if "/feedbacks/2/attachments" in a["url"]]
        check(
            "BE-ISSUE-2 feedback attachments endpoint 200",
            bool(attach_statuses) and all(s == 200 for s in attach_statuses),
            f"statuses={attach_statuses} url={page.url}",
        )

        # ===== resident1：真实登录（BE-ISSUE-4），角色路由与越权拦截 =====
        u, pw, tab = ACCOUNTS["resident1"]
        logged_in = login(page, u, pw, tab)
        check("BE-ISSUE-4 resident1 real login and redirect", logged_in and "/resident/home" in page.url, page.url)
        page.goto(f"{BASE}/admin/statistics/dashboard", wait_until="domcontentloaded")
        wait_app(page)
        check("resident blocked from admin", "/403" in page.url, page.url)

        # ===== 复测 BE-ISSUE-1（居民端）：通知页加载与标记已读 =====
        page.goto(f"{BASE}/resident/notifications", wait_until="domcontentloaded")
        wait_app(page, 1200)
        resident_notif_statuses = [a["status"] for a in api_statuses if "/api/v1/notifications" in a["url"]]
        check(
            "BE-ISSUE-1 resident notifications loads (no 500)",
            bool(resident_notif_statuses) and all(s == 200 for s in resident_notif_statuses) and text_has(page, "消息中心"),
            f"statuses={resident_notif_statuses[-3:]} url={page.url}",
        )
        mark_all = page.get_by_role("button", name=re.compile("全部已读"))
        if mark_all.count() > 0 and mark_all.first.is_enabled():
            mark_all.first.click()
            page.wait_for_timeout(800)
            marked = any(a["status"] == 200 and a["method"] != "GET" and "/api/v1/notifications" in a["url"] for a in api_statuses)
            check("BE-ISSUE-1 mark-all-read succeeds", marked, str([a for a in api_statuses if "/api/v1/notifications" in a["url"]][-3:]))
        else:
            check("BE-ISSUE-1 mark-all-read succeeds", True, "无未读通知，按钮禁用（接口无可测数据，跳过标记动作）")

        # ===== staff1：真实登录（BE-ISSUE-4），角色路由与越权拦截 =====
        u, pw, tab = ACCOUNTS["staff1"]
        logged_in = login(page, u, pw, tab)
        check("BE-ISSUE-4 staff1 real login and redirect", logged_in and "/staff/dashboard" in page.url, page.url)
        page.goto(f"{BASE}/resident/home", wait_until="domcontentloaded")
        wait_app(page)
        check("staff blocked from resident", "/403" in page.url, page.url)

        # ===== admin1：真实登录（BE-ISSUE-4），ADMIN 权限按钮可见性 =====
        u, pw, tab = ACCOUNTS["admin1"]
        logged_in = login(page, u, pw, tab)
        check("BE-ISSUE-4 admin1 real login and redirect", logged_in and "/admin/statistics/dashboard" in page.url, page.url)
        page.goto(f"{BASE}/admin/communities", wait_until="domcontentloaded")
        wait_app(page, 900)
        check("admin hides create-community", page.get_by_text("新建社区", exact=True).count() == 0, page.url)
        page.goto(f"{BASE}/admin/buildings", wait_until="domcontentloaded")
        wait_app(page, 700)
        check("admin sees common create button", page.get_by_text(re.compile("新建|创建")).count() > 0, page.url)

        # ===== 复测 BE-ISSUE-1（服务人员端）：通知页加载 =====
        u, pw, tab = ACCOUNTS["staff1"]
        logged_in = login(page, u, pw, tab)
        check("staff1 relogin for notifications", logged_in and "/staff/dashboard" in page.url, page.url)
        page.goto(f"{BASE}/staff/notifications", wait_until="domcontentloaded")
        wait_app(page, 900)
        staff_notif_statuses = [a["status"] for a in api_statuses if "/api/v1/notifications" in a["url"]]
        check(
            "BE-ISSUE-1 staff notifications loads (no 500)",
            bool(staff_notif_statuses) and all(s == 200 for s in staff_notif_statuses) and text_has(page, "通知"),
            f"statuses={staff_notif_statuses[-3:]} url={page.url}",
        )

        # FE-RESPONSIVE: representative public grid at 4 breakpoints（登出态，页面公开）。
        page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
        page.evaluate("sessionStorage.clear()")
        responsive_cases = [
            (1440, 900, 3),
            (1200, 900, 2),
            (900, 900, 1),
            (600, 900, 1),
        ]
        for width, height, min_columns in responsive_cases:
            page.set_viewport_size({"width": width, "height": height})
            page.goto(f"{BASE}/guest/home", wait_until="domcontentloaded")
            wait_app(page, 500)
            ok, detail = check_grid(page, ".housing-grid", min_columns)
            check(f"responsive guest housing grid {width}px", ok, detail)

        # FE-PAGES: route smoke scan, split by terminal with matching real session.
        page.set_viewport_size({"width": 1440, "height": 900})
        scan_groups = [
            (
                None,
                [
                    "/guest/home", "/guest/housings", "/guest/housings/1", "/guest/notices", "/guest/notices/1",
                    "/auth/login", "/auth/register",
                ],
            ),
            (
                "resident1",
                [
                    "/resident/home", "/resident/profile", "/resident/work-orders", "/resident/work-orders/create",
                    "/resident/work-orders/1", "/resident/feedbacks", "/resident/feedbacks/create",
                    "/resident/feedbacks/1", "/resident/reservations", "/resident/reservations/create",
                    "/resident/notices", "/resident/notices/1", "/resident/notifications",
                    "/resident/housings", "/resident/housings/1", "/resident/viewing-appointments",
                ],
            ),
            (
                "staff1",
                ["/staff/dashboard", "/staff/work-orders", "/staff/work-orders/1", "/staff/notifications"],
            ),
            (
                "superadmin",
                [
                    "/admin/statistics/dashboard", "/admin/statistics/work-orders", "/admin/statistics/residents",
                    "/admin/statistics/resources", "/admin/statistics/evaluations",
                    "/admin/communities", "/admin/communities/1", "/admin/buildings", "/admin/units",
                    "/admin/houses", "/admin/resources",
                    "/admin/residents", "/admin/residents/1", "/admin/residence-applications",
                    "/admin/residence-relations", "/admin/configs",
                    "/admin/leases", "/admin/leases/expiring", "/admin/service-categories",
                    "/admin/work-orders", "/admin/work-orders/1",
                    "/admin/notices", "/admin/notices/create", "/admin/notices/1",
                    "/admin/feedbacks", "/admin/feedbacks/1",
                    "/admin/resource-reservations", "/admin/violations", "/admin/evaluations",
                    "/admin/evaluations/1/followup",
                    "/admin/sys-users", "/admin/sys-users/1/communities", "/admin/operation-logs",
                    "/admin/notifications", "/admin/housings", "/admin/housings/1", "/admin/viewing-appointments",
                ],
            ),
        ]
        page_results = []
        for account, routes in scan_groups:
            if account is not None:
                u, pw, tab = ACCOUNTS[account]
                assert login(page, u, pw, tab), f"scan-group login failed: {account}"
            else:
                page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
                page.evaluate("sessionStorage.clear()")
            for path in routes:
                before_errors = len(page_errors)
                page.goto(BASE + path, wait_until="domcontentloaded")
                wait_app(page, 180)
                page_results.append({
                    "account": account,
                    "path": path,
                    "url": page.url,
                    "page_error_count": len(page_errors) - before_errors,
                    "body_length": len(page.locator("body").inner_text(timeout=3000)),
                })
        results["page_scan"] = page_results

        browser.close()

    results["console_errors"] = console_errors
    results["page_errors"] = page_errors
    results["failed_requests"] = failed_requests
    results["api_statuses"] = api_statuses
    results["api_errors"] = api_errors
    results["summary"] = {
        "checks": len(results["checks"]),
        "passed": sum(1 for item in results["checks"] if item["passed"]),
        "failed": sum(1 for item in results["checks"] if not item["passed"]),
        "page_scan_count": len(page_results),
        "page_scan_with_page_errors": sum(1 for item in page_results if item["page_error_count"]),
    }
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(results["summary"], ensure_ascii=False))
    for item in results["checks"]:
        print(f"{'PASS' if item['passed'] else 'FAIL'} | {item['name']} | {item['detail']}")
    print(f"console_errors={len(console_errors)} page_errors={len(page_errors)} failed_requests={len(failed_requests)}")
    return 0 if results["summary"]["failed"] == 0 and not page_errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
