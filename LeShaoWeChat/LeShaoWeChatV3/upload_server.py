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
STATIC_ROOT = os.path.join(ROOT, "download")
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
 <hr style="border-color:#3a3f36;margin:22px 0">
 <h1>超大文件分片上传（≤1GB，断点续传，推荐）</h1>
 <p>文件切成 8MB 小片逐片上传，网络中断后<b>刷新页面会自动续传</b>，不用重头传。适合 174MB 以上大文件。</p>
 <input type="file" id="chunkfile">
 <button id="chunkbtn" type="button" onclick="chunkUpload()">分片上传</button>
 <div id="chunkprog" class="files"></div>
 <hr style="border-color:#3a3f36;margin:22px 0">
 <h1>普通表单上传（≤1GB）</h1>
 <p>小文件直接用表单上传。</p>
 <form method="POST" action="/upload" enctype="multipart/form-data">
   <input type="file" name="file" required>
   <button type="submit">表单上传</button>
 </form>
 <div class="files">{files}</div>
</div>
<script>
const CHUNK_SIZE = 8*1024*1024;
async function chunkUpload(){
  const f = document.getElementById('chunkfile').files[0];
  if(!f){ alert('请先选择文件'); return; }
  const total = Math.ceil(f.size / CHUNK_SIZE);
  const prog = document.getElementById('chunkprog');
  const btn = document.getElementById('chunkbtn');
  prog.textContent = '文件: ' + f.name + ' (' + (f.size/1048576).toFixed(1) + ' MB, 共 ' + total + ' 片)';
  let start = 0;
  try {
    const st = await (await fetch('/upload_status/' + encodeURIComponent(f.name))).json();
    start = st.received || 0;
    if(start > 0) prog.textContent += ' → 检测到已传 ' + start + ' 片，续传中...';
  } catch(e) {}
  btn.disabled = true;
  try {
    for(let i = start; i < total; i++){
      const blob = f.slice(i*CHUNK_SIZE, Math.min((i+1)*CHUNK_SIZE, f.size));
      const r = await fetch('/upload_chunk/' + encodeURIComponent(f.name) + '/' + i, {method:'PUT', body: blob});
      if(!r.ok){ prog.textContent = '分片 ' + (i+1) + '/' + total + ' 失败(HTTP ' + r.status + ')，刷新页面可续传'; return; }
      prog.textContent = '上传分片 ' + (i+1) + '/' + total + ' (' + ((i+1-start)/total*100).toFixed(0) + '%) ...';
    }
    const mr = await fetch('/upload_merge/' + encodeURIComponent(f.name) + '?total=' + total, {method:'POST'});
    prog.textContent = '完成: ' + await mr.text();
  } catch(e) {
    prog.textContent = '网络中断: ' + e + '（刷新页面可续传）';
  } finally {
    btn.disabled = false;
  }
}
</script>
</body></html>"""


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
    # 大文件上传/下载需要足够长的读写超时（1 小时）
    timeout = 3600

    def __init__(self, *a, **k):
        # 只暴露 download 子目录（APK 与文档），避免项目根源码/签名密钥被公网下载
        super().__init__(*a, directory=STATIC_ROOT, **k)

    def _serve_page(self, msg="", ok=True):
        body = PAGE.replace("{files}", list_files()).replace(
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
        if path.startswith("/upload_status/"):
            raw_name = os.path.basename(path)
            safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", raw_name) or "upload.bin"
            chunk_dir = os.path.join(UPLOAD_DIR, ".chunks", safe)
            received = 0
            if os.path.isdir(chunk_dir):
                received = len([n for n in os.listdir(chunk_dir) if n.isdigit()])
            data = ('{"received": %d}' % received).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path.startswith("/uploads/"):
            rel = path[len("/uploads/"):]
            target = os.path.realpath(os.path.join(UPLOAD_DIR, rel))
            if not target.startswith(os.path.realpath(UPLOAD_DIR)) or not os.path.isfile(target):
                self.send_error(404)
                return
            self._send_file(target)
            return
        # 静态文件：大文件（APK/ZIP 等）走支持 Range 的超高速发送，其他（html/md）交 SimpleHTTPRequestHandler
        rel = path.lstrip("/")
        if rel.endswith((".apk", ".zip", ".bin", ".jar", ".mp3", ".wav", ".ogg", ".dex")):
            target = os.path.realpath(os.path.join(STATIC_ROOT, rel))
            if target.startswith(os.path.realpath(STATIC_ROOT)) and os.path.isfile(target):
                self._send_file(target)
                return
        super().do_GET()

    def _send_file(self, target):
        # 支持 Range（断点续传/多线程超高速下载），下载页的并发分段请求可正常加速
        size = os.path.getsize(target)
        start = 0
        end = size - 1
        status = 200
        rng = self.headers.get("Range")
        if rng:
            m = re.search(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */%d" % size)
                    self.end_headers()
                    return
                if end >= size:
                    end = size - 1
                status = 206
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition",
                         'attachment; filename="%s"' % os.path.basename(target))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        with open(target, "rb") as f:
            if start > 0:
                f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def _handle_merge(self, path):
        raw_name = os.path.basename(path)
        q = urlparse(self.path).query
        total = 0
        m = re.search(r'total=(\d+)', q)
        if m:
            total = int(m.group(1))
        if total <= 0 or total > 100000:
            self.send_error(400, "bad total")
            return
        safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", raw_name) or "upload.bin"
        chunk_dir = os.path.join(UPLOAD_DIR, ".chunks", safe)
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        name = "%s_%s" % (stamp, safe)
        target = os.path.join(UPLOAD_DIR, name)
        ok = True
        try:
            with open(target, "wb") as f:
                for i in range(total):
                    p = os.path.join(chunk_dir, str(i))
                    if not os.path.isfile(p):
                        ok = False
                        break
                    with open(p, "rb") as cf:
                        while True:
                            b = cf.read(1024 * 1024)
                            if not b:
                                break
                            f.write(b)
        except Exception:
            ok = False
        try:
            import shutil
            shutil.rmtree(chunk_dir, ignore_errors=True)
        except Exception:
            pass
        if ok:
            size_mb = os.path.getsize(target) // (1024 * 1024)
            self._serve_page("合并上传成功: " + html.escape(name) + " (%d MB)" % size_mb)
        else:
            try:
                os.remove(target)
            except OSError:
                pass
            self._serve_page("分片不完整，请重新上传", ok=False)

    def do_POST(self):
        path = urlparse(self.path).path
        if path.startswith("/upload_merge/"):
            self._handle_merge(path)
            return
        if path not in ("/upload", "/upload/"):
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
        if length > 1024 * 1024 * 1024:
            self._serve_page("文件过大（上限 1GB）", ok=False)
            return
        # 流式解析 multipart/form-data（分块写盘，内存占用极小，支持 1GB 大文件）
        m = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', ctype)
        boundary = (m.group(1) or m.group(2)).encode()
        if not boundary:
            self._serve_page("缺少 boundary", ok=False)
            return
        # 读取 part 头部（直到 \r\n\r\n），解析文件名
        head = b""
        while b"\r\n\r\n" not in head:
            ch = self.rfile.read(1)
            if not ch:
                break
            head += ch
            if len(head) > 65536:
                break
        fm = re.search(r'filename="([^"]*)"', head.decode("latin-1", errors="replace"))
        if not fm:
            self._serve_page("未找到文件字段", ok=False)
            return
        fn = fm.group(1)
        safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", os.path.basename(fn)) or "upload.bin"
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        name = "%s_%s" % (stamp, safe)
        target = os.path.join(UPLOAD_DIR, name)
        end_marker = b"\r\n--" + boundary
        buf = b""
        saved = False
        with open(target, "wb") as f:
            while True:
                chunk = self.rfile.read(1024 * 1024)
                if not chunk:
                    break
                buf += chunk
                idx = buf.find(end_marker)
                if idx >= 0:
                    f.write(buf[:idx])
                    saved = True
                    break
                keep = len(end_marker) - 1
                if len(buf) > keep:
                    f.write(buf[:-keep])
                    buf = buf[-keep:]
        if saved:
            self._serve_page("上传成功: " + html.escape(name) + " (%d MB)" % (length // (1024 * 1024)))
        else:
            try:
                os.remove(target)
            except OSError:
                pass
            self._serve_page("上传不完整，请重新上传", ok=False)

    def _handle_chunk_put(self, path):
        # 分片上传：PUT /upload_chunk/<文件名>/<index>，body 为单片字节（≤64MB），存入 .chunks 目录
        parts = path[len("/upload_chunk/"):].split("/", 1)
        if len(parts) != 2:
            self.send_error(400, "bad path")
            return
        raw_name = parts[0]
        try:
            index = int(parts[1])
        except ValueError:
            self.send_error(400, "bad index")
            return
        if index < 0 or index > 100000:
            self.send_error(400, "bad index")
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > 64 * 1024 * 1024:
            self.send_error(400, "bad chunk size")
            return
        safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", os.path.basename(raw_name)) or "upload.bin"
        chunk_dir = os.path.join(UPLOAD_DIR, ".chunks", safe)
        try:
            os.makedirs(chunk_dir, exist_ok=True)
        except OSError:
            pass
        target = os.path.join(chunk_dir, str(index))
        remaining = length
        with open(target, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                f.write(chunk)
                remaining -= len(chunk)
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def do_PUT(self):
        # 分片上传：PUT /upload_chunk/<文件名>/<index>
        path = urlparse(self.path).path
        if path.startswith("/upload_chunk/"):
            self._handle_chunk_put(path)
            return
        # 旧式整体流式上传：PUT /upload/<文件名>（保留兼容）
        prefix = "/upload/"
        if not path.startswith(prefix):
            self.send_error(404)
            return
        raw_name = os.path.basename(path)
        safe = re.sub(r"[^\w.\-()\u4e00-\u9fff]+", "_", raw_name) or "upload.bin"
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            self.send_error(400, "empty body")
            return
        if length > 1024 * 1024 * 1024:
            self.send_error(413, "file too large (>1GB)")
            return
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        name = "%s_%s" % (stamp, safe)
        target = os.path.join(UPLOAD_DIR, name)
        remaining = length
        try:
            with open(target, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)
        except Exception as e:
            try:
                os.remove(target)
            except OSError:
                pass
            self.send_error(500, "write error: %s" % e)
            return
        self._serve_page("上传成功(流式): " + html.escape(name) + " (%d MB)" % (length // (1024 * 1024)))

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
