#!/usr/bin/env python3
"""
Kinotrek's TV guide, built on the server so a TV box downloads ~1 MB instead of three guides of 9-90 MB.

Sources (all open XMLTV): epg.pw (Russian channels; writes UTC+8 times marked +0000, corrected as the app did),
epgshare01's UA1 (Ukrainian channels), iptvx.one's full EPG (4000+ channels, honest +0300 times, descriptions).
Only the channels of the app's own playlists (iptv-org in Ukrainian/Russian and of UA/RU/BY/KZ, Free-TV) are kept,
from two hours ago to 24 hours ahead, under the app's name key (Iptv.nameKey), so the app looks a channel up as is.

    python3 kinoguide.py build OUT.json.gz     builds the file (run niced in a child process by kinoindex)

Output: gzip of {"v": 1, "made": unix, "ch": {"<name key>": [[start, stop, "title", "description"?], ...]}}.
"""
import calendar
import gzip
import io
import json
import os
import re
import ssl
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

UA = "Mozilla/5.0 (Linux) Kinotrek guide"
CTX = ssl.create_default_context()
BASE = "https://iptv-org.github.io/iptv/"
# The app's playlists, without world music (those channels have no guide here anyway).
PLAYLISTS = [BASE + p for p in ("languages/ukr.m3u", "languages/rus.m3u", "countries/ua.m3u", "countries/ru.m3u",
                                "countries/by.m3u", "countries/kz.m3u")]
FREE_TV_COUNTRIES = ("Ukraine", "Russia", "Belarus", "Kazakhstan")
FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
# The app's second Ukrainian list (2.8.9; the app takes only its open CDNs, any of its names may have a guide).
UA_EXTRA = "https://raw.githubusercontent.com/slava-67/TV/refs/heads/IPTV/ua.m3u"
SOURCES = [
    # (name, url, skip "(+N)" copies, has trustworthy offsets)
    ("iptvx", "https://iptvx.one/EPG", True, True),
    ("epgpw", "https://epg.pw/xmltv/epg_RU.xml.gz", False, False),
    ("ua1", "https://epgshare01.online/epgshare01/epg_ripper_UA1.xml.gz", True, True),
]
BEFORE_S = 2 * 3600
AHEAD_S = 24 * 3600
DESC_CHARS = 200
# The relay passes at most 2 MB of an index answer.
MAX_FILE = 1_800_000
SHIFTED_COPY = re.compile(r"\((?:\+|-|−)\d")

# ---- names: the same keys as the app (Iptv.nameKey, Epg.spelling) ----

TRANSLIT = dict(zip("абвгґдеёєжзиіїйклмнопрстуфхцчшщъыьэюя",
                    ["a", "b", "v", "g", "g", "d", "e", "e", "e", "zh", "z", "i", "i", "i", "i", "k", "l", "m", "n", "o", "p", "r",
                     "s", "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "i", "", "e", "iu", "ia"]))
VOWELS = "аеёєиіїоуыэюя"
UKRAINE_WORDS = "ukraine|ukraina|україна|украина"
RUSSIA_WORDS = "russia|rossiya|rossia|россия|росія"
NOISE = re.compile(r"(?<![^\W_])(hd|fhd|uhd|4k|sd|tv|тв|channel|канал|kanal|international|int|" + UKRAINE_WORDS + "|" + RUSSIA_WORDS + r"|orig)(?![^\W_])")
# A country as the first word is the name itself ("Россия 24" is "ru24", not "24 Канал").
LEADING_COUNTRY = re.compile(r"^[\W_]*(?:(" + UKRAINE_WORDS + ")|(" + RUSSIA_WORDS + r"))(?![^\W_])")


def name_key(name, fold=True):
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", name.lower())
    lead = LEADING_COUNTRY.match(s)
    prefix = ""
    if lead:
        prefix = "ua" if lead.group(1) else "ru"
        s = s[lead.end():]
    s = NOISE.sub(" ", s)
    out = []
    for i, c in enumerate(s):
        if c in "ьъ" and i + 1 < len(s) and s[i + 1] in VOWELS:
            out.append("i")
        else:
            out.append(TRANSLIT.get(c, c))
    s = "".join(out).replace("kh", "h").replace("ts", "c").replace("y", "i").replace("j", "i").replace("w", "v").replace("x", "ks")
    s = prefix + re.sub(r"[^a-z0-9+]", "", s)
    # Only doubled letters fold: "11" is not "1", "555" is not "5".
    return re.sub(r"([a-z])\1+", r"\1", s) if fold else s


def spelling(key):
    key = key.replace("auto", "avto").replace("liu", "lu").replace("ciber", "kiber").replace("drive", "draiv").replace("ibal", "ibol")
    return re.sub(r"c(?=[aoulrtk]|$)", "k", key).replace("ou", "u")


def match(wanted, guide_keys):
    """Playlist key -> guide key: the same, the same spelled the English way, or the only close one by prefix."""
    found = {}
    folded = {}
    for g in guide_keys:
        folded.setdefault(spelling(g), g)
    known = [g for g in guide_keys if len(g) >= 4]
    for k in wanted:
        if k in guide_keys:
            found[k] = k
        elif spelling(k) in folded:
            found[k] = folded[spelling(k)]
        elif len(k) >= 5:
            close = [g for g in known if (g.startswith(k) or k.startswith(g)) and abs(len(g) - len(k)) <= 3]
            if close:
                best = min(close, key=lambda g: abs(len(g) - len(k)))
                if sum(1 for g in close if abs(len(g) - len(k)) == abs(len(best) - len(k))) == 1:
                    found[k] = best
    return found

# ---- inputs ----


def fetch(url, path=None, timeout=120):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=timeout, context=CTX) as r:
        modified = r.headers.get("Last-Modified")
        if path is None:
            return r.read(30_000_000), modified
        with open(path, "wb") as f:
            while True:
                block = r.read(1 << 20)
                if not block:
                    break
                f.write(block)
        return None, modified


OLD_NOISE = re.compile(r"(?<![^\W_])(hd|fhd|uhd|4k|sd|tv|тв|channel|канал|kanal|international|int|ukraine|ukraina|україна|russia|россия|orig)(?![^\W_])")


def old_name_key(name):
    """The key apps before 2.8.9 look a channel up by: country words dropped anywhere, digits folded too."""
    s = OLD_NOISE.sub(" ", re.sub(r"\(.*?\)|\[.*?\]", " ", name.lower()))
    out = []
    for i, c in enumerate(s):
        out.append("i" if c in "ьъ" and i + 1 < len(s) and s[i + 1] in VOWELS else TRANSLIT.get(c, c))
    s = "".join(out).replace("kh", "h").replace("ts", "c").replace("y", "i").replace("j", "i").replace("w", "v").replace("x", "ks")
    return re.sub(r"(.)\1+", r"\1", re.sub(r"[^a-z0-9+]", "", s))


# Old key -> new key of the same name, for the copies older apps need (filled by playlist_keys).
LEGACY = {}


def playlist_keys():
    """Name key -> the same names without folding doubled letters (to tell "НТВ" from "ННТВ")."""
    keys = {}
    for url in PLAYLISTS + [FREE_TV, UA_EXTRA]:
        try:
            text = fetch(url)[0].decode("utf8", "replace")
        except Exception as e:
            print("playlist", url, type(e).__name__, e, file=sys.stderr)
            continue
        for line in text.splitlines():
            if line.startswith("#EXTINF"):
                if url == FREE_TV and not any(f'group-title="{c}"' in line for c in FREE_TV_COUNTRIES):
                    continue
                name = line.rsplit(",", 1)[-1]
                name = re.sub(r"[ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏ]", "", name)
                name = re.sub(r"\S*\*\S*", " ", name)  # "5 Канал HD fas-tv*com": the list's credits
                key = name_key(name)
                if key:
                    keys.setdefault(key, set()).add(name_key(name, fold=False))
                    old = old_name_key(name)
                    if old and old != key:
                        LEGACY.setdefault(old, key)
    return keys


TIME = re.compile(r"(\d{14})\s*([+-]\d{4})?")


def when(text, error_s):
    m = TIME.match(text or "")
    if not m:
        return None
    t = time.strptime(m.group(1), "%Y%m%d%H%M%S")
    offset = m.group(2) or "+0000"
    seconds = int(offset[1:3]) * 3600 + int(offset[3:5]) * 60
    utc = calendar.timegm(t) - (seconds if offset[0] == "+" else -seconds)
    return utc - error_s


def zone_error(tv_date, published, generator):
    """epg.pw writes UTC+8 marked +0000: the <tv date> next to the real Last-Modified tells the error (as the app did)."""
    if tv_date is not None and published:
        hours = round((tv_date - published) / 3600)
        if -14 <= hours <= 14:
            return hours * 3600
    return 8 * 3600 if generator == "epg.pw" else 0


UA_MARK = re.compile(r"\[ua|\(укра|україн|(^|[.\-_])ua($|[.\-_])", re.I)
FOREIGN_MARK = re.compile(r"\[(?!ua|ru)[a-z]{2}\]|(^|[.\-_])(am|il|ee|kz|by|ge|az|uz|md|lv|lt|tj|kg|tm|pl|de|us|it|fr|es|tr|gb|nl|cz|sk|ro|"
                          r"hu|bg|rs|hr|in|ar)($|[.\-_])|армени|эстони|казах|беларус|белорус|грузи|азербайд|узбек|молдов|латви|литв|израил", re.I)
CYRILLIC = re.compile(r"[а-яёіїєґ]", re.I)


def country_rank(cid, raw_names):
    """
    Which of the guide channels sharing a key is the app's: 0 marked Ukrainian, 1 with a Cyrillic name ("Iнтер"),
    2 Latin only ("Inter TV" is Italy's), 3 marked with another country (not Russia).
    """
    text = " ".join([cid or ""] + list(raw_names))
    if UA_MARK.search(text):
        return 0
    if FOREIGN_MARK.search(text):
        return 3
    return 1 if CYRILLIC.search(" ".join(raw_names)) else 2


def read_guide(path, wanted, skip_shifted, honest, published, now):
    """Programmes of the wanted channels inside the window: playlist key -> [[start, stop, title, desc]]."""
    names = {}
    shows = {}
    ids = None
    error = 0
    lo, hi = now - BEFORE_S, now + AHEAD_S
    with gzip.open(path) as source:
        return _read(source, wanted, skip_shifted, honest, published, now)


def _read(source, wanted, skip_shifted, honest, published, now):
    names = {}
    shows = {}
    ids = None
    error = 0
    lo, hi = now - BEFORE_S, now + AHEAD_S
    context = ET.iterparse(source, events=("start", "end"))
    _, root = next(context)
    if root.tag == "tv" and not honest:
        error = zone_error(when(root.get("date"), 0), published, root.get("generator-info-name"))
    for event, el in context:
        if event != "end":
            continue
        if el.tag == "channel":
            for dn in el.findall("display-name"):
                text = dn.text or ""
                if skip_shifted and SHIFTED_COPY.search(text):
                    continue
                key = name_key(text)
                if key:
                    names.setdefault(key, []).append((el.get("id"), name_key(text, fold=False), text))
            root.clear()
        elif el.tag == "programme":
            if ids is None:
                # The channels are all read: which of them the playlists want, under the playlists' keys.
                ids = {}
                names_of = {}
                for options in names.values():
                    for cid, _, raw in options:
                        names_of.setdefault(cid, []).append(raw)
                for k, g in match(wanted, set(names)).items():
                    # "НТВ" and "ННТВ" share the key "ntv" (doubled letters fold): the one spelled like the playlist wins.
                    # Several countries' "5 канал" share the key "5" too: Ukraine's first, a foreign-tagged one last.
                    choices = sorted(names[g], key=lambda c: country_rank(c[0], names_of.get(c[0], ())))
                    strict = wanted.get(k, set())
                    chosen = next((cid for cid, sk, _ in choices if sk == k), None) or \
                        next((cid for cid, sk, _ in choices if sk in strict), choices[0][0])
                    ids.setdefault(chosen, []).append(k)
            cid = el.get("channel")
            if cid in ids:
                start, stop = when(el.get("start"), error), when(el.get("stop"), error)
                title = (el.findtext("title") or "").strip()
                if start and stop and title and stop > lo and start < hi:
                    item = [start, stop, title]
                    desc = " ".join((el.findtext("desc") or "").split())
                    if desc:
                        if len(desc) > DESC_CHARS:
                            desc = desc[:DESC_CHARS].rsplit(" ", 1)[0] + "…"
                        item.append(desc)
                    shows.setdefault(cid, []).append(item)
            root.clear()
    out = {}
    for cid, keys in (ids or {}).items():
        if cid in shows:
            ordered = sorted(shows[cid])
            for k in keys:
                out.setdefault(k, ordered)
    return out


def build(target):
    now = int(time.time())
    wanted = playlist_keys()
    if not wanted:
        raise RuntimeError("no playlists")
    guides = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, url, skip_shifted, honest in SOURCES:
            path = os.path.join(tmp, name + ".gz")
            try:
                _, modified = fetch(url, path, timeout=300)
                published = int(parsedate_to_datetime(modified).timestamp()) if modified else 0
                guides.append((name, read_guide(path, wanted, skip_shifted, honest, published, now)))
                print(f"guide: {name} has {len(guides[-1][1])} channels", flush=True)
            except Exception as e:
                print("guide", name, type(e).__name__, e, file=sys.stderr)
            finally:
                if os.path.exists(path):
                    os.remove(path)
    channels = {}
    for key in wanted:
        options = [(name, g[key]) for name, g in guides if key in g]
        if not options:
            continue
        most = max(len(s) for _, s in options)
        # The guide with descriptions when it is about as full as the fullest one; else the fullest.
        described = [s for _, s in options if any(len(i) > 3 for i in s) and len(s) >= 0.7 * most]
        channels[key] = described[0] if described else max((s for _, s in options), key=len)
    # Apps before 2.8.9 still ask by the old keys ("1" for "Россия 1"): a copy under the old key, unless a channel
    # already has that key now ("24" stays "24 Канал", the channel the new keys give it to).
    legacy = 0
    for old, key in LEGACY.items():
        if old not in channels and key in channels:
            channels[old] = channels[key]
            legacy += 1
    print(f"guide: {legacy} copies under pre-2.8.9 keys", flush=True)
    limit = DESC_CHARS
    while True:
        # Too big for the relay: shorter descriptions, at last none.
        def cut(item):
            if len(item) < 4:
                return item
            if limit == 0:
                return item[:3]
            desc = item[3] if len(item[3]) <= limit else item[3][:limit].rsplit(" ", 1)[0] + "…"
            return item[:3] + [desc]
        body = {"v": 1, "made": now, "ch": {k: [cut(i) for i in v] for k, v in channels.items()}}
        packed = gzip.compress(json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode(), 9)
        if len(packed) <= MAX_FILE or limit == 0:
            break
        limit = 0 if limit <= 60 else limit // 2
    tmp = target + ".tmp"
    with open(tmp, "wb") as f:
        f.write(packed)
    os.replace(tmp, target)
    print(f"guide: {len(channels)} channels of {len(wanted)}, {len(packed)} bytes, descriptions up to {limit}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "build":
        build(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
