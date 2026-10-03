"""Verify the packaged server with real child-process termination and HTTP peers.

Build first: mvn clean verify
Run: python scripts/recovery_smoke.py
For MySQL, set FLOWTRAIL_TEST_DB_URL / USER / PASSWORD and pass --mysql.
Only a dedicated loopback database whose name ends in _test is accepted.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

from mock_reports import create_server

ROOT = Path(__file__).resolve().parents[1]
CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}))
TERMINAL = {"SUCCEEDED", "FAILED", "MANUAL_REVIEW", "CANCELLED"}


def call(base, path, body=None, *, method=None, headers=None, expected=200):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method or ("POST" if body is not None else "GET"),
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        response = CLIENT.open(req, timeout=20)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        assert response.status == expected, (path, response.status, raw.decode("utf-8", "replace")[:1000])
        return json.loads(raw) if raw else None


def until(action, predicate, timeout=30):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = action()
        if predicate(last):
            return last
        time.sleep(0.08)
    raise AssertionError("Condition timed out: " + repr(last)[:1000])


def read_events(base, run_id, after=0, limit=None):
    req = urllib.request.Request(base + "/api/runs/" + run_id + "/events", headers={"Accept": "text/event-stream", "Last-Event-ID": str(after)})
    events, data, event_id = [], [], None
    with CLIENT.open(req, timeout=35) as response:
        for raw in response:
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith("id:"):
                event_id = int(line[3:].strip())
            elif line.startswith("data:"):
                data.append(line[5:].lstrip())
            elif not line and data:
                value = json.loads("\n".join(data))
                assert event_id == value["seq"] and value["runId"] == run_id
                events.append(value)
                data, event_id = [], None
                if limit and len(events) >= limit:
                    break
    return events


class Service:
    def __init__(self, directory, mysql):
        self.directory = directory
        self.mysql = mysql
        self.log = None
        self.process = None
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        self.base = "http://127.0.0.1:" + str(self.port)
        java_home = os.environ.get("JAVA_HOME")
        self.java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"

    def start(self):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith(("FLOWTRAIL_DB_", "SPRING_DATASOURCE_", "FLOWTRAIL_MODEL_")):
                env.pop(key)
        args = [self.java, "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-jar", str(self.directory / "server.jar"),
                "--server.address=127.0.0.1", "--server.port=" + str(self.port), "--flowtrail.runtime.lease-seconds=2"]
        if self.mysql:
            url = os.environ["FLOWTRAIL_TEST_DB_URL"]
            if not re.match(r"^jdbc:mysql://(?:127\.0\.0\.1|localhost):\d+/[a-zA-Z0-9_]+_test(?:\?|$)", url):
                raise ValueError("Use a dedicated loopback MySQL database ending in _test")
            env["FLOWTRAIL_DB_URL"] = url
            env["FLOWTRAIL_DB_USER"] = os.environ.get("FLOWTRAIL_TEST_DB_USER", "root")
            env["FLOWTRAIL_DB_PASSWORD"] = os.environ.get("FLOWTRAIL_TEST_DB_PASSWORD", "")
            args.append("--spring.profiles.active=mysql")
        self.log = (self.directory / "server.log").open("ab")
        self.process = subprocess.Popen(args, cwd=self.directory, env=env, stdout=self.log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 50
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError("Server exited; see " + str(self.directory / "server.log"))
            try:
                if call(self.base, "/api/health")["status"] == "UP":
                    return
            except OSError:
                pass
            time.sleep(0.15)
        raise AssertionError("Server startup timed out")

    def stop(self, force=False):
        if self.process and self.process.poll() is None:
            self.process.kill() if force else self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        if self.log:
            self.log.close()
            self.log = None

    def run(self, definition, inputs=None, key=None):
        workflow = call(self.base, "/api/workflows", definition, expected=201)
        path = "/api/workflows/" + workflow["id"] + "/runs"
        run = call(self.base, path, {"inputs": inputs or {}}, headers={"Idempotency-Key": key} if key else {}, expected=202)
        return workflow, run

    def result(self, run):
        return until(lambda: call(self.base, "/api/runs/" + run["id"]), lambda value: value["status"] in TERMINAL)


def exercise(service, reports):
    evidence = {}
    workflow, run = service.run({"name": "SSE and idempotent creation", "nodes": [
        {"id": "answer", "type": "LLM", "modelRef": "mock-demo", "userPrompt": "${input.document}"}
    ]}, {"document": "mock stream " * 40}, "request-key")
    replay = call(service.base, "/api/workflows/" + workflow["id"] + "/runs", {"inputs": {"document": "mock stream " * 40}}, headers={"Idempotency-Key": "request-key"}, expected=202)
    assert replay["id"] == run["id"]
    call(service.base, "/api/workflows/" + workflow["id"] + "/runs", {"inputs": {"document": "changed"}}, headers={"Idempotency-Key": "request-key"}, expected=409)
    call(service.base, "/api/workflows/" + workflow["id"] + "/runs", {"inputs": {}}, expected=400)
    first = read_events(service.base, run["id"], limit=3)
    assert service.result(run)["status"] == "SUCCEEDED"
    rest = read_events(service.base, run["id"], after=first[-1]["seq"])
    events = first + rest
    assert [event["seq"] for event in events] == list(range(1, len(events) + 1))
    assert events[-1]["type"] == "RUN_SUCCEEDED"
    assert any(event["type"] == "LLM_DELTA" for event in events)
    evidence["asyncCreationAndSseReplay"] = {"events": len(events), "duplicateRunCreated": False}

    before = call(reports, "/stats")
    _, run = service.run({"name": "Committed checkpoint survives kill", "nodes": [
        {"id": "first", "type": "HTTP", "url": reports + "/metrics"},
        {"id": "slow", "type": "HTTP", "dependsOn": ["first"], "url": reports + "/slow?ms=3000", "timeoutMs": 10000},
        {"id": "join", "type": "TEXT", "dependsOn": ["slow"], "text": "${first.output} | ${slow.output}"}
    ]})
    until(lambda: call(reports, "/stats"), lambda stats: stats["slowCalls"] > before["slowCalls"])
    snapshot = call(service.base, "/api/runs/" + run["id"])
    assert snapshot["nodes"][0]["status"] == "SUCCEEDED"
    service.stop(force=True)
    service.start()
    completed = service.result(run)
    assert completed["status"] == "SUCCEEDED", completed
    after = call(reports, "/stats")
    assert after["metricsCalls"] == before["metricsCalls"] + 1, "Committed HTTP node was replayed"
    assert completed["nodes"][0]["attemptId"] == 1
    assert completed["nodes"][1]["attemptId"] == 2
    history = call(service.base, "/api/runs/" + run["id"] + "/events/history")
    assert sum(event["type"] == "NODE_STARTED" and event["nodeId"] == "join" for event in history) == 1
    evidence["processKillAndCheckpointReuse"] = {"committedNodeCalls": 1, "interruptedNodeAttempts": 2, "joinExecutions": 1}

    _, stream = service.run({"name": "Interrupted model attempt", "nodes": [
        {"id": "model", "type": "LLM", "modelRef": "mock-demo", "userPrompt": "${input.document}"}
    ]}, {"document": "token " * 1000})
    until(lambda: call(service.base, "/api/runs/" + stream["id"] + "/events/history"), lambda values: any(event["type"] == "LLM_DELTA" for event in values))
    service.stop(force=True)
    service.start()
    completed = service.result(stream)
    assert completed["status"] == "SUCCEEDED"
    attempts = completed["nodes"][0]["attempts"]
    assert len(attempts) == 2 and attempts[0]["status"] == "INTERRUPTED" and attempts[1]["status"] == "SUCCEEDED", attempts
    history = call(service.base, "/api/runs/" + stream["id"] + "/events/history")
    assert {event["attemptId"] for event in history if event["type"] == "LLM_DELTA"} == {1, 2}
    evidence["interruptedModelAttempts"] = {"attempts": 2, "oldAttemptStatus": "INTERRUPTED"}

    for supported in (True, False):
        before = call(reports, "/stats")
        node = {"id": "save", "type": "HTTP", "url": reports + "/reports", "method": "POST", "body": "# Durable report", "headers": {"X-Demo-Drop-Response": "once"}}
        if supported:
            node["idempotency"] = {"supported": True, "lookupUrl": reports + "/reports/by-key/{key}"}
        _, run = service.run({"name": "Lost response with lookup=" + str(supported), "nodes": [node]})
        completed = service.result(run)
        expected = "SUCCEEDED" if supported else "MANUAL_REVIEW"
        assert completed["status"] == expected, completed
        if not supported:
            call(service.base, "/api/runs/" + run["id"] + "/resume", method="POST", expected=202)
            assert service.result(run)["status"] == "MANUAL_REVIEW"
        after = call(reports, "/stats")
        assert after["writes"] - before["writes"] == 1
        assert after["postCalls"] - before["postCalls"] == 1, "Unknown write was resent"
        evidence["lostResponseWithLookup" if supported else "unknownWriteManualReview"] = {"writes": 1, "postCalls": 1, "status": expected}
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql", action="store_true")
    args = parser.parse_args()
    archive = ROOT / "target" / "flowtrail-server.jar"
    assert archive.is_file(), "Run mvn clean verify first"
    mode = "mysql" if args.mysql else "h2"
    result_path = ROOT / "target" / ("recovery-report-" + mode + ".json")
    with tempfile.TemporaryDirectory(prefix="recovery-", dir=ROOT / "target") as directory:
        directory = Path(directory)
        shutil.copyfile(archive, directory / "server.jar")
        reports = create_server("127.0.0.1", 0, directory / "reports.sqlite")
        thread = threading.Thread(target=reports.serve_forever, daemon=True)
        thread.start()
        service = Service(directory, args.mysql)
        try:
            service.start()
            evidence = exercise(service, "http://127.0.0.1:" + str(reports.server_port))
            result = {"timestamp": datetime.now(timezone.utc).isoformat(), "database": mode, "modelMode": "mock", "processTermination": "kill", "checks": evidence}
            result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(result, ensure_ascii=False))
        except BaseException:
            service.stop(force=True)
            if (directory / "server.log").exists():
                shutil.copyfile(directory / "server.log", ROOT / "target" / ("recovery-failure-" + mode + ".log"))
            raise
        finally:
            service.stop()
            reports.shutdown()
            reports.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    main()
