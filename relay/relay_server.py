"""Private message relay. Run behind a trusted HTTPS reverse proxy.

Messages are inert text: this service never executes commands or contacts agents.
Only the reverse proxy should be reachable over the network.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import re
import socket
import sqlite3
import threading
import time
import uuid
from contextlib import closing
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Mapping
from urllib.parse import parse_qs, urlsplit


ROLES = ("user", "codex", "dot")
MAX_BODY_BYTES = 32768
MAX_TEXT_CHARS = 8000
MAX_PAGE_SIZE = 100
MAX_CURSOR = (1 << 63) - 1
LOGGER = logging.getLogger("relay")
STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
}
KNOWN_ROUTES = frozenset((*STATIC_FILES, "/healthz", "/api/me", "/api/messages"))


def finish_socket_response(request: socket.socket):
    """Deliver an early rejection without an unread-body TCP reset."""
    try:
        request.shutdown(socket.SHUT_WR)
        deadline = time.monotonic() + 0.1
        remaining = MAX_BODY_BYTES + 8192
        while remaining and time.monotonic() < deadline:
            request.settimeout(max(0.001, deadline - time.monotonic()))
            data = request.recv(min(4096, remaining))
            if not data:
                break
            remaining -= len(data)
    except OSError:
        pass


@dataclass(frozen=True)
class Config:
    db_path: Path
    tokens: Mapping[str, str] = field(repr=False)
    port: int = 8765

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "Config":
        env = os.environ if environ is None else environ
        tokens = {}
        for role in ROLES:
            name = f"RELAY_TOKEN_{role.upper()}"
            token = env.get(name, "")
            if not re.fullmatch(r"[!-~]{32,512}", token):
                raise ValueError(f"{name} must contain 32 to 512 non-whitespace ASCII characters")
            tokens[role] = token
        if len(set(tokens.values())) != len(ROLES):
            raise ValueError("RELAY_TOKEN_USER, RELAY_TOKEN_CODEX and RELAY_TOKEN_DOT must differ")
        raw_path = env.get("RELAY_DB_PATH", "").strip()
        if not raw_path:
            raise ValueError("RELAY_DB_PATH is required")
        db_path = Path(raw_path).expanduser().resolve()
        if not db_path.parent.is_dir():
            raise ValueError("RELAY_DB_PATH parent directory must already exist")
        if db_path.is_dir():
            raise ValueError("RELAY_DB_PATH must name a database file")
        try:
            port = int(env.get("RELAY_PORT", "8765"))
        except ValueError as exc:
            raise ValueError("RELAY_PORT must be an integer between 1 and 65535") from exc
        if not 1 <= port <= 65535:
            raise ValueError("RELAY_PORT must be an integer between 1 and 65535")
        return cls(db_path=db_path, tokens=tokens, port=port)


class MessageConflict(Exception):
    """An idempotency identifier was already used for different content."""


class MessageStore:
    def __init__(self, path: Path):
        self.path = path
        with closing(self._connect()) as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """CREATE TABLE IF NOT EXISTS messages (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender TEXT NOT NULL CHECK (sender IN ('user', 'codex', 'dot')),
                    client_message_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    UNIQUE(sender, client_message_id)
                )"""
            )
            connection.commit()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=5.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=5000")
        return connection

    def read(self, after: int, limit: int) -> dict:
        with closing(self._connect()) as connection:
            rows = connection.execute(
                "SELECT seq, sender, client_message_id, text, created_at "
                "FROM messages WHERE seq > ? ORDER BY seq LIMIT ?",
                (after, limit + 1),
            ).fetchall()
        messages = [dict(row) for row in rows[:limit]]
        return {
            "messages": messages,
            "next_cursor": messages[-1]["seq"] if messages else after,
            "has_more": len(rows) > limit,
        }

    def append(self, sender: str, client_message_id: str, text: str) -> tuple[dict, bool]:
        with closing(self._connect()) as connection, connection:
            # One transaction covers the duplicate check and insert for all writers.
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT seq, sender, client_message_id, text, created_at FROM messages "
                "WHERE sender = ? AND client_message_id = ?",
                (sender, client_message_id),
            ).fetchone()
            if existing is not None:
                if existing["text"] != text:
                    raise MessageConflict()
                return dict(existing), False
            created_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
            cursor = connection.execute(
                "INSERT INTO messages (sender, client_message_id, text, created_at) VALUES (?, ?, ?, ?)",
                (sender, client_message_id, text, created_at),
            )
            return {
                "seq": cursor.lastrowid,
                "sender": sender,
                "client_message_id": client_message_id,
                "text": text,
                "created_at": created_at,
            }, True


class RelayHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 32
    allow_reuse_address = True

    def __init__(
        self,
        config: Config,
        *,
        static_dir: Path | None = None,
        max_connections: int = 32,
        connection_timeout: float = 10.0,
    ):
        self.config = config
        self.store = MessageStore(config.db_path)
        self.static_dir = static_dir or Path(__file__).resolve().parent / "web"
        self.connection_timeout = connection_timeout
        self._slots = threading.BoundedSemaphore(max_connections)
        # The binding is intentionally not configurable to a public interface.
        super().__init__(("127.0.0.1", config.port), RelayHandler)

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(self.connection_timeout)
        return request, address

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            try:
                body = b'{"error":"busy"}'
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"Content-Type: application/json\r\n"
                    b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n"
                    b"Connection: close\r\nRetry-After: 1\r\n\r\n" + body
                )
                finish_socket_response(request)
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            LOGGER.info("OTHER unmatched 503")
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()

    def handle_error(self, request, client_address):
        # Never emit request headers, contents, addresses or exception tracebacks.
        LOGGER.warning("OTHER unmatched request_failed")


class RelayHandler(BaseHTTPRequestHandler):
    server: RelayHTTPServer
    protocol_version = "HTTP/1.1"
    server_version = "Relay"
    sys_version = ""

    def finish(self):
        try:
            super().finish()
        finally:
            finish_socket_response(self.connection)

    def _target(self):
        try:
            path = getattr(self, "path", "")
            if not path.startswith("/") or path.startswith("//"):
                raise ValueError()
            return urlsplit(path)
        except ValueError:
            return None

    def log_request(self, code="-", size="-"):
        target = self._target()
        route = target.path if target and target.path in KNOWN_ROUTES else "unmatched"
        command = getattr(self, "command", None)
        method = command if command in {"GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE"} else "OTHER"
        LOGGER.info("%s %s %s", method, route, code)

    def log_message(self, format, *args):
        # BaseHTTPRequestHandler's defaults include the complete request target.
        pass

    def log_error(self, format, *args):
        pass

    def send_error(self, code, message=None, explain=None):
        self._json(code, {"error": HTTPStatus(code).phrase.lower().replace(" ", "_")})

    def _send(self, status: int, body: bytes, content_type: str):
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                "img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
            )
            if status == 401:
                self.send_header("WWW-Authenticate", 'Bearer realm="relay"')
            if status == 503:
                self.send_header("Retry-After", "1")
            self.end_headers()
            if getattr(self, "command", None) != "HEAD":
                self.wfile.write(body)
        except OSError:
            # Disconnects are normal and must not expose submitted text in logs.
            pass

    def _json(self, status: int, value: dict):
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def _role(self) -> str | None:
        headers = self.headers.get_all("Authorization", [])
        if len(headers) != 1 or not headers[0].startswith("Bearer "):
            self._json(401, {"error": "unauthorized"})
            return None
        candidate = headers[0][7:].encode("utf-8")
        matched = None
        for role in ROLES:
            if hmac.compare_digest(candidate, self.server.config.tokens[role].encode("utf-8")):
                matched = role
        if matched is None:
            self._json(401, {"error": "unauthorized"})
        return matched

    def do_GET(self):
        target = self._target()
        if target is None:
            self._json(400, {"error": "invalid_target"})
            return
        if target.path == "/healthz":
            self._json(200, {"status": "ok"})
            return
        if target.path in STATIC_FILES:
            filename, content_type = STATIC_FILES[target.path]
            try:
                body = (self.server.static_dir / filename).read_bytes()
            except OSError:
                self._json(404, {"error": "not_found"})
                return
            self._send(200, body, content_type)
            return
        if target.path not in {"/api/me", "/api/messages"}:
            self._json(404, {"error": "not_found"})
            return
        role = self._role()
        if role is None:
            return
        if target.path == "/api/me":
            self._json(200, {"role": role})
            return
        try:
            query = parse_qs(target.query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
            if set(query) - {"after", "limit"} or any(len(values) != 1 for values in query.values()):
                raise ValueError()
            after_raw = query.get("after", ["0"])[0]
            limit_raw = query.get("limit", ["100"])[0]
            if not re.fullmatch(r"[0-9]{1,19}", after_raw) or not re.fullmatch(r"[0-9]{1,3}", limit_raw):
                raise ValueError()
            after, limit = int(after_raw), int(limit_raw)
            if not 0 <= after <= MAX_CURSOR or not 1 <= limit <= MAX_PAGE_SIZE:
                raise ValueError()
        except ValueError:
            self._json(400, {"error": "invalid_pagination"})
            return
        try:
            page = self.server.store.read(after, limit)
        except sqlite3.Error:
            self._json(503, {"error": "storage_unavailable"})
            return
        self._json(200, page)

    def do_POST(self):
        target = self._target()
        if target is None or target.path != "/api/messages":
            self._json(404, {"error": "not_found"})
            return
        role = self._role()
        if role is None:
            return
        if target.query:
            self._json(400, {"error": "unexpected_query"})
            return
        if self.headers.get_content_type() != "application/json":
            self._json(415, {"error": "content_type_must_be_application_json"})
            return
        if self.headers.get("Transfer-Encoding") is not None:
            self._json(400, {"error": "transfer_encoding_not_supported"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            self._json(411, {"error": "content_length_required"})
            return
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
            self._json(400, {"error": "invalid_content_length"})
            return
        length = int(lengths[0])
        if length > MAX_BODY_BYTES:
            self._json(413, {"error": "body_too_large"})
            return
        try:
            raw = self.rfile.read(length)
        except (socket.timeout, TimeoutError):
            self._json(408, {"error": "request_timeout"})
            return
        if len(raw) != length:
            self._json(400, {"error": "incomplete_body"})
            return
        try:
            payload = json.loads(raw.decode("utf-8"), object_pairs_hook=self._unique_json_object)
            if not isinstance(payload, dict) or set(payload) != {"text", "client_message_id"}:
                raise ValueError()
            text = payload["text"]
            identifier = payload["client_message_id"]
            if not isinstance(text, str) or not 1 <= len(text) <= MAX_TEXT_CHARS or not text.strip():
                raise ValueError()
            # Escaped lone UTF-16 surrogates are not valid UTF-8 message text.
            text.encode("utf-8")
            if not isinstance(identifier, str) or not re.fullmatch(
                r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", identifier
            ):
                raise ValueError()
            identifier = str(uuid.UUID(identifier))
        except (ValueError, UnicodeError, RecursionError):
            self._json(400, {"error": "invalid_message"})
            return
        try:
            message, created = self.server.store.append(role, identifier, text)
        except MessageConflict:
            self._json(409, {"error": "client_message_id_conflict"})
            return
        except sqlite3.Error:
            self._json(503, {"error": "storage_unavailable"})
            return
        self._json(201 if created else 200, message)

    @staticmethod
    def _unique_json_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        config = Config.from_env()
        server = RelayHTTPServer(config)
    except ValueError as exc:
        LOGGER.error("Configuration error: %s", exc)
        return 1
    except (OSError, sqlite3.Error):
        LOGGER.error("Startup failed; verify listener availability and database permissions")
        return 1
    LOGGER.info("Relay started on loopback port %d", config.port)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
