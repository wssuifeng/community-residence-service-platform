"""Read production-source metadata only; never starts services or calls business APIs."""
from collections import Counter
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
OUTPUT = Path(__file__).with_name("source-inventory.json")


def source_text(path):
    return path.read_text(encoding="utf-8-sig")


def largest(paths, limit=15):
    rows = [{"path": p.relative_to(ROOT).as_posix(),
             "lines": len(source_text(p).splitlines())} for p in paths]
    return sorted(rows, key=lambda row: (-row["lines"], row["path"]))[:limit]


def anchor(path, pattern):
    lines = source_text(ROOT / path).splitlines()
    hits = [i for i, text in enumerate(lines, 1) if re.search(pattern, text)]
    if not hits:
        raise ValueError(f"Evidence anchor missing: {path}: {pattern}")
    return {"path": path, "lines": hits, "pattern": pattern}


vue = list((ROOT / "frontend/src").rglob("*.vue"))
ts = [p for p in (ROOT / "frontend/src").rglob("*.ts")
      if "__tests__" not in p.parts and "test" not in p.relative_to(ROOT / "frontend/src").parts
      and not re.search(r"\.(spec|test)\.ts$", p.name)]
java = list((ROOT / "backend/src/main/java").rglob("*.java"))
roles = Counter(p.relative_to(ROOT / "frontend/src/views").parts[0]
                for p in vue if (ROOT / "frontend/src/views") in p.parents)
domains = Counter(p.relative_to(ROOT / "backend/src/main/java/com/community/residence").parts[0]
                  for p in java)
evidence = [
    anchor("frontend/src/views/admin/workorder/WorkOrderDetailView.vue", r"async function fetchDetail|await Promise.all"),
    anchor("frontend/src/views/resident/WorkOrderDetailView.vue", r"async function fetchDetail|await Promise.all"),
    anchor("frontend/src/views/staff/WorkOrderDetailView.vue", r"async function fetchDetail|order.value = await getWorkOrder|async function fetchTimeline"),
    anchor("frontend/src/utils/request.ts", r"includes\(response.config.method"),
    anchor("frontend/src/utils/dataFreshness.ts", r"dataRevision"),
    anchor("frontend/src/composables/useLiveRefresh.ts", r"function useLiveRefresh|watch\(dataRevision|setInterval"),
    anchor("frontend/src/composables/useConversationInbox.ts", r"async function load|for \(let next"),
    anchor("frontend/src/views/admin/community/StructureTreePane.vue", r"async function loadStructure|async function loadScopeHouses"),
    anchor("frontend/src/views/admin/community/structureData.ts", r"export async function mapLimited"),
    anchor("frontend/src/views/staff/DashboardView.vue", r"async function loadDashboard|listWorkOrders\(|onMounted"),
    anchor("frontend/src/views/resident/LeaseListView.vue", r"async function loadLeases|async function loadLeasePayments|function paymentEntryOf"),
    anchor("backend/src/main/java/com/community/residence/messaging/service/NotificationService.java", r"public void createForUser|private void create\(|convertAndSendToUser"),
    anchor("backend/src/main/java/com/community/residence/conversation/service/ConversationService.java", r"public void sendAgreementMessage|private void pushMessage|messagingTemplate.convertAndSend\("),
    anchor("backend/src/main/java/com/community/residence/feedback/service/FeedbackService.java", r"public void close\(|messagingTemplate.convertAndSend\("),
    anchor("backend/src/main/java/com/community/residence/community/service/CommunityService.java", r"public void delete\("),
    anchor("backend/src/main/java/com/community/residence/conversation/controller/ConversationController.java", r"private final|public ApiResponse.*viewingContext|public ApiResponse.*viewingSlots"),
    anchor("backend/src/main/java/com/community/residence/lease/service/LeaseService.java", r"public void applyTransition"),
    anchor("frontend/src/store/permission.ts", r"export const usePermissionStore"),
    anchor("frontend/src/components/layout/AppSidebar.vue", r"const menuItems"),
    anchor("frontend/src/components/conversation/ConversationPane.vue", r"^\.conversation-pane"),
    anchor("frontend/src/styles/variables.css", r"唯一权威来源"),
    anchor("frontend/src/composables/useChatChannel.ts", r"const channels = new Map|const merge ="),
    anchor("frontend/src/components/business/ChatMessageList.vue", r"const readingPositions"),
]
result = {
    "assessmentDate": "2026-10-18",
    "baseCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "method": "Static source inventory and exact source anchors; no runtime profiling, traffic, database or business mutation.",
    "limits": ["Line counts indicate scale, not defect severity or runtime cost.",
               "Role view counts include layouts/dialogs/subcomponents; not functional-page counts.",
               "No Bean dependency-cycle conclusion or measured latency is inferred from imports."],
    "frontend": {"vueFiles": len(vue), "nonTestTsFiles": len(ts),
                 "viewsByRole": dict(sorted(roles.items())), "largestVue": largest(vue), "largestTs": largest(ts)},
    "backend": {"javaFiles": len(java), "domainFileCounts": dict(sorted(domains.items())),
                "largestJava": largest(java)},
    "evidenceAnchors": evidence,
}
OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"baseCommit": result["baseCommit"], "vueFiles": len(vue),
                  "nonTestTsFiles": len(ts), "javaFiles": len(java), "anchors": len(evidence)}, ensure_ascii=False))
