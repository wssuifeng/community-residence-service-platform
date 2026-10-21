import json
import os
from datetime import datetime, timezone
from pathlib import Path

from playwright.sync_api import sync_playwright

base = os.environ.get("CRSP_FRONTEND_URL", "http://127.0.0.1:5182")
password = os.environ.get("CRSP_SUPERADMIN_PASSWORD")
if not password:
    raise SystemExit("Set CRSP_SUPERADMIN_PASSWORD for the isolated test database.")
out = Path(__file__).resolve().parent

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.goto(f"{base}/auth/login", wait_until="networkidle")
    page.locator(".login-tabs button").nth(1).click()
    page.locator('input[autocomplete="username"]').fill(os.environ.get("CRSP_SUPERADMIN_USERNAME", "superadmin"))
    page.locator('input[autocomplete="current-password"]').fill(password)
    page.locator("form.login-form button[type=submit]").click()
    page.wait_for_url("**/admin/**", timeout=15000)
    page.goto(f"{base}/admin/staff-capabilities", wait_until="networkidle")
    page.get_by_text("服务人员", exact=False).first.wait_for(timeout=10000)
    if "代建服务人员账号" not in page.locator("body").inner_text():
        raise AssertionError("Staff account creation action is not visible.")
    image = out / "superadmin-staff-capabilities.png"
    page.screenshot(path=str(image), full_page=True)
    record = {
        "verifiedAt": datetime.now(timezone.utc).isoformat(),
        "frontend": base,
        "role": "SUPER_ADMIN",
        "browser": "Chromium",
        "browserVersion": browser.version,
        "path": "/admin/staff-capabilities",
        "pageLoaded": True,
        "createStaffAccountActionVisible": True,
        "screenshot": image.name,
        "screenshotBytes": image.stat().st_size,
        "title": page.title(),
    }
    (out / "superadmin-ui.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    browser.close()

print("PASS: superadmin login and service-personnel account page verified")
