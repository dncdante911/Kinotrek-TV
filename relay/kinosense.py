"""
Meaning for kinoindex: an embedding (bge-m3 on the owner's LLM box, through the proxy's Oracle-only gateway) for
every film the index lists, from its title, genres, countries and description. Gives "similar films" for a release
and search by description ("ограбление в космосе"). Nothing about viewers is sent: only release texts and, for a
search, the phrase itself. Without KINOLLM_URL/KINOLLM_KEY the module is off and kinoindex answers 404 for both.

Environment: KINOLLM_URL (https://host:port), KINOLLM_KEY, KINOLLM_CA (the gateway's pinned certificate),
KINOLLM_MODEL (bge-m3).
"""
import json
import os
import re
import ssl
import sys
import threading
import time
import urllib.request

try:
    import numpy as np
except ImportError:  # the module stays off without numpy
    np = None

URL = os.environ.get("KINOLLM_URL", "").rstrip("/")
KEY = os.environ.get("KINOLLM_KEY", "")
CA = os.environ.get("KINOLLM_CA", "")
MODEL = os.environ.get("KINOLLM_MODEL", "bge-m3")
ENABLED = bool(URL and KEY and np is not None)

BATCH = 16
POOL_MAX = 6000
SIMILAR_MIN = 0.55
MEANING_MIN = 0.42
WORK_EVERY_S = 30
# The matrix the queries use is rebuilt from memory at most this often; new vectors wait that long at worst.
MATRIX_EVERY_S = 60
STALE_EVERY_S = 300
TITLE_NOISE = re.compile(r"\s*[(\[].*$")


class Sense:
    def __init__(self, db, db_lock, store_get):
        self.db, self.lock, self.store_get = db, db_lock, store_get
        self.db.execute("CREATE TABLE IF NOT EXISTS vecs (page TEXT PRIMARY KEY, vec BLOB NOT NULL, at INTEGER NOT NULL)")
        self.db.commit()
        # Release page -> the latest hit that listed it (shelves and searches): only these are offered.
        self.pool = {}
        self.pool_lock = threading.Lock()
        self.matrix = None
        self.matrix_pages = []
        self.matrix_at = 0.0
        self.matrix_lock = threading.Lock()
        # Vectors of pool films, float16 as stored: queries never touch SQLite.
        self.mem = {}
        self.dirty = True
        self.ctx = ssl.create_default_context(cafile=CA) if CA else ssl.create_default_context()
        self.ctx.check_hostname = False  # the gateway is reached by IP; its certificate is pinned through cafile
        self.failures = 0
        self.stale_pages = set()
        self.stale_at = 0.0

    # ---- pool ----

    def offer(self, hits):
        """Hits the index just answered with: candidates for similar films and meaning search."""
        with self.pool_lock:
            for h in hits:
                self.pool.pop(h["p"], None)
                self.pool[h["p"]] = h
            while len(self.pool) > POOL_MAX:
                self.pool.pop(next(iter(self.pool)))

    # ---- embeddings ----

    def embed(self, texts):
        body = json.dumps({"model": MODEL, "input": texts}).encode()
        request = urllib.request.Request(URL + "/api/embed", data=body, method="POST",
                                         headers={"Content-Type": "application/json", "X-Kino-Key": KEY})
        with urllib.request.urlopen(request, timeout=60, context=self.ctx) as r:
            vectors = json.loads(r.read())["embeddings"]
        out = []
        for v in vectors:
            a = np.asarray(v, dtype=np.float32)
            n = np.linalg.norm(a)
            out.append(a / n if n else a)
        return out

    @staticmethod
    def text_of(hit, info):
        title = TITLE_NOISE.sub("", hit.get("t", "")).strip()
        parts = [title]
        if info.get("genre"):
            parts.append(info["genre"])
        if info.get("country"):
            parts.append(info["country"])
        if info.get("story"):
            parts.append(info["story"][:900])
        return ". ".join(p for p in parts if p)

    def vectors(self, pages):
        if not pages:
            return {}
        out = {}
        with self.lock:
            for i in range(0, len(pages), 400):
                chunk = pages[i:i + 400]
                rows = self.db.execute(f"SELECT page, vec FROM vecs WHERE page IN ({','.join('?' * len(chunk))})", chunk)
                for page, blob in rows:
                    out[page] = np.frombuffer(blob, dtype=np.float16).astype(np.float32)
        return out

    def save(self, page, vec):
        half = vec.astype(np.float16)
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO vecs VALUES (?,?,?)", (page, half.tobytes(), int(time.time())))
            self.db.commit()
        with self.pool_lock:
            if page in self.pool:
                self.mem[page] = half
        self.dirty = True

    def stale(self, pages):
        """Pages whose vector is older than their last reading: the description came (or changed) after it."""
        out = set()
        with self.lock:
            for i in range(0, len(pages), 400):
                chunk = pages[i:i + 400]
                rows = self.db.execute("SELECT v.page FROM vecs v JOIN pages p ON p.page = v.page "
                                       f"WHERE p.read_at > v.at AND v.page IN ({','.join('?' * len(chunk))})", chunk)
                out.update(r[0] for r in rows)
        return out

    def worker(self):
        """Embeds pool films whose page has been read and that have no vector yet, a batch at a time."""
        while True:
            try:
                with self.pool_lock:
                    pool = list(self.pool.values())
                pages = [h["p"] for h in pool]
                with self.pool_lock:
                    known = set(self.mem)
                self._remember(self.vectors([p for p in pages if p not in known]))
                with self.pool_lock:
                    known = set(self.mem)
                # Vectors made before the page's description: looked for now and then, it is a join over the pool.
                if time.time() - self.stale_at >= STALE_EVERY_S:
                    self.stale_pages = self.stale([p for p in pages if p in known])
                    self.stale_at = time.time()
                want = [p for p in pages if p not in known or p in self.stale_pages]
                infos = self.store_get(want) if want else {}
                # Only pages read with their description (story NULL = read before descriptions were kept: the
                # index reads them again); a vector made from the title alone would never be redone.
                todo = [h for h in pool if h["p"] in infos and infos[h["p"]].get("genre") is not None
                        and infos[h["p"]].get("story") is not None][:BATCH]
                if todo:
                    for h, vec in zip(todo, self.embed([self.text_of(h, infos[h["p"]]) for h in todo])):
                        self.save(h["p"], vec)
                        self.stale_pages.discard(h["p"])
                    self.failures = 0
                    time.sleep(1)
                    continue
            except Exception as e:  # the LLM box may be busy or down: wait longer each time
                self.failures += 1
                print("sense", type(e).__name__, e, file=sys.stderr, flush=True)
            time.sleep(WORK_EVERY_S * min(self.failures + 1, 10))

    # ---- queries ----

    def _remember(self, vecs):
        with self.pool_lock:
            for page, v in vecs.items():
                if page in self.pool and page not in self.mem:
                    self.mem[page] = v.astype(np.float16)
            for page in [p for p in self.mem if p not in self.pool]:
                del self.mem[page]

    def _matrix(self):
        with self.matrix_lock:
            due = self.matrix is None or (self.dirty and time.time() - self.matrix_at >= MATRIX_EVERY_S)
            if due:
                with self.pool_lock:
                    pages = [p for p in self.pool if p in self.mem]
                    rows = [self.mem[p] for p in pages]
                self.matrix_pages = pages
                self.matrix = np.stack(rows).astype(np.float32) if rows else None
                self.matrix_at = time.time()
                self.dirty = False
            return self.matrix, self.matrix_pages

    def _top(self, vec, n, minimum, skip):
        matrix, pages = self._matrix()
        if matrix is None:
            return []
        scores = matrix @ vec
        order = np.argsort(-scores)
        out, titles = [], set()
        with self.pool_lock:
            for i in order:
                if scores[i] < minimum or len(out) >= n:
                    break
                hit = self.pool.get(pages[i])
                if hit is None or pages[i] in skip:
                    continue
                key = TITLE_NOISE.sub("", hit["t"]).lower()
                if key in titles or key in skip:
                    continue
                titles.add(key)
                out.append(dict(hit))
        return out

    def similar(self, page, title="", n=20):
        """Films like the one on [page]; [title] names it when the index lists it no more (a film watched long ago)."""
        with self.pool_lock:
            own = self.pool.get(page)
        name = (own or {}).get("t") or title
        vec = self.vectors([page]).get(page)
        if vec is None:
            info = self.store_get([page]).get(page)
            if info is None or not name:
                return None
            vec = self.embed([self.text_of({"t": name}, info)])[0]
            self.save(page, vec)
        skip = {page} | ({TITLE_NOISE.sub("", name).lower()} if name else set())
        return self._top(vec, n, SIMILAR_MIN, skip)

    def meaning(self, text, n=40):
        vec = self.embed([text])[0]
        return self._top(vec, n, MEANING_MIN, set())
