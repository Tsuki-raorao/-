# Argus 只读发布准备

本目录保存通用模板与发布清单，复制文件不会自动完成部署。真实路径、地址、凭据与现场证据只保存在私有运行记录中。**本轮模板以只读访问为目标，不开放容器动作，不接管其他服务的网站入口。**

## 文件与适用范围

| 文件 | 用途 |
|---|---|
| [nginx/argus.conf.example](nginx/argus.conf.example) / [argus.conf](nginx/argus.conf) | 内容相同，选择一份安装；独立 IP 预览的 Nginx 示例 |
| [控制中心环境](systemd/argus-control-center.env.example) / [服务](systemd/argus-control-center.service.example) | 回环监听、必需令牌、明确关闭执行的生产示例 |
| [Agent 环境](systemd/argus-agent.service.env.example) / [服务](systemd/argus-agent.service.example) | 只读 Agent、固定节点身份、持久 Inbox 与受限目录 |
| [Agent 配置](../agent/config/agent.properties.example) | 与环境变量配合使用，环境变量优先 |
| [数据库备份](database/backup-argus-mysql.sh) / [Minecraft 备份](mc01/backup-minecraft.sh) | 独立备份模板，不由网站发布自动触发 |

模板中的 `/srv/argus/frontend`、`/opt/argus/backend`、服务用户和端口都是示例。前两者分别存放静态产物与后端 JAR，**不能据此认定现有服务器采用这些路径**。

## IP 预览与现有入口共存

```text
浏览器 -> Nginx 独立 IP 预览端口（示例 9090） -> dist 静态文件
                                            -> /api/ -> 127.0.0.1:9091
控制中心 -> 受控网络上的 Agent / MySQL
```

域名备案等待期间可以保留 IP 入口，不强制依赖域名或 HTTPS 跳转。新建独立预览可参考 `http://192.0.2.10:9090`，其中地址是 TEST-NET 占位符。**若现场已经由 80 提供 Argus，则按现有 80 站点核对后更新，不要照模板强行迁到 9090。** 此模板没有 `default_server`、443 监听或 HTTP→HTTPS 重定向；已有聊天服务的 443 入口由其维护者管理，不得覆盖。备案通过后的域名、证书、80/443 路由需要另行验证共存关系。

前端构建以网站根路径 `/` 运行，接口为同源 `/api`，页面使用 `/#/tasks` 等 hash 路由，不能未经修改直接挂到 `/argus/` 子路径。Nginx `root` 必须指向 `dist` **内容所在目录**，不能发布源码仓库、`.git`、环境文件或备份。当前 Vite 代理只对开发服务器生效；生产 API 转发由 Nginx 负责。模板仅为 `/.well-known/acme-challenge/` 保留静态文件例外，缺失仍返回 404；更新已有 80 站点必须沿用其实际 ACME webroot/alias，避免中断共享域名的证书续期，其余隐藏路径仍拒绝。

HTTP 预览不会加密访问令牌。只读入口应使用独立、可轮换的查看令牌与受控访问通路；操作令牌留空，不能把 HTTP 预览视为真实操作的安全发布验收。

## 代理、认证与缓存约定

- 中央后端固定回环监听，Nginx 使用 `$http_host` 保留入口端口，并覆盖 `Forwarded`、`X-Forwarded-*` 等会被后端信任的请求头。模板面向直接接收浏览器请求的单层代理；前面另有 CDN、负载均衡或第二层代理时，应重新确定可信边界，不能照抄外层来源头。[Nginx 请求头文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header)
- `SERVER_FORWARD_HEADERS_STRATEGY=framework` 使 Spring 按清洗后的主机、端口和协议识别请求；配合 `SERVER_TOMCAT_REDIRECT_CONTEXT_ROOT=false`。这以回环监听为前提，不能同时把后端端口向公众暴露。[Spring Boot 3.3 代理说明](https://docs.spring.io/spring-boot/3.3/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)
- `ARGUS_CORS_ORIGINS` 精确填写实际 origin，包含协议、地址及非标准端口，不含路径、末尾斜杠或通配符。CORS 不是鉴权；除健康接口外，API 必须有查看令牌。模板留空令牌会拒绝启动，需私下注入实际值。
- `ARGUS_API_READ_ONLY=true`、`ARGUS_TASK_CONTROL_ENABLED=false`、`ARGUS_TASK_ALLOW_DOCKER=false`、`ARGUS_AGENT_GATEWAY_ALLOW_CONTROL=false` 与 Agent 控制关闭共同保护此次发布。采集网关初始也关闭；需要接入时仅启用白名单内的只读采集，不改变执行开关。
- 首页、Logo、API 与错误响应 `no-store`；成功的 `/assets/` 构建指纹资源长期缓存。不存在的脚本返回 404，不以首页 HTML 假装成功。API 不缓存、不自动重试到其他上游；保留后端的 401/403/405 状态。只部署一份模板，`map` 位于 Nginx `http` 上下文，不能重复定义。[响应头文档](https://nginx.org/en/docs/http/ngx_http_headers_module.html)

## systemd 与持久目录

控制中心服务的 Java 路径、JAR 名称、用户、工作目录和数据目录需要与现场一致。启动前创建 `ReadWritePaths` 对应目录并检查属主；环境文件按模板限制权限，不能出现在网站根目录。

Agent 模板的 `ARGUS_AGENT_BIND_ADDRESS=127.0.0.1` 用于同机读取。跨机中央接入时，显式填写中央可到达的受控网卡地址，并设置防火墙来源限制；`ARGUS_AGENT_ADVERTISED_HOST` 只负责公布访问地址，**不会改变实际监听地址**。保留唯一而稳定的节点 ID，禁止复制 `node-example` 形成多个同身份节点。

`StateDirectory` 创建服务专用目录，`UMask=0077` 限制新文件权限；Inbox 示例为 `/var/lib/argus-agent/task-inbox`。已有 Agent 必须核对原 Inbox 路径与 `storeId`，保留原身份和任务记录，不能因为采用模板就切到空目录、删除旧库或复用别的节点 Inbox。实际备份与迁移还须遵循[可靠任务恢复约定](../docs/可靠任务接口与恢复约定.md)。

## 可审查的发布清单

以下是每次实际发布需完成的项目；本地模板检查不能替代现场验收。

| 阶段 | 必须取得的证据 |
|---|---|
| 现场盘点 | 当前监听端口、Nginx 站点归属、静态根目录、systemd 启动项、数据源、Inbox 路径与磁盘余量；保留已有 IP 入口和其他服务路由 |
| 构建准备 | 同一版本的前端 `dist`、后端 JAR、Agent classes 及校验值；在本地构建后上传产物，不在空间紧张的服务器安装依赖 |
| 恢复准备 | 数据库、当前产物、私有配置与 Inbox 的备份；在隔离库验证迁移和恢复，确认回滚版本兼容数据库结构 |
| 配置检查 | 替换占位符；核实回环、origin、令牌、只读开关、目录权限；执行 `nginx -t` 和适用的 `systemd-analyze verify` 后才安排切换 |
| 网站验收 | IP 首页与刷新、品牌图片、哈希资源、任务/日志页可打开；缺失资源 404；API 错误为 JSON；核对缓存与安全头 |
| 保护验收 | 健康可探活；无令牌 API 为 401；查看令牌可读且不可写；`capabilities.canControl=false`；不以业务容器动作做连通性测试 |
| 采集验收 | 只读接入逐节点核对 ID、来源、时间、失败旧快照与实例日志；MOCK 清楚标注，不据此宣布真实 Docker 采集通过 |
| 共存与观察 | 现有 80/IP 入口保留，其他服务的 443 路由无变化；观察日志、资源余量和数据刷新，记录产物版本与回滚入口 |

`scripts/start-frontend.ps1` 会安装依赖并启动开发服务器，`start-backend.ps1`/`start-agent.ps1` 也是开发入口，不替代上述生产产物与 systemd 流程。`check-local-stack.ps1` 的 HTTP 可达检查也不能代替认证、只读和数据正确性验收。

静态切换时保留上一版本及旧页面仍可能请求的哈希资源，避免在线用户旧标签页突然缺少脚本。后端回滚必须考虑 Flyway 迁移；不要通过删数据库或修改迁移历史恢复旧应用。详细步骤见[部署与备份方案](../docs/部署与备份方案.md)及[备份恢复操作手册](../docs/备份恢复操作手册.md)。

## 本次本地核对边界

2026-10-09 的独立检查使用实际 Spring Boot JAR、隔离 H2 与本机 Node 代理，12 项请求断言通过：旧 Host 丢端口时出现 CORS 403；重建来源后同源请求可达只读保护；缺失令牌、查看令牌写入、未知来源及伪造转发头仍受限制；还验证了代理表示 HTTPS 与非标准端口的情况。测试代理用于复现模板请求头规则，**不等于通过 Nginx 的完整请求验收，也不是 HTTPS 证书验收**。

另经授权，在服务器独立临时目录使用原生 `nginx -t` 检查模板，语法通过。该配置的日志、pid、静态根与临时路径全部独立，没有重载/重启 Nginx、修改既有站点或启动新监听。正式组合配置、systemd、真实入口与采集仍须按发布清单验收；此次模板检查没有执行 Docker 动作。
