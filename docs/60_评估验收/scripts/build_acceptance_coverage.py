"""Build a source/entry/evidence register; never award acceptance or call business APIs."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
PHASE = ROOT / "docs/60_评估验收"
OUT = PHASE / "evidence/acceptance-baseline-20261019"
REQ = "docs/20_需求分析/需求规格说明.md"
RTM = "docs/50_系统测试/evidence/db-final-reconciliation-20261019/rtm-ledger.json"
SOURCE = "95b2fe44954167de547d8b10f2cda869720c10c6"

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8-sig")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, check=True).stdout.decode("utf-8").strip()

def save(name, data):
    (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

BASE_MODULES = [(1, 6, "C1"), (7, 12, "C2"), (13, 16, "C3"), (17, 23, "C4"), (24, 27, "C5"), (28, 31, "C6"), (32, 36, "C7"), (37, 39, "C8"), (40, 42, "C9"), (43, 47, "C10"), (48, 51, "C11"), (52, 57, "C12")]
EXTRA = {12: ["C2", "C5", "C12"], 58: ["C10"], 59: ["C12", "C11"], 60: ["C7", "C12"], 61: ["C3"], 62: ["C1", "C12"], 63: ["C11", "C12"], 64: ["C3"], 65: ["C4"], 66: ["C3", "C2"], 67: ["C3"], 68: ["C11", "C3"], 69: ["C11", "C12", "C3"], 70: ["C3", "C2"], 71: ["C3", "C2"], 72: ["C3"], 73: ["C3"], 74: ["C4", "C11"], 75: ["C1", "C7"]}
E_LINKS = {"E1": list(range(1, 7)), "E2": list(range(7, 12)) + [66, 67, 71], "E3": list(range(13, 17)) + [61, 64, 66, 67, 68, 69, 70, 71, 72, 73], "E4": list(range(17, 24)) + [37, 48, 65, 74], "E5": list(range(24, 28)) + list(range(48, 52)), "E6": list(range(28, 32)), "E7": list(range(32, 37)) + [60, 75], "E8": [37, 38, 39], "E9": [40, 41, 42], "E10": list(range(43, 48)) + [58], "E10b": [1, 43, 44, 45, 46], "E11": list(range(48, 52)), "E12": list(range(52, 58)) + [59, 60, 63]}
PREFIXES = {"C1": ["communities", "buildings", "units", "houses", "resources", "structures"], "C2": ["residents", "residence-applications", "residence-relations"], "C3": ["leases", "lease-extensions", "lease-move-out-applications", "agreement-templates", "lease-agreements", "payment"], "C4": ["work-orders", "work-order-attachments", "service-categories", "staff", "staff-capabilities", "staff-schedules"], "C5": ["notices"], "C6": ["feedbacks", "feedback-attachments"], "C7": ["resources", "resource-reservations", "timeslots"], "C8": ["evaluations"], "C9": ["statistics"], "C10": ["auth", "sys-users", "operation-logs", "configs"], "C11": ["notifications", "conversations", "conversation-inbox"], "C12": ["housings", "housing-timeslots", "viewing-appointments", "houses"]}
PAGE_KEYS = {"C1": ["community"], "C2": ["residents", "profile", "housing-lease"], "C3": ["housing-lease", "leases", "agreements", "conversations"], "C4": ["work-orders", "staff-schedules", "staff-capabilities", "dashboard"], "C5": ["notices"], "C6": ["feedbacks", "conversations"], "C7": ["reservations", "resources"], "C8": ["evaluations", "work-orders"], "C9": ["/admin/dashboard"], "C10": ["system", "auth/login", "auth/register"], "C11": ["conversations", "notifications"], "C12": ["housings", "housing-lease", "viewings", "viewing-appointments"]}
AMENDMENTS = [
    (77, 77, [1, 2, 4, 6, 53, 62], "楼栋自身边界、责任人与授权来源"),
    (366, 373, [2, 4, 8, 43, 46, 53, 62], "中文账号、导入、结构编辑与真实户型"),
    (376, 383, [1, 4, 8, 9, 10, 11, 16, 46, 62, 70], "限定授权、主从关系与迁出"),
    (388, 397, [1, 6, 7, 8, 9, 46, 53, 62, 64, 66, 69, 71], "v1.20现场修正，删除规则后被v1.21覆盖"),
    (402, 409, [1, 6, 53, 61, 64, 66, 71], "v1.21，延长期和订单期限后被v1.22局部覆盖"),
    (411, 414, [61, 66, 70, 71, 73], "延长期申请批准即生效、日常缴租不延长"),
    (416, 416, [24, 27, 28], "公告正文修订、版本阅读"),
    (418, 422, [61, 66, 71], "订单15分钟/15分30秒及跨日有效原单"),
    (425, 426, [30, 63, 68, 74], "三端会话与权限、搜索、已读、历史"),
]

def modules(number):
    if number in EXTRA:
        return EXTRA[number]
    return [m for lo, hi, m in BASE_MODULES if lo <= number <= hi]

def source_ref(start, end):
    return {"path": REQ, "start_line": start, "end_line": end}

def index_text(value, prefix):
    # A textual index only: original full text and revisions remain authoritative.
    return [{"index": f"{prefix}.{n}", "text": item.strip()} for n, item in enumerate(re.split(r"[；;]\s*", value), 1) if item.strip()]

def collect():
    OUT.mkdir(parents=True, exist_ok=True)
    raw = read(REQ)
    lines = raw.splitlines()
    legacy = {r["id"]: r for r in json.loads(read(RTM))}
    with urllib.request.urlopen("http://127.0.0.1:8084/v3/api-docs", timeout=10) as response:
        api = json.load(response)
    operations = []
    for path, methods in api["paths"].items():
        for method, detail in methods.items():
            if method not in {"get", "post", "put", "patch", "delete", "head", "options"}:
                continue
            operations.append({"id": method.upper() + " " + path, "method": method.upper(), "path": path, "operation_id": detail.get("operationId"), "tags": detail.get("tags", []), "parameter_names": [p["name"] for p in detail.get("parameters", [])]})
    route_sources = {"admin": "frontend/src/admin/routes.ts", "resident": "frontend/src/resident/routes.ts", "staff": "frontend/src/staff/routes.ts", "guest": "frontend/src/common/public/routes.ts", "auth": "frontend/src/common/auth/routes.ts"}
    roles = {"admin": ["ADMIN", "SUPER_ADMIN"], "resident": ["RESIDENT"], "staff": ["STAFF"], "guest": ["GUEST"], "auth": ["GUEST", "RESIDENT", "ADMIN", "SUPER_ADMIN", "STAFF"]}
    routes = []
    for portal, path in route_sources.items():
        content = read(path)
        cleaned = re.sub(r"/\*.*?\*/|//[^\n]*", lambda m: "".join("\n" if c == "\n" else " " for c in m[0]), content, flags=re.S)
        matches = list(re.finditer(r"\bpath:\s*(['\"])(.*?)\1", cleaned))
        for n, match in enumerate(matches):
            value = match[2]
            full = value if value.startswith("/") else f"/{portal}/{value}".rstrip("/")
            segment = cleaned[match.end():matches[n+1].start() if n+1 < len(matches) else len(cleaned)]
            component = re.search(r"import\(['\"](@/[^'\"]+)['\"]\)", segment)
            routes.append({"id": f"route-{len(routes)+1:03d}", "path": full, "portal": portal, "roles_candidate": roles[portal], "source": path, "source_line": cleaned[:match.start()].count("\n") + 1, "component": component[1].replace("@/", "frontend/src/") if component else None, "redirect": "redirect:" in segment, "limitation": "静态路由登记，不证明运行可达或后端授权"})
    objects = []
    for n, line in enumerate(lines, 1):
        match = re.match(r"^\|\s*(?:\*\*)?([RN]\d+)(?:\*\*)?\s*\|", line)
        if not match:
            continue
        cells = [c.strip() for c in re.split(r"(?<!\\)\|", line.strip().strip("|"))]
        ident = match[1]
        if ident.startswith("R"):
            assert len(cells) == 3, (n, len(cells))
            desc, standard = cells[1:]
            linked = [int(ident[1:])]
            mods = modules(linked[0])
        else:
            assert len(cells) == 4, (n, len(cells))
            desc, standard = cells[1], cells[2]
            linked, mods = [], list(PREFIXES)
        objects.append({"id": ident, "kind": ident[0], "source": source_ref(n, n), "description": desc, "standard": standard, "related_r": [f"R{i}" for i in linked], "modules": mods, "original_check_index": index_text(desc, ident + ".正文") + index_text(standard, ident + ".标准"), "criterion_source_note": "正文约束及验收标准同时保留；拆句为原文核对索引，非新增标准。"})
    for n, line in enumerate(lines, 1):
        match = re.search(r"端到端流程\s+(E\d+b?)", line)
        if not match or match[1] not in E_LINKS:
            continue
        end = n
        while end < len(lines) and lines[end].strip() and not lines[end].startswith(("###", "|", "**端到端")):
            end += 1
        ident = match[1]
        linked = E_LINKS[ident]
        value = "\n".join(lines[n-1:end])
        objects.append({"id": ident, "kind": "E", "source": source_ref(n, end), "description": value, "standard": "逐段核对角色、操作、状态与最终数据；采用后续批准修订", "related_r": [f"R{i}" for i in linked], "modules": sorted({m for r in linked for m in modules(r)}), "original_check_index": [{"index": f"{ident}.{j}", "text": s.strip()} for j, s in enumerate(re.split(r"→|\n", value), 1) if s.strip()]})
    for obj in objects:
        related = {int(r[1:]) for r in obj["related_r"]}
        obj["amendments"] = [{"source": source_ref(lo, hi), "interpretation": title, "text": "\n".join(lines[lo-1:hi])} for lo, hi, ids, title in AMENDMENTS if related.intersection(ids) or obj["id"] in {"N4", "N5", "N6", "N10"} and lo == 388]
        obj["global_role_source"] = source_ref(46, 71)
        keys = {k for m in obj["modules"] for k in PAGE_KEYS[m]}
        prefixes = {p for m in obj["modules"] for p in PREFIXES[m]}
        matched_routes = [r for r in routes if any(k in r["path"] for k in keys)]
        obj["entry_candidates"] = [r["id"] for r in matched_routes]
        obj["operation_candidates"] = [o["id"] for o in operations if o["path"].startswith("/api/v1/") and o["path"].split("/")[3] in prefixes]
        obj["roles_to_check"] = sorted({role for r in matched_routes for role in r["roles_candidate"]})
        obj["data_scope"] = "按原需求逐项核对全局/绑定社区/本人/会话参与者；角色候选不表示已获授权。"
        linked_ids = obj["related_r"] if obj["kind"] == "E" else [obj["id"]]
        obj["evidence_candidates"] = [{"source": RTM, "requirement": r, "case_ids": legacy[r]["caseIds"], "additional_evidence": legacy[r]["additionalEvidence"], "source_interpretation": legacy[r]["conclusion"]} for r in linked_ids if r in legacy]
        obj["evidence_applicability"] = "候选来源已关联；60-02按原判据、角色、版本、配置、规模核实可采用部分，登记未证明部分；不能以映射非空判通过。"
        obj["known_scope_notes"] = []
        if related.intersection({61, 66, 71, 72}):
            obj["known_scope_notes"].append("涉及金融节点采用2026-10-18用户已确认可用的记录；AI不执行支付/查单/退款/渠道写入，也不扩写为全部异常分支已测。")
        if obj["id"] in {"R40", "R41", "R42", "E9"}:
            obj["known_scope_notes"].append("C9先核实G-001～003；旧16卡/四图数量不替代现行指标、筛选及导出判据。")
        if obj["kind"] == "E":
            obj["known_scope_notes"].append("原流程角色与旧签约/续租步骤须结合现行R及修订解释；未在本轮建账中作流程通过结论。")
        obj["registry_is_acceptance_result"] = False
        obj["evidence_gaps_to_resolve"] = ["各子判据与候选材料的实际证明关系", "当前版本、角色与数据条件适用性", "原要求的成功/拒绝/边界分支及流程接续结果"]
    changes = git("diff", "--name-only", SOURCE, "HEAD", "--", "backend/src", "frontend/src", "backend/pom.xml", "frontend/package.json", "frontend/package-lock.json").splitlines()
    jar = ROOT / "output/runtime/df-closeout/df-final.jar"
    baseline = {"collected_at": datetime.now().astimezone().isoformat(), "source_head": git("rev-parse", "HEAD"), "requirement_source": REQ, "requirement_sha256": sha(ROOT/REQ), "evidence_source_head": SOURCE, "product_files_changed_since_evidence_source": changes, "normal_runtime_record": "docs/50_系统测试/evidence/df-final-remediation-20261019/normal-final-load.json", "local_jar_sha256": sha(jar) if jar.exists() else None, "runtime_check": "OpenAPI GET只读采集；本次不启动/停止服务，不检查数据库或调用业务写入。PID来源由本批运行记录另记。", "openapi_version": api.get("openapi"), "api_path_count": len(api["paths"]), "api_operation_count": len(operations), "static_route_count": len(routes), "route_sources": {path: sha(ROOT/path) for path in route_sources.values()}, "requirement_counts": dict(Counter(o["kind"] for o in objects)), "limitations": ["此产物是来源、入口与证据候选登记，不是验收结果。", "源码相同不单独证明历史性能环境/数据规模相同。", "静态路由与OpenAPI不证明页面可用或权限正确。"], "business_writes": 0, "financial_operations": 0}
    save("baseline.json", baseline)
    save("requirements.json", objects)
    save("routes.json", routes)
    save("api-operations.json", operations)
    markdown = ["# 判据与入口覆盖清单", "", "采集日期：2026-10-19。执行60-01形成的来源登记；逐条结论由[需求验收台账](需求逐条验收台账.md)维护，执行顺序见[细化实施计划](实施计划.md)。", "", "## 1. 来源与适用性", "", f"需求源SHA256：{baseline['requirement_sha256']}。本批包含75条R、10条N、13个E，保留需求正文、标准、完整流程及适用修订。", "", f"实际OpenAPI登记{len(api['paths'])}个路径、{len(operations)}个HTTP操作；当前五组路由源码登记{len(routes)}条静态记录（含父路由及跳转，不等于页面数量）。", "", "详细字段见[需求与判据登记](evidence/acceptance-baseline-20261019/requirements.json)、[路由索引](evidence/acceptance-baseline-20261019/routes.json)、[API操作索引](evidence/acceptance-baseline-20261019/api-operations.json)及[版本基线](evidence/acceptance-baseline-20261019/baseline.json)。", "", "需求正文约束与验收标准同时进入核对索引；分号拆分不能代替语义核对，旧口径与后续修订均保留来源，验收时采用现行口径。入口及角色按模块关联为核对候选，不表示每项已实现、每个角色有权访问或证据充分。", "", "产品源码与50来源提交的差异：" + ("无（仅说明代码适用关系，运行配置/性能数据仍须另核对）。" if not changes else "有，见基线文件；需先核对影响。"), "", "## 2. 98对象覆盖索引", "", "原文行用于回到20源文件；判据索引数仅是文本定位数量，不作为验收标准条数或通过率。表中证据均为候选，详细记录保留角色、数据范围、修订和待补证项。", "", "| 对象 | 模块 | 原文行 | 原判据索引数 | 代表入口 | 证据适用性动作 |", "|---|---|---:|---:|---|---|"]
    route_lookup = {r["id"]: r for r in routes}
    for obj in objects:
        entries = list(dict.fromkeys(route_lookup[k]["path"] for k in obj["entry_candidates"] if not route_lookup[k]["redirect"]))[:3]
        action = "先核实C9；按标准确认" if obj["id"] in {"R40", "R41", "R42", "E9"} else "按原判据核实候选证据"
        if any("金融" in s for s in obj["known_scope_notes"]): action += "；金融沿用户确认"
        markdown.append(f"| {obj['id']} | {'/'.join(obj['modules'])} | {obj['source']['start_line']} | {len(obj['original_check_index'])} | {'、'.join(entries) or '按共享能力与接口核对'} | {action} |")
    markdown += ["", "## 3. 接续核对顺序", "", "先完成60-02.1的C9批次，确认时间筛选、图片导出及旧展示口径；其余功能、非功能和流程按实施计划分批办理。N8浏览器范围在执行当天查明，N7量级/相对变化、完整授权范围和会话时效等材料不足部分保持缺口；金融不补做交易。", "", "候选证据不自动成为当前版本的通过证明；未检查内容和未复现风险保持明确。本轮原需求登记不修改20～50或历史证据，也不启动11项质量评估。", ""]
    (PHASE / "判据与入口覆盖清单.md").write_text("\n".join(markdown), encoding="utf-8", newline="\r\n")
    return check()

def check():
    baseline = json.loads((OUT / "baseline.json").read_text(encoding="utf-8"))
    objects = json.loads((OUT / "requirements.json").read_text(encoding="utf-8"))
    expected = {f"R{i}" for i in range(1, 76)} | {f"N{i}" for i in range(1, 11)} | {f"E{i}" for i in range(1, 13)} | {"E10b"}
    assert len(objects) == 98 and {o["id"] for o in objects} == expected
    assert baseline["requirement_sha256"] == sha(ROOT / REQ)
    assert all(o["original_check_index"] and o["source"] and o["entry_candidates"] and o["operation_candidates"] and o["evidence_candidates"] and o["evidence_gaps_to_resolve"] for o in objects)
    assert all(o["registry_is_acceptance_result"] is False for o in objects)
    routes = {r["id"]: r for r in json.loads((OUT / "routes.json").read_text(encoding="utf-8"))}
    operation_ids = {o["id"] for o in json.loads((OUT / "api-operations.json").read_text(encoding="utf-8"))}
    for obj in objects:
        assert set(obj["operation_candidates"]) <= operation_ids
        for route_id in obj["entry_candidates"]:
            entry = routes[route_id]
            assert (ROOT / entry["source"]).exists()
            if entry["component"]:
                assert (ROOT / entry["component"]).exists(), entry["component"]
    result = {"counts": baseline["requirement_counts"], "api_paths": baseline["api_path_count"], "api_operations": baseline["api_operation_count"], "static_route_records": baseline["static_route_count"], "source_and_entry_register_complete": True, "acceptance_awarded": False, "financial_operations": 0}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Read-only validation of the existing register; no network requests")
    args = parser.parse_args()
    check() if args.check else collect()
