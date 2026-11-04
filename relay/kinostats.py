#!/usr/bin/env python3
"""
Kinotrek usage statistics: anonymous app events, daily counts and GitHub APK downloads.

Two HTTP servers, both bound to 127.0.0.1 only:
  * ingest (KINOSTATS_BIND, default 127.0.0.1:8447) — relay.py forwards the app's POST /v1/telemetry/events and
    POST /v1/telemetry/delete here. It never sees an IP address: relay.py does not pass one on.
  * admin (KINOSTATS_ADMIN_BIND, default 127.0.0.1:8448) — the owner's dashboard, aggregates only. Reachable
    only through an SSH tunnel (ssh -L 8448:127.0.0.1:8448 …); nothing of it is exposed to the internet.

What is stored: a random installation id (made by the app after the viewer agreed), event type, minute-rounded
time, app version, session id. Never titles, searches, magnets, addresses, devices or payments.
GitHub APK downloads are read every hour (and on demand from the dashboard).
Retention: raw events 30 days, per-installation days 90 days, daily aggregates 24 months.

Environment: KINOSTATS_DB (default /var/lib/kinostats/stats.db), KINOSTATS_GITHUB_REPO (dncdante911/Kinotrek-TV),
KINOSTATS_GITHUB_TOKEN (optional, raises the API rate limit).
"""
import csv
import datetime as dt
import html
import io
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("KINOSTATS_DB", "/var/lib/kinostats/stats.db")
BIND = os.environ.get("KINOSTATS_BIND", "127.0.0.1:8447")
ADMIN_BIND = os.environ.get("KINOSTATS_ADMIN_BIND", "127.0.0.1:8448")
REPO = os.environ.get("KINOSTATS_GITHUB_REPO", "dncdante911/Kinotrek-TV")
GITHUB_TOKEN = os.environ.get("KINOSTATS_GITHUB_TOKEN", "")

MAX_BODY = 16_384
MAX_EVENTS = 50
MAX_AGE_S = 7 * 86400
EVENT_TYPES = {"first_open", "session_start", "daily_active", "session_end"}
UUID4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
VERSION = re.compile(r"^[0-9A-Za-z.\-+_]{1,32}$")
POSTS_PER_INSTALL_PER_DAY = 30
RAW_DAYS, DAILY_DAYS, AGGREGATE_DAYS = 30, 90, 730
GITHUB_EVERY_S = 3600  # one API call an hour: a new release shows within the hour

db_lock = threading.Lock()


def connect():
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    db = sqlite3.connect(DB_PATH, check_same_thread=False)
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript("""
        CREATE TABLE IF NOT EXISTS telemetry_events (
            event_id TEXT PRIMARY KEY, installation_id TEXT NOT NULL, event_type TEXT NOT NULL,
            occurred_at_utc INTEGER NOT NULL, received_at_utc INTEGER NOT NULL, app_version TEXT NOT NULL,
            version_code INTEGER, session_id TEXT, schema_version INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS ev_time ON telemetry_events(occurred_at_utc);
        CREATE INDEX IF NOT EXISTS ev_inst ON telemetry_events(installation_id, occurred_at_utc);
        CREATE TABLE IF NOT EXISTS telemetry_daily_installations (
            day_utc TEXT NOT NULL, installation_id TEXT NOT NULL, app_version TEXT NOT NULL,
            first_seen_day_utc TEXT NOT NULL, sessions_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (day_utc, installation_id));
        CREATE TABLE IF NOT EXISTS installations (
            installation_id TEXT PRIMARY KEY, first_seen_day_utc TEXT NOT NULL, last_seen_day_utc TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS daily_aggregates (
            day_utc TEXT PRIMARY KEY, active INTEGER NOT NULL, new_installs INTEGER NOT NULL,
            sessions INTEGER NOT NULL, versions TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS github_asset_snapshots (
            asset_id INTEGER NOT NULL, captured_at_utc INTEGER NOT NULL, release_tag TEXT NOT NULL,
            name TEXT NOT NULL, download_count INTEGER NOT NULL, PRIMARY KEY (asset_id, captured_at_utc));
        CREATE TABLE IF NOT EXISTS post_counts (installation_id TEXT, day_utc TEXT, posts INTEGER,
            PRIMARY KEY (installation_id, day_utc));
        CREATE TABLE IF NOT EXISTS service_log (at INTEGER, what TEXT);
    """)
    db.commit()
    return db


db = None


def day_of(ts):
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%d")


def now():
    return int(time.time())


# ---- Ingest ----

class Rejected(ValueError):
    pass


def validate(body):
    """The batch as rows to store; raises Rejected with a short reason."""
    try:
        data = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        raise Rejected("bad json")
    if not isinstance(data, dict) or set(data) - {"schema", "installation_id", "events", "app"}:
        raise Rejected("unknown fields")
    if data.get("schema") != 1:
        raise Rejected("schema")
    inst = data.get("installation_id")
    if not isinstance(inst, str) or not UUID4.match(inst):
        raise Rejected("installation_id")
    app = data.get("app")
    if not isinstance(app, dict) or set(app) - {"version_name", "version_code"}:
        raise Rejected("app")
    version = app.get("version_name")
    code = app.get("version_code")
    if not isinstance(version, str) or not VERSION.match(version) or not isinstance(code, int) or not 0 < code < 10_000_000:
        raise Rejected("version")
    events = data.get("events")
    if not isinstance(events, list) or not 0 < len(events) <= MAX_EVENTS:
        raise Rejected("events")
    t = now()
    rows = []
    for e in events:
        if not isinstance(e, dict) or set(e) - {"event_id", "type", "at", "session_id"}:
            raise Rejected("event fields")
        if e.get("type") not in EVENT_TYPES:
            raise Rejected("event type")
        eid, sid = e.get("event_id"), e.get("session_id")
        if not isinstance(eid, str) or not UUID4.match(eid):
            raise Rejected("event_id")
        if sid is not None and (not isinstance(sid, str) or not UUID4.match(sid)):
            raise Rejected("session_id")
        try:
            at = dt.datetime.strptime(e.get("at", ""), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc).timestamp()
        except (TypeError, ValueError):
            raise Rejected("at")
        at = int(at) // 60 * 60
        if not t - MAX_AGE_S <= at <= t + 3600:
            continue  # too old or from a wrong clock: dropped quietly, the app must not retry it forever
        rows.append((eid, inst, e["type"], at, t, version, code, sid, 1))
    return inst, rows


def store(inst, rows):
    today = day_of(now())
    with db_lock:
        cur = db.execute("SELECT posts FROM post_counts WHERE installation_id=? AND day_utc=?", (inst, today)).fetchone()
        if cur and cur[0] >= POSTS_PER_INSTALL_PER_DAY:
            raise OverflowError("too many posts")
        db.execute("INSERT INTO post_counts VALUES (?,?,1) ON CONFLICT(installation_id, day_utc) DO UPDATE SET posts=posts+1", (inst, today))
        for row in rows:
            fresh = db.execute("INSERT OR IGNORE INTO telemetry_events VALUES (?,?,?,?,?,?,?,?,?)", row).rowcount
            if not fresh:
                continue  # the same event delivered again changes nothing
            _, _, kind, at, _, version, _, _, _ = row
            day = day_of(at)
            first = db.execute("SELECT first_seen_day_utc FROM installations WHERE installation_id=?", (inst,)).fetchone()
            if first is None:
                db.execute("INSERT INTO installations VALUES (?,?,?)", (inst, day, day))
                first_day = day
            else:
                first_day = min(first[0], day)
                db.execute("UPDATE installations SET first_seen_day_utc=?, last_seen_day_utc=max(last_seen_day_utc, ?) WHERE installation_id=?",
                           (first_day, day, inst))
            db.execute("""INSERT INTO telemetry_daily_installations VALUES (?,?,?,?,?)
                          ON CONFLICT(day_utc, installation_id) DO UPDATE SET app_version=excluded.app_version,
                          sessions_count=sessions_count+excluded.sessions_count""",
                       (day, inst, version, first_day, 1 if kind == "session_start" else 0))
        db.commit()


def forget(inst):
    """Deletes everything kept per installation; daily aggregates already made stay (they hold no ids)."""
    with db_lock:
        for table in ("telemetry_events", "telemetry_daily_installations", "installations", "post_counts"):
            db.execute(f"DELETE FROM {table} WHERE installation_id=?", (inst,))
        db.commit()


class Ingest(BaseHTTPRequestHandler):
    server_version = "kinostats"

    def log_message(self, fmt, *args):
        pass  # no access log: nothing about who sent what

    def do_POST(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= MAX_BODY:
            return self.answer(413 if length > MAX_BODY else 400, {"error": "size"})
        body = self.rfile.read(length)
        try:
            if path == "/v1/telemetry/events":
                inst, rows = validate(body)
                store(inst, rows)
                return self.answer(200, {"accepted": len(rows)})
            if path == "/v1/telemetry/delete":
                data = json.loads(body)
                inst = data.get("installation_id") if isinstance(data, dict) else None
                if not isinstance(inst, str) or not UUID4.match(inst):
                    raise Rejected("installation_id")
                forget(inst)
                return self.answer(200, {"deleted": True})
            return self.answer(404, {"error": "not found"})
        except Rejected as e:
            return self.answer(400, {"error": str(e)})
        except OverflowError:
            return self.answer(429, {"error": "slow down"})
        except (ValueError, UnicodeDecodeError):
            return self.answer(400, {"error": "bad json"})
        except sqlite3.Error:
            return self.answer(503, {"error": "storage"})

    def answer(self, status, body):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


# ---- Housekeeping: daily aggregates, TTL ----

def aggregate_day(day):
    rows = db.execute("SELECT app_version, sessions_count, first_seen_day_utc FROM telemetry_daily_installations WHERE day_utc=?", (day,)).fetchall()
    versions = {}
    for version, _, _ in rows:
        versions[version] = versions.get(version, 0) + 1
    db.execute("INSERT OR REPLACE INTO daily_aggregates VALUES (?,?,?,?,?)",
               (day, len(rows), sum(1 for r in rows if r[2] == day), sum(r[1] for r in rows), json.dumps(versions, sort_keys=True)))


def housekeeping():
    t = now()
    with db_lock:
        # Days still within the per-installation window are recomputed: late events and deletions are reflected.
        for i in range(0, 8):
            aggregate_day(day_of(t - i * 86400))
        db.execute("DELETE FROM telemetry_events WHERE occurred_at_utc < ?", (t - RAW_DAYS * 86400,))
        db.execute("DELETE FROM telemetry_daily_installations WHERE day_utc < ?", (day_of(t - DAILY_DAYS * 86400),))
        db.execute("DELETE FROM daily_aggregates WHERE day_utc < ?", (day_of(t - AGGREGATE_DAYS * 86400),))
        db.execute("DELETE FROM post_counts WHERE day_utc < ?", (day_of(t - 2 * 86400),))
        db.execute("DELETE FROM service_log WHERE at < ?", (t - 30 * 86400,))
        db.commit()


# ---- GitHub downloads ----

def github_get(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "kinostats"}
    if GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
        return json.loads(r.read()), r.headers.get("Link", "")


def collect_github():
    """One snapshot of download_count for every .apk asset of every release (only APKs, not checksums)."""
    url, captured, rows = f"https://api.github.com/repos/{REPO}/releases?per_page=100", now(), []
    while url:
        releases, link = github_get(url)
        for release in releases:
            for asset in release.get("assets", []):
                if asset.get("name", "").lower().endswith(".apk"):
                    rows.append((asset["id"], captured, release.get("tag_name", ""), asset["name"], int(asset.get("download_count", 0))))
        match = re.search(r'<([^>]+)>;\s*rel="next"', link)
        url = match.group(1) if match else None
    with db_lock:
        db.executemany("INSERT OR IGNORE INTO github_asset_snapshots VALUES (?,?,?,?,?)", rows)
        db.commit()
    return len(rows)


def worker():
    last_github = 0
    while True:
        try:
            housekeeping()
        except Exception as e:  # never stop the loop
            log(f"housekeeping {type(e).__name__}: {e}")
        if time.time() - last_github >= GITHUB_EVERY_S:
            try:
                log(f"github: {collect_github()} apk assets")
                last_github = time.time()
            except Exception as e:
                log(f"github {type(e).__name__}: {e}")
                last_github = time.time() - GITHUB_EVERY_S + 1800  # try again in half an hour
        time.sleep(900)


def log(what):
    print(what, file=sys.stderr, flush=True)
    with db_lock:
        db.execute("INSERT INTO service_log VALUES (?,?)", (now(), what[:300]))
        db.commit()


# ---- Admin: aggregates only ----

def stats(days):
    t = now()
    today = day_of(t)
    with db_lock:
        series = []
        for i in range(days - 1, -1, -1):
            day = day_of(t - i * 86400)
            row = db.execute("SELECT active, new_installs, sessions, versions FROM daily_aggregates WHERE day_utc=?", (day,)).fetchone()
            series.append({"day": day, "active": row[0] if row else 0, "new": row[1] if row else 0, "sessions": row[2] if row else 0})

        def distinct_active(window):
            since = day_of(t - (window - 1) * 86400)
            return db.execute("SELECT COUNT(DISTINCT installation_id) FROM telemetry_daily_installations WHERE day_utc >= ?", (since,)).fetchone()[0]

        def retention(n):
            # Installations first seen exactly n days before some day in the last 30, back on that day.
            cohort = db.execute("SELECT installation_id, first_seen_day_utc FROM installations WHERE first_seen_day_utc BETWEEN ? AND ?",
                                (day_of(t - (30 + n) * 86400), day_of(t - n * 86400))).fetchall()
            if not cohort:
                return None
            back = 0
            for inst, first in cohort:
                target = (dt.date.fromisoformat(first) + dt.timedelta(days=n)).isoformat()
                if db.execute("SELECT 1 FROM telemetry_daily_installations WHERE day_utc=? AND installation_id=?", (target, inst)).fetchone():
                    back += 1
            return round(100 * back / len(cohort), 1), len(cohort)

        versions = db.execute("""SELECT app_version, COUNT(DISTINCT installation_id) FROM telemetry_daily_installations
                                 WHERE day_utc >= ? GROUP BY app_version ORDER BY 2 DESC""", (day_of(t - 6 * 86400),)).fetchall()
        latest = db.execute("SELECT MAX(captured_at_utc) FROM github_asset_snapshots").fetchone()[0]
        downloads = []
        if latest:
            downloads = db.execute("SELECT release_tag, name, download_count FROM github_asset_snapshots WHERE captured_at_utc=? ORDER BY asset_id DESC", (latest,)).fetchall()
        day_ago = db.execute("SELECT MAX(captured_at_utc) FROM github_asset_snapshots WHERE captured_at_utc <= ?", (t - 86400,)).fetchone()[0]
        total_day_ago = db.execute("SELECT SUM(download_count) FROM github_asset_snapshots WHERE captured_at_utc=?", (day_ago,)).fetchone()[0] if day_ago else None
        last_event = db.execute("SELECT MAX(received_at_utc) FROM telemetry_events").fetchone()[0]
        installs_total = db.execute("SELECT COUNT(*) FROM installations").fetchone()[0]
        notes = db.execute("SELECT at, what FROM service_log ORDER BY at DESC LIMIT 8").fetchall()
    total = sum(d[2] for d in downloads)
    return {
        "today": today, "series": series,
        "dau": distinct_active(1), "wau": distinct_active(7), "mau": distinct_active(30),
        "new_today": series[-1]["new"] if series else 0, "installs_known": installs_total,
        "sessions_7": sum(s["sessions"] for s in series[-7:]),
        "retention": {f"d{n}": retention(n) for n in (1, 7, 30)},
        "versions": versions,
        "downloads_total": total, "downloads_24h": (total - total_day_ago) if total_day_ago is not None else None,
        "downloads": downloads, "downloads_at": latest, "last_event": last_event, "notes": notes,
    }


def svg_bars(series, key, color):
    values = [s[key] for s in series]
    peak = max(values) or 1
    w, h, n = 900, 160, len(values)
    bar = w / max(n, 1)
    rects = "".join(
        f'<rect x="{i * bar + 1:.1f}" y="{h - v / peak * (h - 16):.1f}" width="{max(bar - 2, 1):.1f}" height="{v / peak * (h - 16):.1f}" fill="{color}"><title>{s["day"]}: {v}</title></rect>'
        for i, (s, v) in enumerate(zip(series, values)))
    return f'<svg viewBox="0 0 {w} {h}" preserveAspectRatio="none" class="chart">{rects}</svg>'


def page(days):
    s = stats(days)
    when = lambda ts: dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC") if ts else "—"
    ret = lambda r: f"{r[0]}% <small>из {r[1]}</small>" if r else "—"
    card = lambda title, value, note="": f'<div class="card"><div class="t">{title}</div><div class="v">{value}</div><div class="n">{note}</div></div>'
    cards = "".join([
        card("Загрузки APK с GitHub", s["downloads_total"], f'+{s["downloads_24h"]} за сутки' if s["downloads_24h"] is not None else "скачивания файлов, не люди"),
        card("Активные установки · сегодня", s["dau"], "согласившиеся на статистику"),
        card("За 7 дней (WAU)", s["wau"]), card("За 30 дней (MAU)", s["mau"]),
        card("Новые установки сегодня", s["new_today"], f'всего известно {s["installs_known"]}'),
        card("Сессии за 7 дней", s["sessions_7"]),
        card("Возврат D1", ret(s["retention"]["d1"])), card("Возврат D7", ret(s["retention"]["d7"])), card("Возврат D30", ret(s["retention"]["d30"])),
    ])
    versions = "".join(f"<tr><td>{html.escape(v)}</td><td>{n}</td></tr>" for v, n in s["versions"]) or '<tr><td colspan="2">нет данных</td></tr>'
    downloads = "".join(f"<tr><td>{html.escape(tag)}</td><td>{html.escape(name)}</td><td>{n}</td></tr>" for tag, name, n in s["downloads"]) or '<tr><td colspan="3">ещё не собрано</td></tr>'
    notes = "".join(f"<li>{when(at)} — {html.escape(w)}</li>" for at, w in s["notes"])
    tabs = " ".join(f'<a href="/?days={d}" class="{"on" if d == days else ""}">{d} дней</a>' for d in (7, 30, 90))
    return f"""<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Кинотрек · статистика</title><style>
:root{{--bg:#0f141c;--card:#18202b;--text:#eef2f7;--muted:#93a0b4;--accent:#5ee0d2;--blue:#6aa8ff}}
body{{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,sans-serif;padding:20px}}
h1{{font-size:22px;margin:0 0 4px}} .sub{{color:var(--muted);margin-bottom:18px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px}}
.card{{background:var(--card);border-radius:14px;padding:14px 16px}} .t{{color:var(--muted);font-size:13px}}
.v{{font-size:28px;font-weight:650;margin:4px 0}} .n{{color:var(--muted);font-size:12px}} small{{color:var(--muted);font-size:13px}}
h2{{font-size:16px;margin:24px 0 8px}} .chart{{width:100%;height:160px;background:var(--card);border-radius:14px}}
table{{border-collapse:collapse;width:100%;background:var(--card);border-radius:14px;overflow:hidden}} td{{padding:7px 12px;border-top:1px solid #222c39}}
a{{color:var(--accent);margin-right:10px;text-decoration:none}} a.on{{font-weight:700;text-decoration:underline}} ul{{color:var(--muted);font-size:13px}}
</style></head><body>
<h1>Кинотрек · статистика</h1>
<div class="sub">Активные установки, а не люди: считаются только те, кто согласился на анонимную статистику. Последние данные: {when(s["last_event"])}. Загрузки собраны: {when(s["downloads_at"])}.</div>
<div>{tabs} · <a href="/export.csv?days={days}">CSV</a> · <a href="/refresh?days={days}">Обновить загрузки сейчас</a></div><br>
<div class="grid">{cards}</div>
<h2>Активные установки по дням (UTC)</h2>{svg_bars(s["series"], "active", "#5ee0d2")}
<h2>Новые установки по дням</h2>{svg_bars(s["series"], "new", "#6aa8ff")}
<h2>Версии за 7 дней</h2><table>{versions}</table>
<h2>Загрузки APK по релизам</h2><table>{downloads}</table>
<h2>Служебный журнал</h2><ul>{notes}</ul>
</body></html>"""


class Admin(BaseHTTPRequestHandler):
    server_version = "kinostats-admin"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        try:
            days = max(7, min(int(query.get("days", ["30"])[0]), 90))
        except ValueError:
            days = 30
        if url.path == "/":
            return self.send(200, page(days).encode(), "text/html; charset=utf-8")
        if url.path == "/refresh":
            # Read GitHub now (a release published a minute ago), then back to the dashboard.
            try:
                log(f"github (by hand): {collect_github()} apk assets")
            except Exception as e:
                log(f"github {type(e).__name__}: {e}")
            self.send_response(303)
            self.send_header("Location", f"/?days={days}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if url.path == "/api/stats":
            return self.send(200, json.dumps(stats(days), ensure_ascii=False).encode(), "application/json")
        if url.path == "/export.csv":
            out = io.StringIO()
            writer = csv.writer(out)
            writer.writerow(["day_utc", "active_installations", "new_installations", "sessions"])
            for row in stats(days)["series"]:
                writer.writerow([row["day"], row["active"], row["new"], row["sessions"]])
            return self.send(200, out.getvalue().encode(), "text/csv; charset=utf-8")
        return self.send(404, b"not found", "text/plain")

    def send(self, status, body, kind):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def serve(handler, bind):
    host, port = bind.rsplit(":", 1)
    if host not in ("127.0.0.1", "localhost", "::1"):
        sys.exit(f"refusing to listen on {bind}: kinostats binds to localhost only")
    ThreadingHTTPServer((host, int(port)), handler).serve_forever()


def main():
    global db
    db = connect()
    threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=serve, args=(Admin, ADMIN_BIND), daemon=True).start()
    print(f"kinostats on {BIND}, admin on {ADMIN_BIND}", file=sys.stderr, flush=True)
    serve(Ingest, BIND)


if __name__ == "__main__":
    main()
