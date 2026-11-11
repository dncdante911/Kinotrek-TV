"""
TorrentKino (torrentkino.org) for kinoindex: a film catalogue whose releases are torrents of RuTracker and other
trackers, readable without an account and without Cloudflare's check. That is what makes it worth having: RuTracker's
own pages need the viewer's sign-in and a "not a robot" pass, which many viewers (MTS, old TVs) cannot get.

How the site is read, politely and with caching (its robots.txt asks crawlers to keep off /api/ and /search: this is
not a crawler, it reads only what a viewer searched for, a few pages per search, at most a few a second):
  * /api/search?q=<text>   films matching a title (~1.5 KB of JSON: slug, title, original title, year, Kinopoisk id);
  * /films/<slug>          the film page (~0.5 MB, Next.js): its releases ("sources": quality, size, seeders,
                           dubbing, seasons) and a schema.org block with the description, genres and countries;
  * /api/torrents/<id>?t=  the .torrent of one release (signed link from the film page, ~20 KB): its info hash.

Hits look like the other trackers' (see kinoindex.hit); the page of a release is the film page with "#<release id>",
so each release has a page of its own for the app's caches and the index's store.
"""
import hashlib
import json
import re
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

BASE = "https://torrentkino.org"
HOST = "torrentkino.org"
SOURCE = "TORRENTKINO"
FILMS_PER_SEARCH = 3
FILM_TTL = 900
FILM_CACHE_MAX = 200
# Film pages of one search are read side by side (each is half a megabyte): a search waits for the slowest one only.
pages_pool = ThreadPoolExecutor(max_workers=4)
FLIGHT = re.compile(r'self\.__next_f\.push\(\[1,(".*?")\]\)</script>', re.S)
IMDB = re.compile(r"imdb\.com/title/(tt\d{6,10})")
PAGE = re.compile(r"^https://torrentkino\.org/films/([a-z0-9-]{1,120})#(\d{1,12})$")
SIZE_UNITS = {"ТБ": "TB", "ГБ": "GB", "МБ": "MB", "КБ": "KB"}


def flight(html):
    """The Next.js data of a page, as one string (the page's JSON pieces are inside it)."""
    return "".join(json.loads(part) for part in FLIGHT.findall(html))


def json_after(data, marker):
    """The JSON value right after [marker] (e.g. '"sources":'), or None."""
    at = data.find(marker)
    if at < 0:
        return None
    try:
        value, _ = json.JSONDecoder().raw_decode(data, at + len(marker))
        return value
    except ValueError:
        return None


def schema_block(data):
    """The schema.org Movie / TVSeries object of a film page: description, genres, countries, poster."""
    for kind in ("Movie", "TVSeries"):
        at = data.find('{"@context":"https://schema.org","@type":"%s"' % kind)
        if at >= 0:
            try:
                return json.JSONDecoder().raw_decode(data, at)[0]
            except ValueError:
                pass
    return {}


class Film:
    def __init__(self, slug, sources, info, at):
        self.slug, self.sources, self.info, self.at = slug, sources, info, at


class TorrentKino:
    def __init__(self, fetch, fetch_bytes, pace, store):
        """[fetch](url) -> text and [fetch_bytes](url) -> bytes go through the index's own fetching (allowed hosts,
        user agent); [pace].wait(host) keeps the rate polite; [store] is the index's page store."""
        self.fetch, self.fetch_bytes, self.pace, self.store = fetch, fetch_bytes, pace, store
        self.films = {}
        self.lock = threading.Lock()

    # ---- reading ----

    def search_films(self, query, words, title_has):
        self.pace.wait(HOST)
        found = json.loads(self.fetch(f"{BASE}/api/search?q={urllib.parse.quote(query)}"))
        films = [f for f in (found.get("results") or []) if isinstance(f, dict) and f.get("slug")]
        # The site's search is fuzzy (a word finds similar words): keep the films whose title holds the query.
        matching = [f for f in films if title_has(f"{f.get('title', '')} {f.get('originalTitle', '')}", words)]
        return (matching or films[:1])[:FILMS_PER_SEARCH]

    def film(self, slug):
        """The film's releases and facts; cached for a while (the page is half a megabyte)."""
        with self.lock:
            known = self.films.get(slug)
        if known and time.time() - known.at < FILM_TTL:
            return known
        self.pace.wait(HOST)
        data = flight(self.fetch(f"{BASE}/films/{slug}"))
        sources = [s for s in (json_after(data, '"sources":') or []) if isinstance(s, dict) and s.get("id")]
        schema = schema_block(data)
        poster = str(schema.get("image") or "")
        imdb = IMDB.search(data)
        info = {
            "magnet": None,
            "poster": urllib.parse.urljoin(BASE, poster) if poster else "",
            "country": ", ".join(schema.get("countryOfOrigin") or [])[:160],
            "genre": ", ".join(schema.get("genre") or [])[:160],
            "story": str(schema.get("description") or "")[:1200],
            "imdb": imdb.group(1) if imdb else "",
            "kp": "",
        }
        film = Film(slug, sources, info, time.time())
        with self.lock:
            if len(self.films) >= FILM_CACHE_MAX:
                for old in sorted(self.films, key=lambda k: self.films[k].at)[:FILM_CACHE_MAX // 4]:
                    del self.films[old]
            self.films[slug] = film
        return film

    # ---- hits ----

    @staticmethod
    def title(meta, source):
        """A title the app's parser reads like a tracker's: name, year, seasons, quality, dubbing, studios."""
        name = str(meta.get("title") or "").strip()
        original = str(meta.get("originalTitle") or "").strip()
        head = name + (f" / {original}" if original and original.lower() != name.lower() else "")
        year = meta.get("year")
        season = ""
        first = source.get("seasonStart")
        if first:
            last = source.get("seasonEnd") or first
            season = f" [S{int(first):02d}" + (f"-S{int(last):02d}" if last != first else "") + "]"
            if source.get("episodeEnd"):
                total = source.get("episodesTotal") or "?"
                season += f" Серии: {source.get('episodeStart') or 1}-{source['episodeEnd']} из {total}"
        quality = str(source.get("qualityLabel") or source.get("quality") or "").strip()
        dubbing = str(source.get("translation") or "").strip()
        studios = ", ".join(source.get("sortedStudios") or [])[:90]
        parts = [p for p in (dubbing, studios) if p]
        return f"{head} ({year}){season} {quality}".strip() + "".join(f" | {p}" for p in parts)

    @staticmethod
    def size(text):
        text = str(text or "")
        for ru, en in SIZE_UNITS.items():
            text = text.replace(ru, en)
        return text

    def search(self, query, words, title_has, make_hit):
        """Releases of the films matching [query], as index hits. Pages are stored with the film's facts, so cards get
        their poster, genres, countries and ids, and a release's page answers /film."""
        hits = []
        films = self.search_films(query, words, title_has)

        def read(meta):
            try:
                return self.film(meta["slug"])
            except Exception:
                return None

        for meta, film in zip(films, pages_pool.map(read, films)):
            if film is None:
                continue
            info = dict(film.info, kp=str(meta.get("kinopoiskId") or "") if str(meta.get("kinopoiskId") or "").isdigit() else "")
            if not info["poster"] and meta.get("posterUrl"):
                info["poster"] = urllib.parse.urljoin(BASE, meta["posterUrl"])
            for source in film.sources:
                page = f"{BASE}/films/{film.slug}#{source['id']}"
                known = self.store.get([page]).get(page)
                if not known or known.get("story") != info["story"] or (known.get("poster") or "") != info["poster"]:
                    self.store.put(page, dict(info, magnet=(known or {}).get("magnet")))
                magnet = (known or {}).get("magnet")
                h = make_hit(f"tk:{source['id']}", self.title(meta, source), SOURCE, page, magnet,
                             self.size(source.get("size")), int(source.get("seeds") or 0), int(source.get("peers") or 0), 0, None)
                h["_tp"] = source.get("torrentPath")
                hits.append(h)
        return hits

    # ---- magnets ----

    def magnet(self, page, torrent_path=None):
        """The magnet of a release page ("…/films/<slug>#<id>"): from the store, or from its .torrent (kept)."""
        m = PAGE.match(page or "")
        if not m:
            raise ValueError("not a TorrentKino release")
        known = self.store.get([page]).get(page)
        if known and known.get("magnet"):
            return known["magnet"]
        if not torrent_path:
            # The signed link on a cached page may have expired: read the page anew.
            with self.lock:
                self.films.pop(m.group(1), None)
            source = next((s for s in self.film(m.group(1)).sources if str(s.get("id")) == m.group(2)), None)
            if source is None:
                raise ValueError("release not on the film page any more")
            torrent_path = source.get("torrentPath")
        if not torrent_path or not str(torrent_path).startswith("/api/torrents/"):
            raise ValueError("no torrent link")
        self.pace.wait(HOST)
        magnet = magnet_of(self.fetch_bytes(BASE + torrent_path))
        if known is not None:
            self.store.put(page, dict(known, magnet=magnet))
        return magnet


# ---- .torrent files ----

def _skip(data, i):
    """Index just past the bencoded value at [i]."""
    c = data[i:i + 1]
    if c == b"i":
        return data.index(b"e", i) + 1
    if c in (b"l", b"d"):
        i += 1
        while data[i:i + 1] != b"e":
            i = _skip(data, i)
        return i + 1
    if c.isdigit():
        colon = data.index(b":", i)
        return colon + 1 + int(data[i:colon])
    raise ValueError("not bencode")


def _decode(data, i):
    """(value, end) of the bencoded value at [i]: bytes, int, list or dict (keys as bytes)."""
    c = data[i:i + 1]
    if c == b"i":
        end = data.index(b"e", i)
        return int(data[i + 1:end]), end + 1
    if c == b"l":
        out, i = [], i + 1
        while data[i:i + 1] != b"e":
            value, i = _decode(data, i)
            out.append(value)
        return out, i + 1
    if c == b"d":
        out, i = {}, i + 1
        while data[i:i + 1] != b"e":
            key, i = _string_at(data, i)
            out[key], i = _decode(data, i)
        return out, i + 1
    return _string_at(data, i)


def _string_at(data, i):
    colon = data.index(b":", i)
    end = colon + 1 + int(data[i:colon])
    return data[colon + 1:end], end


def magnet_of(torrent):
    """A magnet link (info hash, name, the torrent's trackers) for a .torrent file."""
    if not torrent.startswith(b"d"):
        raise ValueError("not a torrent")
    i, info, announce, announces, name = 1, None, [], [], ""
    while torrent[i:i + 1] != b"e":
        key, i = _string_at(torrent, i)
        end = _skip(torrent, i)
        if key == b"info":
            info = torrent[i:end]
            fields, _ = _decode(torrent, i)
            if isinstance(fields.get(b"name"), bytes):
                name = fields[b"name"].decode("utf-8", "replace")
        elif key == b"announce":
            announce = [_string_at(torrent, i)[0].decode("utf-8", "replace")]
        elif key == b"announce-list":
            tiers, _ = _decode(torrent, i)
            announces = [t.decode("utf-8", "replace") for tier in tiers if isinstance(tier, list) for t in tier if isinstance(t, bytes)]
        i = end
    if info is None:
        raise ValueError("torrent without info")
    digest = hashlib.sha1(info).hexdigest()
    trackers = list(dict.fromkeys(announce + announces))[:6]
    return f"magnet:?xt=urn:btih:{digest}" + (f"&dn={urllib.parse.quote(name)}" if name else "") + "".join(f"&tr={urllib.parse.quote(t, safe='')}" for t in trackers)
