"""
Community ratings for kinoindex: stars (1-5 in half steps, kept as 1..10) and a thumb (+1 / -1) per film, one vote per
install token. No accounts and no personal data: a token is 32 random hex characters the app made for itself, a film is
its IMDb id ("tt0133093") or Kinopoisk id ("kp301"). Nothing is shown below MIN_VOTES votes: the app prints a dash.

Rows are never deleted, only zeroed (stars=0, thumb=0, new `at`): that is what lets two servers (Oracle and the office
radio) merge by last write, see sync_pull. Aggregates count only rows with a vote in them.

Environment (all optional):
  KINORATE_DB        path of the SQLite file (default: rates.db next to the index database)
  KINORATE_PEER      https://host:port of the other server's relay, to pull its votes from every few minutes
  KINORATE_PEER_CA   its certificate (pinned), KINORATE_SYNC_KEY  shared secret of the sync, RELAY_SECRET  the relay key
"""
import collections
import gzip
import json
import os
import re
import sqlite3
import ssl
import sys
import threading
import time
import urllib.request

MIN_VOTES = 5
FILM_ID = re.compile(r"^(?:tt\d{6,10}|kp\d{2,9})$")
TOKEN_ID = re.compile(r"^[0-9a-f]{32}$")
MAX_FILMS_PER_ASK = 60
# Limits against ballot stuffing: calls an address may make in an hour, new tokens it may bring in a day, votes a token
# may cast in a day. Generous for a household behind one address, hopeless for a script.
IP_CALLS_PER_HOUR = 240
IP_NEW_TOKENS_PER_DAY = 24
TOKEN_VOTES_PER_DAY = 400
SYNC_EVERY_S = 300
# A batch must fit one 64 KB part of the relay's gzipped answer (it speaks to apps in parts; here one is enough).
SYNC_BATCH = 600


class Rates:
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS rates (film TEXT NOT NULL, token TEXT NOT NULL, stars INTEGER NOT NULL DEFAULT 0, "
                        "thumb INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL, PRIMARY KEY (film, token))")
        self.db.execute("CREATE INDEX IF NOT EXISTS rates_token ON rates (token)")
        self.db.execute("CREATE INDEX IF NOT EXISTS rates_at ON rates (at)")
        self.db.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        self.db.commit()
        self.lock = threading.Lock()
        self.ip_calls = collections.defaultdict(collections.deque)
        self.ip_tokens = collections.defaultdict(dict)
        self.token_votes = collections.defaultdict(collections.deque)

    # ---- reading ----

    def aggregate(self, films):
        """{film: [votes, average stars (one decimal), thumbs up, thumbs down]} for films that have any vote."""
        films = [f for f in dict.fromkeys(films) if FILM_ID.match(f)][:MAX_FILMS_PER_ASK * 4]
        out = {}
        if not films:
            return out
        with self.lock:
            for i in range(0, len(films), 400):
                chunk = films[i:i + 400]
                rows = self.db.execute(
                    "SELECT film, SUM(stars > 0), SUM(stars), SUM(thumb = 1), SUM(thumb = -1) FROM rates "
                    f"WHERE film IN ({','.join('?' * len(chunk))}) GROUP BY film", chunk)
                for film, votes, total, up, down in rows:
                    votes, total, up, down = votes or 0, total or 0, up or 0, down or 0
                    if votes or up or down:
                        out[film] = [votes, round(total / (2 * votes), 1) if votes else 0, up, down]
        return out

    # ---- limits ----

    def _allow(self, ip, token):
        now = time.time()
        with self.lock:
            calls = self.ip_calls[ip]
            while calls and now - calls[0] > 3600:
                calls.popleft()
            if len(calls) >= IP_CALLS_PER_HOUR:
                return False
            calls.append(now)
            tokens = self.ip_tokens[ip]
            for t in [t for t, at in tokens.items() if now - at > 86400]:
                del tokens[t]
            if token not in tokens:
                known = self.db.execute("SELECT 1 FROM rates WHERE token = ? LIMIT 1", (token,)).fetchone()
                if not known and len(tokens) >= IP_NEW_TOKENS_PER_DAY:
                    return False
                tokens[token] = now
            votes = self.token_votes[token]
            while votes and now - votes[0] > 86400:
                votes.popleft()
            if len(votes) >= TOKEN_VOTES_PER_DAY:
                return False
            votes.append(now)
            if len(self.ip_calls) > 20000:
                self.ip_calls.clear(); self.ip_tokens.clear(); self.token_votes.clear()
        return True

    # ---- writing ----

    def vote(self, ip, film, token, stars=None, thumb=None):
        """Sets the stars (0 clears) and/or the thumb (0 clears) of [token] on [film]; None leaves a part as it was.
        Returns the film's new aggregate, or None when the call is refused."""
        if not FILM_ID.match(film) or not TOKEN_ID.match(token):
            raise ValueError("bad id")
        if stars is not None and not 0 <= stars <= 10:
            raise ValueError("bad stars")
        if thumb is not None and thumb not in (-1, 0, 1):
            raise ValueError("bad thumb")
        if stars is None and thumb is None:
            raise ValueError("nothing to set")
        if not self._allow(ip, token):
            return None
        with self.lock:
            row = self.db.execute("SELECT stars, thumb FROM rates WHERE film = ? AND token = ?", (film, token)).fetchone()
            new_stars = row[0] if stars is None and row else (stars or 0)
            new_thumb = row[1] if thumb is None and row else (thumb or 0)
            self.db.execute("INSERT OR REPLACE INTO rates (film, token, stars, thumb, at) VALUES (?,?,?,?,?)",
                            (film, token, new_stars, new_thumb, int(time.time() * 1000)))
            self.db.commit()
        return self.aggregate([film]).get(film, [0, 0, 0, 0])

    def forget(self, ip, token):
        """Zeroes every vote of [token] (kept as marks so that the other server learns of it)."""
        if not TOKEN_ID.match(token):
            raise ValueError("bad id")
        if not self._allow(ip, token):
            return None
        with self.lock:
            cursor = self.db.execute("UPDATE rates SET stars = 0, thumb = 0, at = ? WHERE token = ? AND (stars != 0 OR thumb != 0)",
                                     (int(time.time() * 1000), token))
            self.db.commit()
            return cursor.rowcount

    # ---- two servers, one set of votes ----

    def changes(self, since, limit=SYNC_BATCH):
        with self.lock:
            rows = self.db.execute("SELECT film, token, stars, thumb, at FROM rates WHERE at > ? ORDER BY at LIMIT ?", (since, limit)).fetchall()
        return [list(r) for r in rows]

    def merge(self, rows):
        """Rows from the other server: the later write of a (film, token) wins."""
        merged = 0
        with self.lock:
            for film, token, stars, thumb, at in rows:
                if not (FILM_ID.match(str(film)) and TOKEN_ID.match(str(token))):
                    continue
                if not (isinstance(stars, int) and 0 <= stars <= 10 and thumb in (-1, 0, 1) and isinstance(at, int)):
                    continue
                cursor = self.db.execute(
                    "INSERT INTO rates (film, token, stars, thumb, at) VALUES (?,?,?,?,?) "
                    "ON CONFLICT(film, token) DO UPDATE SET stars = excluded.stars, thumb = excluded.thumb, at = excluded.at WHERE excluded.at > rates.at",
                    (film, token, stars, thumb, at))
                merged += cursor.rowcount
            self.db.commit()
        return merged

    def _meta(self, key, default=""):
        with self.lock:
            row = self.db.execute("SELECT v FROM meta WHERE k = ?", (key,)).fetchone()
        return row[0] if row else default

    def _set_meta(self, key, value):
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", (key, str(value)))
            self.db.commit()

    def sync_worker(self):
        """Pulls what the other server learned since the last time, every few minutes (both servers do it)."""
        peer = os.environ.get("KINORATE_PEER", "").rstrip("/")
        sync_key = os.environ.get("KINORATE_SYNC_KEY", "")
        secret = os.environ.get("RELAY_SECRET", "")
        if not (peer and sync_key and secret):
            return
        ca = os.environ.get("KINORATE_PEER_CA", "")
        context = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
        context.check_hostname = False  # reached by IP; the certificate is pinned through cafile
        failures = 0
        while True:
            try:
                since = int(self._meta("peer_since", "0"))
                request = urllib.request.Request(f"{peer}/x/ratesync?since={since}",
                                                 headers={"X-Relay-Key": secret, "X-Rate-Key": sync_key, "X-Relay-Chunk": "64000"})
                with urllib.request.urlopen(request, timeout=40, context=context) as r:
                    if r.headers.get("X-Relay-Parts", "1") != "1":
                        raise ValueError("answer in several parts")
                    rows = json.loads(gzip.decompress(r.read())).get("rows", [])
                if rows:
                    self.merge(rows)
                    self._set_meta("peer_since", max(r[4] for r in rows))
                    if len(rows) >= SYNC_BATCH:
                        continue  # more waiting
                failures = 0
            except Exception as e:
                failures += 1
                print("ratesync", type(e).__name__, e, file=sys.stderr, flush=True)
            time.sleep(SYNC_EVERY_S * min(failures + 1, 6))
