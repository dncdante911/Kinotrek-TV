#!/usr/bin/env python3
"""Kinotrek relay: fetches tracker pages for viewers whose provider blocks the trackers.

GET /r?u=<https url>  with header X-Relay-Key. Only the tracker sites below, only GET, no
redirects followed (the app follows them itself, through the relay again). The answer is the
site's own status, Content-Type, Location and body. Errors of the relay itself are 4xx/502 with
an X-Relay-Error header, so the app can tell them from the site's answers.

With the request header X-Relay-Chunk: <bytes> the answer is split instead: Russian DPI freezes a
connection to an unknown foreign address once some 16 KB have passed through it, so the body is
gzipped and served in parts small enough for one connection each. /r then answers 200 with the
site's status, Content-Type and Location in X-Relay-Status/-Type/-Location, the part count in
X-Relay-Parts, a token in X-Relay-Id and the first part as the body; GET /p?id=<token>&n=<i>
returns part i. The parts stay in memory for PARTS_TTL seconds.

GET /i?u=<image url>&w=<width> fetches a poster for a release card: only from the image hosts the
trackers use (IMAGE_HOSTS), only images, and never to an address that is not public. With Pillow
installed it is scaled down to the card's width first (a 150 KB poster becomes ~20 KB). The answer
is always split, as /r with X-Relay-Chunk.

Streams carry the app's built-in browser to RuTracker where the provider blocks it: a TCP stream to
port 443 of STREAM_HOSTS only, its TLS end to end between the TV and the site (the relay sees only
encrypted bytes). As with pages, each piece travels on a short connection of its own:
POST /s/open?h=<host> answers the stream's id; POST /s/w?id=&seq=<n> writes the body (pieces in
seq order); GET /s/r?id=&off=<offset>&size=<bytes>&ack=<offset> answers the bytes from off on,
waiting up to STREAM_WAIT s for some, with X-Relay-Eof: 1 once the site has closed and all is read
(ack: everything before it has arrived and may be dropped); POST /s/c?id= closes it.

GET /x/browse?cat=&popular=, /x/search?q=&cat= answer finished tracker lists from kinoindex.py.
POST /log stores an app crash or playback report (JSON, at most 64 KB) under RELAY_REPORTS.
POST /v1/telemetry/events, /v1/telemetry/delete pass anonymous usage events (only from viewers who agreed) to
kinostats.py on this host; no address of the sender is passed on or kept.
GET /t?imdb=tt<digits>&kp=<digits> answers a film's ratings as JSON, {"imdb": [7.8, 123456],
"kp": [7.9, 4567], "rt": 92, "mc": 80, "bd": "<TMDB still URL>"}, each key only when known: IMDb, Rotten Tomatoes and
Metacritic from OMDb (OMDB_KEY), Kinopoisk and IMDb from kinopoiskapiunofficial.tech (KP_KEY).
Without a key that source is skipped. Answers are kept in memory for RATINGS_TTL, so the free
daily limits of the services are spent on new films only. Split like /i.

Needs only the standard library; Pillow (python3-pil) is optional. Settings come from the
environment: RELAY_BIND (host:port), RELAY_CERT, RELAY_KEY_FILE (TLS), RELAY_SECRET (shared app key).
"""
import gzip
import hmac
import io
import json
import re
import ipaddress
import os
import secrets
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ALLOWED_HOSTS = {"rutor.info", "rutor.org.in", "rutor.org", "rutracker.org", "nnmclub.to", "megapeer.vip",
                 # AniLibria's API (the anime tab); its video comes from its CDN straight to the TV.
                 "aniliberty.top", "anilibria.top"}
MAX_BYTES = 4_000_000
UPSTREAM_TIMEOUT = 12
# Per viewer address: a home screen loads ~10 pages, a search ~20.
RATE_PER_MINUTE = 120
FORWARDED = ("User-Agent", "Accept-Language", "Cookie")
CHUNK_MIN, CHUNK_MAX = 2_000, 64_000
PARTS_TTL = 120
# Posters on release pages; a host and its subdomains (i128.fastpic.org).
IMAGE_HOSTS = ("fastpic.org", "fastpic.ru", "lostpix.com", "imageban.ru", "ibb.co", "imgur.com",
               "radikal.ru", "pixhost.to", "imgbox.com", "postimg.cc", "geekpic.net", "youpic.su",
               "hostingkartinok.com", "nnmstatic.win", "aniliberty.top", "anilibria.top", "image.tmdb.org")
IMAGE_MAX_BYTES = 8_000_000
IMAGE_WIDTH_MIN, IMAGE_WIDTH_MAX, IMAGE_WIDTH = 160, 480, 360
# A screen of cards asks for a poster each.
IMAGE_RATE_PER_MINUTE = 240

# Streams: RuTracker, its static files and Cloudflare's check (and names under them), for the browser; nothing else.
STREAM_HOSTS = {"rutracker.org", "static.rutracker.cc", "challenges.cloudflare.com"}
STREAM_PIECE_MAX = 16_000
STREAM_WAIT = 8
# A read returns once data has stopped arriving for this long, not only when [size] is full.
STREAM_SETTLE = 0.12
STREAM_IDLE = 90
STREAM_BUFFER = 1_000_000
STREAMS_PER_VIEWER = 48
STREAMS_MAX = 600
STREAM_OPENS_PER_MINUTE = 300

SECRET = os.environ["RELAY_SECRET"].encode()

OMDB_KEY = os.environ.get("OMDB_KEY", "")
KP_KEY = os.environ.get("KP_KEY", "")
# TMDB: a wide still of the film for the back of its page ("bd" in /t), by its IMDb id.
TMDB_KEY = os.environ.get("TMDB_KEY", "")
TMDB_IMAGE = "https://image.tmdb.org/t/p/w1280"
RATINGS_TTL = 3 * 24 * 3600
RATINGS_EMPTY_TTL = 6 * 3600
RATINGS_MAX = 100_000
RATINGS_RATE_PER_MINUTE = 240
IMDB_ID = re.compile(r"^tt\d{6,10}$")

# Kinotrek's search index (kinoindex.py) on this host: finished tracker lists for the app (GET /x/...).
INDEX_URL = os.environ.get("KINOINDEX_URL", "http://127.0.0.1:8446")
INDEX_RATE_PER_MINUTE = 60
INDEX_MAX_BYTES = 2_000_000

# Usage statistics (kinostats.py on this host, localhost only). Without it the paths answer 404.
STATS_URL = os.environ.get("KINOSTATS_URL", "http://127.0.0.1:8447")
STATS_PATHS = ("/v1/telemetry/events", "/v1/telemetry/delete")
STATS_MAX = 16_384
STATS_PER_MINUTE = 10

# Crash and playback reports from the app (POST /log): JSON files, one per report, no addresses kept.
REPORTS_DIR = os.environ.get("RELAY_REPORTS", "/var/lib/kinorelay/reports")
REPORT_MAX = 64_000
REPORTS_PER_MINUTE = 6
REPORTS_PER_DAY = 2000
KP_ID = re.compile(r"^\d{2,9}$")

try:
    from PIL import Image, ImageOps
except ImportError:
    Image = None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


class RateLimit:
    def __init__(self, per_minute):
        self.per_minute = per_minute
        self.hits = {}
        self.lock = threading.Lock()

    def allow(self, address):
        now = time.monotonic()
        with self.lock:
            if len(self.hits) > 50_000:
                self.hits = {a: t for a, t in self.hits.items() if t and now - t[-1] < 60}
            recent = [t for t in self.hits.get(address, ()) if now - t < 60]
            if len(recent) >= self.per_minute:
                self.hits[address] = recent
                return False
            recent.append(now)
            self.hits[address] = recent
            return True


limits = RateLimit(RATE_PER_MINUTE)
image_limits = RateLimit(IMAGE_RATE_PER_MINUTE)
stream_limits = RateLimit(STREAM_OPENS_PER_MINUTE)
ratings_limits = RateLimit(RATINGS_RATE_PER_MINUTE)
report_limits = RateLimit(REPORTS_PER_MINUTE)
stats_limits = RateLimit(STATS_PER_MINUTE)
index_limits = RateLimit(INDEX_RATE_PER_MINUTE)


def save_report(body):
    """Stores one app report under REPORTS_DIR/<UTC day>/; raises ValueError when it is not a JSON object."""
    data = json.loads(body.decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("not an object")
    folder = os.path.join(REPORTS_DIR, time.strftime("%Y-%m-%d", time.gmtime()))
    os.makedirs(folder, mode=0o750, exist_ok=True)
    if len(os.listdir(folder)) >= REPORTS_PER_DAY:
        raise OverflowError("enough reports today")
    data["received"] = int(time.time())
    name = f"{time.strftime('%H%M%S', time.gmtime())}-{secrets.token_hex(4)}.json"
    with open(os.path.join(folder, name), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)


def fetch_json(url, headers=None):
    """A rating service's JSON, or None when it cannot be had now."""
    request = urllib.request.Request(url, headers={"User-Agent": "kinorelay", "Accept": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=UPSTREAM_TIMEOUT) as response:
            return json.loads(response.read(1_000_000).decode("utf-8", "replace"))
    except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError, OSError, ValueError):
        return None


def number(text):
    try:
        return float(str(text).split("/")[0].replace("%", "").replace(",", "").strip())
    except (TypeError, ValueError):
        return None


class RatingsCache:
    def __init__(self):
        self.items = {}
        self.lock = threading.Lock()

    def get(self, key):
        with self.lock:
            entry = self.items.get(key)
        if not entry:
            return None
        stored, body, empty = entry
        return body if time.time() - stored < (RATINGS_EMPTY_TTL if empty else RATINGS_TTL) else None

    def put(self, key, body, empty):
        with self.lock:
            if len(self.items) >= RATINGS_MAX:
                for old in sorted(self.items, key=lambda k: self.items[k][0])[:RATINGS_MAX // 10]:
                    del self.items[old]
            self.items[key] = (time.time(), body, empty)


ratings_cache = RatingsCache()


def film_ratings(imdb, kp):
    """Asks the services that have a key; returns (ratings dict, whether any service answered)."""
    result, answered = {}, False
    if KP_KEY and (kp or imdb):
        headers = {"X-API-KEY": KP_KEY}
        if kp:
            film = fetch_json(f"https://kinopoiskapiunofficial.tech/api/v2.2/films/{kp}", headers)
        else:
            found = fetch_json(f"https://kinopoiskapiunofficial.tech/api/v2.2/films?imdbId={imdb}", headers)
            items = (found or {}).get("items") or []
            film = items[0] if items else ({} if found is not None else None)
        if film is not None:
            answered = True
            value = number(film.get("ratingKinopoisk"))
            if value and 0 < value <= 10:
                result["kp"] = [value, int(film.get("ratingKinopoiskVoteCount") or 0)]
            value = number(film.get("ratingImdb"))
            if value and 0 < value <= 10:
                result["imdb"] = [value, int(film.get("ratingImdbVoteCount") or 0)]
            if not imdb and IMDB_ID.match(str(film.get("imdbId") or "")):
                imdb = film["imdbId"]
    if OMDB_KEY and imdb:
        film = fetch_json(f"https://www.omdbapi.com/?i={imdb}&apikey={urllib.parse.quote(OMDB_KEY)}")
        if film is not None and film.get("Response") == "True":
            answered = True
            value = number(film.get("imdbRating"))
            if value and 0 < value <= 10:
                result["imdb"] = [value, int(number(film.get("imdbVotes")) or 0)]
            for rating in film.get("Ratings") or []:
                value = number(rating.get("Value"))
                if value is None:
                    continue
                if rating.get("Source") == "Rotten Tomatoes" and 0 <= value <= 100:
                    result["rt"] = int(value)
                elif rating.get("Source") == "Metacritic" and 0 <= value <= 100:
                    result["mc"] = int(value)
        elif film is not None:
            answered = True
    if TMDB_KEY and imdb:
        found = fetch_json(f"https://api.themoviedb.org/3/find/{imdb}?api_key={urllib.parse.quote(TMDB_KEY)}&external_source=imdb_id")
        if found is not None:
            answered = True
            for kind in ("movie_results", "tv_results"):
                path = next((item.get("backdrop_path") for item in found.get(kind) or [] if item.get("backdrop_path")), None)
                if path and re.match(r"^/[A-Za-z0-9_]+\.(?:jpg|png|webp)$", path):
                    result["bd"] = TMDB_IMAGE + path
                    break
    return result, answered


def requote(url):
    """Links taken from pages may carry raw « or ß; urllib sends ASCII only."""
    return urllib.parse.quote(url, safe=":/?#[]@!$&'()*+,;=%~")


def image_host_allowed(host):
    host = (host or "").lower()
    return any(host == h or host.endswith("." + h) for h in IMAGE_HOSTS)


def public_address(host):
    """True when every address the name resolves to is public: no way to this host's own network."""
    try:
        infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except OSError:
        return False
    return bool(infos) and all(ipaddress.ip_address(info[4][0]).is_global for info in infos)


def shrink(body, width):
    """The image as a JPEG at most [width] wide; as it is when Pillow is missing or cannot read it."""
    if Image is None:
        return body, None
    try:
        # One picture at a time and never at full size: a 6000x8000 poster opened whole is ~150 MB,
        # and two of those at once took the relay past its memory limit.
        with SHRINKING, Image.open(io.BytesIO(body)) as picture:
            if picture.width * picture.height > SHRINK_MAX_PIXELS:
                return body, None
            picture.draft("RGB", (width, width * 3))  # JPEGs decode at 1/2, 1/4, 1/8 straight away
            picture.thumbnail((width, width * 3))
            picture = ImageOps.exif_transpose(picture).convert("RGB")
            out = io.BytesIO()
            picture.save(out, "JPEG", quality=80, optimize=True, progressive=True)
            return out.getvalue(), "image/jpeg"
    except Exception:
        return body, None


SHRINKING = threading.Semaphore(2)
SHRINK_MAX_PIXELS = 50_000_000


class Parts:
    """Split answers waiting for the app to fetch their remaining parts."""

    def __init__(self, ttl):
        self.ttl = ttl
        self.items = {}
        self.lock = threading.Lock()

    def put(self, parts):
        token = secrets.token_urlsafe(12)
        now = time.monotonic()
        with self.lock:
            self.items = {k: v for k, v in self.items.items() if now - v[0] < self.ttl}
            self.items[token] = (now, parts)
        return token

    def get(self, token, n):
        with self.lock:
            item = self.items.get(token)
        if not item or time.monotonic() - item[0] >= self.ttl or not 0 <= n < len(item[1]):
            return None
        return item[1][n]


pending = Parts(PARTS_TTL)


def stream_host_allowed(host):
    """One of STREAM_HOSTS or a name under one (Cloudflare's check runs on brunhild.challenges.cloudflare.com)."""
    return any(host == h or host.endswith("." + h) for h in STREAM_HOSTS)


def stream_address(host):
    """A public IPv4 address of [host], or None: a stream never leads into a private network."""
    try:
        infos = socket.getaddrinfo(host, 443, socket.AF_INET, socket.SOCK_STREAM)
    except OSError:
        return None
    addresses = [info[4][0] for info in infos]
    if not addresses or not all(ipaddress.ip_address(a).is_global for a in addresses):
        return None
    return addresses[0]


class Stream:
    """One TCP stream to a site; what the site sends waits in [buffer] until the app has read it."""

    def __init__(self, sock, viewer):
        self.sock = sock
        self.viewer = viewer
        self.buffer = bytearray()
        self.base = 0  # stream offset of buffer[0]
        self.eof = False
        self.closed = False
        self.used = time.monotonic()
        self.ready = threading.Condition()
        self.write_lock = threading.Lock()
        self.next_seq = 0
        self.early = {}  # pieces that arrived before the ones they follow
        threading.Thread(target=self.pump, daemon=True).start()

    def pump(self):
        try:
            while True:
                with self.ready:
                    while len(self.buffer) >= STREAM_BUFFER and not self.closed:
                        self.ready.wait(1)
                    if self.closed:
                        break
                try:
                    data = self.sock.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    break
                with self.ready:
                    self.buffer += data
                    self.ready.notify_all()
        except OSError:
            pass
        with self.ready:
            self.eof = True
            self.ready.notify_all()

    def write(self, seq, data):
        with self.write_lock:
            self.used = time.monotonic()
            if seq < self.next_seq:
                return  # a retried piece that already went through
            if seq > self.next_seq:
                if len(self.early) >= 32:
                    raise ValueError("pieces out of order")
                self.early[seq] = data
                return
            self.sock.sendall(data)
            self.next_seq += 1
            while self.next_seq in self.early:
                self.sock.sendall(self.early.pop(self.next_seq))
                self.next_seq += 1

    def read(self, offset, size, ack):
        """(bytes from [offset], finished): waits for some, then for the burst to settle."""
        deadline = time.monotonic() + STREAM_WAIT
        with self.ready:
            self.used = time.monotonic()
            end = self.base + len(self.buffer)
            if self.base < ack <= end:
                del self.buffer[:ack - self.base]
                self.base = ack
                self.ready.notify_all()
            if offset < self.base:
                raise ValueError("offset already dropped")
            seen = None
            while True:
                end = self.base + len(self.buffer)
                now = time.monotonic()
                if end >= offset + size or self.eof or now >= deadline:
                    break
                if end > offset:
                    if seen == end:
                        break
                    seen = end
                    self.ready.wait(min(STREAM_SETTLE, deadline - now))
                else:
                    self.ready.wait(deadline - now)
            start = offset - self.base
            data = bytes(self.buffer[start:start + size]) if end > offset else b""
            self.used = time.monotonic()
            return data, self.eof and offset + len(data) >= end

    def close(self):
        with self.ready:
            self.closed = True
            self.ready.notify_all()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class Streams:
    def __init__(self):
        self.items = {}
        self.lock = threading.Lock()
        threading.Thread(target=self.sweep, daemon=True).start()

    def open(self, host, viewer):
        with self.lock:
            if len(self.items) >= STREAMS_MAX:
                raise ValueError("relay busy")
            if sum(1 for s in self.items.values() if s.viewer == viewer) >= STREAMS_PER_VIEWER:
                raise ValueError("too many streams")
        address = stream_address(host)
        if address is None:
            raise ValueError("host not allowed")
        sock = socket.create_connection((address, 443), timeout=UPSTREAM_TIMEOUT)
        sock.settimeout(30)
        stream = Stream(sock, viewer)
        token = secrets.token_urlsafe(12)
        with self.lock:
            self.items[token] = stream
        return token

    def get(self, token):
        with self.lock:
            return self.items.get(token)

    def close(self, token):
        with self.lock:
            stream = self.items.pop(token, None)
        if stream:
            stream.close()

    def sweep(self):
        while True:
            time.sleep(10)
            now = time.monotonic()
            with self.lock:
                idle = [t for t, s in self.items.items() if now - s.used > STREAM_IDLE]
            for token in idle:
                self.close(token)


streams = Streams()


class Handler(BaseHTTPRequestHandler):
    server_version = "kinorelay"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 20

    def log_message(self, fmt, *args):
        pass

    def fail(self, code, reason):
        body = reason.encode()
        self.send_response(code)
        self.send_header("X-Relay-Error", reason)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_part(self, body, meta=()):
        """One connection per part: closing it keeps every connection under the DPI's limit."""
        self.close_connection = True
        self.send_response(200)
        for name, value in meta:
            self.send_header(name, value)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/health":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path not in ("/r", "/p", "/i", "/s/r", "/t", "/x/browse", "/x/search"):
            return self.fail(404, "not found")
        if not hmac.compare_digest(self.headers.get("X-Relay-Key", "").encode(), SECRET):
            return self.fail(403, "bad key")
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/s/r":
            return self.stream_read(query)
        if url.path == "/p":
            try:
                part = pending.get(query.get("id", [""])[0], int(query.get("n", ["-1"])[0]))
            except ValueError:
                part = None
            return self.send_part(part) if part is not None else self.fail(404, "part expired")
        if url.path == "/i":
            return self.image(query)
        if url.path == "/t":
            return self.ratings(query)
        if url.path.startswith("/x/"):
            return self.index(url)
        if not limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        target = query.get("u", [""])[0]
        parts = urllib.parse.urlsplit(target)
        if parts.scheme != "https" or (parts.hostname or "").lower() not in ALLOWED_HOSTS or parts.port not in (None, 443):
            return self.fail(400, "host not allowed")

        headers = {name: self.headers[name] for name in FORWARDED if self.headers.get(name)}
        if parts.hostname.lower() != "rutracker.org":
            headers.pop("Cookie", None)
        request = urllib.request.Request(requote(target), headers=headers, method="GET")
        try:
            response = opener.open(request, timeout=UPSTREAM_TIMEOUT)
        except urllib.error.HTTPError as e:
            response = e
        except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError, OSError) as e:
            return self.fail(502, f"site unreachable: {type(getattr(e, 'reason', e)).__name__}")
        try:
            body = response.read(MAX_BYTES + 1)
        except (socket.timeout, TimeoutError, OSError):
            return self.fail(502, "site timed out")
        finally:
            response.close()
        if len(body) > MAX_BYTES:
            return self.fail(502, "page too large")

        status = response.status if hasattr(response, "status") else response.code
        try:
            chunk = int(self.headers.get("X-Relay-Chunk", "0"))
        except ValueError:
            chunk = 0
        if chunk:
            return self.send_split(body, chunk, status, response.headers.get("Content-Type"), response.headers.get("Location"))

        self.send_response(status)
        for name in ("Content-Type", "Location"):
            value = response.headers.get(name)
            if value:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path not in ("/s/open", "/s/w", "/s/c", "/log") + STATS_PATHS:
            return self.fail(404, "not found")
        if not hmac.compare_digest(self.headers.get("X-Relay-Key", "").encode(), SECRET):
            return self.fail(403, "bad key")
        if url.path == "/log":
            return self.receive_report()
        if url.path in STATS_PATHS:
            return self.forward_stats(url.path)
        query = urllib.parse.parse_qs(url.query)
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 <= length <= STREAM_PIECE_MAX:
            return self.fail(400, "bad length")
        body = self.rfile.read(length) if length else b""
        token = query.get("id", [""])[0]
        if url.path == "/s/open":
            host = query.get("h", [""])[0].lower()
            if not stream_host_allowed(host):
                return self.fail(400, "host not allowed")
            if not stream_limits.allow(self.client_address[0]):
                return self.fail(429, "too many requests")
            try:
                token = streams.open(host, self.client_address[0])
            except ValueError as e:
                return self.fail(400 if "allowed" in str(e) else 429, str(e))
            except OSError as e:
                return self.fail(502, f"site unreachable: {type(e).__name__}")
            return self.send_part(token.encode())
        if url.path == "/s/c":
            streams.close(token)
            return self.send_part(b"")
        stream = streams.get(token)
        if stream is None:
            return self.fail(410, "stream closed")
        try:
            stream.write(int(query.get("seq", ["-1"])[0]), body)
        except ValueError as e:
            return self.fail(400, str(e))
        except OSError:
            streams.close(token)
            return self.fail(410, "stream closed")
        return self.send_part(b"")

    def forward_stats(self, path):
        """Hands the body to kinostats as is; the client's address stays here (only in the rate limiter)."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= STATS_MAX:
            return self.fail(400, "bad length")
        body = self.rfile.read(length)
        if not stats_limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        request = urllib.request.Request(STATS_URL + path, data=body, method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                status, answer = response.status, response.read(4096)
        except urllib.error.HTTPError as e:
            status, answer = e.code, e.read(4096)
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError):
            return self.fail(404, "no statistics here")
        if status != 200:
            return self.fail(status, answer.decode("utf-8", "replace")[:200])
        return self.send_part(answer)

    def receive_report(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= REPORT_MAX:
            return self.fail(400, "bad length")
        body = self.rfile.read(length)
        if not report_limits.allow(self.client_address[0]):
            return self.fail(429, "too many reports")
        try:
            save_report(body)
        except OverflowError as e:
            return self.fail(429, str(e))
        except (ValueError, UnicodeDecodeError):
            return self.fail(400, "bad report")
        except OSError as e:
            return self.fail(500, f"cannot store: {type(e).__name__}")
        return self.send_part(b"")

    def stream_read(self, query):
        stream = streams.get(query.get("id", [""])[0])
        if stream is None:
            return self.fail(410, "stream closed")
        try:
            offset = int(query.get("off", ["0"])[0])
            size = min(max(int(query.get("size", ["8000"])[0]), 1), STREAM_PIECE_MAX)
            ack = int(query.get("ack", ["0"])[0])
            data, finished = stream.read(offset, size, ack)
        except ValueError as e:
            return self.fail(400, str(e))
        return self.send_part(data, [("X-Relay-Eof", "1")] if finished else [])

    def send_split(self, body, chunk, status, content_type, location=None):
        """Gzipped and in parts of [chunk] bytes; the first part answers, the rest wait in [pending]."""
        chunk = min(max(chunk, CHUNK_MIN), CHUNK_MAX)
        packed = gzip.compress(body, 6)
        pieces = [packed[i:i + chunk] for i in range(0, len(packed), chunk)] or [b""]
        meta = [("X-Relay-Status", str(status)), ("X-Relay-Parts", str(len(pieces)))]
        if len(pieces) > 1:
            meta.append(("X-Relay-Id", pending.put(pieces)))
        if content_type:
            meta.append(("X-Relay-Type", content_type))
        if location:
            meta.append(("X-Relay-Location", location))
        return self.send_part(pieces[0], meta)

    def index(self, url):
        """A list from the search index on this host, gzipped and in parts like every answer."""
        try:
            chunk = int(self.headers.get("X-Relay-Chunk", "0")) or 8_000
        except ValueError:
            return self.fail(400, "bad request")
        if not index_limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        target = INDEX_URL + url.path[2:] + ("?" + url.query if url.query else "")
        try:
            with urllib.request.urlopen(target, timeout=25) as response:
                status, body = response.status, response.read(INDEX_MAX_BYTES)
        except urllib.error.HTTPError as e:
            status, body = e.code, e.read(INDEX_MAX_BYTES)
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as e:
            return self.fail(503, f"index unavailable: {type(getattr(e, 'reason', e)).__name__}")
        return self.send_split(body, chunk, status, "application/json")

    def ratings(self, query):
        imdb = query.get("imdb", [""])[0]
        kp = query.get("kp", [""])[0]
        imdb = imdb if IMDB_ID.match(imdb) else ""
        kp = kp if KP_ID.match(kp) else ""
        if not imdb and not kp:
            return self.fail(400, "bad request")
        try:
            chunk = int(self.headers.get("X-Relay-Chunk", "0")) or 8_000
        except ValueError:
            return self.fail(400, "bad request")
        key = f"{imdb}|{kp}"
        body = ratings_cache.get(key)
        if body is None:
            if not ratings_limits.allow(self.client_address[0]):
                return self.fail(429, "too many requests")
            result, answered = film_ratings(imdb, kp)
            body = json.dumps(result).encode()
            # A service that did not answer is asked again on the next request, not in hours.
            if answered or not (OMDB_KEY or KP_KEY):
                ratings_cache.put(key, body, empty=not result)
        return self.send_split(body, chunk, 200, "application/json")

    def image(self, query):
        """A poster from an image host, scaled to the card; redirects are followed here, each checked."""
        if not image_limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        try:
            chunk = int(self.headers.get("X-Relay-Chunk", "0")) or 8_000
            width = min(max(int(query.get("w", [IMAGE_WIDTH])[0]), IMAGE_WIDTH_MIN), IMAGE_WIDTH_MAX)
        except ValueError:
            return self.fail(400, "bad request")
        target = requote(query.get("u", [""])[0])
        for _ in range(4):
            parts = urllib.parse.urlsplit(target)
            if parts.scheme not in ("http", "https") or parts.port not in (None, 80, 443) \
                    or not image_host_allowed(parts.hostname) or not public_address(parts.hostname):
                return self.fail(400, "host not allowed")
            request = urllib.request.Request(target, headers={"User-Agent": "Mozilla/5.0"}, method="GET")
            try:
                response = opener.open(request, timeout=UPSTREAM_TIMEOUT)
            except urllib.error.HTTPError as e:
                response = e
            except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError, OSError, ValueError) as e:
                return self.fail(502, f"site unreachable: {type(getattr(e, 'reason', e)).__name__}")
            status = response.status if hasattr(response, "status") else response.code
            if status in (301, 302, 303, 307, 308) and response.headers.get("Location"):
                target = requote(urllib.parse.urljoin(target, response.headers["Location"]))
                response.close()
                continue
            break
        else:
            return self.fail(502, "too many redirects")
        content_type = response.headers.get("Content-Type") or ""
        try:
            body = response.read(IMAGE_MAX_BYTES + 1)
        except (socket.timeout, TimeoutError, OSError):
            return self.fail(502, "site timed out")
        finally:
            response.close()
        if status != 200 or not content_type.startswith("image/"):
            return self.fail(502, f"not an image ({status})")
        if len(body) > IMAGE_MAX_BYTES:
            return self.fail(502, "image too large")
        body, shrunk_type = shrink(body, width)
        return self.send_split(body, chunk, 200, shrunk_type or content_type)


class Server(ThreadingHTTPServer):
    """HAProxy in front speaks the PROXY protocol: the first line carries the viewer's address.
    TLS is set up in the worker thread, so a slow client never holds up the others."""
    daemon_threads = True
    request_queue_size = 128

    def __init__(self, address, context):
        super().__init__(address, Handler)
        self.context = context

    def finish_request(self, request, client_address):
        request.settimeout(Handler.timeout)
        try:
            client_address = (read_proxy_header(request) or client_address[0], client_address[1])
            tls = self.context.wrap_socket(request, server_side=True)
        except (OSError, ValueError):
            return
        Handler(tls, client_address, self)


def read_proxy_header(sock):
    """'PROXY TCP4 <src> <dst> <sport> <dport>\r\n' (v1, at most 107 bytes); None without one."""
    head = sock.recv(6, socket.MSG_PEEK)
    if head != b"PROXY ":
        return None
    line = b""
    while not line.endswith(b"\r\n"):
        chunk = sock.recv(1)
        if not chunk or len(line) > 107:
            raise ValueError("bad PROXY header")
        line += chunk
    fields = line.decode("ascii", "replace").split()
    return fields[2] if len(fields) >= 3 and fields[1] in ("TCP4", "TCP6") else None


def main():
    host, port = os.environ.get("RELAY_BIND", "0.0.0.0:8443").rsplit(":", 1)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(os.environ["RELAY_CERT"], os.environ["RELAY_KEY_FILE"])
    server = Server((host, int(port)), context)
    print(f"kinorelay on {host}:{port}", file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
