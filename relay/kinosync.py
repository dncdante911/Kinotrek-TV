"""
Kinotrek's sync between a viewer's devices (TV, phone, tablet): library, watch progress, collections, settings,
favourite channels, own ratings. Lives in kinoindex; the app reaches it through relay.py (GET /x/sync/...).

The server never sees what is synced. Devices of one viewer share a random 256-bit key that never leaves them in
the clear; each record travels as
    id   HMAC of the record's name ("lib/item:btih:…") under a key derived from the shared key: 32 hex
    t    when it was last changed (ms, a clock that never goes back across the devices, see the app's SyncClient)
    dev  which device changed it (8 hex), to break ties
    blob AES-GCM of the name and the value (base64), or "" for a deleted record
and a group is named by a hash of the shared key. Records merge by last write: a newer t wins, at equal t the larger
dev. Every accepted change gets the next number of a sequence, and a device asks for what came after the number it
saw last, so each sync moves only what changed.

Linking a device: the first one uploads the shared key encrypted with a short code it shows (/pair); the second
one, given the code, takes it back (/claim) once, within PAIR_TTL. Only a hash of the code is kept.

GET /sync/push?g=<group>&s=<seq seen>&dev=<device>[&d=<base64url gzip JSON [[id,t,dev,blob],…]>]
    -> {"v":1,"epoch":…,"seq":<newest>,"e":[[id,t,dev,blob,seq],…],"x":[[id,t,dev,blob,seq],…],"more":0|1}
       e: records changed after s (at most ANSWER_BYTES of them, "more" says there are others);
       x: pushed records refused as older than what is stored, with what is stored.
GET /sync/pair?l=<sha256 of the code>&w=<wrapped key>   GET /sync/claim?l=…  -> {"w":…}
GET /sync/forget?g=<group>   removes the group's records (an "unlink all devices").

The epoch is a random id of the store: a device that sees another one knows the server lost its data and sends
everything again.

Several servers: the one with KINOSYNC_PRIMARY=1 keeps the store; the others pass each call to it through its relay
(KINORATE_PEER, KINORATE_PEER_CA, RELAY_SECRET: the same settings the ratings use), so a device may reach any server.
"""
import base64
import gzip
import hashlib
import json
import os
import re
import secrets
import sqlite3
import ssl
import threading
import time
import urllib.parse
import urllib.request

PRIMARY = os.environ.get("KINOSYNC_PRIMARY") == "1"
# The servers' shared key (the ratings' sync key): what lets the primary believe a forwarded viewer address.
SERVERS_KEY = os.environ.get("KINORATE_SYNC_KEY", "")
GROUP = re.compile(r"^[0-9a-f]{32}$")
RECORD = re.compile(r"^[0-9a-f]{32}$")
DEVICE = re.compile(r"^[0-9a-f]{8}$")
LOOKUP = re.compile(r"^[0-9a-f]{64}$")
BLOB = re.compile(r"^[A-Za-z0-9_\-=]{0,12000}$")
WRAPPED = re.compile(r"^[A-Za-z0-9_\-=]{20,400}$")
PUSH_MAX_RECORDS = 400
GROUP_MAX_RECORDS = 60_000
GROUP_MAX_BYTES = 40_000_000
# One answer stays one part of the relay's split answer (64 KB, gzip does nothing for ciphertext).
ANSWER_BYTES = 44_000
PAIR_TTL = 15 * 60
CLAIMS_PER_HOUR = 30
CLAIMS_PER_HOUR_ALL = 2_000
PAIRS_PER_HOUR = 30


class SyncError(ValueError):
    pass


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS records (g TEXT NOT NULL, id TEXT NOT NULL, t INTEGER NOT NULL, dev TEXT NOT NULL, "
                        "blob TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (g, id))")
        self.db.execute("CREATE INDEX IF NOT EXISTS records_seq ON records (g, seq)")
        self.db.execute("CREATE TABLE IF NOT EXISTS pairs (l TEXT PRIMARY KEY, w TEXT NOT NULL, until INTEGER NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        self.db.commit()
        self.lock = threading.Lock()
        with self.lock:
            row = self.db.execute("SELECT v FROM meta WHERE k = 'epoch'").fetchone()
            if row is None:
                self.db.execute("INSERT INTO meta VALUES ('epoch', ?)", (secrets.token_hex(8),))
                self.db.execute("INSERT OR IGNORE INTO meta VALUES ('seq', '0')")
                self.db.commit()
                row = self.db.execute("SELECT v FROM meta WHERE k = 'epoch'").fetchone()
            self.epoch = row[0]
            self.seq = int(self.db.execute("SELECT v FROM meta WHERE k = 'seq'").fetchone()[0])
        self.claims = {}
        self.pairs_made = {}

    # ---- records ----

    def push(self, group, since, rows):
        """Merges [rows] (last write wins) and answers what changed after [since]."""
        if not GROUP.match(group):
            raise SyncError("bad group")
        refused = []
        accepted = set()
        with self.lock:
            if rows:
                count, size = self.db.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(blob)), 0) FROM records WHERE g = ?", (group,)).fetchone()
                for rid, t, dev, blob in rows:
                    stored = self.db.execute("SELECT t, dev, blob, seq FROM records WHERE g = ? AND id = ?", (group, rid)).fetchone()
                    if stored and (stored[0], stored[1]) >= (t, dev):
                        if (stored[0], stored[1]) != (t, dev):
                            refused.append([rid, stored[0], stored[1], stored[2], stored[3]])
                        continue
                    if not stored and (count >= GROUP_MAX_RECORDS or size + len(blob) > GROUP_MAX_BYTES):
                        raise SyncError("too much data")
                    self.seq += 1
                    self.db.execute("INSERT OR REPLACE INTO records (g, id, t, dev, blob, seq) VALUES (?,?,?,?,?,?)",
                                    (group, rid, t, dev, blob, self.seq))
                    accepted.add(rid)
                    count += 0 if stored else 1
                    size += len(blob) - (len(stored[2]) if stored else 0)
                self.db.execute("UPDATE meta SET v = ? WHERE k = 'seq'", (str(self.seq),))
                self.db.commit()
            # What the device just sent is not sent back to it (its connection may carry only ~16 KB under some DPI);
            # the number it gets covers those records too.
            out, used, more, newest = [], 0, 0, max(since, 0)
            for rid, t, dev, blob, seq in self.db.execute(
                    "SELECT id, t, dev, blob, seq FROM records WHERE g = ? AND seq > ? ORDER BY seq", (group, since)):
                if rid in accepted:
                    newest = seq
                    continue
                if used + len(blob) + 80 > ANSWER_BYTES and out:
                    more = 1
                    break
                out.append([rid, t, dev, blob, seq])
                used += len(blob) + 80
                newest = seq
        return {"v": 1, "epoch": self.epoch, "seq": newest, "e": out, "x": refused, "more": more}

    def forget(self, group):
        if not GROUP.match(group):
            raise SyncError("bad group")
        with self.lock:
            gone = self.db.execute("DELETE FROM records WHERE g = ?", (group,)).rowcount
            self.db.commit()
        return {"v": 1, "deleted": gone}

    # ---- linking ----

    def _limited(self, table, key, per_hour):
        now = time.time()
        with self.lock:
            hits = [t for t in table.get(key, ()) if now - t < 3600]
            if len(hits) >= per_hour:
                table[key] = hits
                return True
            table[key] = hits + [now]
            if len(table) > 20_000:
                table.clear()
        return False

    def pair(self, ip, lookup, wrapped):
        if not LOOKUP.match(lookup) or not WRAPPED.match(wrapped):
            raise SyncError("bad pair")
        if self._limited(self.pairs_made, ip, PAIRS_PER_HOUR):
            return None
        now = int(time.time())
        with self.lock:
            self.db.execute("DELETE FROM pairs WHERE until < ?", (now,))
            self.db.execute("INSERT OR REPLACE INTO pairs VALUES (?,?,?)", (lookup, wrapped, now + PAIR_TTL))
            self.db.commit()
        return {"v": 1, "ttl": PAIR_TTL}

    def claim(self, ip, lookup):
        if not LOOKUP.match(lookup):
            raise SyncError("bad code")
        # Codes are short: guessing is limited per address and for everyone together.
        if self._limited(self.claims, ip, CLAIMS_PER_HOUR) or self._limited(self.claims, "*", CLAIMS_PER_HOUR_ALL):
            return None
        now = int(time.time())
        with self.lock:
            row = self.db.execute("SELECT w FROM pairs WHERE l = ? AND until >= ?", (lookup, now)).fetchone()
            if row:
                self.db.execute("DELETE FROM pairs WHERE l = ?", (lookup,))
                self.db.commit()
        return {"v": 1, "w": row[0]} if row else {"v": 1}


def parse_rows(packed):
    """The pushed records from d= (base64url of gzipped JSON), checked."""
    if not packed:
        return []
    try:
        raw = gzip.decompress(base64.urlsafe_b64decode(packed + "=" * (-len(packed) % 4)))
        rows = json.loads(raw.decode("utf-8"))
    except (ValueError, OSError):
        raise SyncError("bad data")
    if not isinstance(rows, list) or len(rows) > PUSH_MAX_RECORDS:
        raise SyncError("bad data")
    out = []
    for row in rows:
        if not (isinstance(row, list) and len(row) == 4):
            raise SyncError("bad record")
        rid, t, dev, blob = row
        if not (isinstance(rid, str) and RECORD.match(rid) and isinstance(t, int) and 0 < t < 2 ** 53
                and isinstance(dev, str) and DEVICE.match(dev) and isinstance(blob, str) and BLOB.match(blob)):
            raise SyncError("bad record")
        out.append((rid, t, dev, blob))
    return out


class Sync:
    """The /sync/* calls: answered from the store on the primary server, passed to it from the others."""

    def __init__(self, path):
        self.store = Store(path) if PRIMARY else None
        peer = os.environ.get("KINORATE_PEER", "").rstrip("/")
        self.peer = None if PRIMARY or not peer else peer
        ca = os.environ.get("KINORATE_PEER_CA", "")
        self.ctx = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
        self.ctx.check_hostname = False  # reached by IP; the certificate is pinned through cafile
        self.secret = os.environ.get("RELAY_SECRET", "")

    @property
    def enabled(self):
        return self.store is not None or (self.peer is not None and bool(self.secret))

    def handle(self, path, query, raw_query, ip):
        """(status, answer dict) for one call; path is "/sync/push" etc."""
        if self.store is None:
            return self.forward(path, raw_query, ip)

        def first(name):
            return query.get(name, [""])[0]

        try:
            if path == "/sync/push":
                dev = first("dev")
                if not DEVICE.match(dev):
                    raise SyncError("bad device")
                since = int(first("s") or 0)
                return 200, self.store.push(first("g"), since, parse_rows(first("d")))
            if path == "/sync/pair":
                answer = self.store.pair(ip, first("l"), first("w"))
                return (429, {"error": "slow down"}) if answer is None else (200, answer)
            if path == "/sync/claim":
                answer = self.store.claim(ip, first("l"))
                return (429, {"error": "slow down"}) if answer is None else (200, answer)
            if path == "/sync/forget":
                return 200, self.store.forget(first("g"))
        except SyncError as e:
            return 400, {"error": str(e)}
        except ValueError:
            return 400, {"error": "bad request"}
        return 404, {"error": "not found"}

    def forward(self, path, raw_query, ip):
        """The primary's answer to the same call, through its relay (one gzipped part). The viewer's address goes along
        (via=), believed there only with the servers' shared key, so the limits on codes count viewers, not this server."""
        if self.peer is None or not self.secret:
            return 404, {"error": "no sync here"}
        query = "&".join(p for p in raw_query.split("&") if not p.startswith("via="))
        headers = {"X-Relay-Key": self.secret, "X-Relay-Chunk": "64000"}
        if SERVERS_KEY:
            headers["X-Rate-Key"] = SERVERS_KEY
            query += "&via=" + urllib.parse.quote(ip)
        request = urllib.request.Request(f"{self.peer}/x{path}?{query}", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=20, context=self.ctx) as r:
                if r.headers.get("X-Relay-Parts", "1") != "1":
                    return 502, {"error": "answer in several parts"}
                status = int(r.headers.get("X-Relay-Status", "200"))
                return status, json.loads(gzip.decompress(r.read()))
        except urllib.error.HTTPError as e:
            return 502, {"error": f"primary: http {e.code}"}
        except Exception as e:
            return 503, {"error": f"primary unavailable: {type(e).__name__}"}
