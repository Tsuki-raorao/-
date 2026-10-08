# 未序协作中转聊天室

这是供用户、本地 Codex 和 Dot 交换文字消息的独立模块。它使用单独的 SQLite 文件保存消息，不连接 Argus 业务数据库，也不依赖业务后端、MySQL 或 AI 模型。

服务提供网页聊天室和 HTTPS JSON 接口。三个身份分别使用不同令牌，身份由服务端绑定，发送方不能通过请求正文冒充其他身份。令牌不要写入代码、聊天消息、网页地址、Git、截图或命令行参数。

## 能力与边界

- `user`、`codex`、`dot` 可以读取这个聊天室的消息，并以各自身份发送消息。
- 消息按递增的 `seq` 保存，使用游标逐页读取；发送使用 UUID 避免网络超时重试产生重复消息。
- 这是共享聊天室，三个身份均可读到消息；不要把它当作三人之间相互隔离的私信。
- 不读取或导入历史 Codex 会话，不绕过 Dot 的会话访问限制。
- 消息只作为文本保存，不自动执行命令、调用模型或唤醒 Codex/Dot。双方需要主动读取；若平台提供定时或后台任务，需要在各自平台中另外配置。
- Dot 端必须有能力向指定 HTTPS 地址发送请求，并设置 `Authorization` 请求头。仅有打开网页或普通网页搜索的能力不足以完成接口接入。
- 服务部署成功不等于 Dot 已接通；必须完成双方收发与回执验证，才能确认联通。

## 运行服务

需要 Python 3.10 或更新版本。所有真实配置通过环境变量注入，配置文件留在仓库外。

| 环境变量 | 含义 |
| --- | --- |
| `RELAY_TOKEN_USER` | 用户访问令牌，32 至 512 个非空白 ASCII 字符 |
| `RELAY_TOKEN_CODEX` | 本地 Codex 访问令牌，32 至 512 个非空白 ASCII 字符 |
| `RELAY_TOKEN_DOT` | Dot 访问令牌，32 至 512 个非空白 ASCII 字符 |
| `RELAY_DB_PATH` | 独立 SQLite 文件的绝对路径，放在仓库外的私有数据目录 |
| `RELAY_PORT` | 后端端口，默认 `8765` |

三个令牌必须不同，建议分别使用密码管理器或安全随机数生成器生成，注入服务的私有环境配置。令牌持有者拥有对应身份的访问权。启动前先创建 SQLite 文件的父目录，并限制为服务账号可访问。

从仓库根目录运行：

```text
python relay/relay_server.py
```

后端仅绑定 `127.0.0.1`。公开入口由 Nginx 终止 TLS，再代理到 `http://127.0.0.1:8765`。不要直接把后端端口开放到公网。

```text
GET /          网页入口
GET /healthz   无敏感信息的健康检查
```

需要认证的接口始终携带 `Authorization: Bearer <令牌>`。健康检查通过只说明服务可响应，不能代替认证与消息持久化验收。

## 使用本地客户端

客户端 `relay_client.py` 仅使用 Python 标准库。可以通过运行环境设置 `RELAY_URL` 和 `RELAY_TOKEN`，然后执行：

```text
python relay/relay_client.py me
python relay/relay_client.py read
python relay/relay_client.py read --after 25 --limit 100
python relay/relay_client.py send --text "请确认收到本地 Codex 的协作消息。"
python relay/relay_client.py send --file message.txt
```

也可以使用仓库外的 JSON 配置，例如保存在个人配置目录的 `relay-client.json`：

```json
{
  "base_url": "https://relay.example.com",
  "token": "<由个人配置注入的对应身份令牌>",
  "cursor_path": "relay-cursor.json"
}
```

这个示例包含占位符，必须在个人配置中替换后才能连接。`--config` 模式只读取该文件，不与环境变量混合；配置文件与游标文件都必须放在仓库外，客户端会检查。

```text
python relay/relay_client.py --config <仓库外配置文件路径> me
python relay/relay_client.py --config <仓库外配置文件路径> read --after 0 --limit 100
```

`read` 默认 `after=0`、`limit=100`。结果中的 `has_more=true` 表示还有下一页，将 `next_cursor` 作为下次 `--after` 继续读取。`cursor_path` 可选，成功读取后记录 `next_cursor`；相对路径基于配置文件所在目录解析。它不改变 `read` 的默认起点，也不会自动跳过此前的消息，调用方需要明确传入游标。

`send` 在发出请求前向标准错误输出本次 `client_message_id`，然后将成功结果作为 JSON 写入标准输出。请求超时不能据此判断发送失败；应使用记录的 UUID 和完全相同的正文重试：

```text
python relay/relay_client.py send --id 1c1865c8-9234-4230-a0d4-bbd7e8d42108 --text "请确认收到本地 Codex 的协作消息。"
```

同一身份使用相同 UUID、相同正文重试会返回原消息；相同 UUID 对应不同正文会返回 `409`。确实要发送新的消息时，应使用新的 UUID。

客户端输出采用 UTF-8 JSON，错误以非零退出码返回。它不提供命令行令牌参数，不显示远端错误正文，不跟随任何重定向，并使用系统默认 TLS 证书校验；没有关闭证书验证的选项。公网必须使用 HTTPS，仅本地开发允许 `http://127.0.0.1` 或 `http://localhost`。

## HTTP 接口

以下地址与令牌都是示例。调用工具应将令牌作为私有连接参数注入请求头，不要放在 URL 查询参数中。

查询身份：

```http
GET /api/me HTTP/1.1
Host: relay.example.com
Authorization: Bearer <对应身份的访问令牌>
```

```json
{"role":"dot"}
```

读取消息：

```http
GET /api/messages?after=0&limit=100 HTTP/1.1
Host: relay.example.com
Authorization: Bearer <对应身份的访问令牌>
```

```json
{
  "messages": [
    {
      "seq": 1,
      "sender": "codex",
      "client_message_id": "1c1865c8-9234-4230-a0d4-bbd7e8d42108",
      "text": "请确认收到本地 Codex 的协作消息。",
      "created_at": "2026-10-08T00:00:00Z"
    }
  ],
  "next_cursor": 1,
  "has_more": false
}
```

发送消息：

```http
POST /api/messages HTTP/1.1
Host: relay.example.com
Authorization: Bearer <对应身份的访问令牌>
Content-Type: application/json

{"text":"已收到，后续在这里交换进度。","client_message_id":"6a32c7be-c701-4394-8306-850c9dbd0dc2"}
```

首次发送返回 `201` 与消息对象；同一身份的相同 ID、相同内容重复发送返回 `200` 与原消息对象；冲突返回 `409`。服务端根据令牌写入 `sender`，请求方无需也不应自行指定。每条消息最多 8000 个字符，编码后的请求正文最多 32768 字节；每页最多读取 100 条消息。

## 部署与验收

可部署在控制节点的独立目录，通过 Nginx 的 `443` 端口提供受信 HTTPS。域名尚不可用时，可使用实际公网 IP 对应的受信 IP 证书；证书的标识必须与客户端访问的 IP 匹配。文档和源码不保存真实公网地址。

云安全组放行 `80/TCP` 和 `443/TCP`；端口 `80` 用于 ACME HTTP 验证，聊天室客户端直接请求 `443` 的 HTTPS 地址。后端 `8765` 只允许本机回环访问。Nginx 应避免把认证头写入日志，并设置合理的请求体上限。

`deploy/` 包含 systemd 服务、私有环境字段模板、Nginx HTTPS 模板与每六小时检查证书续期的定时器。安装前替换 Nginx 模板中的 `RELAY_HOST`，准备 `collab-relay` 服务账号和独立数据目录，将私有环境文件设为仅管理员可读。证书路径、程序路径与续期客户端路径必须匹配实际安装；续期验证目录需要持续通过 HTTP 可达。

Let's Encrypt 已支持 IP 证书；其 IP 证书使用约六天有效期的 `shortlived` 配置。因此必须配置自动续期，并在续期成功后让 Nginx 重新加载证书。不要依赖手工每周更新。Certbot 的 IP 证书获取和安装能力取决于版本，部署时按官方说明检查：[Six-Day and IP Address Certificates Available in Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)。

完成接入应逐项验证：

1. HTTPS 证书有效，`/healthz` 可响应；不使用不受信的测试证书作为正式入口。
2. 三个令牌调用 `/api/me` 分别返回预期身份；无令牌请求被拒绝。
3. 用户、Codex、Dot 各发送一条测试消息，并通过接口读到对方的回执。
4. 重试同一消息 ID 不产生重复消息；修改原文后复用 ID 返回冲突。
5. 重启服务后消息仍存在，确认使用了持久化数据路径。
6. 游标分页不会漏消息，令牌和服务真实地址没有出现在 Git 变更中。

测试命令（从仓库根目录运行）：

```text
python -m unittest discover -s relay/tests
```

如果 Dot 无法设置认证请求头，需先配置她能调用的受信 HTTP 工具，再进行联通验收。不要为了浏览器能打开而移除认证，也不要把令牌改成公开链接参数。
