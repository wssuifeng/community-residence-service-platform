import json
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright


PACKAGE = Path(__file__).resolve().parents[1]
EVIDENCE = PACKAGE / "evidence" / "quality-closeout-20261021"
BASE_URL = "http://127.0.0.1:5183"
START_DATE = "2026-09-22"
END_DATE = "2026-10-21"

dashboard = {
    "startDate": START_DATE,
    "endDate": END_DATE,
    "communityCount": 1,
    "buildingCount": 8,
    "houseCount": 5060,
    "occupiedHouseCount": 10,
    "residentCount": 12,
    "activeLeaseCount": 2,
    "expiringLeaseCount": 0,
    "workOrderTotal": 150,
    "workOrderPending": 12,
    "workOrderProcessing": 30,
    "workOrderCompleted": 108,
    "reservationTotal": 20,
    "reservationPending": 1,
    "violationCount": 0,
    "evaluationTotal": 3,
    "workOrderTrend7d": {
        "2026-10-15": 4,
        "2026-10-16": 5,
        "2026-10-17": 3,
        "2026-10-18": 6,
        "2026-10-19": 7,
        "2026-10-20": 2,
        "2026-10-21": 3,
    },
    "workOrderStatusDistribution": {"COMPLETED": 108, "PROCESSING": 30, "PENDING": 12},
    "houseStatusDistribution": {"OWNER_OCCUPIED": 10, "VACANT": 5050},
    "ratingDistribution": {"4": 1, "5": 2},
    "workOrderPeriodCount": 30,
    "workOrderPeriodCompletedCount": 24,
    "workOrderCompletionRate": 80.0,
    "workOrderAvgProcessingHours": 3.2,
    "residentMoveInTotal": 2,
    "residentMoveOutTotal": 1,
    "reservationPeriodTotal": 8,
    "reservationCompletedTotal": 6,
    "reservationCompletionRate": 75.0,
    "viewingPeriodTotal": 4,
    "viewingCompletedTotal": 3,
    "housingViewPeriodTotal": 30,
    "housingViewTrend": {},
    "evaluationPeriodTotal": 3,
    "evaluationAvgRating": 4.7,
    "evaluationSatisfactionRate": 100.0,
    "staffWorkload": [],
}


def api_response(data):
    return {"code": 200, "message": "success", "data": data, "timestamp": "2026-10-21T00:00:00"}


def respond(route):
    path = urlsplit(route.request.url).path.split("/api/v1", 1)[-1]
    if path == "/statistics/communities":
        data = [{"id": 1, "name": "合成社区"}]
    elif path == "/statistics/dashboard":
        data = dashboard
    elif path == "/statistics/todos":
        data = {}
    elif path in {"/notifications/unread", "/notifications/pull"}:
        data = []
    elif path in {"/work-orders", "/notices", "/resource-reservations"}:
        data = {"records": [], "total": 0, "page": 1, "size": 5, "pages": 0}
    else:
        data = None
    route.fulfill(status=200, content_type="application/json", body=json.dumps(api_response(data), ensure_ascii=False))


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    results = []
    console_errors = []
    page_errors = []
    failed_requests = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page()
        page.on(
            "console",
            lambda message: console_errors.append({"text": message.text, "location": message.location})
            if message.type == "error"
            else None,
        )
        page.on("pageerror", lambda error: page_errors.append(error.stack or str(error)))
        page.on("requestfailed", lambda request: failed_requests.append({"url": request.url, "failure": request.failure}))
        page.route("**/api/v1/**", respond)
        page.route("**/ws/**", lambda route: route.abort())
        page.add_init_script(
            "sessionStorage.setItem('crsp_session', JSON.stringify({"
            "token:'synthetic-admin-token', expiresAt: Date.now() + 86400000,"
            "user:{id:1,username:'synthetic-admin',realName:'合成管理员',role:'SUPER_ADMIN'}"
            "}));"
        )

        for width, height in [(768, 1024), (1024, 768), (1440, 900)]:
            page.set_viewport_size({"width": width, "height": height})
            page.goto(f"{BASE_URL}/admin/dashboard?tab=overview", wait_until="networkidle")
            period_label = page.get_by_text("所选期间工单", exact=True)
            owner_label = page.get_by_text("业主自住", exact=True)
            if not period_label.count() or not owner_label.count():
                results.append(
                    {
                        "viewport": f"{width}x{height}",
                        "url": page.url,
                        "title": page.title(),
                        "bodyPreview": page.locator("body").inner_text()[:1200],
                        "periodLabelPresent": period_label.count() > 0,
                        "ownerLabelPresent": owner_label.count() > 0,
                    }
                )
                page.screenshot(path=str(EVIDENCE / f"diagnostic-{width}x{height}.png"), full_page=True)
                continue
            dimensions = page.evaluate(
                """() => ({
                  viewport: window.innerWidth,
                  document: document.documentElement.scrollWidth,
                  body: document.body.scrollWidth,
                  horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth
                })"""
            )
            owner_share = page.locator(".house-rows li").filter(has_text="业主自住").locator(".house-pct").inner_text()
            result = {
                "viewport": f"{width}x{height}",
                **dimensions,
                "periodLabelPresent": period_label.count() == 1,
                "smallOccupancyShare": owner_share,
            }
            results.append(result)
            page.screenshot(path=str(EVIDENCE / f"admin-dashboard-{width}x{height}.png"), full_page=True)

        browser.close()

    payload = {
        "recordedAt": "2026-10-21",
        "method": "Local Vite app with synthetic API responses; no backend, database, or financial endpoint used.",
        "checks": results,
        "consoleErrors": console_errors,
        "pageErrors": page_errors,
        "failedRequests": failed_requests,
        "expectedExclusions": {
            "websocketRequestFailures": sum("/ws/" in item["url"] for item in failed_requests),
            "reason": "WebSocket endpoint is intentionally unavailable in this synthetic frontend-only check.",
        },
        "passed": len(results) == 3
        and all(
            not item.get("horizontalOverflow", True)
            and item.get("periodLabelPresent")
            and item.get("ownerLabelPresent", True)
            and item.get("smallOccupancyShare") == "0.2%"
            for item in results
        )
        and len(console_errors) == sum("/ws/" in item["url"] for item in failed_requests)
        and all("/ws/" in item["url"] for item in failed_requests)
        and not page_errors,
    }
    (EVIDENCE / "browser-layout-check.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if not payload["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
