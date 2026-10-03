"""Loopback-only demonstration of a durable, queryable idempotency contract.

Run: python scripts/mock_reports.py --port 18082 --database target/demo-reports.sqlite
It deliberately drops one response when X-Demo-Drop-Response: once is supplied.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import sqlite3
import time
from urllib.parse import parse_qs, unquote, urlsplit

MAX_BODY = 256 * 1024


def create_server(host, port, database):
    database = Path(database).resolve()
    database.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database) as connection:
        connection.executescript("""
            CREATE TABLE IF NOT EXISTS reports (
                operation_key TEXT PRIMARY KEY, digest TEXT NOT NULL,
                response TEXT NOT NULL, drop_consumed INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
        """)

    def count(connection, name):
        connection.execute("INSERT INTO counters(name,value) VALUES (?,1) ON CONFLICT(name) DO UPDATE SET value=value+1", (name,))

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def handle(self):
            try:
                super().handle()
            except (BrokenPipeError, ConnectionResetError):
                # The process-kill and lost-response scenarios deliberately disconnect peers.
                pass

        def send(self, status, value):
            raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            parsed = urlsplit(self.path)
            if parsed.path == "/health":
                return self.send(200, {"status": "UP", "mode": "mock"})
            with sqlite3.connect(database, timeout=5) as connection:
                if parsed.path == "/stats":
                    values = {name: value for name, value in connection.execute("SELECT name,value FROM counters")}
                    values["reportCount"] = connection.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
                    for name in ("writes", "postCalls", "lookups", "metricsCalls", "slowCalls"):
                        values.setdefault(name, 0)
                    return self.send(200, values)
                if parsed.path.startswith("/reports/by-key/"):
                    count(connection, "lookups")
                    key = unquote(parsed.path.removeprefix("/reports/by-key/"))
                    row = connection.execute("SELECT response FROM reports WHERE operation_key=?", (key,)).fetchone()
                    connection.commit()
                    return self.send(200, json.loads(row[0])) if row else self.send(404, {"status": "MISSING"})
                if parsed.path in ("/metrics", "/slow"):
                    count(connection, "metricsCalls" if parsed.path == "/metrics" else "slowCalls")
                    connection.commit()
                else:
                    return self.send(404, {"message": "Unknown endpoint"})
            if parsed.path == "/slow":
                try:
                    delay = int(parse_qs(parsed.query).get("ms", ["1500"])[0])
                except ValueError:
                    return self.send(400, {"message": "ms must be an integer"})
                time.sleep(max(0, min(delay, 30000)) / 1000)
                return self.send(200, {"mode": "mock", "result": "slow step completed"})
            self.send(200, {"mode": "mock", "period": "demo-week", "requests": 1200, "errorRate": 0.02, "p95LatencyMs": 180})

        def do_POST(self):
            if urlsplit(self.path).path != "/reports":
                return self.send(404, {"message": "Unknown endpoint"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self.send(400, {"message": "Invalid Content-Length"})
            if length < 0 or length > MAX_BODY:
                self.close_connection = True
                return self.send(413, {"message": "Report exceeds 256 KiB"})
            raw = self.rfile.read(length)
            if len(raw) != length:
                self.close_connection = True
                return self.send(400, {"message": "Incomplete report body"})
            key = self.headers.get("Idempotency-Key", "")
            if not key or len(key) > 200:
                return self.send(400, {"message": "Idempotency-Key is required (max 200 characters)"})
            try:
                report = raw.decode("utf-8")
            except UnicodeDecodeError:
                return self.send(400, {"message": "Report must be UTF-8"})
            digest = hashlib.sha256(raw).hexdigest()
            drop = False
            with sqlite3.connect(database, timeout=5) as connection:
                connection.execute("BEGIN IMMEDIATE")
                count(connection, "postCalls")
                row = connection.execute("SELECT digest,response,drop_consumed FROM reports WHERE operation_key=?", (key,)).fetchone()
                if row and row[0] != digest:
                    connection.commit()
                    return self.send(409, {"message": "Same idempotency key with different request"})
                if row:
                    response = json.loads(row[1])
                else:
                    response = {"key": key, "report": report, "sha256": digest, "mode": "mock"}
                    connection.execute("INSERT INTO reports(operation_key,digest,response) VALUES (?,?,?)", (key, digest, json.dumps(response, ensure_ascii=False)))
                    count(connection, "writes")
                if self.headers.get("X-Demo-Drop-Response") == "once" and (not row or not row[2]):
                    connection.execute("UPDATE reports SET drop_consumed=1 WHERE operation_key=?", (key,))
                    drop = True
                connection.commit()
            if drop:
                self.close_connection = True
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.connection.close()
                return
            self.send(200, response)

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--database", type=Path, default=Path("target/demo-reports.sqlite"))
    parser.add_argument("--ready-file", type=Path)
    args = parser.parse_args()
    server = create_server("127.0.0.1", args.port, args.database)
    ready = json.dumps({"url": "http://127.0.0.1:" + str(server.server_port), "mode": "mock"})
    if args.ready_file:
        args.ready_file.parent.mkdir(parents=True, exist_ok=True)
        args.ready_file.write_text(ready, encoding="utf-8")
    print(ready, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
