"""Collect existing test evidence and fixed read-only environment facts; run no tests."""
import csv
import hashlib
import json
import math
import os
import re
import socket
import subprocess
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "docs/50_系统测试"
OUT = Path(__file__).resolve().parent


def text(path):
    return path.read_text(encoding="utf-8-sig")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cells(line):
    safe = re.sub(r"(`+).*?\1", lambda m: m[0].replace("|", "¦"), line)
    return [c.strip().replace("¦", "|") for c in safe.strip("|").split("|")]


cases = []
for path in sorted((BASE / "04_测试用例").glob("*.md")):
    for match in re.finditer(r"^### (TC-[A-Z0-9]+-\d+) ([^\n]+)\n([\s\S]*?)(?=^### |\Z)", text(path), re.M):
        case_id, title, body = match.groups()
        priority = re.search(r"优先级[：:]\s*(P[0-3])", body)
        cases.append({"id": case_id, "title": title, "path": path.relative_to(ROOT).as_posix(),
                      "priority": priority[1] if priority else "unspecified",
                      "tr": sorted(set(re.findall(r"TR-[A-Z0-9]+-\d+", body)))})

# Later cases are defined as table rows, so count both formats without double counting.
heading_ids = {case["id"] for case in cases}
for path in sorted((BASE / "04_测试用例").glob("*.md")):
    for line in text(path).splitlines():
        if re.match(r"^\| TC-[A-Z0-9]+-\d+ \|", line):
            row = cells(line)
            if row[0] not in heading_ids:
                cases.append({"id": row[0], "title": row[1], "path": path.relative_to(ROOT).as_posix(),
                              "priority": "unspecified", "tr": [], "format": "supplementary table"})
                heading_ids.add(row[0])

rtm_rows = []
for line in text(BASE / "02_需求跟踪矩阵.md").splitlines():
    if re.match(r"^\| (?:R\d+|N\d+) \|", line):
        c = cells(line)
        rtm_rows.append({"id": c[0], "testRequirement": c[2], "testCase": c[3], "recordedResult": c[4]})

primary = text(BASE / "08_缺陷跟踪与回归.md").split("## 3. 回归测试记录", 1)[0]
defects = []
for line in primary.splitlines():
    if not re.match(r"^\| DEF-\d+ \|", line):
        continue
    c = cells(line)
    if len(c) != 8:
        raise ValueError("Unexpected historical defect row shape: " + c[0])
    severity_text = c[4].split("（", 1)[0].replace("**", "")
    severity = severity_text.split("→")[-1].split("/")[0]
    defects.append({"id": c[0], "severity": severity, "recordedState": c[5],
                    "recordedFix": c[6], "recordedRegression": c[7]})

all_def_ids = sorted(set(re.findall(r"DEF-(\d{3})", text(BASE / "08_缺陷跟踪与回归.md"))), key=int)

# Reaggregate the original fifth-round dataset, not a new performance run.
jtl = BASE / "scripts/nf_steady_r5.jtl"
groups = {}
count = failed = 0
failed_codes = Counter()
with jtl.open(encoding="utf-8-sig", newline="") as stream:
    reader = csv.DictReader(stream)
    for row in reader:
        count += 1
        ok = row["success"].lower() == "true"
        failed += not ok
        if not ok:
            failed_codes[row["responseCode"]] += 1
        entry = groups.setdefault(row["label"], {"total": 0, "failed": 0, "successfulElapsed": []})
        entry["total"] += 1
        entry["failed"] += not ok
        if ok:
            entry["successfulElapsed"].append(int(row["elapsed"]))
metrics = {}
for name, entry in groups.items():
    values = sorted(entry.pop("successfulElapsed"))
    metrics[name] = {**entry, "successfulSamples": len(values),
                     "p95Milliseconds": values[max(0, math.ceil(len(values) * .95) - 1)] if values else None}
performance = {"source": jtl.relative_to(ROOT).as_posix(), "sha256": digest(jtl),
               "historicalDate": "2026-09-15", "baseline": "07f216f", "newLoadTestRun": False,
               "totalSamples": count, "failedSamples": failed, "failureCodes": dict(failed_codes), "labels": metrics,
               "limitation": "Historical baseline only; no final-source N1/N2/N3 claim."}

# Query only migration metadata, table count and the single retention setting.
env = os.environ.copy()
for raw in text(ROOT / ".env").splitlines():
    if "=" in raw and not raw.lstrip().startswith("#"):
        key, value = raw.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
query = (
    "SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1; "
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='community_residence' "
    "AND table_name<>'flyway_schema_history'; "
    "SELECT config_value FROM sys_config WHERE config_key='log.retention_days';"
)
result = subprocess.run(
    ["C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe", "-h127.0.0.1",
     "-u" + env.get("DB_USERNAME", "root"), "-Dcommunity_residence", "-N", "-B", "-e", query],
    env={**env, "MYSQL_PWD": env["DB_PASSWORD"]}, capture_output=True, encoding="utf-8",
    creationflags=subprocess.CREATE_NO_WINDOW, timeout=15)
if result.returncode:
    raise RuntimeError("Fixed read-only SQL failed; private stderr intentionally not published")
values = result.stdout.split()
if len(values) != 3:
    raise RuntimeError("Unexpected read-only SQL shape; no database values published")
ports = {}
for port in (8084, 5176, 8087, 5181, 5184, 5188):
    with socket.socket() as connection:
        connection.settimeout(1)
        ports[str(port)] = connection.connect_ex(("127.0.0.1", port)) == 0
runtime = {"verifiedAt": datetime.now().astimezone().isoformat(), "sqlMode": "SELECT only",
           "flywayVersion": int(values[0]), "businessTables": int(values[1]),
           "logRetentionDays": int(values[2]), "listeningPorts": ports,
           "servicesStartedOrStopped": False, "dataChanged": False, "financialCommands": 0,
           "paymentConfigurationReadOrChanged": False, "browserInteraction": False}

source_files = [BASE / name for name in ["01_测试需求分析.md", "02_需求跟踪矩阵.md", "03_系统测试计划.md",
    "05_测试环境与数据.md", "06_冒烟测试记录.md", "07_测试执行记录.md", "08_缺陷跟踪与回归.md"]]
source_files += list((BASE / "04_测试用例").glob("*.md"))
source_files += [BASE / "scripts/nf_load.jmx", BASE / "scripts/nf_steady_r2.jtl.gz", jtl]
source_files += [BASE / "evidence" / folder / name for folder, name in [
    ("cv-architecture-implementation-20261018", "final-validation.json"),
    ("cv-architecture-implementation-20261018", "s3-validation.json"),
    ("cv-architecture-implementation-20261018", "final-migration-validation.json"),
    ("cx-current-runtime-20261018", "startup-summary.json"),
    ("cy-header-navigation-20261018", "verification-summary.json"),
    ("ce-stale-test-orders-20261016", "verification-summary.json")]]
summary = {"date": "2026-10-18", "sourceCommit": subprocess.check_output(
    ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "activity": "existing-evidence review, JTL reaggregation, fixed read-only SQL and socket checks",
    "newBusinessTests": 0, "newFinancialTests": 0,
    "testCaseCatalog": {"definitions": len(cases), "uniqueIds": len({c['id'] for c in cases}),
        "byPriority": dict(Counter(c['priority'] for c in cases)),
        "byDomain": dict(Counter(c['id'].rsplit('-', 1)[0] for c in cases)),
        "executionRate": None, "reason": "Catalog definitions are not final-source executions."},
    "rtm": {"rows": len(rtm_rows), "uniqueRequirementIds": len({r['id'] for r in rtm_rows}),
        "missingRequirements": [x for x in [*(f'R{i}' for i in range(1,76)), *(f'N{i}' for i in range(1,11))]
                                 if x not in {r['id'] for r in rtm_rows}],
        "blankMappingCells": sum(not r['testRequirement'] or not r['testCase'] for r in rtm_rows),
        "finalExecutionRate": None},
    "historicalDefectTable": {"rows": len(defects), "bySeverity": dict(Counter(d['severity'] for d in defects)),
        "latestMentionedId": 'DEF-' + all_def_ids[-1], "currentClosureRate": None,
        "reason": "Primary rows and later round-specific amendments use different dates and dispositions."},
    "runtime": runtime,
    "userConfirmations": [
        {"date": "2026-10-18", "scope": "latest running version and affected clients",
         "text": "已确认最新运行版本。未发现受到影响的客户端。开始 50_系统测试阶段的收尾工作吧。"},
        {"date": "2026-10-18", "scope": "financial availability", "source": "user instruction; cx record",
         "text": "金融专项，我确认了可用就行了，不需要AI再测试一遍。",
         "claim": "User accepts availability; no claim that all financial abnormal branches ran."}],
    "sources": [{"path": p.relative_to(ROOT).as_posix(), "sha256": digest(p)} for p in sorted(source_files)]}
for name, payload in [("evidence-summary.json", summary), ("test-case-catalog.json", cases),
                      ("rtm-snapshot.json", rtm_rows), ("defect-table-snapshot.json", defects),
                      ("historical-performance.json", performance)]:
    (OUT / name).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"testCaseCatalog": summary["testCaseCatalog"], "rtm": summary["rtm"],
    "historicalDefectTable": summary["historicalDefectTable"], "runtime": runtime,
    "historicalJtl": {"samples": count, "failures": failed, "labels": metrics}}, ensure_ascii=False))
