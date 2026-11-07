#!/usr/bin/env python3
"""Kinotrek's search index: the trackers read once on the server for every viewer.

Rutor (three mirrors), NNM-Club and Megapeer are read here instead of on each TV. The app asks for
a finished list and gets a few kilobytes of JSON: no tracker pages (100 KB each) on a slow box or
through a relay. Release pages are read once per release, politely, for the cover, the countries and
the IMDb/Kinopoisk ids, so cards show all of it at once. Video never passes through here.

Only relay.py talks to it (GET /x/browse, /x/search, forwarded with the app's key checked there);
it listens on the loopback only.

GET /browse?cat=MOVIES&popular=1[&e=<etag>]   fresh or most seeded releases of a category
GET /search?q=<text>&cat=ALL[&e=<etag>]        search across the three trackers
Answer: {"v":1,"e":"<etag>","hits":[...],"errors":[{"s":"NNM","m":"..."}]} or {"v":1,"e":..,"same":1}
when the app already has that answer. Hit keys are short, the lists go out thousands of times a day.

environment: KINOINDEX_BIND (host:port, default 127.0.0.1:8446), KINOINDEX_DB (SQLite file),
KINOINDEX_STANDBY=1 (no background rebuilding of the home rows: lists are built only when asked).
"""

import html
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIND = os.environ.get("KINOINDEX_BIND", "127.0.0.1:8446")
DB_PATH = os.environ.get("KINOINDEX_DB", "/var/lib/kinoindex/index.db")
STANDBY = os.environ.get("KINOINDEX_STANDBY") == "1"

UA = "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
RUTOR_MIRRORS = ["https://rutor.info/", "https://rutor.is/", "https://rutor.org.in/"]
NNM = "https://nnmclub.to/forum/"
MEGAPEER = "https://megapeer.vip/"
ALLOWED_HOSTS = {"rutor.info", "rutor.is", "rutor.org.in", "rutor.org", "nnmclub.to", "megapeer.vip"}

# Rutor category ids, as in the app (net/SourceSearchClient.kt Category).
CATEGORIES = {
    "ALL": [1, 5, 4, 16, 7, 10, 12], "MOVIES": [1, 5], "SERIES": [4, 16], "CARTOONS": [7],
    "ANIME": [10], "DOCUMENTARY": [12], "TV": [6], "SPORT": [13],
}
TRACKER_CATEGORIES = {"ALL", "MOVIES", "SERIES"}
# The home rows: kept fresh in the background, so they answer at once.
SHELVES = [("MOVIES", True), ("SERIES", True), ("MOVIES", False), ("CARTOONS", True), ("ANIME", True)]

BROWSE_TTL = 600
SEARCH_TTL = 900
# An answer is up to SEARCH_LIMIT hits (~250 KB in memory): a few hundred are plenty, and what is older than
# SEARCH_TTL is dropped as new ones come in. (3000 and no sweeping of the expired grew the process to its
# memory limit in about a day, and systemd killed it.)
SEARCH_CACHE_MAX = 300
FETCH_TIMEOUT = 10
MIN_SEEDS = 2
BROWSE_LIMIT = 100
# Catalog lists in the app scroll without end: as many Rutor pages as that site keeps.
MAX_BROWSE_PAGE = 60
SEARCH_LIMIT = 200
HASH_LOOKUPS = 20
# Release pages read for covers and ids: at most this many a second per site, in the background.
PAGES_PER_SECOND = 1.0
PAGE_QUEUE_MAX = 5000
MONTHS = ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"]

RUTOR_ROW = re.compile(r'<tr class="(?:gai|tum)">(.*?)</tr>', re.S)
ANY_ROW = re.compile(r"<tr\b[^>]*>(.*?)</tr>", re.S | re.I)
FIRST_CELL = re.compile(r"<td[^>]*>(.*?)</td>", re.S)
RUTOR_LINK = re.compile(r"<a\b[^>]*href=[\"']([^\"']*/torrent/(\d+)[^\"']*)[\"'][^>]*>(.*?)</a>", re.S | re.I)
RUTOR_SEEDS = re.compile(r"alt=[\"']S[\"'][^>]*>(?:&nbsp;|\s|<[^>]+>)*(\d+)", re.I)
RUTOR_PEERS = re.compile(r"alt=[\"']L[\"'][^>]*>(?:&nbsp;|\s|<[^>]+>)*(\d+)", re.I)
TRACKER_LINK = re.compile(r"<a\b[^>]*href=[\"'][^\"']*viewtopic\.php\?t=(\d+)[^\"']*[\"'][^>]*>(.*?)</a>", re.S | re.I)
TRACKER_SEEDS = re.compile(r"class=[\"'][^\"']*seedmed[^\"']*[\"'][^>]*>\s*(?:<b>)?\s*(\d+)", re.I)
TRACKER_PEERS = re.compile(r"class=[\"'][^\"']*leechmed[^\"']*[\"'][^>]*>\s*(?:<b>)?\s*(\d+)", re.I)
SECTION_LINK = re.compile(r"<a\b[^>]*href=[\"'][^\"']*tracker\.php\?f=\d+[^\"']*[\"'][^>]*>(.*?)</a>", re.S | re.I)
VIDEO_SECTION = re.compile(r"фильм|сериал|кино|мульт|аниме|документ|передач|шоу|концерт|онгоинг|тележурнал|\bvideo\b")
NOT_VIDEO_SECTION = re.compile(r"игр|плеер|кодек|программ|\bпо\b|музык|lossless|сборники|vinyl|трейлер")
VIDEO_FORMAT = re.compile(r"web-?dl|web-?rip|bd-?rip|hd-?rip|dvd-?rip|blu-?ray|remux|hdtv|tv-?rip|sat-?rip|\b(?:480|576|720|1080|2160)[pi]\b|\b4k\b", re.I)
NNM_ADDED = re.compile(r"<u>(\d{9,11})</u>\s*\d{1,2}-\d{1,2}-\d{4}")
MEGAPEER_LINK = re.compile(r"<a\b[^>]*href=[\"'](/torrent/(\d+)/[^\"']*)[\"'][^>]*>(.*?)</a>", re.S | re.I)
MAGNET = re.compile(r"(magnet:\?xt=urn:btih:[^\"'<>\s]+)", re.I)
BTIH = re.compile(r"xt=urn:btih:([a-zA-Z0-9]+)", re.I)
SIZE = re.compile(r"\b\d+(?:[.,]\d+)?\s*(?:TB|GB|MB|ТБ|ГБ|МБ)\b", re.I)
NOT_VIDEO = re.compile(r"\b(mp3|flac|fb2|epub|pdf|djvu|portable|pc game|lossless|windows|office|android game|ps4|ps5|xbox|apk|repack|dlc|steam-?rip|gog|nsp|xci)\b|\b(?:pc|mac|linux)\s*\||\[v\s?\d+\.\d|аудиокниг")
SERIES = re.compile(r"\bs\d{1,2}(e\d{1,3})?\b|сезон|серии|серия|\bseason|\bepisodes?\b|\d+\s*из\s*\d+")
TAG = re.compile(r"<[^>]+>")
SPACES = re.compile(r"\s+")

# Release pages (net/ReleaseDetails.kt): the cover, the countries, the film's ids.
IMG = re.compile(r"<img[^>]+src\s*=\s*[\"']([^\"']+)[\"']", re.I)
FORUM_IMG = re.compile(r"<(?:var|img)\b(?=[^>]*\bpostImg\b)[^>]*?\b(?:title|src)\s*=\s*[\"']((?:https?:)?//[^\"']+)[\"']", re.I)
LINKED_IMAGE = re.compile(r"<a[^>]+href=[\"']([^\"']+\.(?:jpe?g|png|webp))[\"'][^>]*>\s*<(?:img[^>]+src|var[^>]+title)=[\"']([^\"']+)[\"']", re.I)
NOT_POSTER = re.compile(r"imdb|kinopoisk|/flag/|smile|rating|\.gif(\?|$)|button|banner|userbar|logo", re.I)
NNM_PROXY = re.compile(r"https?://[^\"'\s]*/forum/image\.php\?link=([^\"'\s&]+)", re.I)
LINE_BREAK = re.compile(r"<br\s*/?>|</p>|</div>|</li>", re.I)
STORY_FIELD = re.compile(r"^(?:Описание(?: фильма| сериала)?|О фильме|О сериале|О мультфильме|Сюжет)\s*:?\s*(.*)$", re.I)
ANY_FIELD = re.compile(r"^[А-ЯЁA-Z][^:]{1,40}:\s")
GENRE_FIELD = re.compile(r"(?:^|\s)Жанры?\s*:\s*(.+)$", re.I)
COUNTRY_FIELD = re.compile(r"(?:^|\s)(?:Страна|Производство|Страна производства)\s*:\s*(.+)$", re.I)
IMDB_ID = re.compile(r"imdb\.com/title/(tt\d{6,10})|/imdb/pic/(\d{6,10})\.gif", re.I)
KINOPOISK_ID = re.compile(r"kinopoisk\.ru/(?:(?:film|series)/(?:film/)?|rating/|level/1/film/)?(\d{2,9})(?:/|\.gif|\.xml|[\"'?])", re.I)


def plain(fragment):
    return SPACES.sub(" ", html.unescape(TAG.sub(" ", fragment))).strip()


def hash_of(magnet):
    match = BTIH.search(magnet or "")
    return match.group(1).lower() if match else None


def looks_like_video(title):
    return not NOT_VIDEO.search(title.lower())


def is_series(title):
    return bool(SERIES.search(title.lower()))


def day_of(date):
    parts = date.split(" ")
    if len(parts) != 3:
        return 0
    try:
        month = MONTHS.index(parts[1].lower()[:3]) + 1
        return (2000 + int(parts[2])) * 10000 + month * 100 + int(parts[0])
    except ValueError:
        return 0


def date_label(date):
    parts = date.split(" ")
    return f"{parts[0]} {parts[1].lower()}" if len(parts) == 3 else None


# ---- Fetching ----

class Polite:
    """At most [rate] requests a second to one host: release pages trickle in, they never flood a site."""

    def __init__(self, rate):
        self.gap = 1.0 / rate
        self.next = {}
        self.lock = threading.Lock()

    def wait(self, host):
        with self.lock:
            now = time.monotonic()
            at = max(now, self.next.get(host, 0))
            self.next[host] = at + self.gap
        if at > now:
            time.sleep(at - now)


page_pace = Polite(PAGES_PER_SECOND)
# A viewer's search waits for the magnets of its best results: those pages go faster.
lookup_pace = Polite(6)


def fetch(url, timeout=FETCH_TIMEOUT):
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise OSError("host not allowed")
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru,en;q=0.8"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read(3_000_000)
        declared = (response.headers.get("Content-Type", "") + body[:2048].decode("latin-1")).lower()
    return body.decode("cp1251" if "windows-1251" in declared else "utf-8", "replace")


mirrors = list(RUTOR_MIRRORS)
mirrors_lock = threading.Lock()


def rutor_page(path):
    global mirrors
    failure = None
    for base in list(mirrors):
        try:
            text = fetch(base + path)
            if base != mirrors[0]:
                with mirrors_lock:
                    mirrors = [base] + [m for m in mirrors if m != base]
            return base, text
        except (OSError, urllib.error.URLError, TimeoutError) as e:
            failure = e
    raise failure or OSError("rutor unreachable")


# ---- Parsing lists (the app's parsers, net/SourceSearchClient.kt) ----

def hit(id_, title, source, page, magnet, size, seeds, peers, day, label):
    return {"id": id_, "t": title, "s": source, "h": hash_of(magnet), "p": page, "m": magnet, "z": size,
            "se": seeds, "pe": peers, "d": day, "dl": label, "n": 1}


def parse_rutor(base, text):
    out = []
    for row in RUTOR_ROW.finditer(text):
        cells = row.group(1)
        link = RUTOR_LINK.search(cells)
        if not link:
            continue
        title = plain(link.group(3))
        if len(title) < 3 or not looks_like_video(title):
            continue
        seeds = int((RUTOR_SEEDS.search(cells) or [None, 0])[1])
        if seeds < 1:
            continue
        first = FIRST_CELL.search(cells)
        date = plain(first.group(1)) if first else ""
        magnet = MAGNET.search(cells)
        magnet = magnet.group(1).replace("&amp;", "&") if magnet else None
        size = SIZE.search(plain(cells))
        peers = RUTOR_PEERS.search(cells)
        out.append(hit(f"rutor:{link.group(2)}", title, "RUTOR", urllib.parse.urljoin(base, link.group(1).replace("&amp;", "&")),
                       magnet, size.group(0) if size else "", seeds, int(peers.group(1)) if peers else 0, day_of(date), date_label(date)))
    return out


def video_section(cells, title):
    section = SECTION_LINK.search(cells)
    if not section:
        return False
    name = plain(section.group(1)).lower()
    if NOT_VIDEO_SECTION.search(name):
        return False
    return bool(VIDEO_SECTION.search(name) or VIDEO_FORMAT.search(title))


def parse_nnm(text):
    out = []
    for row in ANY_ROW.finditer(text):
        cells = row.group(1)
        link = TRACKER_LINK.search(cells)
        if not link:
            continue
        title = plain(link.group(2))
        if len(title) < 3 or not looks_like_video(title) or not video_section(cells, title):
            continue
        seeds = TRACKER_SEEDS.search(cells)
        seeds = int(seeds.group(1)) if seeds else 0
        if seeds < 1:
            continue
        added = NNM_ADDED.search(cells)
        day, label = 0, None
        if added:
            t = time.localtime(int(added.group(1)))
            day, label = t.tm_year * 10000 + t.tm_mon * 100 + t.tm_mday, f"{t.tm_mday} {MONTHS[t.tm_mon - 1]}"
        size = SIZE.search(plain(cells))
        peers = TRACKER_PEERS.search(cells)
        out.append(hit(f"nnm:{link.group(1)}", title, "NNM", f"{NNM}viewtopic.php?t={link.group(1)}", None,
                       size.group(0) if size else "", seeds, int(peers.group(1)) if peers else 0, day, label))
    return out


def parse_megapeer(text):
    out = []
    for row in ANY_ROW.finditer(text):
        cells = row.group(1)
        link = MEGAPEER_LINK.search(cells)
        if not link:
            continue
        title = plain(link.group(3))
        if len(title) < 3 or not looks_like_video(title) or not VIDEO_FORMAT.search(title):
            continue
        seeds = RUTOR_SEEDS.search(cells)
        seeds = int(seeds.group(1)) if seeds else 0
        if seeds < 1:
            continue
        first = FIRST_CELL.search(cells)
        date = plain(first.group(1)) if first else ""
        size = SIZE.search(plain(cells))
        peers = RUTOR_PEERS.search(cells)
        out.append(hit(f"megapeer:{link.group(2)}", title, "MEGAPEER", MEGAPEER.rstrip("/") + link.group(1).replace("&amp;", "&"), None,
                       size.group(0) if size else "", seeds, int(peers.group(1)) if peers else 0, day_of(date), date_label(date)))
    return out


def merge(hits):
    """One entry per torrent; the same info hash on several sites adds up its seeds, per site once."""
    groups = {}
    for h in hits:
        groups.setdefault(f"btih:{h['h']}" if h["h"] else h["id"], []).append(h)
    out = []
    for key, group in groups.items():
        if len(group) == 1:
            out.append(dict(group[0], id=key))
            continue
        per_site = {}
        for h in group:
            if h["s"] not in per_site or h["se"] > per_site[h["s"]]["se"]:
                per_site[h["s"]] = h
        best = sorted(group, key=lambda h: (h["m"] is not None, h["se"]), reverse=True)[0]
        newest = max(group, key=lambda h: h["d"])
        out.append(dict(best, id=key, se=sum(h["se"] for h in per_site.values()), pe=sum(h["pe"] for h in per_site.values()),
                        z=best["z"] or next((h["z"] for h in group if h["z"]), ""), d=newest["d"], dl=newest["dl"], n=len(per_site)))
    return out


# ---- Release pages ----

def absolute(base, path):
    url = urllib.parse.urljoin(base, path.replace("&amp;", "&"))
    return url if url.startswith(("http://", "https://")) else None


def parse_page(text, page, source):
    """Cover, countries, IMDb and Kinopoisk ids and the magnet of one release page; empty when unreadable."""
    info = {"magnet": None, "poster": "", "country": "", "imdb": "", "kp": "", "genre": "", "story": ""}
    magnet = MAGNET.search(text)
    if magnet:
        info["magnet"] = magnet.group(1).replace("&amp;", "&")
    if source == "NNM":
        start = text.find('class="postbody"')
        if start < 0:
            return info
        block = text[text.find(">", start) + 1:]
        end = block.find("</td>")
        block = block[:end] if end > 0 else block
        block = NNM_PROXY.sub(lambda m: urllib.parse.unquote(m.group(1)), block)
        wide, images = block, FORUM_IMG
    else:
        start = text.find('id="details"')
        if start < 0:
            return info
        block = text[text.find(">", start) + 1:]
        end = block.find("</td></tr>")
        wide = block[:end] if end > 0 else block
        cuts = [i for i in (block.find('<div class="hidewrap"'), block.find("</td></tr>")) if i > 0]
        block = block[:min(cuts)] if cuts else block
        images = IMG
    full = {m.group(2): m.group(1) for m in LINKED_IMAGE.finditer(wide)}
    for m in images.finditer(block):
        src = m.group(1)
        if NOT_POSTER.search(src):
            continue
        poster = absolute(page, full.get(src, src))
        if poster:
            info["poster"] = poster
            break
    # The description: the text after «Описание:» / «О фильме:» up to the next field, for the meaning of the film.
    story, collecting = [], False
    for line in LINE_BREAK.split(block):
        line = SPACES.sub(" ", html.unescape(TAG.sub(" ", line))).strip()
        if collecting:
            if not line and story:
                break
            if ANY_FIELD.match(line) or sum(map(len, story)) > 1000:
                break
            if line:
                story.append(line)
            continue
        m = STORY_FIELD.match(line)
        if m:
            collecting = True
            if m.group(1).strip():
                story.append(m.group(1).strip())
    info["story"] = " ".join(story)[:1200]
    # The fields may follow a picture on the same line (NNM-Club), so they are looked for anywhere in a line.
    for line in LINE_BREAK.split(block):
        line = SPACES.sub(" ", html.unescape(TAG.sub(" ", line))).strip()
        if not info["country"]:
            field = COUNTRY_FIELD.search(line)
            if field and field.group(1).strip():
                info["country"] = field.group(1).strip()[:160]
        if not info["genre"]:
            field = GENRE_FIELD.search(line)
            if field and field.group(1).strip():
                info["genre"] = field.group(1).strip()[:160]
        if info["country"] and info["genre"]:
            break
    imdb = IMDB_ID.search(wide)
    if imdb:
        info["imdb"] = imdb.group(1) or "tt" + imdb.group(2)
    kp = KINOPOISK_ID.search(wide)
    if kp:
        info["kp"] = kp.group(1)
    return info


class Store:
    """What each release page said: kept for good, a page is read once."""

    def __init__(self, path):
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS pages (
            page TEXT PRIMARY KEY, magnet TEXT, poster TEXT, country TEXT, imdb TEXT, kp TEXT, read_at INTEGER)""")
        # Genres came later: pages read before have NULL there and are read once more when they show up again.
        columns = [row[1] for row in self.db.execute("PRAGMA table_info(pages)")]
        if "genre" not in columns:
            self.db.execute("ALTER TABLE pages ADD COLUMN genre TEXT")
            self.db.commit()
        # Descriptions came later still: NULL means read before, worth one more visit when the page shows up.
        if "story" not in columns:
            self.db.execute("ALTER TABLE pages ADD COLUMN story TEXT")
            self.db.commit()
        self.lock = threading.Lock()

    def get(self, pages):
        if not pages:
            return {}
        out = {}
        with self.lock:
            for i in range(0, len(pages), 400):
                chunk = pages[i:i + 400]
                rows = self.db.execute(f"SELECT page, magnet, poster, country, imdb, kp, genre, story FROM pages WHERE page IN ({','.join('?' * len(chunk))})", chunk)
                for page, magnet, poster, country, imdb, kp, genre, story in rows:
                    out[page] = {"magnet": magnet, "poster": poster, "country": country, "imdb": imdb, "kp": kp, "genre": genre, "story": story}
        return out

    def put(self, page, info):
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO pages (page, magnet, poster, country, imdb, kp, read_at, genre, story) VALUES (?,?,?,?,?,?,?,?,?)",
                            (page, info["magnet"], info["poster"], info["country"], info["imdb"], info["kp"], int(time.time()), info.get("genre") or "", info.get("story") or ""))
            self.db.commit()

    def count(self):
        with self.lock:
            return self.db.execute("SELECT COUNT(*) FROM pages").fetchone()[0]


store = None


def read_page(page, source, pace=None):
    host = (urllib.parse.urlsplit(page).hostname or "").lower()
    (pace or page_pace).wait(host)
    try:
        info = parse_page(fetch(page), page, source)
    except (OSError, urllib.error.URLError, TimeoutError, ValueError):
        return None
    store.put(page, info)
    return info


class PageQueue:
    """Release pages waiting to be read for covers and ids; the newest wishes go first."""

    def __init__(self):
        self.items = []
        self.waiting = set()
        self.cond = threading.Condition()

    def add(self, hits):
        known = store.get([h["p"] for h in hits])
        with self.cond:
            for h in hits:
                # Read, and after genres were added (NULL genre: read before, worth one more visit).
                done = h["p"] in known and known[h["p"]].get("genre") is not None and (sense is None or known[h["p"]].get("story") is not None)
                if done or h["p"] in self.waiting:
                    continue
                if len(self.items) >= PAGE_QUEUE_MAX:
                    break
                self.items.append((h["p"], h["s"]))
                self.waiting.add(h["p"])
            self.cond.notify_all()

    def take(self):
        with self.cond:
            while not self.items:
                self.cond.wait()
            page, source = self.items.pop(0)
            self.waiting.discard(page)
            return page, source


pages = PageQueue()


def page_worker():
    while True:
        page, source = pages.take()
        try:
            read_page(page, source)
        except Exception as e:  # a bad page never stops the worker
            print("page", page, type(e).__name__, e, file=sys.stderr, flush=True)


# ---- Lists ----

pool = ThreadPoolExecutor(max_workers=16)


def attempt(source, job):
    try:
        return job(), None
    except (OSError, urllib.error.URLError, TimeoutError, ValueError) as e:
        reason = "не ответил вовремя" if "timed out" in str(e) else f"сайт недоступен ({type(getattr(e, 'reason', e)).__name__})"
        return [], {"s": source, "m": reason}


def with_hashes(hits):
    """NNM-Club and Megapeer rows carry no magnet: the best seeded get theirs from the release page (read once, kept)."""
    missing = sorted((h for h in hits if not h["h"]), key=lambda h: -h["se"])[:HASH_LOOKUPS]
    known = store.get([h["p"] for h in missing])
    unknown = [h for h in missing if h["p"] not in known]
    for h, info in zip(unknown, pool.map(lambda h: read_page(h["p"], h["s"], lookup_pace), unknown)):
        if info:
            known[h["p"]] = info
    for h in missing:
        magnet = (known.get(h["p"]) or {}).get("magnet")
        if magnet:
            h["m"], h["h"] = magnet, hash_of(magnet)
    return hits


def browse(cat, popular, page=0):
    jobs = [pool.submit(attempt, "RUTOR", lambda c=c: parse_rutor(*rutor_page(f"browse/{page}/{c}/0/{2 if popular else 0}"))) for c in CATEGORIES[cat]]
    outcomes = [j.result() for j in jobs]
    hits = merge([h for found, _ in outcomes for h in found if h["se"] >= MIN_SEEDS])
    hits.sort(key=(lambda h: -h["se"]) if popular else (lambda h: (-h["d"], -h["se"])))
    return hits[:BROWSE_LIMIT if page == 0 else BROWSE_LIMIT * 2], [e for _, e in outcomes if e]


def stem(word):
    word = word.lower().replace("ё", "е")
    return word[:max(4, len(word) - 2)] if len(word) > 5 else word


def query_words(text):
    return [stem(w) for w in re.findall(r"[\w]+", text) if len(w) > 1]


def title_has(title, words):
    low = title.lower().replace("ё", "е")
    return all(w in low for w in words)


# Words that only say "a film": dropped before the descriptions are searched.
FILLER = {"фильм", "фильмы", "фильмов", "кино", "сериал", "сериалы", "мультфильм", "мультики", "про", "об", "обо", "где",
          "про", "для", "что", "как", "или", "и", "с", "со", "в", "во", "на", "о", "из", "по", "от", "film", "films", "movie", "movies", "about", "with", "the"}


def description_search(text, plan=None):
    """Releases whose description (cast, genre, plot) matches the query: Rutor's full-text search, declensions
    included. With the LLM's [plan] each person, keyword and genre is asked apart and a film scores by how many
    of them its description holds; without one the query's own words are used."""
    if plan and plan["people"]:
        # A name is the whole question: themes the model adds around it would only bring other films in.
        terms = [(t, 3) for t in plan["people"]]
    elif plan:
        terms = [(t, 3) for t in plan["people"]] + [(t, 2) for t in plan["keywords"][:5]] + [(t, 1) for t in plan["genres"][:2]]
    else:
        words = [w for w in re.findall(r"[\w-]+", text) if w.lower() not in FILLER]
        terms = [(" ".join(words), 3)] if words else []
        terms += [(w, 1) for w in words if len(w) >= 5][:4] if len(words) > 1 else []
    if not terms:
        return []

    def run(phrase):
        q = urllib.parse.quote(phrase)
        try:
            # Category 0 = every section; parse_rutor keeps video only.
            return merge([h for h in parse_rutor(*rutor_page(f"search/0/0/010/0/{q}"))
                          if h["se"] >= MIN_SEEDS and VIDEO_FORMAT.search(h["t"])])
        except Exception:
            return []

    jobs = [(w, pool.submit(run, t)) for t, w in terms]
    score = {}
    for w, job in jobs:
        for h in job.result():
            entry = score.setdefault(h["id"], [0, h])
            entry[0] += w
    kind = (plan or {}).get("kind", "any")
    rows = [(n, h) for n, h in score.values() if kind == "any" or is_series(h["t"]) == (kind == "series")]
    rows.sort(key=lambda e: (-e[0], -e[1]["se"]))
    # A genre word alone (weight 1) matches half the catalogue: it only ranks, unless nothing else is left.
    strong = [e for e in rows if e[0] >= 2]
    return [h for n, h in (strong if len(strong) >= 6 else rows)[:60]]


def search(query, cat):
    q = urllib.parse.quote(query)
    cp1251 = urllib.parse.quote(query.encode("cp1251", "ignore"))
    rutor_cats = [0] if cat == "ALL" else CATEGORIES[cat]
    jobs = [pool.submit(attempt, "RUTOR", lambda c=c: parse_rutor(*rutor_page(f"search/0/{c}/000/0/{q}"))) for c in rutor_cats]
    if cat in TRACKER_CATEGORIES:
        jobs.append(pool.submit(attempt, "NNM", lambda: parse_nnm(fetch(f"{NNM}tracker.php?nm={cp1251}"))))
        jobs.append(pool.submit(attempt, "MEGAPEER", lambda: parse_megapeer(fetch(f"{MEGAPEER}browse.php?search={cp1251}"))))
    outcomes = [j.result() for j in jobs]

    def fits(h):
        if h["s"] == "RUTOR" or cat not in ("MOVIES", "SERIES"):
            return True
        return is_series(h["t"]) == (cat == "SERIES")

    words = query_words(query)

    def relevant(h):
        # NNM-Club and Megapeer answer a query they cannot match with their newest releases: keep only titles
        # that carry every word of the query (a stem of it, so «послезавтра» finds «Послезавтра» in any case).
        return h["s"] == "RUTOR" or not words or title_has(h["t"], words)

    alive = [h for found, _ in outcomes for h in found if h["se"] >= MIN_SEEDS and fits(h) and relevant(h)]
    hits = sorted(merge(with_hashes(alive)), key=lambda h: -h["se"])[:SEARCH_LIMIT]
    return hits, [e for _, e in outcomes if e]


class Cache:
    def __init__(self, ttl, limit):
        self.ttl, self.limit = ttl, limit
        self.items = {}
        self.lock = threading.Lock()
        self.building = {}

    def get(self, key, build):
        """A fresh entry, or one built now; viewers asking the same at once share one build."""
        with self.lock:
            item = self.items.get(key)
            if item and time.time() - item[0] < self.ttl:
                return item[1]
            event = self.building.get(key)
            mine = event is None
            if mine:
                event = self.building[key] = threading.Event()
        if not mine:
            event.wait(30)
            with self.lock:
                item = self.items.get(key)
            if item:
                return item[1]
        try:
            value = build()
            with self.lock:
                # A list every source failed for is not kept: the next viewer tries again.
                if value[0] or not value[1]:
                    now = time.time()
                    for k in [k for k, v in self.items.items() if now - v[0] >= self.ttl]:
                        del self.items[k]
                    if len(self.items) >= self.limit:
                        oldest = sorted(self.items, key=lambda k: self.items[k][0])[: self.limit // 10]
                        for k in oldest:
                            del self.items[k]
                    self.items[key] = (time.time(), value)
            return value
        finally:
            if mine:
                with self.lock:
                    self.building.pop(key, None)
                event.set()

    def refresh(self, key, build):
        value = build()
        if value[0]:
            with self.lock:
                self.items[key] = (time.time(), value)


browse_cache = Cache(BROWSE_TTL, 200)
search_cache = Cache(SEARCH_TTL, SEARCH_CACHE_MAX)


def shelves_worker():
    """The home rows, rebuilt every few minutes whoever asks, so the first viewer of the hour waits for nothing."""
    while True:
        for cat, popular in SHELVES:
            try:
                browse_cache.refresh((cat, popular), lambda: browse(cat, popular))
                pages.add(browse_cache.items[(cat, popular)][1][0])
                if sense is not None:
                    sense.offer(browse_cache.items[(cat, popular)][1][0])
            except Exception as e:
                print("shelf", cat, popular, type(e).__name__, e, file=sys.stderr, flush=True)
        time.sleep(BROWSE_TTL - 60)


def enrich(hits):
    """Each hit with what its page told (cover, countries, ids) when the page has been read."""
    known = store.get([h["p"] for h in hits])
    out = []
    for h in hits:
        info = known.get(h["p"])
        h = {k: v for k, v in h.items() if v not in (None, "", 0) or k in ("se", "pe", "d", "n")}
        if info:
            for key, short in (("poster", "po"), ("country", "c"), ("imdb", "i"), ("kp", "k"), ("genre", "g")):
                if info.get(key):
                    h[short] = info[key]
            # Read but without a cover: the app should not read the page again for one.
            if not info.get("poster"):
                h["po"] = ""
        out.append(h)
    return out


def answer(hits, errors, etag_seen):
    body = {"v": 1, "hits": enrich(hits), "errors": errors}
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    etag = format(zlib.crc32(raw), "x")
    if etag_seen == etag:
        return json.dumps({"v": 1, "e": etag, "same": 1}).encode()
    body["e"] = etag
    return json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()


class Stats:
    """Requests and bytes answered today (before the relay's gzip, which shrinks them about 6 times)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.day, self.requests, self.bytes, self.same = "", 0, 0, 0

    def add(self, size, same):
        with self.lock:
            today = time.strftime("%Y-%m-%d", time.gmtime())
            if today != self.day:
                self.day, self.requests, self.bytes, self.same = today, 0, 0, 0
            self.requests += 1
            self.bytes += size
            self.same += 1 if same else 0

    def view(self):
        with self.lock:
            return {"day": self.day, "requests": self.requests, "same": self.same,
                    "mb_raw": round(self.bytes / 1e6, 2), "mb_gzip_approx": round(self.bytes / 6e6, 2)}


stats = Stats()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "kinoindex"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        cat = query.get("cat", ["ALL"])[0].upper()
        etag = query.get("e", [""])[0]
        if cat not in CATEGORIES:
            return self.send(400, b'{"error":"bad category"}')
        if url.path == "/health":
            return self.send(200, json.dumps({"pages": store.count(), "queued": len(pages.items), "today": stats.view()}).encode())
        if url.path == "/browse":
            popular = query.get("popular", ["0"])[0] == "1"
            page = max(0, min(int(query.get("page", ["0"])[0] or 0), MAX_BROWSE_PAGE)) if query.get("page", ["0"])[0].isdigit() else 0
            hits, errors = browse_cache.get((cat, popular) if page == 0 else (cat, popular, page), lambda: browse(cat, popular, page))
        elif url.path in ("/similar", "/meaning"):
            return self.meaning(url.path, query)
        elif url.path == "/search":
            text = " ".join(query.get("q", [""])[0].split())[:120]
            if len(text) < 2:
                return self.send(400, b'{"error":"short query"}')
            hits, errors = search_cache.get((cat, text.lower()), lambda: search(text, cat))
        else:
            return self.send(404, b'{"error":"not found"}')
        pages.add(hits[:40])
        if sense is not None:
            sense.offer(hits)
        body = answer(hits, errors, etag)
        stats.add(len(body), body.startswith(b'{"v": 1, "e"'))
        return self.send(200, body)

    def meaning(self, path, query):
        """Similar films (?page=&t=) or search by description (?q=): descriptions on Rutor plus kinosense's vectors."""
        try:
            if path == "/similar":
                if sense is None:
                    return self.send(404, b'{"error":"no meaning here"}')
                page = query.get("page", [""])[0]
                if not page.startswith(("http://", "https://")):
                    return self.send(400, b'{"error":"bad page"}')
                hits = sense.similar(page, query.get("t", [""])[0][:200])
                if hits is None:
                    return self.send(404, b'{"error":"page not read yet"}')
            else:
                text = " ".join(query.get("q", [""])[0].split())[:200]
                if len(text) < 3:
                    return self.send(400, b'{"error":"short query"}')
                hits = meaning_search(text)
        except Exception as e:  # the LLM box is away: the app shows nothing new, everything else works
            print("meaning", type(e).__name__, e, file=sys.stderr, flush=True)
            return self.send(503, b'{"error":"meaning unavailable"}')
        pages.add(hits[:40])
        body = answer(hits, [], "")
        stats.add(len(body), False)
        return self.send(200, body)

    def send(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


sense = None
meaning_cache = Cache(SEARCH_TTL, 200)


def meaning_search(text):
    """Description hits (cast, genre words) and vector hits merged: films in both first, then Rutor's, then the vectors'."""
    def build():
        plan = None
        try:
            plan = sense.understand(text) if sense is not None else None
        except Exception:
            plan = None
        try:
            by_text = description_search(text, plan)
        except Exception as e:
            print("describe", type(e).__name__, e, file=sys.stderr, flush=True)
            by_text = []
        try:
            # The vectors are asked with the keywords too: «про людей со сверхспособностями» alone is far from «мутанты, комикс».
            by_sense = sense.meaning(text + (". " + ", ".join(plan["keywords"] + plan["genres"]) if plan and not plan["people"] else "")) if sense is not None else []
        except Exception as e:
            print("sense", type(e).__name__, e, file=sys.stderr, flush=True)
            by_sense = []
            if not by_text:
                raise
        key = lambda h: re.sub(r"\s*[(\[].*$", "", h["t"]).lower()
        titles_sense = {key(h) for h in by_sense}
        titles_text = {key(h) for h in by_text}
        candidates = {}
        for h in by_text + by_sense:
            candidates.setdefault(h["id"], h)
        # A title the model recognised in the query («Матрицв» -> Матрица): its releases lead.
        named = []
        if plan and plan["title"]:
            try:
                named = [h for h in search(plan["title"], "ALL")[0] if title_has(h["t"], query_words(plan["title"]))][:10]
            except Exception:
                named = []
        # Read each candidate's genres and plot: a film whose own text holds the asked words ranks above one that
        # only sat near them in the vectors.
        for h in ([] if not named else named):
            candidates.setdefault(h["id"], h)
        infos = store.get([h["p"] for h in candidates.values()])
        terms = []
        if plan:
            terms = [(stem(w), 2.0) for t in plan["keywords"] for w in re.findall(r"[\w-]+", t) if len(w) > 3]
            terms += [(stem(w), 3.0) for t in plan["people"] for w in re.findall(r"[\w-]+", t) if len(w) > 2]
        genre_names = {g.lower().replace("ё", "е")[:5] for g in (plan["genres"] if plan else [])}
        named_ids = {h["id"] for h in named}

        def score(h):
            info = infos.get(h["p"]) or {}
            body = ((info.get("story") or "") + " " + h["t"]).lower().replace("ё", "е")
            points = sum(w for t, w in terms if t in body)
            genre = (info.get("genre") or "").lower().replace("ё", "е")
            points += 2.5 * sum(1 for g in genre_names if g in genre)
            if h["id"] in named_ids:
                points += 100
            if key(h) in titles_sense and key(h) in titles_text:
                points += 4
            elif key(h) in titles_text:
                points += 1.5
            return points + min(h["se"], 200) / 400

        for h in named:
            candidates.setdefault(h["id"], h)
        ranked = sorted(candidates.values(), key=score, reverse=True)
        # What nothing but the vectors vouches for (score under 1.5) is dropped when enough better films are there.
        good = [h for h in ranked if score(h) >= 1.5]
        ranked = good if len(good) >= 8 else ranked
        seen, out = set(), []
        for h in ranked:
            if key(h) in seen:
                continue
            seen.add(key(h))
            out.append(h)
        return out[:40], []
    return meaning_cache.get(text.lower(), build)[0]


def main():
    global store, sense
    store = Store(DB_PATH)
    try:
        import kinosense
        if kinosense.ENABLED:
            sense = kinosense.Sense(store.db, store.lock, store.get)
            threading.Thread(target=sense.worker, daemon=True).start()
            print("kinosense on", kinosense.URL, flush=True)
    except ImportError:
        pass
    threading.Thread(target=page_worker, daemon=True).start()
    # A standby index (the office relay behind the cloud one) builds lists only when asked: its uplink is for other work.
    if not STANDBY:
        threading.Thread(target=shelves_worker, daemon=True).start()
    host, port = BIND.rsplit(":", 1)
    server = ThreadingHTTPServer((host, int(port)), Handler)
    server.daemon_threads = True
    print(f"kinoindex on {BIND}, db {DB_PATH}{', standby' if STANDBY else ''}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
