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

No dependencies beyond the standard library. Settings come from the environment:
RELAY_BIND (host:port), RELAY_CERT, RELAY_KEY_FILE (TLS), RELAY_SECRET (shared app key).
"""
import gzip
import hmac
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

ALLOWED_HOSTS = {"rutor.info", "rutor.org.in", "rutor.org", "rutracker.org"}
MAX_BYTES = 4_000_000
UPSTREAM_TIMEOUT = 12
# Per viewer address: a home screen loads ~10 pages, a search ~20.
RATE_PER_MINUTE = 120
FORWARDED = ("User-Agent", "Accept-Language", "Cookie")
CHUNK_MIN, CHUNK_MAX = 2_000, 64_000
PARTS_TTL = 120

SECRET = os.environ["RELAY_SECRET"].encode()


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
        if url.path not in ("/r", "/p"):
            return self.fail(404, "not found")
        if not hmac.compare_digest(self.headers.get("X-Relay-Key", "").encode(), SECRET):
            return self.fail(403, "bad key")
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/p":
            try:
                part = pending.get(query.get("id", [""])[0], int(query.get("n", ["-1"])[0]))
            except ValueError:
                part = None
            return self.send_part(part) if part is not None else self.fail(404, "part expired")
        if not limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        target = query.get("u", [""])[0]
        parts = urllib.parse.urlsplit(target)
        if parts.scheme != "https" or (parts.hostname or "").lower() not in ALLOWED_HOSTS or parts.port not in (None, 443):
            return self.fail(400, "host not allowed")

        headers = {name: self.headers[name] for name in FORWARDED if self.headers.get(name)}
        if parts.hostname.lower() != "rutracker.org":
            headers.pop("Cookie", None)
        request = urllib.request.Request(target, headers=headers, method="GET")
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
            chunk = min(max(chunk, CHUNK_MIN), CHUNK_MAX)
            packed = gzip.compress(body, 6)
            pieces = [packed[i:i + chunk] for i in range(0, len(packed), chunk)] or [b""]
            meta = [("X-Relay-Status", str(status)), ("X-Relay-Parts", str(len(pieces)))]
            if len(pieces) > 1:
                meta.append(("X-Relay-Id", pending.put(pieces)))
            for name in ("Type", "Location"):
                value = response.headers.get("Content-Type" if name == "Type" else name)
                if value:
                    meta.append(("X-Relay-" + name, value))
            return self.send_part(pieces[0], meta)

        self.send_response(status)
        for name in ("Content-Type", "Location"):
            value = response.headers.get(name)
            if value:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


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
