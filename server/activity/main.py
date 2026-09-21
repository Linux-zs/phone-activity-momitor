import asyncio
from contextlib import asynccontextmanager, suppress
import json
import os
from pathlib import Path
import secrets
import time
from typing import Literal

from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field, model_validator, ConfigDict

from .db import connect, initialize, cleanup
from .security import digest
from .stats import aggregate, DAY

ROOT = Path(__file__).parent


def now_ms():
    return int(time.time() * 1000)


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-f0-9]{64}$")
    at: int = Field(ge=0)
    kind: Literal["unlock", "lock", "screen_on", "screen_off", "reset"]


class Coverage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-f0-9]{64}$")
    start: int = Field(ge=0)
    end: int = Field(ge=0)

    @model_validator(mode="after")
    def valid_range(self):
        if not 0 <= self.end - self.start <= 3 * DAY:
            raise ValueError("Invalid coverage range")
        return self


class Sync(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: str = Field(pattern=r"^[a-zA-Z0-9-]{8,64}$")
    captured_at: int = Field(ge=0)
    covered_until: int | None = Field(default=None, ge=0)
    battery: int | None = Field(default=None, ge=0, le=100)
    charging: bool | None = None
    network: Literal["wifi", "mobile", "other", "offline"]
    permission: bool
    diagnostic: Literal["ok", "permission_denied", "user_locked", "history_gap", "clock_changed", "query_failed"]
    events: list[Event] = Field(default_factory=list, max_length=1000)
    coverage: list[Coverage] = Field(default_factory=list, max_length=200)


def create_app(config=None, db_path=None):
    if config is None:
        config = json.loads(Path(os.environ.get("ACTIVITY_CONFIG", "/etc/phone-activity/secrets.json")).read_text())
    db_path = str(db_path or os.environ.get("ACTIVITY_DB", "/var/lib/phone-activity/activity.db"))
    initialize(db_path)

    async def janitor():
        while True:
            with connect(db_path) as db:
                cleanup(db, now_ms())
            await asyncio.sleep(3600)

    @asynccontextmanager
    async def lifespan(app):
        task = asyncio.create_task(janitor())
        yield
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")
    templates = Jinja2Templates(directory=ROOT / "templates")

    @app.middleware("http")
    async def headers(request, call_next):
        # Nginx has the same limit; also protect direct ASGI access.
        if request.method == "POST":
            length = request.headers.get("content-length")
            if length is None or not length.isdigit() or int(length) > 262144:
                return HTMLResponse("Request too large", status_code=413)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store, private"
        response.headers["X-Content-Type-Options"] = "nosniff"
        # Preserve same-origin form Origin; no-referrer can turn it into "null".
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'"
        return response

    def uploader(request: Request):
        token = request.headers.get("authorization", "")
        if not token.startswith("Bearer ") or not secrets.compare_digest(digest(token[7:]), config["upload_hash"]):
            raise HTTPException(401, "上传密钥无效")

    @app.get("/healthz")
    def health():
        return {"status": "ok"}

    @app.get("/login")
    def legacy_login():
        return RedirectResponse("/", status_code=303)

    @app.get("/", response_class=HTMLResponse)
    def home(request: Request):
        return templates.TemplateResponse(request=request, name="dashboard.html",
                                          context={"preview": config.get("preview", False)})

    @app.post("/api/v1/sync", dependencies=[Depends(uploader)])
    def sync(payload: Sync):
        now = now_ms()
        # Reject large clock skew before acknowledging anything. Client retains its queue.
        if abs(payload.captured_at - now) > 10 * 60000:
            raise HTTPException(422, "手机时间与服务器相差超过 10 分钟，请校准")
        if any(e.at > payload.captured_at for e in payload.events) or any(c.end > payload.captured_at for c in payload.coverage):
            raise HTTPException(422, "事件或覆盖范围晚于采集时间")
        if payload.covered_until is not None and payload.covered_until > payload.captured_at:
            raise HTTPException(422, "覆盖时间异常")
        cutoff = now - 30 * DAY
        with connect(db_path) as db:
            db.execute("BEGIN IMMEDIATE")
            device = db.execute("SELECT id FROM device").fetchone()
            if device and device[0] != payload.device_id:
                raise HTTPException(409, "已绑定其他设备，请管理员重置绑定")
            db.execute("INSERT OR IGNORE INTO device VALUES (?)", (payload.device_id,))
            for e in payload.events:
                existing = db.execute("SELECT at,kind FROM events WHERE id=?", (e.id,)).fetchone()
                if existing and tuple(existing) != (e.at, e.kind):
                    raise HTTPException(409, "事件 ID 冲突")
                if e.at >= cutoff:
                    db.execute("INSERT OR IGNORE INTO events VALUES (?,?,?)", (e.id, e.at, e.kind))
            for c in payload.coverage:
                existing = db.execute("SELECT start,end FROM coverage WHERE id=?", (c.id,)).fetchone()
                if existing and tuple(existing) != (c.start, c.end):
                    raise HTTPException(409, "覆盖 ID 冲突")
                if c.end >= cutoff:
                    db.execute("INSERT OR IGNORE INTO coverage VALUES (?,?,?)", (c.id, c.start, c.end))
            confirmed_until = db.execute("SELECT MAX(end) FROM coverage").fetchone()[0]
            db.execute("INSERT INTO snapshots(received,captured,covered_until,battery,charging,network,permission,diagnostic) VALUES (?,?,?,?,?,?,?,?)",
                       (now, payload.captured_at, confirmed_until, payload.battery, payload.charging,
                        payload.network, payload.permission, payload.diagnostic))
            cleanup(db, now)
        return {"accepted_event_ids": [e.id for e in payload.events], "accepted_coverage_ids": [c.id for c in payload.coverage], "server_time": now}

    @app.get("/api/v1/dashboard")
    def dashboard():
        now = now_ms()
        with connect(db_path) as db:
            cleanup(db, now)
            events = [dict(e) for e in db.execute("SELECT * FROM events ORDER BY at,id")]
            coverage = [(c[0], c[1]) for c in db.execute("SELECT start,end FROM coverage")]
            snapshot = db.execute("SELECT * FROM snapshots ORDER BY id DESC LIMIT 1").fetchone()
            # Bound public chart samples to one actual observation per ten-minute bucket.
            battery_history = [dict(row) for row in db.execute(
                "SELECT captured,battery,charging FROM snapshots WHERE battery IS NOT NULL "
                "AND captured<=? AND id IN (SELECT MAX(id) FROM snapshots WHERE battery IS NOT NULL "
                "AND captured<=? GROUP BY CAST(captured/600000 AS INTEGER)) ORDER BY captured",
                (now, now))]
        result = aggregate(events, coverage, now)
        result.update({"snapshot": {key: snapshot[key] for key in ("received", "covered_until", "battery", "charging", "permission", "diagnostic")} if snapshot else None,
                       "battery_history": battery_history,
                       "stale": not snapshot or now - snapshot["received"] > 7200000,
                       "server_time": now, "timeline": [{"at": e["at"], "kind": e["kind"]} for e in reversed(events[-100:])]})
        return result

    return app
