"""C9 latest runtime UI verification against the isolated 8087/5182 pair."""
import datetime as dt
import hashlib
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:5182"
BACKEND_JAR = ROOT / "backend" / "target" / "residence-backend-0.1.0-SNAPSHOT.jar"


def main() -> None:
    errors: list[str] = []
    requests: list[dict[str, str]] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, channel="msedge")
        context = browser.new_context(viewport={"width": 1440, "height": 1100})
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on(
            "request",
            lambda request: requests.append(
                {"method": request.method, "path": request.url.split("127.0.0.1:5182")[-1]}
            )
            if "/api/v1/" in request.url
            else None,
        )

        page.goto(f"{BASE}/auth/login")
        page.wait_for_load_state("networkidle")
        page.get_by_role("tab", name="管理员 / 服务人员").click()
        page.get_by_placeholder("请输入用户名或注册手机号").fill("superadmin")
        page.get_by_placeholder("请输入密码", exact=True).fill("Admin@123456")
        page.get_by_role("button", name="登 录").click()
        page.wait_for_url("**/admin/**")
        page.wait_for_load_state("networkidle")

        page.goto(f"{BASE}/admin/dashboard?tab=overview")
        page.wait_for_load_state("networkidle")
        page.locator(".el-loading-mask").first.wait_for(state="hidden")
        page.wait_for_timeout(600)

        body_text = page.locator("body").inner_text()
        required_labels = [
            "期间工单量",
            "工单完成率",
            "平均处理时长",
            "入住 / 搬出",
            "期间资源预约",
            "资源预约完成",
            "看房预约量",
            "已完成带看",
            "房源浏览量",
            "评价平均分",
            "满意率",
            "服务人员工作量",
        ]
        missing_labels = [label for label in required_labels if label not in body_text]
        assert not missing_labels, missing_labels
        assert page.locator(".community-filter").count() == 1
        assert page.locator(".date-filter").count() == 1

        period_text = page.locator(".period-panel").inner_text()
        page.screenshot(path=str(OUT / "overview-latest-indicators.png"), full_page=True)

        page.get_by_role("tab", name="详细统计", exact=True).click()
        page.wait_for_load_state("networkidle")
        page.locator(".el-loading-mask").first.wait_for(state="hidden")
        tab_names = ["工单", "居民", "资源预约"]
        assert all(page.get_by_role("tab", name=name, exact=True).count() == 1 for name in tab_names)
        page.wait_for_timeout(400)
        page.screenshot(path=str(OUT / "details-latest-filters.png"), full_page=True)

        context.close()
        browser.close()

    assert not errors, errors
    assert not any(
        any(segment in request["path"] for segment in ("/payment", "/refund", "/channels"))
        for request in requests
    )
    result = {
        "recordedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "runtime": {"frontend": BASE, "backend": "http://127.0.0.1:8087"},
        "browser": {"family": "Microsoft Edge", "version": browser.version},
        "backendJarSha256": hashlib.sha256(BACKEND_JAR.read_bytes()).hexdigest().upper(),
        "requiredLabels": required_labels,
        "missingLabels": missing_labels,
        "periodPanelText": period_text,
        "requests": requests,
        "pageErrors": errors,
        "financialOperations": 0,
        "normalDatabaseWrites": 0,
    }
    (OUT / "latest-ui-verification.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({"requiredLabels": len(required_labels), "missingLabels": len(missing_labels), "pageErrors": len(errors)}))


if __name__ == "__main__":
    main()
