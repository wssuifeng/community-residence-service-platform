"""G-016 actual HTTP retest. Temporal fixtures only touch isolated synthetic rows."""
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import psutil

spec = importlib.util.spec_from_file_location("flows", Path(__file__).with_name("supplement_acceptance_flows.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
rd = Path("output/runtime/acceptance-flow-20261020")
state = json.loads((rd/"state.json").read_text(encoding="utf-8-sig"))
jar = Path(state["jarPath"])
assert str(jar) in psutil.Process(state["backendPid"]).cmdline()
assert hashlib.sha256(jar.read_bytes()).hexdigest() == state["jarSha256"] == "8cd5106bfe2197ce895b9aa51b29d677e53c1358e7c759a5e3b6848a23d3a2db"
expiry = json.loads((m.OUT/"notice-expiry.json").read_text(encoding="utf-8"))
resident_id = expiry["ids"]["residentId"]
name = m.sql(f"SELECT username FROM resident WHERE id={int(resident_id)}")[0][0]
assert name.startswith("acc_expiry_")
m.login(name, "Resident123456", True); m.login("admin1", "Admin123456")
today = dt.date.today(); target = dt.date.fromisoformat(m.ids["targetDate"])
assert today < target
m.api("admin1", "configure today's template for completion retest", "POST", f"/api/v1/resources/{m.ids['resourceId']}/timeslots", {"dayOfWeek": today.isoweekday(), "startTime": "09:00:00", "endTime": "11:00:00", "isAvailable": 1})

def create(label, day, start, end, confirm=True):
    reservation = m.api(name, label+" submit", "POST", "/api/v1/resource-reservations", {"resourceId": m.ids["resourceId"], "reserveDate": day.isoformat(), "startTime": start, "endTime": end, "purpose": "isolated G-016 retest", "contactPhone": "13100000000"})
    if confirm: m.api("admin1", label+" approve", "PATCH", f"/api/v1/resource-reservations/{reservation['id']}/confirm", {"reason": "G-016 retest"})
    return reservation["id"]

def read(res_id, label): return m.api(name, label, "GET", f"/api/v1/resource-reservations/{res_id}")

def reject(res_id, label, message):
    m.api("admin1", label, "PATCH", f"/api/v1/resource-reservations/{res_id}/complete", {"reason": "G-016 retest"}, deny=True)
    assert m.trace[-1]["code"] == 5004 and message in m.trace[-1]["message"], m.trace[-1]

future = create("future reservation", target, "09:00:00", "09:30:00")
url = f"/api/v1/resources/{m.ids['resourceId']}/available-slots?startDate={target}&endDate={target}"
before = m.api(name, "future capacity before rejection", "GET", url)
reject(future, "future completion rejected", "预约时段尚未结束")
assert read(future, "future status unchanged")["status"] == "RESERVED"
after = m.api(name, "future capacity after rejection", "GET", url)
assert before == after
same_day = create("today unfinished reservation", today, "09:00:00", "09:30:00")
reject(same_day, "today before end rejected", "预约时段尚未结束")
assert read(same_day, "today status unchanged")["status"] == "RESERVED"
elapsed = create("elapsed temporal fixture", target, "09:30:00", "10:00:00")
pending = create("pending temporal fixture", target, "10:00:00", "10:30:00", False)
fixture_before = {str(x): read(x, "read temporal fixture before date setup") for x in (elapsed, pending)}
env = dict(os.environ)
for line in Path(".env").read_text(encoding="utf-8-sig").splitlines():
    if line.strip() and not line.lstrip().startswith("#") and "=" in line:
        key, val = line.removeprefix("export ").split("=", 1); env.setdefault(key.strip(), val.strip().strip(chr(34)).strip(chr(39)))
env["MYSQL_PWD"] = env["DB_PASSWORD"]
yesterday = today-dt.timedelta(days=1)
sql = f"UPDATE resource_reservation SET reserve_date='{yesterday}' WHERE id IN ({int(elapsed)},{int(pending)}) AND user_id={int(resident_id)} AND resource_id={int(m.ids['resourceId'])} AND reserve_date='{target}'; SELECT ROW_COUNT();"
command = [r"C:\Program Files\MySQL\MySQL Server 9.6\bin\mysql.exe", "-h", "localhost", "-u", env.get("DB_USERNAME", "root"), "--batch", "--skip-column-names", "community_residence_dc_20261019", "-e", sql]
run = subprocess.run(command, env=env, capture_output=True, text=True)
assert run.returncode == 0 and run.stdout.strip() == "2", "isolated temporal fixture update failed"
m.api("admin1", "elapsed reservation completes", "PATCH", f"/api/v1/resource-reservations/{elapsed}/complete", {"reason": "elapsed temporal fixture"})
assert read(elapsed, "elapsed status completed")["status"] == "COMPLETED"
reject(pending, "pending remains invalid after elapsed", "状态")
assert read(pending, "pending status unchanged")["status"] == "PENDING"
result = {"recordedAt": dt.datetime.now().isoformat(), "jarSha256": state["jarSha256"], "normalDatabaseWrites": 0, "financialOperations": 0, "trace": m.trace, "ids": {"residentId": resident_id, "futureReservation": future, "todayReservation": same_day, "elapsedReservation": elapsed, "pendingReservation": pending}, "temporalFixture": {"sql": sql, "affectedRows": 2, "before": fixture_before, "purpose": "Simulate elapsed time only on API-created and approved synthetic isolated rows; no production data or payment fields modified."}, "assertions": {"futureRejected5004": True, "todayBeforeEndRejected5004": True, "rejectedStatusUnchanged": True, "rejectedCapacityUnchanged": True, "elapsedApprovedCompleted": True, "elapsedPendingRejected": True}, "limitations": ["The past-date setup is an explicit test fixture, not a claim that a real-world booking interval elapsed during this run.", "Exact-end boundary and cross-community rejection are additionally covered by the seven fixed-time unit tests."]}
(m.OUT/"g016-retest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"requests": len(m.trace), "ids": result["ids"], "assertions": result["assertions"]}, ensure_ascii=False))
