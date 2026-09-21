from datetime import datetime
import hashlib
import time
import subprocess
import sys

import pytest
from fastapi.testclient import TestClient

from activity.main import create_app
from activity.db import connect
from activity.security import digest
from activity.stats import aggregate, ZONE, DAY

TOKEN = "test-upload-token-which-is-not-production"
PASSWORD = "test-viewer-password"


def eid(value):
    return hashlib.sha256(value.encode()).hexdigest()


def event(at, kind):
    return {"id": eid(f"{at}:{kind}"), "at": at, "kind": kind}


@pytest.fixture
def system(tmp_path):
    path = tmp_path / "test.db"
    app = create_app({"upload_hash": digest(TOKEN)}, path)
    with TestClient(app, base_url="https://testserver") as client:
        yield client, path


def payload():
    now = int(time.time() * 1000)
    return {"device_id": "test-device", "captured_at": now, "covered_until": now,
            "battery": 75, "charging": False, "network": "wifi", "permission": True,
            "diagnostic": "ok", "events": [event(now - 60000, "unlock")],
            "coverage": [{"id": eid("coverage"), "start": now - 120000, "end": now}]}


def upload(client, data):
    return client.post("/api/v1/sync", json=data, headers={"Authorization": "Bearer " + TOKEN})


def test_public_read_private_upload(system):
    client, _ = system
    assert client.get("/").status_code == 200
    response = client.get("/api/v1/dashboard")
    assert response.status_code == 200
    assert "set-cookie" not in response.headers
    assert response.headers["cache-control"] == "no-store, private"
    assert client.post("/api/v1/sync", json=payload()).status_code == 401
    assert client.post("/api/v1/sync", json=payload(), headers={"Authorization": "Bearer wrong-key"}).status_code == 401
    assert upload(client, payload()).status_code == 200
    data = client.get("/api/v1/dashboard").json()
    assert "network" not in data["snapshot"]
    assert "id" not in data["timeline"][0]
    assert client.get("/login", follow_redirects=False).headers["location"] == "/"
    assert client.post("/login", data={"password": PASSWORD}).status_code == 405


def test_public_dashboard_empty_and_assets(system):
    client, _ = system
    page = client.get("/")
    assert "亮屏之间" in page.text
    assert "本地设计预览" not in page.text
    assert "script-src 'self'" in page.headers["content-security-policy"]
    for asset in ("dashboard.css", "dashboard.js"):
        assert client.get("/static/" + asset).status_code == 200
    data = client.get("/api/v1/dashboard").json()
    assert data["snapshot"] is None and data["last_unlock"] is None
    assert data["stale"] and data["timeline"] == []
    assert len(data["daily"]) == 30
    assert all(day["incomplete"] for day in data["daily"])


def test_idempotent_upload_and_historical_time(system):
    client, path = system
    p = payload()
    p["events"][0] = event(p["captured_at"] - DAY, "unlock")
    assert upload(client, p).status_code == 200
    assert upload(client, p).status_code == 200
    with connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 1
    data = client.get("/api/v1/dashboard").json()
    assert data["last_unlock"] == p["events"][0]["at"]
    assert data["snapshot"]["received"] >= p["captured_at"]
    assert not data["stale"]


def test_binding_conflict_and_clock_skew(system):
    client, _ = system
    p = payload()
    assert upload(client, p).status_code == 200
    p["device_id"] = "another-device"
    assert upload(client, p).status_code == 409
    p["device_id"] = "test-device"
    p["events"][0]["kind"] = "lock"
    assert upload(client, p).status_code == 409
    p["captured_at"] += DAY
    assert upload(client, p).status_code == 422


def test_expiry_and_stale(system):
    client, path = system
    p = payload()
    upload(client, p)
    with connect(path) as db:
        db.execute("UPDATE snapshots SET received=?", (p["captured_at"] - 3 * 3600000,))
        db.execute("INSERT INTO events VALUES (?,?,?)", (eid("old"), p["captured_at"] - 31 * DAY, "unlock"))
    data = client.get("/api/v1/dashboard").json()
    assert data["stale"]
    assert len(data["timeline"]) == 1


def test_cross_midnight_and_notification_only():
    at = int(datetime(2026, 9, 19, 23, 59, tzinfo=ZONE).timestamp() * 1000)
    events = [event(at, "screen_on"), event(at + 10000, "unlock"), event(at + 120000, "screen_off"),
              event(at + 180000, "screen_on"), event(at + 190000, "screen_off")]
    result = aggregate(events, [(at, at + 200000)], at + 200000)
    a, b = result["daily"][-2:]
    assert a["screen_ms"] == 60000 and b["screen_ms"] == 70000
    assert a["unlocked_ms"] == 50000 and b["unlocked_ms"] == 60000
    assert a["unlocks"] == 1 and b["unlocks"] == 0


def test_missing_event_gap_and_reset_are_not_duration():
    now = int(time.time() * 1000)
    start = now - 3600000
    for events, ranges in [
        ([event(start, "screen_on")], [(start, now)]),
        ([event(start, "screen_on"), event(now, "screen_off")], [(start, start + 1000), (now - 1000, now)]),
        ([event(start, "screen_on"), event(start + 10, "unlock"), event(now, "reset")], [(start, now)]),
    ]:
        result = aggregate(events, ranges, now)
        assert sum(d["screen_ms"] for d in result["daily"]) == 0
        assert sum(d["unlocked_ms"] for d in result["daily"]) == 0
        assert result["daily"][-1]["incomplete"]


def test_event_page_without_coverage_does_not_inflate_totals(system):
    client, _ = system
    p = payload()
    p["events"] = [event(p["captured_at"] - 60000, "screen_on"), event(p["captured_at"] - 1000, "screen_off")]
    p["coverage"] = []
    p["covered_until"] = None
    assert upload(client, p).status_code == 200
    assert sum(d["screen_ms"] for d in client.get("/api/v1/dashboard").json()["daily"]) == 0


def test_backup_restore_cli(system, tmp_path):
    client, path = system
    upload(client, payload())
    backup = tmp_path / "backup.db"
    restored = tmp_path / "restored.db"
    subprocess.run([sys.executable, "-m", "activity.manage", "backup", "--db", str(path), "--output", str(backup)], check=True, capture_output=True)
    subprocess.run([sys.executable, "-m", "activity.manage", "restore", "--db", str(restored), "--input", str(backup)], check=True, capture_output=True)
    with connect(restored) as db:
        assert db.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 1


def test_permission_denied_does_not_claim_coverage(system):
    client, _ = system
    p = payload()
    p.update(permission=False, diagnostic="permission_denied", covered_until=None, events=[], coverage=[])
    assert upload(client, p).status_code == 200
    data = client.get("/api/v1/dashboard").json()
    assert data["snapshot"]["permission"] == 0
    assert data["snapshot"]["covered_until"] is None
    assert data["daily"][-1]["incomplete"]


def test_mosaic_bins_split_midnight_and_exclude_unclosed_sessions():
    midnight = int(datetime(2026, 9, 20, tzinfo=ZONE).timestamp() * 1000)
    start = midnight - 5 * 60000
    events = [event(start, "screen_on"), event(start + 60000, "unlock"),
              event(midnight + 5 * 60000, "lock"), event(midnight + 15 * 60000, "screen_off"),
              event(midnight + 20 * 60000, "screen_on")]
    result = aggregate(events, [(start, midnight + 30 * 60000)], midnight + 30 * 60000)
    a, b = result["daily"][-2:]
    assert a["screen_bins"][-1] == 5 * 60000
    assert b["screen_bins"][:3] == [10 * 60000, 5 * 60000, 0]
    assert all(sum(d["screen_bins"]) == d["screen_ms"] for d in result["daily"])
    assert a["first_unlock"] == start + 60000 and a["hour_unlocks"][23] == 1
    assert b["first_unlock"] is None and b["last_lock"] == midnight + 5 * 60000
    assert b["sessions"] == 1 and b["longest_ms"] == 15 * 60000
    assert b["coverage_bins"][:4] == [600000, 600000, 600000, 0]
    assert b["elapsed_ms"] == 30 * 60000 and b["incomplete"]


def test_mosaic_missing_coverage_never_becomes_screen_time():
    start = int(datetime(2026, 9, 20, 10, tzinfo=ZONE).timestamp() * 1000)
    result = aggregate([event(start, "screen_on"), event(start + 1200000, "screen_off")],
                       [(start, start + 300000), (start + 900000, start + 1200000)], start + 1200000)
    day = result["daily"][-1]
    assert sum(day["screen_bins"]) == day["sessions"] == day["longest_ms"] == 0
    assert day["coverage_bins"][60:62] == [300000, 300000]
    assert day["last_lock"] is None  # Screen off is not a lock observation.


def test_battery_chart_uses_actual_bounded_public_samples(system):
    client, path = system
    p = payload()
    assert upload(client, p).status_code == 200
    p["battery"] = 70
    assert upload(client, p).status_code == 200
    data = client.get("/api/v1/dashboard").json()
    assert data["battery_history"] == [{"captured": p["captured_at"], "battery": 70, "charging": 0}]
    with connect(path) as db:
        db.execute("UPDATE snapshots SET captured=?", (p["captured_at"] + DAY,))
    assert client.get("/api/v1/dashboard").json()["battery_history"] == []
