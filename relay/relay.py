#!/usr/bin/env python3
"""Kinotrek relay: fetches tracker pages for viewers whose provider blocks the trackers.

GET /r?u=<https url>  with header X-Relay-Key. Only the tracker sites below, only GET, no
redirects followed (the app follows them itself, through the relay again). The answer is the
site's own status, Content-Type, Location and body. Errors of the relay itself are 4xx/502 with
an X-Relay-Error header, so the app can tell them from the site's answers.

No dependencies beyond the standard library. Settings come from the environment:
RELAY_BIND (host:port), RELAY_CERT, RELAY_KEY_FILE (TLS), RELAY_SECRET (shared app key).
"""
import hmac
import os
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

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/health":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path != "/r":
            return self.fail(404, "not found")
        if not hmac.compare_digest(self.headers.get("X-Relay-Key", "").encode(), SECRET):
            return self.fail(403, "bad key")
        if not limits.allow(self.client_address[0]):
            return self.fail(429, "too many requests")
        target = urllib.parse.parse_qs(url.query).get("u", [""])[0]
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

        self.send_response(response.status if hasattr(response, "status") else response.code)
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
