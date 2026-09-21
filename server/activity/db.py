import sqlite3
from contextlib import contextmanager


@contextmanager
def connect(path):
    db = sqlite3.connect(path, timeout=15)
    db.row_factory = sqlite3.Row
    try:
        db.execute("PRAGMA journal_mode=WAL")
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def initialize(path):
    with connect(path) as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS device(id TEXT PRIMARY KEY);
        CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS event_time ON events(at);
        CREATE TABLE IF NOT EXISTS coverage(id TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS snapshots(id INTEGER PRIMARY KEY, received INTEGER NOT NULL,
            captured INTEGER NOT NULL, covered_until INTEGER, battery INTEGER, charging INTEGER,
            network TEXT, permission INTEGER NOT NULL, diagnostic TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS attempts(ip TEXT NOT NULL, at INTEGER NOT NULL);
        """)


def cleanup(db, now):
    cutoff = now - 30 * 86400000
    db.execute("DELETE FROM events WHERE at < ?", (cutoff,))
    db.execute("DELETE FROM coverage WHERE end < ?", (cutoff,))
    db.execute("DELETE FROM snapshots WHERE received < ?", (cutoff,))
    db.execute("DELETE FROM sessions WHERE expires < ?", (now,))
    db.execute("DELETE FROM attempts WHERE at < ?", (now - 900000,))
