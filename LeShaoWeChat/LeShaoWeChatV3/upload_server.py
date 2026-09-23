#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""8899 端口服务: 静态文件下载(APK) + 日志文件上传。
同一端口既保留原有下载功能, 又提供 /upload 上传页面。
"""
import os
import re
import email
import html
import uuid
import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.abspath(__file__))
UPLOAD_DIR = "/workspace/uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)

PAGE = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>上传 LSPosed 日志</title>
<style>
 body{{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#12140f;color:#e6e9e2;
      margin:0;padding:24px;display:flex;justify-content:center}}
 .card{{max-width:520px;width:100%;background:#1e211b;border:1px solid #3a3f36;
       border-radius:20px;padding:24px}}
 h1{{font-size:20px;margin:0 0 8px}}
 p{{color:#a9b0a3;font-size:14px;line-height:1.6}}
 input[type=file]{{width:100%;margin:16px 0;padding:14px;background:#262a22;color:#e6e9e2;
       border:1px dashed #4b5245;border-radius:12px;box-sizing:border-box}}
 button{{width:100%;padding:14px;border:0;border-radius:999px;background:#6cdbac;color:#062d20;
       font-size:16px;font-weight:600;cursor:pointer}}
 .files{{margin-top:18px;font-size:13px;color:#a9b0a3}}
 .files a{{color:#6cdbac;text-decoration:none}}
 .ok{{color:#6cdbac}} .err{{color:#ff8a80}}
</style></head><body>
<div class="card">
 <h1>上传 LSPosed 完整日志</h1>
 <p>选择 LSPosed 导出的日志文件(zip/txt/log 均可), 上传后我会读取分析微信分身唤醒问题。</p>
 <form method="POST" action="/upload" enctype="multipart/form-data">
   <input type="file" name="file" required>
   <button type="submit">上传</button>
 </form>
 <div class="files">{files}</div>
</div></body></html>"""


def list_files():
    try:
        rows = []
        for n in sorted(os.listdir(UPLOAD_DIR), reverse=True):
            p = os.path.join(UPLOAD_DIR, n)
            if os.path.isfile(p):
                size = os.path.getsize(p)
                rows.append('<div><a href="/uploads/%s">%s</a> (%d KB)</div>'
                            % (n, html.escape(n), size // 1024))
        return "已上传:<br>" + ("".join(rows) if rows else "暂无")
    except Exception as e:
        return "读取失败: %s" % html.escape(str(e))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def _serve_page(self, msg="", ok=True):
        body = PAGE.format(files=list_files()).replace(
            "<div class=\"files\">", '<div class="files"><div class="%s">%s</div>'
            % ("ok" if ok else "err", msg))
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/upload", "/upload/"):
            self._serve_page()
            return
        if path.startswith("/uploads/"):
            rel = path[len("/uploads/"):]
            target = os.path.realpath(os.path.join(UPLOAD_DIR, rel))
            if not target.startswith(os.path.realpath(UPLOAD_DIR)) or not os.path.isfile(target):
                self.send_error(404)
                return
            self._send_file(target)
            return
        super().do_GET()

    def _send_file(self, target):
        with open(target, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition",
                         'attachment; filename="%s"' % os.path.basename(target))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if urlparse(self.path).path not in ("/upload", "/upload/"):
            self.send_error(404)
            return
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            self._serve_page("请求格式不支持", ok=False)
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            self._serve_page("空请求", ok=False)
            return
        body = self.rfile.read(length)
        raw = (b"Content-Type: " + ctype.encode("latin-1")
               + b"\r\nMIME-Version: 1.0\r\n\r\n" + body)
        msg = email.message_from_bytes(raw)
        saved = []
        for part in msg.walk():
            fn = part.get_filename()
            if not fn:
                continue
            payload = part.get_payload(decode=True) or b""
            safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", os.path.basename(fn)) or "upload.bin"
            stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            name = "%s_%s" % (stamp, safe)
            with open(os.path.join(UPLOAD_DIR, name), "wb") as f:
                f.write(payload)
            saved.append(name)
        if saved:
            self._serve_page("上传成功: " + ", ".join(html.escape(s) for s in saved))
        else:
            self._serve_page("未找到文件字段", ok=False)

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt, *args):
        try:
            with open("/tmp/upload_server.log", "a") as f:
                f.write("%s - %s\n" % (self.log_date_time_string(), fmt % args))
        except Exception:
            pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8899"))
    srv = HTTPServer(("0.0.0.0", port), Handler)
    print("serving root=%s upload=%s on :%d" % (ROOT, UPLOAD_DIR, port), flush=True)
    srv.serve_forever()
