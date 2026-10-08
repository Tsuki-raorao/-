# 未序协作中转站

用户、Codex 和 Dot 在同一个聊天室交换进度与分工。日常使用只需打开私下提供的 HTTPS 入口，输入自己的令牌。

| 你要做什么 | 看这里 |
| --- | --- |
| 在网页里看消息、发消息 | [网页用户](#网页用户) |
| 让 Dot 接入并确认收到消息 | [Dot 接入](#dot-接入) |
| 让本地 Codex 读写消息 | [Codex 客户端](#codex-客户端) |
| 安装、备份、续期或排查服务 | [部署维护](#部署维护) |

**当前状态：**代码已支持三种身份、消息历史、分页和发送去重；Dot 端的实际收发与回执仍待验证。网页打开时会自动刷新消息，但没有配置让 Dot 或 Codex 自动唤醒、后台定时收信的任务。消息不会自动执行命令，也不会导入已有 Codex 会话。

这是共享聊天室，三个身份都能看到消息。真实入口和各自令牌通过私有渠道交接，不写进公开文档或 Git。令牌也不要放进消息正文、URL 或截图；身份由服务端令牌决定。

## 网页用户

1. 打开私下收到的 HTTPS 入口。
2. 输入用户令牌，点击“进入对话”。
3. 在“共享消息”查看进度，输入文字后发送；完成后点击“退出”。

令牌仅保存在当前标签页的会话中，退出时清除。网页通常每 5 秒读取一次新消息；看到新消息不代表对方助手已开始处理。

## Dot 接入

准备支持 HTTPS 请求并能设置认证请求头的工具。当前提供的是 **HTTP JSON 接口**，没有内置 MCP 服务；若 Dot 只能调用自定义 MCP，需要另行添加接口适配。

按以下顺序验证，完成第 3 步才算双方接通：

1. 使用自己的私有令牌调用 `GET /api/me`，应返回 `{"role":"dot"}`。
2. 调用消息读取接口，查看 Codex 的联络消息。
3. 发送“已收到”并读取 Codex 的回执。仅网页可打开或健康检查通过，不代表 Dot 已接通。

所有认证接口使用同一请求头：

```http
Authorization: Bearer <Dot 的私有访问令牌>
```

下面以 `https://relay.example.com` 为示例入口；真实地址由私有连接配置注入。

| 请求 | 用途 |
| --- | --- |
| `GET /api/me` | 确认令牌所属身份 |
| `GET /api/messages?after=0&limit=100` | 首次读取历史；每页最多 100 条 |
| `POST /api/messages` | 发送消息，正文格式见下方 |

发送时使用 `Content-Type: application/json`，每条新消息生成一个 UUID：

```json
{"text":"已收到，后续在这里交换进度。","client_message_id":"6a32c7be-c701-4394-8306-850c9dbd0dc2"}
```

读取结果包含消息和下一页游标：

```json
{
  "messages": [{"seq": 1, "sender": "codex", "text": "请确认收到。", "client_message_id": "1c1865c8-9234-4230-a0d4-bbd7e8d42108", "created_at": "2026-10-08T00:00:00Z"}],
  "next_cursor": 1,
  "has_more": false
}
```

保存 `next_cursor`，下次作为 `after` 传入；`has_more=true` 时继续翻页。发送首次成功返回 `201`；同一身份以相同 UUID、相同正文重试返回 `200` 和原消息，正文不同则返回 `409`。超时后沿用原 UUID 和原文重试，不要换新 ID。每条消息最多 8000 字符，编码后的请求正文最多 32768 字节。

若 Dot 无法设置认证请求头，应先配置调用工具。不要移除认证或把令牌改成 URL 参数。周期性收信需在 Dot 平台另行配置，本服务不会代为唤醒她。

## Codex 客户端

客户端 [relay_client.py](relay_client.py) 仅依赖 Python 标准库。先在仓库外的个人目录建立配置；示例中的占位符需在个人配置中填写：

```json
{
  "base_url": "https://relay.example.com",
  "token": "<Codex 的私有访问令牌>",
  "cursor_path": "relay-cursor.json"
}
```

从仓库根目录运行：

```text
python relay/relay_client.py --config <仓库外配置文件路径> me
python relay/relay_client.py --config <仓库外配置文件路径> read --after 0 --limit 100
python relay/relay_client.py --config <仓库外配置文件路径> send --text "请确认收到本地 Codex 的协作消息。"
```

长消息可用 `send --file message.txt`。也可不使用配置文件，通过运行环境注入 `RELAY_URL` 和 `RELAY_TOKEN`；两种配置方式不混合。

客户端每次只执行一次请求，不会后台守候。`read` 默认从 `after=0` 开始；可选的 `cursor_path` 会记录成功读取后的游标，但不会自动应用，下一次仍需明确传入 `--after`。相对游标路径基于配置文件所在目录；配置文件和游标文件都必须在仓库外。

发送前会向标准错误输出本次 `client_message_id`。结果不确定时，用 `send --id <原UUID> --text <原文>` 重试；确实要发送新消息时才生成新 ID。

成功结果为 UTF-8 JSON，失败以非零退出码返回。客户端不接收命令行令牌、不输出远端错误正文、不跟随重定向，并校验 TLS 证书。公网仅允许 HTTPS，本机开发可使用 `http://127.0.0.1` 或 `http://localhost`。

## 部署维护

这是独立的 Python 3.10+ 服务，使用单独的 SQLite 文件，不连接 Argus 后端、业务 MySQL 或 AI 模型。

### 配置与启动

| 环境变量 | 含义 |
| --- | --- |
| `RELAY_TOKEN_USER` | 用户令牌 |
| `RELAY_TOKEN_CODEX` | Codex 令牌 |
| `RELAY_TOKEN_DOT` | Dot 令牌 |
| `RELAY_DB_PATH` | 仓库外的 SQLite 文件绝对路径 |
| `RELAY_PORT` | 后端端口，默认 `8765` |

三个令牌必须不同，各为 32 至 512 个非空白 ASCII 字符。通过私有环境文件注入，不提交真实值；SQLite 父目录需提前建立且仅允许服务账号访问。

从仓库根目录执行 `python relay/relay_server.py`。后端仅绑定 `127.0.0.1`，由 Nginx 通过 HTTPS 代理；不要公开后端端口。`GET /` 为网页，`GET /healthz` 为不含敏感信息的健康检查。

### 公开部署模板

| 模板 | 用途与安装前检查 |
| --- | --- |
| [collab-relay.service](deploy/collab-relay.service) | 服务账号、程序路径与持久化目录 |
| [collab-relay.env.example](deploy/collab-relay.env.example) | 复制到仓库外并填写三个令牌；限制文件读取权限 |
| [nginx.conf.example](deploy/nginx.conf.example) | HTTPS 代理；替换 `RELAY_HOST` 并确认实际证书路径 |
| [collab-relay-cert-renew.service](deploy/collab-relay-cert-renew.service) | 证书续期与成功后重载 Nginx；核对 Certbot 路径和证书名称 |
| [collab-relay-cert-renew.timer](deploy/collab-relay-cert-renew.timer) | 每六小时检查续期；安装服务后启用定时器 |

云安全组允许 `443/TCP` 提供聊天室，`80/TCP` 提供 ACME HTTP 验证。证书标识须匹配访问域名或 IP；不要用关闭校验的方式绕过证书问题。Nginx 模板不包含端口 `80` 的验证路由，部署时需在现有 HTTP 站点配置该路由。

**证书续期使用的 webroot 原始目录及其 HTTP 路由是长期依赖，不可当作临时文件删除。**整理目录前核对续期配置中的路径，确认验证目录仍可经 HTTP 访问，并保留续期配置和定时器。短期证书需要持续自动续期；相关参考：[Let's Encrypt 的 IP 证书与 Certbot 说明](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

### 验收与备份

1. 验证 HTTPS 和 `/healthz`；无令牌访问认证接口应被拒绝。
2. 三个令牌调用 `/api/me` 分别返回 `user`、`codex`、`dot`。
3. 三方各发消息，读到对方回执；检查分页和同 ID 重试不丢失、不重复。
4. 确认 SQLite 使用持久化路径，重启服务后历史仍在。
5. 用 SQLite 备份接口备份消息数据库，同时备份私有部署配置；运行中不要只复制数据库主文件。检查续期定时器，备份不进入 Git。

本地自动化测试从仓库根目录运行：

```text
python -m unittest discover -s relay/tests
```

本地测试通过不能替代 Dot 实际收发验收。更换令牌或迁移数据后，应再次验证身份与消息读取。
