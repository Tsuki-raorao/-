#!/usr/bin/env python3
"""A small, dependency-free HTTPS client for the independent relay service."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import ssl
import sys
import tempfile
from typing import Any
import urllib.error
import urllib.parse
import urllib.request
import uuid


REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
MAX_TEXT_LENGTH = 8000
MAX_TEXT_BYTES = MAX_TEXT_LENGTH * 4
MAX_REQUEST_BYTES = 32768
SENSITIVE_KEYS = {"authorization", "token", "access_token", "refresh_token", "password", "secret"}


class ClientError(Exception):
    """An actionable error that is safe to show without raw request details."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error may echo an accidentally supplied credential.
        emit({"error": "命令行参数无效，请使用 --help 查看用法；令牌只能放在环境变量或私有配置文件中。"}, error=True)
        self.exit(2)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Even same-host redirects are rejected: requests never forward a bearer token.
        return None


def safe_value(value: Any, token: str = "") -> Any:
    if isinstance(value, dict):
        return {
            key: "[REDACTED]"
            if str(key).lower() in SENSITIVE_KEYS or "token" in str(key).lower()
            else safe_value(item, token)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [safe_value(item, token) for item in value]
    if isinstance(value, str) and token:
        return value.replace(token, "[REDACTED]")
    return value


def emit(value: Any, token: str = "", *, error: bool = False) -> None:
    stream = sys.stderr if error else sys.stdout
    encoded = json.dumps(safe_value(value, token), ensure_ascii=False)
    if token:
        encoded = encoded.replace(token, "[REDACTED]")
    print(encoded, file=stream, flush=True)


def outside_repository(path: Path, label: str) -> Path:
    try:
        resolved = path.expanduser().resolve()
    except (OSError, RuntimeError):
        raise ClientError(f"{label}路径无效。") from None
    if resolved.is_relative_to(REPOSITORY_ROOT):
        raise ClientError(f"{label}必须放在项目仓库外，避免将个人配置或状态提交到 Git。")
    return resolved


def validate_url(value: Any) -> str:
    if not isinstance(value, str) or not value or any(char.isspace() for char in value):
        raise ClientError("请设置有效的 RELAY_URL，或在配置文件中设置 base_url。")
    try:
        parts = urllib.parse.urlsplit(value)
        port = parts.port
    except ValueError:
        raise ClientError("中转服务地址格式无效。") from None
    if not parts.hostname or parts.username is not None or parts.password is not None:
        raise ClientError("中转服务地址必须包含主机名，且不能包含用户名或密码。")
    if parts.query or parts.fragment or parts.path not in ("", "/"):
        raise ClientError("中转服务地址只能包含协议、主机和可选端口，不能包含路径、查询或片段。")
    if port is not None and not 1 <= port <= 65535:
        raise ClientError("中转服务端口无效。")
    if parts.scheme != "https":
        if parts.scheme != "http" or parts.hostname.lower() not in {"127.0.0.1", "localhost"}:
            raise ClientError("仅允许 HTTPS；本地开发可使用 http://127.0.0.1 或 http://localhost。")
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, "", "", ""))


def load_settings(config_file: str | None) -> tuple[str, str, Path | None]:
    cursor_path = None
    if config_file:
        config_path = outside_repository(Path(config_file), "配置文件")
        try:
            with config_path.open("r", encoding="utf-8-sig") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            raise ClientError("无法读取配置文件；请检查文件是否存在以及 JSON 格式是否正确。") from None
        if not isinstance(data, dict):
            raise ClientError("配置文件必须是 JSON 对象。")
        base_url = data.get("base_url")
        token = data.get("token")
        raw_cursor = data.get("cursor_path")
        if raw_cursor is not None:
            if not isinstance(raw_cursor, str) or not raw_cursor.strip():
                raise ClientError("cursor_path 必须是仓库外的文件路径。")
            selected_path = Path(raw_cursor).expanduser()
            if not selected_path.is_absolute():
                selected_path = config_path.parent / selected_path
            cursor_path = outside_repository(selected_path, "游标文件")
            if cursor_path == config_path:
                raise ClientError("cursor_path 不能与配置文件相同。")
    else:
        base_url = os.environ.get("RELAY_URL")
        token = os.environ.get("RELAY_TOKEN")
    if not isinstance(token, str) or not 32 <= len(token) <= 512:
        raise ClientError("请通过 RELAY_TOKEN 或仓库外的配置文件提供 32 至 512 个字符的访问令牌。")
    if not all(33 <= ord(char) <= 126 for char in token):
        raise ClientError("访问令牌必须为不含空白字符的 ASCII 字符串。")
    return validate_url(base_url), token, cursor_path


def request_json(base_url: str, token: str, method: str, path: str, data: Any = None) -> Any:
    encoded = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    if encoded is not None and len(encoded) > MAX_REQUEST_BYTES:
        raise ClientError("编码后的请求超过 32768 字节，请缩短正文。")
    request = urllib.request.Request(
        base_url + path,
        data=encoded,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    # Use the platform CA trust store; there is intentionally no insecure option.
    opener = urllib.request.build_opener(
        NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context())
    )
    try:
        with opener.open(request, timeout=30) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ClientError("服务响应过大；请减少 read 的 --limit。")
    except urllib.error.HTTPError as exc:
        # Do not echo a remote error body or Location header: either may contain credentials.
        explanations = {
            400: "请求格式或参数不正确。",
            401: "访问令牌缺失或无效。",
            403: "此令牌没有执行该操作的权限。",
            404: "接口不存在，请核对服务地址和版本。",
            409: "消息 ID 已用于不同内容；原消息重试应沿用原内容，新消息应使用新 ID。",
            413: "消息超过服务端大小限制。",
            429: "请求过于频繁，请稍后重试。",
        }
        if 300 <= exc.code < 400:
            explanation = "服务返回重定向，客户端已拒绝。请直接配置最终 HTTPS 地址。"
        else:
            explanation = explanations.get(exc.code, "服务请求失败，请检查服务运行状态。")
        raise ClientError(f"HTTP {exc.code}：{explanation}") from None
    except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
        raise ClientError(
            "连接失败、证书验证失败或请求超时。请检查地址、网络和服务状态；"
            "发送结果可能不确定，重试时沿用已显示的消息 ID 和原文。"
        ) from None
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeError, ValueError):
        raise ClientError("服务没有返回有效的 UTF-8 JSON；请检查地址和反向代理配置。") from None


def record_cursor(path: Path, base_url: str, cursor: int) -> None:
    temporary = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(
                {"base_url": base_url, "next_cursor": cursor, "updated_at": datetime.now(timezone.utc).isoformat()},
                handle,
                ensure_ascii=False,
            )
            handle.write("\n")
        os.replace(temporary, path)
    except OSError:
        raise ClientError("消息已读取，但游标记录失败；可从输出的 next_cursor 手动继续读取。") from None
    finally:
        if temporary is not None and temporary.exists():
            try:
                temporary.unlink()
            except OSError:
                pass


def arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = SafeArgumentParser(description="未序独立中转聊天室客户端（Python 3.10+，无第三方依赖）。")
    parser.add_argument("--config", metavar="PATH", help="仓库外的 JSON 配置文件；不使用命令行令牌")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("me", help="查询令牌对应的身份")
    reader = commands.add_parser("read", help="按序号读取消息")
    reader.add_argument("--after", type=int, default=0, help="仅获取此序号之后的消息，默认 0")
    reader.add_argument("--limit", type=int, default=100, help="每页消息数，默认 100")
    sender = commands.add_parser("send", help="发送一条消息")
    text_source = sender.add_mutually_exclusive_group(required=True)
    text_source.add_argument("--text", help="消息正文，不要包含访问令牌")
    text_source.add_argument("--file", metavar="PATH", help="从 UTF-8 文本文件读取消息正文")
    sender.add_argument("--id", dest="message_id", help="UUID；重试时沿用此前的 ID 和正文")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    token = ""
    try:
        args = arguments(argv)
        base_url, token, cursor_path = load_settings(args.config)
        if args.command == "me":
            emit(request_json(base_url, token, "GET", "/api/me"), token)
        elif args.command == "read":
            if not 0 <= args.after <= (1 << 63) - 1 or not 1 <= args.limit <= 100:
                raise ClientError("--after 必须是有效的非负 64 位序号，--limit 必须在 1 至 100 之间。")
            query = urllib.parse.urlencode({"after": args.after, "limit": args.limit})
            result = request_json(base_url, token, "GET", "/api/messages?" + query)
            if (
                not isinstance(result, dict)
                or not isinstance(result.get("messages"), list)
                or type(result.get("next_cursor")) is not int
                or result["next_cursor"] < args.after
                or type(result.get("has_more")) is not bool
            ):
                raise ClientError("消息响应结构无效，未更新游标。")
            emit(result, token)
            if cursor_path is not None:
                record_cursor(cursor_path, base_url, result["next_cursor"])
        else:
            if args.file:
                try:
                    with Path(args.file).open("rb") as handle:
                        raw_text = handle.read(MAX_TEXT_BYTES + 1)
                    if len(raw_text) > MAX_TEXT_BYTES:
                        raise ClientError("消息文件过大，请缩短正文。")
                    text = raw_text.decode("utf-8-sig")
                except (OSError, UnicodeError):
                    raise ClientError("无法读取消息文件，请检查路径和 UTF-8 编码。") from None
            else:
                text = args.text
            if not text.strip() or len(text) > MAX_TEXT_LENGTH:
                raise ClientError("消息不能为空或超过 8000 个字符。")
            if token in text:
                raise ClientError("消息包含当前访问令牌，已拒绝发送；请先移除令牌。")
            try:
                message_id = str(uuid.UUID(args.message_id)) if args.message_id else str(uuid.uuid4())
            except (ValueError, AttributeError):
                raise ClientError("--id 必须是有效的 UUID。") from None
            emit({"event": "sending", "client_message_id": message_id}, token, error=True)
            emit(
                request_json(
                    base_url, token, "POST", "/api/messages", {"text": text, "client_message_id": message_id}
                ),
                token,
            )
        return 0
    except ClientError as exc:
        emit({"error": str(exc)}, token, error=True)
        return 1
    except KeyboardInterrupt:
        emit({"error": "操作已中断；发送结果若不确定，请使用此前的消息 ID 和原文重试。"}, token, error=True)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
