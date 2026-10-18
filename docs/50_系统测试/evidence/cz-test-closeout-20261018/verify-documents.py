"""Review changed docs against source HEAD and validate this closeout's artifacts."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
SKILL = Path("C:/Users/17841/.agents/skills/project-docs-governance/scripts/audit_project_docs.py")
spec = importlib.util.spec_from_file_location("cz_docs_audit", SKILL)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
summary = json.loads((OUT / "evidence-summary.json").read_text(encoding="utf-8"))
source_head = summary["sourceCommit"]
changed = [rel for rel in subprocess.check_output(
    ["git", "diff", source_head, "--name-only", "-z"], cwd=ROOT).decode().split("\0")
    if rel and not rel.startswith("docs/50_系统测试/evidence/cz-test-closeout-20261018/")]
head_texts = {}
for rel in filter(None, changed):
    if rel in ("AGENTS.md", "README.md") or rel.startswith("docs/"):
        head_texts[rel] = subprocess.check_output(["git", "show", source_head + ":" + rel], cwd=ROOT).decode("utf-8-sig")


class BaselineAudit(module.Audit):
    def collect(self):
        super().collect()
        self.texts.update(head_texts)
        for rel in list(self.texts):
            if rel.startswith("docs/50_系统测试/evidence/cz-test-closeout-20261018/"):
                del self.texts[rel]


def signatures(audit, findings):
    result = Counter()
    for item in findings:
        lines = audit.texts.get(item.path, "").splitlines()
        content = lines[item.line - 1].strip() if 0 < item.line <= len(lines) else ""
        result[(item.severity, item.code, item.path, item.message, content)] += 1
    return result


current = module.Audit(ROOT)
findings = current.run()
baseline = BaselineAudit(ROOT)
previous = baseline.run()
added = signatures(current, findings) - signatures(baseline, previous)
source_checks = []
for item in summary["sources"]:
    path = ROOT / item["path"]
    source_checks.append({"path": item["path"], "sha256Matches": path.is_file() and
        hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]})
case_ids = {x["id"] for x in json.loads((OUT / "test-case-catalog.json").read_text(encoding="utf-8"))}
checks = {
    "sourceHashesMatch": all(item["sha256Matches"] for item in source_checks),
    "testCaseCatalogUnique305": len(case_ids) == 305,
    "requirementRows85NoMissingIds": summary["rtm"]["rows"] == 85 and not summary["rtm"]["missingRequirements"],
    "normalEnvironmentV43BusinessTables61Retention730": all(summary["runtime"][k] == v for k, v in
        {"flywayVersion": 43, "businessTables": 61, "logRetentionDays": 730}.items()),
    "normalPortsObservedListening": all(summary["runtime"]["listeningPorts"][p] for p in ("8084", "5176")),
    "noNewBusinessOrFinancialTests": summary["newBusinessTests"] == 0 and summary["newFinancialTests"] == 0,
    "finalRatesNotInvented": summary["testCaseCatalog"]["executionRate"] is None and
        summary["historicalDefectTable"]["currentClosureRate"] is None,
    "governanceAuditComplete": not current.incomplete,
    "noAddedGovernanceFindings": not added,
    "gitDiffCheckPassed": subprocess.run(["git", "-c", "core.safecrlf=false", "diff", source_head, "--check"],
        cwd=ROOT, capture_output=True).returncode == 0,
}
# Inspect explicit Markdown links in newly written report, archive list and evidence README.
link_checks = []
for path in [ROOT / "docs/50_系统测试/09_测试评估与报告.md",
             ROOT / "docs/50_系统测试/10_测试总结与归档.md", OUT / "README.md"]:
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
        if "://" not in target and not target.startswith("#"):
            link_checks.append({"source": path.relative_to(ROOT).as_posix(), "target": target,
                "exists": (path.parent / target.split("#", 1)[0]).exists()})
checks["newReportAndArchiveLinksResolve"] = all(x["exists"] for x in link_checks)
report = {"date": "2026-10-18", "sourceHead": summary["sourceCommit"],
    "method": "Skill read-only audit against current files and recorded source commit texts, compared by path/code/message/source-line text; plus fixed evidence hashes, facts and local links.",
    "globalAuditPassed": not findings, "globalAuditComplete": not current.incomplete,
    "currentFindingCount": len(findings), "baselineFindingCount": len(previous),
    "newFindingCount": sum(added.values()), "newFindings": [list(k) + [v] for k, v in added.items()],
    "existingFindingTypes": dict(Counter(item.code for item in previous)),
    "checks": checks, "sourceChecks": source_checks, "linkChecks": link_checks,
    "changedTrackedFiles": list(filter(None, changed)),
    "businessAcceptance": "not_evaluated; no new business tests; original exit criteria not all signed",
    "reportUserReview": "pending; latest-runtime confirmation is recorded separately"}
(OUT / "document-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"checks": checks, "globalFindings": len(findings), "baselineFindings": len(previous),
    "newFindings": report["newFindings"]}, ensure_ascii=False))
if not all(checks.values()):
    raise SystemExit(1)
