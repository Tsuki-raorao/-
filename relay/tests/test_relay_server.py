"""Exercise the actual HTTP boundary and SQLite persistence without real credentials."""

from __future__ import annotations

import http.client
import json
import logging
import socket
import sys
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from relay_server import Config, RelayHTTPServer  # noqa: E402


TOKENS = {role: "test-only-" + role + "-" + "x" * 40 for role in ("user", "codex", "dot")}


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = {
            "RELAY_DB_PATH": str(Path(self.temp.name) / "messages.sqlite3"),
            **{f"RELAY_TOKEN_{role.upper()}": token for role, token in TOKENS.items()},
        }

    def test_required_private_configuration(self):
        config = Config.from_env(self.env)
        self.assertEqual(config.port, 8765)
        self.assertNotIn(TOKENS["dot"], repr(config))
        for key in self.env:
            with self.subTest(missing=key):
                missing = self.env.copy()
                del missing[key]
                with self.assertRaises(ValueError):
                    Config.from_env(missing)

    def test_distinct_long_tokens_existing_parent_and_valid_port(self):
        for changes in (
            {"RELAY_TOKEN_DOT": TOKENS["user"]},
            {"RELAY_TOKEN_DOT": "short"},
            {"RELAY_TOKEN_DOT": " " * 40},
            {"RELAY_DB_PATH": str(Path(self.temp.name) / "missing" / "db.sqlite3")},
            {"RELAY_PORT": "0"},
            {"RELAY_PORT": "65536"},
            {"RELAY_PORT": "not-a-port"},
        ):
            with self.subTest(fields=list(changes)):
                with self.assertRaises(ValueError):
                    Config.from_env(self.env | changes)


class RelayHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        logging.getLogger("relay").setLevel(logging.CRITICAL)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.db_path = Path(self.temp.name) / "messages.sqlite3"
        self.web_path = Path(self.temp.name) / "web"
        self.web_path.mkdir()
        (self.web_path / "index.html").write_text("<!doctype html><title>Relay</title>", encoding="utf-8")
        (self.web_path / "app.js").write_text("'use strict';", encoding="utf-8")
        self.start_server()
        self.addCleanup(self.stop_server)

    def start_server(self, **options):
        self.server = RelayHTTPServer(Config(self.db_path, TOKENS, port=0), static_dir=self.web_path, **options)
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def stop_server(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=2)
            self.server = None

    def request(self, method="GET", path="/api/messages", role="user", payload=None, raw=None, headers=None):
        request_headers = {}
        if role is not None:
            request_headers["Authorization"] = "Bearer " + TOKENS.get(role, role)
        if payload is not None:
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request_headers.update(headers or {})
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=8)
        try:
            connection.request(method, path, body=raw, headers=request_headers)
            response = connection.getresponse()
            body = response.read()
            response_headers = dict(response.getheaders())
            data = json.loads(body) if response_headers.get("Content-Type", "").startswith("application/json") else body
            return response.status, data, response_headers
        finally:
            connection.close()

    def post(self, text, role="user", identifier=None):
        return self.request("POST", role=role, payload={"text": text, "client_message_id": identifier or str(uuid.uuid4())})

    def test_authentication_roles_and_safe_public_health(self):
        status, health, _ = self.request(path="/healthz", role=None)
        self.assertEqual((status, health), (200, {"status": "ok"}))
        for role in (None, "invalid-token"):
            for method in ("GET", "POST"):
                self.assertEqual(self.request(method, role=role, payload={})[0], 401)
        for role in TOKENS:
            self.assertEqual(self.request(path="/api/me", role=role)[:2], (200, {"role": role}))
            status, record, _ = self.post("hello from " + role, role=role)
            self.assertEqual(status, 201)
            self.assertEqual(record["sender"], role)
        self.assertEqual(len(self.request(role="dot")[1]["messages"]), 3)

    def test_client_cannot_impersonate_a_role(self):
        status, _, _ = self.request("POST", role="dot", payload={
            "text": "spoof", "client_message_id": str(uuid.uuid4()), "sender": "user"
        })
        self.assertEqual(status, 400)
        self.assertEqual(self.request()[1]["messages"], [])

    def test_idempotent_retry_conflict_and_sender_scoping(self):
        identifier = str(uuid.uuid4())
        status, original, _ = self.post("same", identifier=identifier)
        self.assertEqual(status, 201)
        self.assertEqual(self.post("same", identifier=identifier)[:2], (200, original))
        self.assertEqual(self.post("changed", identifier=identifier)[0], 409)
        self.assertEqual(self.post("same", role="dot", identifier=identifier)[0], 201)
        self.assertEqual(len(self.request()[1]["messages"]), 2)

    def test_cursor_pagination_has_no_gaps_and_preserves_empty_cursor(self):
        records = [self.post(f"message {number}")[1] for number in range(5)]
        cursor = 0
        received = []
        more_flags = []
        while True:
            status, page, _ = self.request(path=f"/api/messages?after={cursor}&limit=2")
            self.assertEqual(status, 200)
            received.extend(page["messages"])
            cursor = page["next_cursor"]
            more_flags.append(page["has_more"])
            if not page["has_more"]:
                break
        self.assertEqual(received, records)
        self.assertEqual(more_flags, [True, True, False])
        self.assertEqual(self.request(path=f"/api/messages?after={cursor}")[1], {
            "messages": [], "next_cursor": cursor, "has_more": False,
        })
        fresh = self.post("later")[1]
        self.assertEqual(self.request(path=f"/api/messages?after={cursor}")[1]["messages"], [fresh])

    def test_concurrent_inserts_and_duplicate_retries(self):
        shared_id = str(uuid.uuid4())
        jobs = [(f"distinct {number}", str(uuid.uuid4())) for number in range(12)]
        jobs += [("concurrent duplicate", shared_id)] * 12
        with ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(lambda job: self.post(job[0], identifier=job[1]), jobs))
        self.assertEqual(sum(status == 201 for status, _, _ in results), 13)
        self.assertEqual(sum(status == 200 for status, _, _ in results), 11)
        page = self.request()[1]
        self.assertEqual(len(page["messages"]), 13)
        sequences = [record["seq"] for record in page["messages"]]
        self.assertEqual(sequences, sorted(set(sequences)))
        duplicate_sequences = {record["seq"] for _, record, _ in results[12:]}
        self.assertEqual(len(duplicate_sequences), 1)

    def test_messages_persist_across_restart(self):
        identifier = str(uuid.uuid4())
        original = self.post("persist me", role="codex", identifier=identifier)[1]
        self.stop_server()
        self.start_server()
        self.assertEqual(self.request()[1]["messages"], [original])
        self.assertEqual(self.post("persist me", role="codex", identifier=identifier)[:2], (200, original))

    def test_untrusted_text_remains_inert_data(self):
        marker = Path(self.temp.name) / "must-not-exist"
        text = f'<script>alert("x")</script>\n$(touch "{marker}")\n\'; DROP TABLE messages; --\n你好'
        status, record, headers = self.post(text, role="dot")
        self.assertEqual(status, 201)
        self.assertEqual(record["text"], text)
        self.assertFalse(marker.exists())
        self.assertEqual(self.request()[1]["messages"][0]["text"], text)
        self.assertEqual(self.post("table still works")[0], 201)
        self.assertIn("application/json", headers["Content-Type"])
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_invalid_messages_and_request_limits(self):
        identifier = str(uuid.uuid4())
        for payload in (
            {}, [], {"text": "x"},
            {"text": "", "client_message_id": identifier},
            {"text": "   ", "client_message_id": identifier},
            {"text": "x" * 8001, "client_message_id": identifier},
            {"text": True, "client_message_id": identifier},
            {"text": "ok", "client_message_id": "invalid"},
            {"text": "ok", "client_message_id": identifier, "command": "anything"},
        ):
            with self.subTest(payload_type=type(payload).__name__):
                self.assertEqual(self.request("POST", payload=payload)[0], 400)
        json_headers = {"Content-Type": "application/json"}
        for raw in (b"{", b"\xff", b'{"text":"a","text":"b","client_message_id":"' + identifier.encode() + b'"}',
                    b'{"text":"\\ud800","client_message_id":"' + identifier.encode() + b'"}'):
            self.assertEqual(self.request("POST", raw=raw, headers=json_headers)[0], 400)
        self.assertEqual(self.request("POST", raw=b"{}", headers={"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.request("POST", raw=b"x" * 32769, headers=json_headers)[0], 413)
        self.assertEqual(self.post("界" * 8000)[0], 201)

    def test_invalid_pagination(self):
        for query in ("after=-1", "after=no", "limit=0", "limit=101", "after=1&after=2", "limit=", "other=1",
                      "after=9223372036854775808", "after=0&limit=1&extra=1"):
            with self.subTest(query=query):
                self.assertEqual(self.request(path="/api/messages?" + query)[0], 400)

    def test_static_mapping_does_not_expose_database_or_arbitrary_files(self):
        self.assertEqual(self.request(path="/", role=None)[0], 200)
        self.assertEqual(self.request(path="/app.js", role=None)[0], 200)
        for path in ("/../messages.sqlite3", "/%2e%2e/messages.sqlite3", "/relay_server.py", "/messages.sqlite3", "/.env"):
            self.assertEqual(self.request(path=path, role=None)[0], 404)

    def test_logs_exclude_credentials_queries_and_message_contents(self):
        private_text = "sensitive-text-must-not-be-logged"
        with self.assertLogs("relay", level="INFO") as captured:
            self.post(private_text)
            self.request(path="/unknown?token=" + TOKENS["dot"])
        logs = "\n".join(captured.output)
        self.assertIn("POST /api/messages 201", logs)
        self.assertIn("GET unmatched 404", logs)
        self.assertNotIn(private_text, logs)
        self.assertNotIn("?token=", logs)
        for token in TOKENS.values():
            self.assertNotIn(token, logs)

    def test_connection_limit_and_incomplete_body_timeout(self):
        self.stop_server()
        self.start_server(max_connections=1, connection_timeout=0.5)
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as slow:
            partial_request = (
                "POST /api/messages HTTP/1.1\r\n"
                "Host: localhost\r\n"
                "Content-Type: application/json\r\n"
                "Content-Length: 100\r\n"
                "Authorization: Bearer " + TOKENS["user"] + "\r\n\r\n{"
            )
            slow.sendall(partial_request.encode("ascii"))
            self.assertEqual(self.request(path="/healthz", role=None)[0], 503)
            response = http.client.HTTPResponse(slow)
            response.begin()
            self.assertEqual(response.status, 408)
            self.assertEqual(json.loads(response.read()), {"error": "request_timeout"})


if __name__ == "__main__":
    unittest.main()
