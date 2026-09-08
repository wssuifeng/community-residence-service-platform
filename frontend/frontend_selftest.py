from __future__ import annotations

import json
import re
import time
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

BASE = "http://localhost:5173"
OUT = Path("frontend-selftest-results.json")


def wait_app(page, ms: int = 350) -> None:
    try:
        page.wait_for_load_state("domcontentloaded", timeout=8000)
    except PlaywrightTimeoutError:
        pass
    page.wait_for_timeout(ms)


def text_has(page, value: str) -> bool:
    return value in page.locator("body").inner_text(timeout=3000)


def login(page, username: str = "superadmin", password: str = "Admin@123456") -> bool:
    # 已登录态访问 /auth/login 会被守卫重定向，先清理会话
    page.goto(BASE + "/guest/home", wait_until="domcontentloaded")
    page.evaluate("sessionStorage.clear()")
    page.goto(f"{BASE}/auth/login", wait_until="domcontentloaded")
    wait_app(page)
    # 种子账号是系统用户：先切到管理员/服务人员 Tab（双端点账号体系隔离）
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


def set_fake_role(page, role: str) -> None:
    session = page.evaluate("sessionStorage.getItem('crsp_session')")
    assert session, "expected a real session before role mutation"
    data = json.loads(session)
    data["user"]["role"] = role
    data["user"]["realName"] = f"测试{role}"
    page.evaluate("data => sessionStorage.setItem('crsp_session', JSON.stringify(data))", data)


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
        "notes": [],
    }
    console_errors: list[str] = []
    page_errors: list[str] = []
    failed_requests: list[str] = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()
        page.on("console", lambda msg: console_errors.append(f"{msg.type}: {msg.text}") if msg.type == "error" else None)
        page.on("pageerror", lambda exc: page_errors.append(str(exc)))
        page.on("requestfailed", lambda req: failed_requests.append(f"{req.method} {req.url}: {req.failure}"))

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
        for name, path in [("resident guard", "/resident/home"), ("staff guard", "/staff/dashboard"), ("admin guard", "/admin/statistics/dashboard")]:
            page.goto(BASE + path, wait_until="domcontentloaded")
            wait_app(page)
            parsed = urlparse(page.url)
            check(f"unauthenticated route guard: {name}", parsed.path == "/auth/login" and "redirect=" in parsed.query, page.url)

        # Authenticate with the only seeded account.
        logged_in = login(page)
        check("superadmin login and redirect", logged_in and "/admin/statistics/dashboard" in page.url, page.url)

        # FE-PERM: superadmin controls visible.
        page.wait_for_timeout(1200)
        results["notes"].append(f"pre-perm url={page.url}")
        page.goto(f"{BASE}/admin/communities", wait_until="networkidle")
        wait_app(page, 900)
        has_session = page.evaluate("() => sessionStorage.getItem('crsp_session') !== null")
        body_head = page.locator("body").inner_text(timeout=3000)[:80].replace("\n", "|")
        results["notes"].append(f"perm1 url={page.url} session={has_session} body={body_head!r}")
        check("superadmin create-community visible", page.get_by_text("新建社区", exact=True).count() > 0, page.url)
        page.goto(f"{BASE}/admin/sys-users", wait_until="networkidle")
        wait_app(page, 900)
        check("superadmin create-user visible", page.get_by_text(re.compile("创建.*用户|新建.*用户")).count() > 0, page.url)
        page.goto(f"{BASE}/admin/configs", wait_until="networkidle")
        wait_app(page, 900)
        check("superadmin config edit visible", page.get_by_text("编辑", exact=True).count() > 0, page.url)

        # FE-ROUTE: role home redirects and role mismatch using a valid token with client-side role mutation.
        set_fake_role(page, "RESIDENT")
        page.goto(f"{BASE}/auth/login", wait_until="domcontentloaded")
        wait_app(page)
        check("resident home redirect", "/resident/home" in page.url, page.url)
        page.goto(f"{BASE}/admin/statistics/dashboard", wait_until="domcontentloaded")
        wait_app(page)
        check("resident blocked from admin", "/403" in page.url, page.url)

        set_fake_role(page, "STAFF")
        page.goto(f"{BASE}/auth/login", wait_until="domcontentloaded")
        wait_app(page)
        check("staff home redirect", "/staff/dashboard" in page.url, page.url)
        page.goto(f"{BASE}/resident/home", wait_until="domcontentloaded")
        wait_app(page)
        check("staff blocked from resident", "/403" in page.url, page.url)

        # FE-PERM: ADMIN role hides superadmin-only controls but keeps common controls.
        # Re-login to restore a valid token/session, then mutate only the client role.
        logged_in = login(page)
        check("restore superadmin session", logged_in, page.url)
        set_fake_role(page, "ADMIN")
        page.goto(f"{BASE}/admin/communities", wait_until="domcontentloaded")
        wait_app(page, 700)
        check("admin hides create-community", page.get_by_text("新建社区", exact=True).count() == 0, page.url)
        page.goto(f"{BASE}/admin/buildings", wait_until="domcontentloaded")
        wait_app(page, 700)
        check("admin sees common create button", page.get_by_text(re.compile("新建|创建")).count() > 0, page.url)

        # FE-RESPONSIVE: representative public and dashboard grids at 4 breakpoints.
        set_fake_role(page, "SUPER_ADMIN")
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

        # FE-PAGES: route smoke scan for all functional route families. API errors are recorded separately.
        page.set_viewport_size({"width": 1440, "height": 900})
        functional_routes = [
            "/guest/home", "/guest/housings", "/guest/housings/1", "/guest/notices", "/guest/notices/1",
            "/auth/login", "/auth/register",
            "/resident/home", "/resident/profile", "/resident/work-orders", "/resident/work-orders/create", "/resident/work-orders/1",
            "/resident/feedbacks", "/resident/feedbacks/create", "/resident/feedbacks/1", "/resident/reservations", "/resident/reservations/create",
            "/resident/notices", "/resident/notices/1", "/resident/notifications", "/resident/housings", "/resident/housings/1", "/resident/viewing-appointments",
            "/staff/dashboard", "/staff/work-orders", "/staff/work-orders/1", "/staff/notifications",
            "/admin/statistics/dashboard", "/admin/statistics/work-orders", "/admin/statistics/residents", "/admin/statistics/resources", "/admin/statistics/evaluations",
            "/admin/communities", "/admin/communities/1", "/admin/buildings", "/admin/units", "/admin/houses", "/admin/resources",
            "/admin/residents", "/admin/residents/1", "/admin/residence-applications", "/admin/residence-relations", "/admin/configs",
            "/admin/leases", "/admin/leases/expiring", "/admin/service-categories", "/admin/work-orders", "/admin/work-orders/1",
            "/admin/notices", "/admin/notices/create", "/admin/notices/1", "/admin/feedbacks", "/admin/feedbacks/1",
            "/admin/resource-reservations", "/admin/violations", "/admin/evaluations", "/admin/evaluations/1/followup",
            "/admin/sys-users", "/admin/sys-users/1/communities", "/admin/operation-logs", "/admin/notifications",
            "/admin/housings", "/admin/housings/1", "/admin/viewing-appointments",
        ]
        page_results = []
        for path in functional_routes:
            before_errors = len(page_errors)
            page.goto(BASE + path, wait_until="domcontentloaded")
            wait_app(page, 180)
            page_results.append({
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
    results["summary"] = {
        "checks": len(results["checks"]),
        "passed": sum(1 for item in results["checks"] if item["passed"]),
        "failed": sum(1 for item in results["checks"] if not item["passed"]),
        "page_scan_count": len(results.get("page_scan", [])),
        "page_scan_with_page_errors": sum(1 for item in results.get("page_scan", []) if item["page_error_count"]),
    }
    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(results["summary"], ensure_ascii=False))
    for item in results["checks"]:
        print(f"{'PASS' if item['passed'] else 'FAIL'} | {item['name']} | {item['detail']}")
    print(f"console_errors={len(console_errors)} page_errors={len(page_errors)} failed_requests={len(failed_requests)}")
    return 0 if results["summary"]["failed"] == 0 and not page_errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
