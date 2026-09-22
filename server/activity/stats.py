"""Conservative interval aggregation: only closed, observed sessions count."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Asia/Shanghai")
DAY = 86_400_000


def aggregate(events, coverage, now_ms, days=30):
    today = datetime.fromtimestamp(now_ms / 1000, ZONE).date()
    daily = {str(today - timedelta(days=i)): {"date": str(today - timedelta(days=i)),
             "unlocks": 0, "screen_ms": 0, "unlocked_ms": 0, "incomplete": False,
             "screen_bins": [0] * 144, "coverage_bins": [0] * 144,
             "hour_unlocks": [0] * 24, "sessions": 0, "longest_ms": 0,
             "first_unlock": None, "last_lock": None}
             for i in reversed(range(days))}
    intervals = []
    for start, end in sorted(coverage):
        if end <= start:
            continue
        if intervals and start <= intervals[-1][1]:
            intervals[-1][1] = max(end, intervals[-1][1])
        else:
            intervals.append([start, end])

    def covered(start, end):
        return any(a <= start and end <= b for a, b in intervals)

    def mark(start, end):
        for item in daily.values():
            a = int(datetime.fromisoformat(item["date"]).replace(tzinfo=ZONE).timestamp() * 1000)
            # Intervals are half-open. A gap ending exactly at midnight must not
            # mark the following day as incomplete.
            if start < a + DAY and end > a:
                item["incomplete"] = True

    def add(start, end, field):
        if end <= start:
            return
        if not covered(start, end):
            mark(start, end)
            return
        while start < end:
            dt = datetime.fromtimestamp(start / 1000, ZONE)
            next_day = datetime.combine(dt.date() + timedelta(days=1), datetime.min.time(), ZONE)
            stop = min(end, int(next_day.timestamp() * 1000))
            if str(dt.date()) in daily:
                item = daily[str(dt.date())]
                item[field] += stop - start
                if field == "screen_ms":
                    item["sessions"] += 1
                    item["longest_ms"] = max(item["longest_ms"], stop - start)
                    midnight = int(dt.replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
                    cursor = start
                    while cursor < stop:
                        index = (cursor - midnight) // 600000
                        edge = min(stop, midnight + (index + 1) * 600000)
                        item["screen_bins"][index] += edge - cursor
                        cursor = edge
            start = stop

    screen = unlocked = None
    hidden = False
    last_unlock = None
    for event in sorted(events, key=lambda e: (e["at"], e["id"])):
        at, kind = event["at"], event["kind"]
        if at > now_ms:
            continue
        date = str(datetime.fromtimestamp(at / 1000, ZONE).date())
        if kind == "unlock":
            last_unlock = at
            if date in daily:
                daily[date]["unlocks"] += 1
                daily[date]["hour_unlocks"][datetime.fromtimestamp(at / 1000, ZONE).hour] += 1
                if daily[date]["first_unlock"] is None:
                    daily[date]["first_unlock"] = at
            hidden = True
            if screen is not None and unlocked is None:
                unlocked = at
        elif kind == "screen_on":
            if screen is not None:
                mark(screen, at)
            screen = at
            unlocked = at if hidden else None
        elif kind in ("screen_off", "lock", "reset"):
            if kind == "lock" and date in daily:
                daily[date]["last_lock"] = at
            if unlocked is not None and kind != "reset":
                add(unlocked, at, "unlocked_ms")
            unlocked = None
            hidden = False
            if kind != "lock":
                if screen is not None:
                    if kind == "reset":
                        mark(screen, at)
                    else:
                        add(screen, at, "screen_ms")
                screen = None
    if screen is not None:
        mark(screen, now_ms)
    # No observed coverage, permission gaps and partial days must never look complete.
    for item in daily.values():
        start = int(datetime.fromisoformat(item["date"]).replace(tzinfo=ZONE).timestamp() * 1000)
        item["elapsed_ms"] = max(0, min(DAY, now_ms - start))
        for index in range(144):
            left, right = start + index * 600000, min(start + (index + 1) * 600000, now_ms)
            if right > left:
                item["coverage_bins"][index] = sum(max(0, min(right, b) - max(left, a)) for a, b in intervals)
        if not covered(start, min(start + DAY, now_ms)):
            item["incomplete"] = True
    return {"daily": list(daily.values()), "last_unlock": last_unlock}
