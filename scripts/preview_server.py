"""Local-only UI verification with synthetic activity. Never deploy this script."""
from pathlib import Path
import sys
import time
from datetime import datetime

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from activity.main import create_app
from activity.security import digest
from activity.db import connect
from activity.stats import ZONE, DAY
import uvicorn

path = Path(__file__).resolve().parents[1] / "output" / "playwright"
path.mkdir(parents=True, exist_ok=True)
db_path = path / "preview.db"
app = create_app({"preview": True, "upload_hash": digest("preview-only-upload-token")}, db_path)
now = int(time.time() * 1000)
midnight = int(datetime.fromtimestamp(now / 1000, ZONE).replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
with connect(str(db_path)) as db:
    db.execute("DELETE FROM events")
    db.execute("DELETE FROM coverage")
    db.execute("DELETE FROM snapshots")
    for day in range(30):
        start_day = midnight - day * DAY
        count = 8 + (day * 7) % 23
        # Today's sessions fit inside elapsed time even when preview starts before 08:00.
        end = now - 120000 if day == 0 else start_day + 22 * 3600000
        beginning = min(start_day + 8 * 3600000, start_day + max(0, end-start_day) // 4)
        if end <= beginning:
            continue
        step = (end - beginning) // count
        for i in range(count):
            start = beginning + i * step
            duration = min((8 + i % 17) * 60000, step - 10000)
            if duration <= 5000:
                continue
            for at, kind in [(start, "screen_on"), (start + 5000, "unlock"), (start + duration, "screen_off")]:
                db.execute("INSERT INTO events VALUES (?,?,?)", (digest(f"{at}:{kind}"), at, kind))
        # One deliberately missing day demonstrates incomplete coverage honestly.
        if day != 4:
            db.execute("INSERT INTO coverage VALUES (?,?,?)", (digest(f"preview-{day}"), start_day, now if day == 0 else start_day + DAY))
    # Synthetic half-hour observations let the preview exercise the real chart path.
    for day in reversed(range(30)):
        base = midnight - day * DAY
        for half_hour in range(48):
            captured = base + half_hour * 1800000
            if captured >= now:
                continue
            hour = half_hour / 2
            charging = 12 <= hour < 14 or 19 <= hour < 20
            battery = round(max(15, min(100, 96 - hour * 2.5 + (20 if hour >= 14 else 0))))
            db.execute("INSERT INTO snapshots(received,captured,covered_until,battery,charging,network,permission,diagnostic) VALUES (?,?,?,?,?,?,?,?)",
                       (captured, captured, captured, battery, int(charging), "wifi", 1, "ok"))
    db.execute("INSERT INTO snapshots(received,captured,covered_until,battery,charging,network,permission,diagnostic) VALUES (?,?,?,?,?,?,?,?)", (now, now, now, 68, 0, "wifi", 1, "ok"))
uvicorn.run(app, host="127.0.0.1", port=8766, access_log=False)
