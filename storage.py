"""Persist scan metadata only. Images and boxes are never written to SQLite."""

import json
import sqlite3
from contextlib import closing, contextmanager
from datetime import datetime, timezone


@contextmanager
def connect(path):
    with closing(sqlite3.connect(path, timeout=5)) as database, database:
        database.execute("PRAGMA secure_delete = ON")
        database.execute(
            "CREATE TABLE IF NOT EXISTS scans (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT, detections TEXT)"
        )
        yield database


def save_scan(path, detections):
    metadata = [{"label": item["label"], "confidence": item["confidence"]} for item in detections]
    with connect(path) as database:
        row = database.execute(
            "INSERT INTO scans (time, detections) VALUES (?, ?)",
            (datetime.now(timezone.utc).isoformat(), json.dumps(metadata)),
        )
        return row.lastrowid


def records(path, scan_id=None):
    with connect(path) as database:
        if scan_id is None:
            rows = database.execute("SELECT id, time, detections FROM scans ORDER BY id DESC").fetchall()
        else:
            rows = database.execute("SELECT id, time, detections FROM scans WHERE id = ?", (scan_id,)).fetchall()
    return [{"id": i, "time": time, "detections": json.loads(data)} for i, time, data in rows]


def clear(path):
    with connect(path) as database:
        database.execute("DELETE FROM scans")


def summarize(detections, labels):
    groups = {label: [] for label in labels}
    for item in detections:
        groups[item["label"]].append(item["confidence"])
    return {
        label: {"count": len(scores), "average": sum(scores) / len(scores) if scores else None}
        for label, scores in groups.items()
    }
