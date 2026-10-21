"""Verify C9 chart exports against the final isolated runtime."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path

from PIL import Image, ImageStat
from playwright.sync_api import sync_playwright

root = Path.cwd()
evidence = root / "docs/60_评估验收/evidence/acceptance-c9-latest-20261020"
download_dir = evidence / "downloads"
download_dir.mkdir(exist_ok=True)
base = "http://127.0.0.1:5182"
errors, requests, downloads, observations = [], [], [], []
sources = ["frontend/src/common/statistics/components/EChart.vue", "frontend/src/admin/statistics/views/DashboardOverview.vue", "frontend/src/admin/statistics/views/StatisticsView.vue"]

def wait_ready(page):
    page.wait_for_load_state("networkidle")
    masks = page.locator(".el-loading-mask")
    if masks.count():
        masks.last.wait_for(state="hidden", timeout=20000)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="msedge")
    context = browser.new_context(viewport={"width": 1440, "height": 1100}, accept_downloads=True)
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.on("request", lambda r: requests.append({"method": r.method, "path": r.url.split(":5182")[-1]}) if "/api/v1/" in r.url else None)
    page.goto(f"{base}/auth/login")
    wait_ready(page)
    page.get_by_role("tab", name="管理员 / 服务人员").click()
    page.get_by_placeholder("请输入用户名或注册手机号").fill("superadmin")
    page.get_by_placeholder("请输入密码", exact=True).fill("Admin@123456")
    page.get_by_role("button", name="登 录").click()
    page.wait_for_url("**/admin/**")
    wait_ready(page)
    for view, tab in [("overview", None), ("workorder", "工单"), ("resident", "居民"), ("resource", "资源预约")]:
        if view == "overview":
            page.goto(f"{base}/admin/dashboard?tab=overview")
        else:
            if view == "workorder":
                page.goto(f"{base}/admin/dashboard?tab=details")
            page.get_by_role("tab", name=tab, exact=True).click()
        wait_ready(page)
        assert "较上月" not in page.locator("body").inner_text(), view
        buttons = page.get_by_role("button", name="导出图片", exact=False)
        count = buttons.count()
        for index in range(count):
            button = buttons.nth(index)
            with page.expect_download() as pending:
                button.click()
            item = pending.value
            path = download_dir / f"{view}-{index + 1}.png"
            item.save_as(path)
            with Image.open(path) as image:
                assert image.format == "PNG" and image.width > 100 and image.height > 100
                assert max(ImageStat.Stat(image.convert("RGB")).stddev) > 5
                size = list(image.size)
            downloads.append({"view": view, "label": button.get_attribute("aria-label"), "file": path.name, "suggestedFilename": item.suggested_filename, "size": size, "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        observations.append({"view": view, "exportButtons": count, "pngDownloads": count})
        page.screenshot(path=str(evidence / f"final-{view}-page.png"), full_page=True)
    context.close()
    edge_engine_version = browser.version
    browser.close()

financial = [r for r in requests if any(s in r["path"] for s in ("/payment", "/refund", "/channels"))]
writes = [r for r in requests if r["method"] not in ("GET", "HEAD") and "/api/v1/auth/" not in r["path"]]
assert not errors and not financial and not writes
jar = root / "backend/target/residence-backend-0.1.0-SNAPSHOT.jar"
result = {"recordedAt": dt.datetime.now().astimezone().isoformat(), "runtime": {"frontend": base, "backend": "http://127.0.0.1:8087"}, "browser": {"family": "Microsoft Edge", "productVersion": os.environ.get("N8_EDGE_PRODUCT_VERSION"), "executable": os.environ.get("N8_EDGE_EXECUTABLE"), "engineVersion": edge_engine_version}, "backendJarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "frontendSources": {s: hashlib.sha256((root / s).read_bytes()).hexdigest() for s in sources}, "observations": observations, "downloadCount": len(downloads), "downloads": downloads, "requests": requests, "pageErrors": errors, "businessWrites": len(writes), "financialOperations": len(financial), "normalDatabaseWrites": 0, "limitations": ["Read-only final-runtime chart export check; does not satisfy the N8 multi-browser matrix.", "Numeric comparison remains limited to the fixed sample in full-numeric-reconciliation.json."]}
(evidence / "final-export-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"jar": result["backendJarSha256"], "downloads": len(downloads), "errors": len(errors), "writes": len(writes), "financial": len(financial)}))
