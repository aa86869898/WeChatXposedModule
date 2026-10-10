#!/usr/bin/env python3
"""LeShaoWeChat 静态文件服务器：强制 .md 以 UTF-8 编码返回，避免浏览器按 GBK 解码中文乱码。"""
import http.server
import socketserver

PORT = 8731


class MarkdownHandler(http.server.SimpleHTTPRequestHandler):
    def guess_type(self, path):
        base = super().guess_type(path)
        if path.endswith(".md"):
            return "text/markdown; charset=utf-8"
        if base.startswith("text/"):
            return base + "; charset=utf-8"
        return base


class ThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    with ThreadingTCPServer(("0.0.0.0", PORT), MarkdownHandler) as httpd:
        print("Serving on 0.0.0.0:" + str(PORT))
        httpd.serve_forever()