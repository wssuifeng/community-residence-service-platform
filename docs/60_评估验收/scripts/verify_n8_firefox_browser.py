"""Run the N8 read-only UI path in a pinned Mozilla Firefox binary."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import subprocess
from pathlib import Path

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.firefox.options import Options
from selenium.webdriver.firefox.service import Service
from selenium.webdriver.support import expected_conditions as expected
from selenium.webdriver.support.ui import WebDriverWait


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
    parser.add_argument("--label", required=True, help="Matrix label, for example firefox-157")
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--executable", required=True, type=Path)
    parser.add_argument("--geckodriver", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:5182")
    args = parser.parse_args()

    root = Path.cwd()
    jar = root / "backend/target/residence-backend-0.1.0-SNAPSHOT.jar"
    jar_hash = sha256(jar)
    if jar_hash != EXPECTED_JAR_SHA256:
        raise RuntimeError(f"Isolated backend fingerprint changed: {jar_hash}")
    for path in (args.executable, args.geckodriver):
        if not path.is_file():
            raise FileNotFoundError(path)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    result_path = args.output_dir / "verification.json"
    if result_path.exists():
        raise FileExistsError(f"Refusing to replace existing evidence: {result_path}")

    options = Options()
    options.binary_location = str(args.executable)
    options.add_argument("-headless")
    service = Service(
        executable_path=str(args.geckodriver),
        service_args=["--allow-system-access"],
    )
    driver = webdriver.Firefox(service=service, options=options)
    observations: list[dict[str, object]] = []
    browser_logs: list[dict[str, str]] = []
    try:
        driver.set_window_size(1440, 1024)
        driver.set_page_load_timeout(45)
        version = driver.capabilities.get("browserVersion", "")
        if version != args.expected_version:
            raise RuntimeError(f"Browser version mismatch: expected {args.expected_version}, got {version}")

        base = args.base_url.rstrip("/")
        driver.get(f"{base}/auth/login")
        wait = WebDriverWait(driver, 30)
        tabs = wait.until(expected.presence_of_all_elements_located((By.CSS_SELECTOR, "button[role='tab']")))
        next(tab for tab in tabs if "管理员" in tab.text).click()
        username = wait.until(
            expected.visibility_of_element_located((By.CSS_SELECTOR, "input[placeholder='请输入用户名或注册手机号']"))
        )
        username.send_keys(os.environ.get("N8_ADMIN_USERNAME", "superadmin"))
        driver.find_element(By.CSS_SELECTOR, "input[type='password']").send_keys(
            os.environ.get("N8_ADMIN_PASSWORD", "Admin@123456")
        )
        driver.find_element(By.CSS_SELECTOR, "button[type='submit']").click()
        wait.until(lambda current: "/admin/" in current.current_url)

        for name, route, required_text in PAGES:
            driver.get(f"{base}{route}")
            body = wait.until(expected.visibility_of_element_located((By.TAG_NAME, "body")))
            wait.until(lambda current: required_text in current.find_element(By.TAG_NAME, "body").text)
            text = body.text
            screenshot = args.output_dir / f"{name}.png"
            if screenshot.exists():
                raise FileExistsError(f"Refusing to replace screenshot: {screenshot}")
            if not driver.save_screenshot(str(screenshot)):
                raise RuntimeError(f"Screenshot capture failed: {screenshot}")
            observations.append(
                {
                    "page": name,
                    "route": route,
                    "requiredText": required_text,
                    "bodyCharacters": len(text),
                    "screenshot": screenshot.name,
                    "screenshotBytes": screenshot.stat().st_size,
                }
            )

        try:
            browser_logs = [
                {"level": str(entry.get("level", "")), "message": str(entry.get("message", ""))}
                for entry in driver.get_log("browser")
            ]
        except Exception as error:  # Firefox/geckodriver may not expose browser logs on every build.
            browser_logs = [{"level": "UNAVAILABLE", "message": str(error)}]
    finally:
        driver.quit()

    severe_logs = [entry for entry in browser_logs if entry["level"].upper() == "SEVERE"]
    if severe_logs:
        raise AssertionError(json.dumps(severe_logs, ensure_ascii=False, indent=2))

    driver_version = subprocess.run(
        [str(args.geckodriver), "--version"], capture_output=True, text=True, check=True
    ).stdout.splitlines()[0]
    result = {
        "recordedAt": dt.datetime.now().astimezone().isoformat(),
        "requirement": "N8 pinned browser normal-use smoke path",
        "browser": {
            "label": args.label,
            "family": "Mozilla Firefox",
            "productVersion": version,
            "executable": str(args.executable),
            "automation": "Selenium WebDriver controlling the specified vendor browser binary",
            "driver": driver_version,
        },
        "runtime": {
            "frontend": args.base_url.rstrip("/"),
            "backend": "http://127.0.0.1:8087",
            "backendJarSha256": jar_hash,
        },
        "observations": observations,
        "browserLogs": browser_logs,
        "severeBrowserLogs": severe_logs,
        "limitations": [
            "Read-only administrator login, dashboard, community, and work-order list smoke path; not exhaustive browser conformance testing.",
            "No business forms or financial actions were submitted. Selenium/geckodriver did not expose a cross-version request-method log in this run.",
        ],
    }
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        json.dumps(
            {
                "browser": args.label,
                "version": version,
                "pages": len(observations),
                "browserLogs": len(browser_logs),
                "evidence": str(result_path),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
