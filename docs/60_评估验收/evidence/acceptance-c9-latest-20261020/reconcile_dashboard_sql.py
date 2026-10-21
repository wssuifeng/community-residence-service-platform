"""Compare every C9 dashboard metric with independent SQL on the isolated DB."""
from __future__ import annotations

import hashlib
import json
import math
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[4]
OUTPUT = Path(__file__).resolve().parent / "full-numeric-reconciliation.json"
BASE = "http://127.0.0.1:8087"
DATABASE = "community_residence_dc_20261019"
COMMUNITY_ID = 2
START_DATE = "2026-10-01"
END_DATE = "2026-10-01"
END_EXCLUSIVE = "2026-10-02"


def sql_rows(query: str) -> list[list[str]]:
    env = os.environ.copy()
    password = env.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("DB_PASSWORD is required; password is never printed or persisted")
    env["MYSQL_PWD"] = password
    result = subprocess.run(
        [
            "mysql",
            "--protocol=TCP",
            "-h",
            "127.0.0.1",
            "-P",
            "3306",
            "-u",
            "root",
            DATABASE,
            "--batch",
            "--skip-column-names",
            "-e",
            query,
        ],
        check=True,
        capture_output=True,
        text=True,
        env=env,
    )
    return [line.split("\t") for line in result.stdout.splitlines() if line]


def one(query: str) -> str:
    rows = sql_rows(query)
    return rows[0][0] if rows and rows[0] else "0"


def grouped(query: str) -> dict[str, int]:
    return {row[0]: int(row[1]) for row in sql_rows(query)}


def period_filter(column: str) -> str:
    return f"{column} >= '{START_DATE}' AND {column} < '{END_EXCLUSIVE}'"


def main() -> None:
    session = requests.Session()
    login = session.post(
        f"{BASE}/api/v1/auth/admin/login",
        json={"username": "superadmin", "password": "Admin@123456"},
        timeout=15,
    )
    login.raise_for_status()
    token = login.json()["data"]["token"]
    headers = {"Authorization": f"Bearer {token}"}
    query = {
        "communityId": COMMUNITY_ID,
        "startDate": START_DATE,
        "endDate": END_DATE,
    }
    response = session.get(f"{BASE}/api/v1/statistics/dashboard", params=query, headers=headers, timeout=30)
    response.raise_for_status()
    payload = response.json()
    if payload.get("code") != 200:
        raise RuntimeError(f"Dashboard API returned business code {payload.get('code')}")
    actual = payload["data"]

    scope = f"community_id = {COMMUNITY_ID}"
    period_orders = f"{scope} AND {period_filter('created_at')}"
    period_order_ids = (
        "SELECT id FROM work_order WHERE " + period_orders
    )
    completion = (
        "SELECT work_order_id, MIN(created_at) completed_at FROM work_order_process "
        "WHERE operator_type='STAFF' AND action='COMPLETE' AND new_status='TO_CONFIRM' "
        f"AND {period_filter('created_at')} GROUP BY work_order_id"
    )
    expected_view_trend = {START_DATE: 0}
    expected_view_trend.update({row[0]: int(row[1]) for row in sql_rows(
        "SELECT snapshot_date,COALESCE(SUM(metric_value),0) FROM statistics_snapshot "
        f"WHERE {scope} AND metric_type='HOUSING_VIEW_DAILY' AND {period_filter('snapshot_date')} "
        "GROUP BY snapshot_date"
    )})
    expected: dict[str, object] = {
        "communityCount": 1,
        "buildingCount": int(one(f"SELECT COUNT(*) FROM building WHERE {scope} AND is_deleted=0")),
        "houseCount": int(one(f"SELECT COUNT(*) FROM house WHERE {scope} AND is_deleted=0")),
        "occupiedHouseCount": int(one(
            f"SELECT COUNT(*) FROM house WHERE {scope} AND is_deleted=0 "
            "AND status IN ('OWNER_OCCUPIED','TENANT_OCCUPIED')"
        )),
        "residentCount": int(one(
            f"SELECT COUNT(DISTINCT resident_id) FROM residence_relation WHERE {scope} AND move_out_date IS NULL"
        )),
        "activeLeaseCount": int(one(f"SELECT COUNT(*) FROM lease_record WHERE {scope} AND status='ACTIVE'")),
        "workOrderTotal": int(one(f"SELECT COUNT(*) FROM work_order WHERE {scope}")),
        "workOrderPending": int(one(
            f"SELECT COUNT(*) FROM work_order WHERE {scope} AND status IN ('PENDING','TO_ASSIGN')"
        )),
        "workOrderProcessing": int(one(
            f"SELECT COUNT(*) FROM work_order WHERE {scope} "
            "AND status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS','TO_CONFIRM')"
        )),
        "workOrderCompleted": int(one(
            f"SELECT COUNT(*) FROM work_order WHERE {scope} AND status IN ('COMPLETED','CLOSED')"
        )),
        "workOrderPeriodCount": int(one(f"SELECT COUNT(*) FROM work_order WHERE {period_orders}")),
        "workOrderPeriodCompletedCount": int(one(
            "SELECT COUNT(DISTINCT p.work_order_id) FROM work_order_process p "
            f"JOIN work_order w ON w.id=p.work_order_id WHERE w.{scope.split(' = ')[0]}={COMMUNITY_ID} "
            f"AND {period_filter('w.created_at')} AND p.operator_type='STAFF' "
            "AND p.action='COMPLETE' AND p.new_status='TO_CONFIRM' "
            f"AND {period_filter('p.created_at')}"
        )),
        "residentMoveInTotal": int(one(
            f"SELECT COUNT(*) FROM residence_relation WHERE {scope} AND {period_filter('move_in_date')}"
        )),
        "residentMoveOutTotal": int(one(
            f"SELECT COUNT(*) FROM residence_relation WHERE {scope} AND {period_filter('move_out_date')}"
        )),
        "reservationPeriodTotal": int(one(
            f"SELECT COUNT(*) FROM resource_reservation WHERE {scope} AND {period_filter('reserve_date')}"
        )),
        "reservationCompletedTotal": int(one(
            f"SELECT COUNT(*) FROM resource_reservation WHERE {scope} AND {period_filter('reserve_date')} "
            "AND status='COMPLETED'"
        )),
        "viewingPeriodTotal": int(one(
            f"SELECT COUNT(*) FROM viewing_appointment WHERE {scope} AND {period_filter('appointment_date')}"
        )),
        "viewingCompletedTotal": int(one(
            f"SELECT COUNT(*) FROM viewing_appointment WHERE {scope} AND {period_filter('appointment_date')} "
            "AND status='COMPLETED'"
        )),
        "housingViewPeriodTotal": int(one(
            "SELECT COALESCE(SUM(metric_value),0) FROM statistics_snapshot "
            f"WHERE {scope} AND metric_type='HOUSING_VIEW_DAILY' AND {period_filter('snapshot_date')}"
        )),
        "evaluationPeriodTotal": int(one(
            f"SELECT COUNT(*) FROM work_order_evaluation WHERE {scope} AND {period_filter('created_at')}"
        )),
        "workOrderStatusDistribution": grouped(
            f"SELECT status,COUNT(*) FROM work_order WHERE {scope} GROUP BY status"
        ),
        "houseStatusDistribution": grouped(
            f"SELECT status,COUNT(*) FROM house WHERE {scope} AND is_deleted=0 GROUP BY status"
        ),
        "ratingDistribution": grouped(
            f"SELECT CAST(rating AS CHAR),COUNT(*) FROM work_order_evaluation "
            f"WHERE {scope} AND {period_filter('created_at')} GROUP BY rating"
        ),
        "housingViewTrend": expected_view_trend,
    }

    expected["workOrderAvgProcessingHours"] = float(one(
        "SELECT COALESCE(AVG(TIMESTAMPDIFF(MICROSECOND,w.created_at,c.completed_at)/3600000000),0) "
        f"FROM work_order w JOIN ({completion}) c ON c.work_order_id=w.id WHERE {period_orders.replace('created_at','w.created_at')}"
    ))
    expected["workOrderCompletionRate"] = (
        expected["workOrderPeriodCompletedCount"] * 100.0 / expected["workOrderPeriodCount"]
        if expected["workOrderPeriodCount"]
        else 0.0
    )
    expected["reservationCompletionRate"] = (
        expected["reservationCompletedTotal"] * 100.0 / expected["reservationPeriodTotal"]
        if expected["reservationPeriodTotal"]
        else 0.0
    )
    evaluation_rows = sql_rows(
        "SELECT COALESCE(AVG(rating),0),COALESCE(SUM(is_satisfied=1)*100.0/NULLIF(COUNT(*),0),0) "
        f"FROM work_order_evaluation WHERE {scope} AND {period_filter('created_at')}"
    )
    expected["evaluationAvgRating"] = float(evaluation_rows[0][0])
    expected["evaluationSatisfactionRate"] = float(evaluation_rows[0][1])

    assigned_rows = sql_rows(
        "SELECT a.assignee_id,u.real_name,COUNT(*) FROM work_order_assignment a "
        "JOIN work_order w ON w.id=a.work_order_id JOIN sys_user u ON u.id=a.assignee_id "
        f"WHERE w.{scope.split(' = ')[0]}={COMMUNITY_ID} AND {period_filter('w.created_at')} "
        f"AND {period_filter('a.assign_time')} GROUP BY a.assignee_id,u.real_name"
    )
    completed_rows = sql_rows(
        "SELECT p.operator_id,u.real_name,COUNT(DISTINCT p.work_order_id) FROM work_order_process p "
        "JOIN work_order w ON w.id=p.work_order_id JOIN sys_user u ON u.id=p.operator_id "
        f"WHERE w.{scope.split(' = ')[0]}={COMMUNITY_ID} AND {period_filter('w.created_at')} "
        f"AND {period_filter('p.created_at')} AND p.operator_type='STAFF' "
        "AND p.action='COMPLETE' AND p.new_status='TO_CONFIRM' GROUP BY p.operator_id,u.real_name"
    )
    staff_expected: dict[int, dict[str, object]] = {}
    for staff_id, name, count in assigned_rows:
        staff_expected[int(staff_id)] = {"staffId": int(staff_id), "name": name, "assignedCount": int(count), "completedCount": 0}
    for staff_id, name, count in completed_rows:
        row = staff_expected.setdefault(int(staff_id), {"staffId": int(staff_id), "name": name, "assignedCount": 0, "completedCount": 0})
        row["completedCount"] = int(count)
    expected["staffWorkload"] = sorted(
        [dict(row, total=int(row["assignedCount"]) + int(row["completedCount"])) for row in staff_expected.values()],
        key=lambda row: (-int(row["total"]), int(row["staffId"])),
    )

    checks: dict[str, dict[str, object]] = {}
    for key, expected_value in expected.items():
        actual_value = actual.get(key)
        if isinstance(expected_value, float):
            passed = math.isclose(float(actual_value or 0), expected_value, rel_tol=1e-9, abs_tol=1e-6)
        else:
            passed = actual_value == expected_value
        checks[key] = {"passed": passed, "expected": expected_value, "actual": actual_value}
    overall = all(item["passed"] for item in checks.values())

    result = {
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "runtime": {"backend": BASE, "jarSha256": hashlib.sha256(
            (ROOT / "backend/target/residence-backend-0.1.0-SNAPSHOT.jar").read_bytes()
        ).hexdigest().upper()},
        "database": DATABASE,
        "filter": {"communityId": COMMUNITY_ID, "startDate": START_DATE, "endDate": END_DATE},
        "checkedMetrics": len(checks),
        "allPassed": overall,
        "checks": checks,
        "financialOperations": 0,
        "businessWrites": 0,
        "normalDatabaseWrites": 0,
        "limitations": [
            "Current snapshot values and period-event values are compared according to the fields' distinct backend semantics.",
            "This is a fixed isolated sample, not an exhaustive proof over all communities, dates, or every database row.",
        ],
    }
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"checkedMetrics": len(checks), "allPassed": overall,
                      "failed": [key for key, value in checks.items() if not value["passed"]],
                      "financialOperations": 0, "businessWrites": 0}))
    if not overall:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
