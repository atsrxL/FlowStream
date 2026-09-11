#!/usr/bin/env python3
"""Tests only the bundled fixture, not the Android network implementation."""
import base64
from http.server import ThreadingHTTPServer
import http.client
from pathlib import Path
import tempfile
import threading
import xml.etree.ElementTree as ET
from webdav_fixture import dav_handler

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / 'A+B 中文.mp4').write_bytes(bytes(range(256)) * 32)
    (root / 'folder').mkdir()
    server = ThreadingHTTPServer(('127.0.0.1', 0), dav_handler(root, 'demo', 'demo-only'))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    def request(method, path='/dav/', headers=None):
        conn = http.client.HTTPConnection(*server.server_address, timeout=5)
        conn.request(method, path, headers=headers or {})
        response = conn.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        conn.close()
        return result
    auth = {'Authorization': 'Basic ' + base64.b64encode(b'demo:demo-only').decode()}
    try:
        status, _, _ = request('PROPFIND')
        assert status == 401
        print('PASS fixture rejects unauthenticated request')
        status, _, body = request('PROPFIND', headers={**auth, 'Depth': '1'})
        assert status == 207
        xml = ET.fromstring(body)
        assert len(xml.findall('{DAV:}response')) == 3
        hrefs = [e.text for e in xml.findall('.//{DAV:}href')]
        assert '/dav/A%2BB%20%E4%B8%AD%E6%96%87.mp4' in hrefs
        print('PASS fixture multistatus and UTF-8 paths')
        file = '/dav/A%2BB%20%E4%B8%AD%E6%96%87.mp4'
        status, headers, body = request('GET', file, {**auth, 'Range': 'bytes=257-270'})
        assert status == 206 and body == bytes(range(1, 15))
        assert headers['Content-Range'] == 'bytes 257-270/8192'
        print('PASS fixture byte-range seek')
        status, _, _ = request('GET', file, {**auth, 'Range': 'bytes=99999-'})
        assert status == 416
        print('PASS fixture range beyond EOF')
        status, _, _ = request('GET', '/dav/%2e%2e/etc/passwd', auth)
        assert status == 403
        print('PASS fixture rejects path traversal')
        status, headers, body = request('HEAD', file, auth)
        assert status == 200 and headers['Content-Length'] == '8192' and body == b''
        print('PASS fixture HEAD without response body')
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
print('ALL 6 fixture checks passed. Android client interoperability is NOT tested by these checks.')
