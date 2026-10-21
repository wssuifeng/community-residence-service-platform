"""Run the N8 read-only UI path in a pinned Chromium-family browser binary."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path

from playwright.sync_api import sync_playwright


EXPECTED_JAR_SHA256 = "F435478ABF19618DD01DA7C72D5CCB86FD5258211D7FFEB70049263AA31C20DD"
PAGES = [
    ("dashboard", "/admin/dashboard?tab=overview", "运营看板"),
    ("community", "/admin/community", "社区管理"),
    ("work-orders", "/admin/work-orders", "工单"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True, help="Matrix label, for example chrome-154")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:5182")
    args = parser.parse_args()

    root = Path.cwd()
    jar = root / "backend/target/residence-backend-0.1.0-SNAPSHOT.jar"
    jar_hash = sha256(jar)
    if jar_hash != EXPECTED_JAR_SHA256:
        raise RuntimeError(f"Isolated backend fingerprint changed: {jar_hash}")
    if not args.executable.is_file():
        raise FileNotFoundError(args.executable)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    result_path = args.output_dir / "verification.json"
    if result_path.exists():
        raise FileExistsError(f"Refusing to replace existing evidence: {result_path}")

    requests: list[dict[str, str]] = []
    responses: list[dict[str, object]] = []
    page_errors: list[str] = []
    console_errors: list[str] = []
    observations: list[dict[str, object]] = []
    base = args.base_url.rstrip("/")

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=str(args.executable),
            headless=True,
            args=["--no-first-run", "--no-default-browser-check"],
        )
        version = browser.version
        if version != args.expected_version:
            browser.close()
            raise RuntimeError(f"Browser version mismatch: expected {args.expected_version}, got {version}")

        context = browser.new_context(viewport={"width": 1440, "height": 1024})
        page = context.new_page()
        page.on("pageerror", lambda error: page_errors.append(str(error)))
        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
        page.on(
            "request",
            lambda request: requests.append(
                {"method": request.method, "path": request.url.split(":5182")[-1]}
            )
            if "/api/v1/" in request.url
            else None,
        )
        page.on(
            "response",
            lambda response: responses.append(
                {"status": response.status, "path": response.url.split(":5182")[-1]}
            )
            if "/api/v1/" in response.url
            else None,
        )

        page.goto(f"{base}/auth/login", wait_until="domcontentloaded")
        page.get_by_role("tab", name="管理员 / 服务人员").click()
        page.get_by_placeholder("请输入用户名或注册手机号").fill(
            os.environ.get("N8_ADMIN_USERNAME", "superadmin")
        )
        page.get_by_placeholder("请输入密码", exact=True).fill(
            os.environ.get("N8_ADMIN_PASSWORD", "Admin@123456")
        )
        page.get_by_role("button", name="登 录").click()
        page.wait_for_url("**/admin/**", timeout=45_000)

        for name, route, expected_text in PAGES:
            page.goto(f"{base}{route}", wait_until="domcontentloaded")
            page.locator("body").wait_for(state="visible")
            page.wait_for_timeout(1000)
            body = page.locator("body").inner_text()
            if expected_text not in body:
                raise AssertionError(f"{args.label} {name}: expected visible text {expected_text!r}")
            screenshot = args.output_dir / f"{name}.png"
            if screenshot.exists():
                raise FileExistsError(f"Refusing to replace screenshot: {screenshot}")
            page.screenshot(path=str(screenshot), full_page=True)
            observations.append(
                {
                    "page": name,
                    "route": route,
                    "requiredText": expected_text,
                    "bodyCharacters": len(body),
                    "screenshot": screenshot.name,
                    "screenshotBytes": screenshot.stat().st_size,
                }
            )

        context.close()
        browser.close()

    writes = [
        item
        for item in requests
        if item["method"] not in ("GET", "HEAD")
        and item["path"] != "/api/v1/auth/admin/login"
    ]
    financial = [
        item
        for item in requests
        if any(marker in item["path"].lower() for marker in ("/payment", "/refund", "/channels"))
    ]
    server_errors = [item for item in responses if int(item["status"]) >= 500]
    if page_errors or console_errors or writes or financial or server_errors:
        raise AssertionError(
            json.dumps(
                {
                    "pageErrors": page_errors,
                    "consoleErrors": console_errors,
                    "nonAuthWrites": writes,
                    "financialRequests": financial,
                    "serverErrors": server_errors,
                },
                ensure_ascii=False,
                indent=2,
            )
        )

    result = {
        "recordedAt": dt.datetime.now().astimezone().isoformat(),
        "requirement": "N8 pinned browser normal-use smoke path",
        "browser": {
            "label": args.label,
            "family": "Google Chrome" if args.label.startswith("chrome-") else "Microsoft Edge",
            "productVersion": version,
            "executable": str(args.executable),
            "automation": "Playwright controlling the specified vendor browser binary",
        },
        "runtime": {
            "frontend": base,
            "backend": "http://127.0.0.1:8087",
            "backendJarSha256": jar_hash,
        },
        "observations": observations,
        "requests": requests,
        "responses": responses,
        "pageErrors": page_errors,
        "consoleErrors": console_errors,
        "nonAuthWrites": writes,
        "financialRequests": financial,
        "serverErrors": server_errors,
        "limitations": [
            "Read-only administrator login, dashboard, community, and work-order list smoke path; not exhaustive browser conformance testing.",
            "The administrator login POST is excluded from business-write checks.",
        ],
    }
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"browser": args.label, "version": version, "pages": len(observations), "apiRequests": len(requests), "evidence": str(result_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
