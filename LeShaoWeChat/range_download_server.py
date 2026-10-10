#!/usr/bin/env python3
"""Range-capable static file server (thread-pool + resumable/multi-segment downloads).

Usage:
    python3 range_http_server.py [PORT] [ROOT]

Env:
    HTTP_POOL   max worker threads (default 32)
    HTTP_CHUNK  write chunk bytes (default 262144)
"""
import html
import mimetypes
import os
import posixpath
import re
import sys
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8085
ROOT = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.getcwd())
POOL = int(os.environ.get("HTTP_POOL", "32"))
CHUNK = int(os.environ.get("HTTP_CHUNK", "262144"))


def parse_range(header, size):
    """Return (start, end) inclusive, or (-1, None) if unsatisfiable, or None if not a valid range."""
    m = re.match(r"bytes=(\d*)-(\d*)\s*$", header or "")
    if not m:
        return None
    s, e = m.group(1), m.group(2)
    if s == "" and e == "":
        return None
    if s == "":
        suffix = int(e)
        if suffix <= 0:
            return (-1, None)
        start = max(0, size - suffix)
        end = size - 1
    else:
        start = int(s)
        end = int(e) if e != "" else size - 1
    if size == 0 or start >= size:
        return (-1, None)
    end = min(end, size - 1)
    if end < start:
        return (-1, None)
    return (start, end)


class Handler(BaseHTTPRequestHandler):
    server_version = "RangeHTTP/1.1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))
        sys.stderr.flush()

    def _resolve(self):
        path = urllib.parse.unquote(self.path.split("?", 1)[0])
        path = posixpath.normpath(path)
        parts = [p for p in path.split("/") if p and p != "."]
        if any(p == ".." for p in parts):
            return None
        return os.path.join(ROOT, *parts)

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only):
        fs = self._resolve()
        if fs is None:
            self.send_error(400, "Bad path")
            return
        if os.path.isdir(fs):
            index = os.path.join(fs, "index.html")
            if os.path.isfile(index):
                fs = index
            else:
                self._list_dir(fs, head_only)
                return
        if not os.path.isfile(fs):
            self.send_error(404, "Not Found")
            return

        size = os.path.getsize(fs)
        ctype = mimetypes.guess_type(fs)[0] or "application/octet-stream"
        if ctype.startswith("text/") and "charset" not in ctype:
            ctype += "; charset=utf-8"
        ext = os.path.splitext(fs)[1].lower()
        download = ext in (".apk", ".apks", ".xapk", ".zip", ".jar", ".exe", ".rar", ".7z")
        rng = self.headers.get("Range")

        if rng is not None:
            parsed = parse_range(rng, size)
            if parsed is None:
                rng = None
            elif parsed[0] == -1:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            else:
                start, end = parsed
                self.send_response(206)
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                if not head_only:
                    self._stream(fs, start, end)
                return

        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(size))
        if download:
            self.send_header("Content-Disposition",
                             'attachment; filename="%s"' % os.path.basename(fs))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        if not head_only:
            self._stream(fs, 0, size - 1)

    def _stream(self, fs, start, end):
        remaining = end - start + 1
        with open(fs, "rb") as f:
            f.seek(start)
            while remaining > 0:
                data = f.read(min(CHUNK, remaining))
                if not data:
                    break
                try:
                    self.wfile.write(data)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                    return
                remaining -= len(data)

    def _list_dir(self, fs, head_only):
        try:
            names = sorted(os.listdir(fs))
        except OSError:
            self.send_error(403, "Forbidden")
            return
        rows = []
        for name in names:
            full = os.path.join(fs, name)
            label = name + ("/" if os.path.isdir(full) else "")
            if os.path.isfile(full):
                try:
                    label += "  (%d bytes)" % os.path.getsize(full)
                except OSError:
                    pass
            rows.append('<li><a href="%s">%s</a></li>'
                        % (urllib.parse.quote(name), html.escape(label)))
        body = ("<html><head><meta charset='utf-8'><title>Index of %s</title></head>"
                "<body><h1>Index of %s</h1><ul>%s</ul></body></html>"
                % (html.escape(self.path), html.escape(self.path), "".join(rows))).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        if not head_only:
            self.wfile.write(body)


class ThreadPoolHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, handler, pool_size):
        super().__init__(addr, handler)
        self._pool = ThreadPoolExecutor(max_workers=pool_size)

    def process_request(self, request, client_address):
        self._pool.submit(self._process_request, request, client_address)

    def _process_request(self, request, client_address):
        try:
            self.finish_request(request, client_address)
        except Exception:
            self.handle_error(request, client_address)
        finally:
            self.shutdown_request(request)

    def server_close(self):
        try:
            super().server_close()
        finally:
            self._pool.shutdown(wait=False)


if __name__ == "__main__":
    if not os.path.isdir(ROOT):
        sys.exit("ROOT not a directory: %s" % ROOT)
    srv = ThreadPoolHTTPServer(("0.0.0.0", PORT), Handler, POOL)
    sys.stderr.write("Serving %s on 0.0.0.0:%d (thread pool=%d, Range/resume enabled)\n"
                     % (ROOT, PORT, POOL))
    sys.stderr.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
