"""Exercise the demo service over real loopback HTTP, including a lost response."""
import hashlib
import http.client
import json
import socket
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from mock_reports import create_server


class ReportContractTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.server = create_server("127.0.0.1", 0, Path(self.temporary.name) / "reports.sqlite")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, path, text=None, key=None, drop=False):
        headers = {"Content-Type": "text/plain; charset=utf-8"}
        if key:
            headers["Idempotency-Key"] = key
        if drop:
            headers["X-Demo-Drop-Response"] = "once"
        req = urllib.request.Request(self.base + path, data=None if text is None else text.encode(), headers=headers)
        try:
            response = self.client.open(req, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)

    def test_same_key_same_body_replays_original_response_and_does_not_write_twice(self):
        first = self.request("/reports", "# 报告", "stable-key")
        second = self.request("/reports", "# 报告", "stable-key")
        self.assertEqual(first, second)
        status, found = self.request("/reports/by-key/stable-key")
        self.assertEqual(200, status)
        self.assertEqual(first[1], found)
        self.assertEqual(hashlib.sha256("# 报告".encode()).hexdigest(), found["sha256"])
        stats = self.request("/stats")[1]
        self.assertEqual(1, stats["reportCount"])
        self.assertEqual(1, stats["writes"])

    def test_conflicting_body_is_rejected_without_overwriting_original(self):
        self.request("/reports", "original", "stable-key")
        self.assertEqual(409, self.request("/reports", "changed", "stable-key")[0])
        self.assertEqual("original", self.request("/reports/by-key/stable-key")[1]["report"])

    def test_response_loss_preserves_queryable_committed_result(self):
        with self.assertRaises((http.client.RemoteDisconnected, urllib.error.URLError, ConnectionResetError)):
            self.request("/reports", "saved-before-disconnect", "lost-response", drop=True)
        self.assertEqual("saved-before-disconnect", self.request("/reports/by-key/lost-response")[1]["report"])
        replay = self.request("/reports", "saved-before-disconnect", "lost-response", drop=True)
        self.assertEqual(200, replay[0])
        self.assertEqual(1, self.request("/stats")[1]["writes"])

    def test_missing_key_is_explicit_and_post_requires_idempotency_key(self):
        self.assertEqual(404, self.request("/reports/by-key/missing")[0])
        self.assertEqual(400, self.request("/reports", "no-key")[0])
        self.assertEqual(0, self.request("/stats")[1]["writes"])

    def test_interrupted_request_body_never_commits_a_truncated_report(self):
        with socket.create_connection(self.server.server_address, timeout=3) as connection:
            connection.sendall(b"POST /reports HTTP/1.1\r\nHost: localhost\r\nIdempotency-Key: partial\r\nContent-Length: 8\r\nConnection: close\r\n\r\nabc")
            connection.shutdown(socket.SHUT_WR)
            response = connection.recv(4096)
        self.assertIn(b"400", response.split(b"\r\n", 1)[0])
        self.assertEqual(404, self.request("/reports/by-key/partial")[0])
        stats = self.request("/stats")[1]
        self.assertEqual(0, stats["writes"])
        self.assertEqual(0, stats["postCalls"])


if __name__ == "__main__":
    unittest.main()
