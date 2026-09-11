#!/usr/bin/env python3
"""Read-only local WebDAV fixture for testing directory listing and Range playback.
Not a production service. No third-party packages. Defaults to loopback only.
"""
from __future__ import annotations
import argparse
import base64
import hmac
import mimetypes
import os
from pathlib import Path
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit
import xml.etree.ElementTree as ET

ET.register_namespace('d', 'DAV:')

def dav_handler(root: Path, username: str, password: str, allow_delete: bool = False):
    root = root.resolve(strict=True)
    token = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, fmt, *args):
            # Do not log URLs, filenames, auth headers, or credentials.
            pass
        def authorize(self) -> bool:
            if hmac.compare_digest(self.headers.get('Authorization', ''), token):
                return True
            self.send_response(401)
            self.send_header('WWW-Authenticate', 'Basic realm="local-fixture", charset="UTF-8"')
            self.send_header('Content-Length', '0')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            return False
        def resolve(self) -> Path | None:
            raw = unquote(urlsplit(self.path).path)
            if raw != '/dav' and not raw.startswith('/dav/'):
                self.send_error(404)
                return None
            rel = raw[4:].lstrip('/')
            if '\\' in rel or '..' in Path(rel).parts:
                self.send_error(403)
                return None
            try:
                path = (root / rel).resolve(strict=True)
                path.relative_to(root)
                return path
            except (FileNotFoundError, ValueError, OSError):
                self.send_error(404)
                return None
        def do_PROPFIND(self):
            if not self.authorize():
                return
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > 65536:
                self.send_error(413)
                self.close_connection = True
                return
            self.rfile.read(length)
            path = self.resolve()
            if path is None:
                return
            if self.headers.get('Depth', '1') != '1' or not path.is_dir():
                self.send_error(405)
                return
            children = sorted(path.iterdir())
            if len(children) > 10000:
                self.send_error(507)
                return
            tree = ET.Element('{DAV:}multistatus')
            for item in [path, *children]:
                try:
                    # Exclude symlinks whose resolved target is outside the fixture.
                    item.resolve(strict=True).relative_to(root)
                    rel = item.relative_to(root).as_posix()
                    rel = '' if rel == '.' else rel
                    response = ET.SubElement(tree, '{DAV:}response')
                    href = '/dav/' + quote(rel, safe='/')
                    if item.is_dir() and not href.endswith('/'):
                        href += '/'
                    ET.SubElement(response, '{DAV:}href').text = href
                    propstat = ET.SubElement(response, '{DAV:}propstat')
                    prop = ET.SubElement(propstat, '{DAV:}prop')
                    kind = ET.SubElement(prop, '{DAV:}resourcetype')
                    if item.is_dir():
                        ET.SubElement(kind, '{DAV:}collection')
                    ET.SubElement(prop, '{DAV:}displayname').text = item.name
                    ET.SubElement(prop, '{DAV:}getcontentlength').text = str(item.stat().st_size if item.is_file() else 0)
                    ET.SubElement(propstat, '{DAV:}status').text = 'HTTP/1.1 200 OK'
                except (OSError, ValueError):
                    continue
            body = ET.tostring(tree, encoding='utf-8', xml_declaration=True)
            self.send_response(207)
            self.send_header('Content-Type', 'application/xml; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_DELETE(self):
            if not self.authorize():
                return
            path = self.resolve()
            if path is None:
                return
            if not allow_delete or path.name.startswith('protected-'):
                self.send_error(403)
                return
            if not path.is_file() or path.is_symlink():
                self.send_error(404)
                return
            path.unlink()
            self.send_response(204)
            self.send_header('Content-Length', '0')
            self.end_headers()
        def do_HEAD(self):
            self.serve_file(head=True)
        def do_GET(self):
            self.serve_file(head=False)
        def serve_file(self, head: bool):
            if not self.authorize():
                return
            path = self.resolve()
            if path is None:
                return
            if not path.is_file():
                self.send_error(405)
                return
            size = path.stat().st_size
            start, end = 0, size - 1
            partial = self.headers.get('Range')
            if partial:
                m = re.fullmatch(r'bytes=(\d+)-(\d*)', partial)
                if not m:
                    self.send_error(416)
                    return
                start = int(m[1])
                end = min(int(m[2]), end) if m[2] else end
                if start >= size or end < start:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{size}')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
            self.send_response(206 if partial else 200)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Type', mimetypes.guess_type(path.name)[0] or 'application/octet-stream')
            self.send_header('Content-Length', str(max(0, end - start + 1)))
            if partial:
                self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            if head:
                return
            try:
                with path.open('rb') as f:
                    f.seek(start)
                    left = end - start + 1
                    while left > 0:
                        data = f.read(min(65536, left))
                        if not data:
                            break
                        self.wfile.write(data)
                        left -= len(data)
            except (BrokenPipeError, ConnectionResetError):
                pass
    return Handler

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8089)
    parser.add_argument('--allow-delete', action='store_true', help='Allow deletion of fixture files only')
    args = parser.parse_args()
    if not args.root.is_dir():
        parser.error('--root must be an existing directory')
    server = ThreadingHTTPServer((args.bind, args.port), dav_handler(args.root, os.getenv('FLOW_USER', 'demo'), os.getenv('FLOW_PASSWORD', 'demo-only'), args.allow_delete))
    print(f'Local test fixture listening on {args.bind}:{args.port}; WebDAV path /dav/.')
    print('HTTP only; trusted development network only. Press Ctrl+C to stop.')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == '__main__':
    main()
