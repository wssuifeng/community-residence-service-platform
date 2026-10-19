"""E5 isolated expiry evidence, using the fixed build's real task and mappers."""
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import psutil

spec = importlib.util.spec_from_file_location("flows", Path(__file__).with_name("supplement_acceptance_flows.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
rd = Path("output/runtime/acceptance-flow-20261020").resolve()
state = json.loads((rd / "state.json").read_text(encoding="utf-8-sig"))
jar = Path(state["jarPath"]).resolve()
assert str(jar) in psutil.Process(state["backendPid"]).cmdline()
assert hashlib.sha256(jar.read_bytes()).hexdigest() == state["jarSha256"] == m.seed["jarSha256"]
assert "df-closeout" in str(jar)
m.login("admin1", "Admin123456")
suffix = str(int(time.time()*1000)%1000000)
name = "acc_expiry_" + suffix
resident = m.api("guest", "E5 register isolated target resident", "POST", "/api/v1/auth/resident/register", {"username": name, "password": "Resident123456", "realName": "Acceptance Expiry Resident", "phone": "131"+suffix.zfill(8)})
m.login(name, "Resident123456", True)
house = m.api("admin1", "E5 create dedicated target house", "POST", "/api/v1/houses", {"unitId": m.ids["unitId"], "houseNumber": "E"+suffix, "floor": 1, "area": 60, "status": "VACANT"})
relation = m.api("admin1", "E5 establish target community relation", "POST", "/api/v1/residence-relations", {"residentId": resident["id"], "houseId": house["id"], "relationType": "OWNER", "moveInDate": dt.date.today().isoformat(), "remark": "isolated expiry evidence"})
now = dt.datetime.now().replace(microsecond=0)
expires = now + dt.timedelta(seconds=8)
notice = m.api("admin1", "E5 create expiring notice", "POST", "/api/v1/notices", {"communityId": m.cid, "targets": [], "title": "AC expiry "+suffix, "content": "isolated natural expiry", "priority": "NORMAL", "type": "ANNOUNCEMENT", "publishTime": now.isoformat(), "endTime": expires.isoformat()})
nid = notice["id"]
m.api("admin1", "E5 publish same notice", "PATCH", f"/api/v1/notices/{nid}/publish", {"publishTime": now.isoformat()})
before = m.api(name, "E5 resident sees same notice", "GET", f"/api/v1/notices?communityId={m.cid}&page=1&size=100")
assert any(x["id"] == nid for x in m.rows(before))
m.api(name, "E5 record same notice read", "POST", f"/api/v1/notices/{nid}/view")
viewers = m.api("admin1", "E5 verify same notice receipt", "GET", f"/api/v1/notices/{nid}/viewers?page=1&size=100")
assert any(x["residentId"] == resident["id"] for x in m.rows(viewers))
notifications = m.api(name, "E5 same notice notification", "GET", "/api/v1/notifications?page=1&size=100")
related = [x for x in m.rows(notifications) if str(x.get("sourceId")) == str(nid) and "NOTICE" in str(x)]
assert related, notifications
log_start = m.sql("SELECT COALESCE(MAX(id),0) FROM sys_task_log")[0][0]
while dt.datetime.now() <= expires:
    time.sleep(0.3)
expired = m.api(name, "E5 expired notice hidden before task", "GET", f"/api/v1/notices?communityId={m.cid}&page=1&size=100")
assert all(x["id"] != nid for x in m.rows(expired))
env = dict(os.environ)
for line in Path(".env").read_text(encoding="utf-8-sig").splitlines():
    if line.strip() and not line.lstrip().startswith("#") and "=" in line:
        key, value = line.removeprefix("export ").split("=", 1)
        env.setdefault(key.strip(), value.strip().strip(chr(34)).strip(chr(39)))
classpath = os.pathsep.join([str(rd), str(rd/"task-runner-classpath/BOOT-INF/classes"), str(rd/"task-runner-classpath/BOOT-INF/lib/*")])
run = subprocess.run([r"C:\Program Files\Java\jdk-17.0.4.1\bin\java.exe", "-cp", classpath, "AcceptanceNoticeTask"], env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=40)
(rd/"notice-task.log").write_text(run.stdout+run.stderr, encoding="utf-8")
assert run.returncode == 0, "task runner failed; inspect local diagnostic log"
after = m.api("admin1", "E5 archived notice remains readable", "GET", f"/api/v1/notices/{nid}")
assert after["status"] == "WITHDRAWN", after["status"]
logs = m.sql(f"SELECT id,task_name,status,processed_count,success_count,failed_count,start_time,end_time FROM sys_task_log WHERE id>{int(log_start)} AND task_name='NoticeAutoOfflineTask'")
assert any(x[2] == "SUCCESS" and int(x[3]) >= 1 and int(x[4]) >= 1 and int(x[5]) == 0 for x in logs), logs
result = {"recordedAt": dt.datetime.now().isoformat(), "jarSha256": state["jarSha256"], "normalDatabaseWrites": 0, "financialOperations": 0, "ids": {"residentId": resident["id"], "houseId": house["id"], "noticeId": nid, "notificationIds": [x["id"] for x in related]}, "expiresAt": expires.isoformat(), "trace": m.trace, "taskLogs": logs, "assertions": {"publishedAndVisible": True, "sameNoticeNotified": True, "sameNoticeReadReceipt": True, "naturallyExpiredHidden": True, "realTaskWithRealMapperWithdrawn": True, "historyRetained": True, "taskLoggedSuccess": True}, "limitations": ["Controlled invocation of the original task kernel, real MySQL mapper and isolated Redis lock; hourly cron delivery itself was not observed."]}
(m.OUT/"notice-expiry.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"requests": len(m.trace), "ids": result["ids"], "taskLogs": logs}, ensure_ascii=False))
