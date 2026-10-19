"""Observe the actual C9 dashboard with a separate, read-only browser session."""
from pathlib import Path
from datetime import datetime
from urllib.parse import urlparse, parse_qsl
import hashlib
import json
import os
import re
import subprocess
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
result = {"date": datetime.now().astimezone().isoformat(), "scope": "C9需求验收首批；独立真实浏览器、正常统计页面只读观察", "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "observations": [], "http": [], "blocked_requests": [], "page_errors": [], "financial_requests": 0, "business_write_requests": 0, "limitation": "本批不核对统计值与数据库明细、不验证全部角色授权、不重放金融；不属于质量评估或N8完整兼容性验收。"}

def save():
    (HERE / "runtime-observations.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge", headless=True)
    result["browser"] = {"engine": "Chromium", "channel": "msedge", "version": browser.version, "viewport": {"width": 1440, "height": 1024}}
    context = browser.new_context(viewport={"width": 1440, "height": 1024}, locale="zh-CN")
    def guard(route):
        request = route.request
        path = urlparse(request.url).path
        if path.startswith("/api/v1/payment"):
            result["blocked_requests"].append({"method": request.method, "path": path, "reason": "金融边界"})
            route.abort()
            return
        if request.method not in {"GET", "HEAD", "OPTIONS"} and path != "/api/v1/auth/admin/login":
            result["blocked_requests"].append({"method": request.method, "path": path, "reason": "只读业务核对"})
            route.abort()
            return
        route.continue_()
    context.route("**/*", guard)
    page = context.new_page()
    page.on("pageerror", lambda error: result["page_errors"].append(str(error)[:500]))
    def record(response):
        parsed = urlparse(response.url)
        if parsed.path.startswith("/api/v1/statistics/"):
            result["http"].append({"method": response.request.method, "path": parsed.path, "query_parameter_names": sorted(k for k, _ in parse_qsl(parsed.query)), "status": response.status})
    page.on("response", record)
    try:
        page.goto("http://127.0.0.1:5176/auth/login", wait_until="domcontentloaded")
        page.get_by_role("tab", name="管理员 / 服务人员", exact=True).click()
        page.get_by_placeholder("请输入用户名或注册手机号").fill(os.environ.get("PHASE60_ADMIN_USERNAME", "superadmin"))
        page.get_by_placeholder("请输入密码", exact=True).fill(os.environ["PHASE60_ADMIN_PASSWORD"])
        page.locator('button[type="submit"]').click()
        page.wait_for_url(re.compile(r"/admin/"), timeout=20000)
        result["authentication"] = "独立会话登录成功；凭据/令牌不保存"
        page.goto("http://127.0.0.1:5176/admin/dashboard", wait_until="domcontentloaded")
        page.get_by_role("heading", name="运营看板", exact=True).wait_for(state="visible")
        page.wait_for_function("document.querySelectorAll('.overview-section, .overview-grid, .metrics-grid, .stat-card, canvas').length > 0", timeout=15000)
        def observe(name, filename):
            page.wait_for_function("![...document.querySelectorAll('.el-loading-mask')].some(x=>x.getBoundingClientRect().width>0)", timeout=15000)
            page.wait_for_timeout(600)
            root = page.locator('.admin-page').first
            data = root.evaluate("""el => ({
              headings: [...el.querySelectorAll('h1,h2,h3')].filter(x=>x.getBoundingClientRect().width>0).map(x=>x.innerText),
              tabs: [...el.querySelectorAll('[role=tab]')].filter(x=>x.getBoundingClientRect().width>0).map(x=>x.innerText.trim()),
              visibleButtons: [...el.querySelectorAll('button')].filter(x=>x.getBoundingClientRect().width>0).map(x=>({text:x.innerText.trim(),label:x.getAttribute('aria-label'),title:x.getAttribute('title')})),
              inputs: [...el.querySelectorAll('input')].filter(x=>x.getBoundingClientRect().width>0).map(x=>({type:x.type,placeholder:x.placeholder,label:x.getAttribute('aria-label')})),
              chartCount: [...el.querySelectorAll('canvas')].filter(x=>x.getBoundingClientRect().width>0).length,
              visibleDatePickers: [...el.querySelectorAll('.el-date-editor,input[type=date],input[type=datetime-local]')].filter(x=>x.getBoundingClientRect().width>0).length,
              exportControls: [...el.querySelectorAll('button,a,[role=button]')].filter(x=>x.getBoundingClientRect().width>0&&/导出|下载|export/i.test([x.innerText,x.getAttribute('aria-label'),x.getAttribute('title')].join(' '))).map(x=>x.innerText.trim())
            })""")
            data.update({"view": name, "path": urlparse(page.url).path, "query": dict(parse_qsl(urlparse(page.url).query)), "screenshot": filename})
            result["observations"].append(data)
            root.screenshot(path=str(HERE / filename))
        observe("概览", "overview.png")
        page.get_by_role("tab", name="详细统计", exact=True).click()
        page.get_by_role("tab", name="工单", exact=True).wait_for()
        for name, filename in [("工单", "details-workorders.png"), ("居民", "details-residents.png"), ("公共资源", "details-resources.png")]:
            if page.get_by_role("tab", name=name, exact=True).count() == 0:
                if name == "公共资源": name = "资源预约"
            page.get_by_role("tab", name=name, exact=True).click()
            page.locator('.el-tab-pane:visible').wait_for()
            # Poll DOM readiness rather than networkidle: the app maintains real-time connections.
            page.wait_for_function("![...document.querySelectorAll('.el-loading-mask')].some(x=>x.getBoundingClientRect().width>0)", timeout=15000)
            observe("详细统计/" + name, filename)
        result["completed"] = True
        result["requirements_observed"] = {
            "R40_time_filter": "未观察到时间筛选入口；结合源码与OpenAPI参数记录判定",
            "R42_image_export": "未观察到导出入口；结合EChart注册组件与页面源码判定",
            "R41_data_reconciliation": "本批未执行，保持待验收",
        }
    except Exception as error:
        result["completed"] = False
        result["error"] = type(error).__name__ + ": " + str(error)[:1200]
        if "/admin/" in page.url:
            page.screenshot(path=str(HERE / "interrupted.png"), full_page=False)
    finally:
        context.close()
        browser.close()
        result["independent_context_closed"] = True
        result["source_files"] = [{"path": f, "sha256": hashlib.sha256((ROOT/f).read_bytes()).hexdigest()} for f in ["frontend/src/admin/statistics/views/DashboardTabView.vue", "frontend/src/admin/statistics/views/DashboardOverview.vue", "frontend/src/admin/statistics/views/StatisticsView.vue", "frontend/src/common/statistics/components/EChart.vue", "frontend/src/common/statistics/api/statistics.ts", "backend/src/main/java/com/community/residence/common/statistics/controller/StatisticsController.java"]]
        save()
print(json.dumps({"completed": result["completed"], "views": [o["view"] for o in result["observations"]], "http_count": len(result["http"]), "blocked_count": len(result["blocked_requests"]), "page_errors": result["page_errors"], "error": result.get("error")}, ensure_ascii=False, indent=2))
raise SystemExit(0 if result["completed"] else 1)
