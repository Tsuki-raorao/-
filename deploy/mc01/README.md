# Minecraft 节点部署与备份模板

本目录保留历史名称 `mc01`，以下内容均为通用部署示例，不代表任何已部署服务器的实测状态。实际地址、容器名、世界目录和配置应由部署人员填写，并保存在受控运维记录中。任何实际变更都必须先备份世界存档和容器配置。

## 示例部署参数

| 项目 | 值 |
|---|---|
| 服务器 | 游戏节点，文档保留地址 `192.0.2.20` |
| 容器 | 示例名 `minecraft-example`；启动参数按部署需求配置 |
| 镜像 | 与目标 Minecraft/加载器版本匹配的 Java 镜像，固定经过验证的 tag 或 digest |
| 数据目录 | `/srv/minecraft/example-world` → 容器 `/data` |
| 游戏端口 | 示例为宿主 `25565` → 容器 `25565/tcp`，按实际需求调整 |
| Minecraft | 选择并记录实际使用的 Minecraft、加载器和 Mod 版本 |
| 世界 | 示例名 `example-world`，以 `server.properties` 和实际目录为准 |
| 正版验证 | 明确配置 `online-mode` 并记录部署策略 |
| RCON | 无需求时保持关闭；启用时限制网络来源并外置凭据 |

## DNS

- `game.example.com` A 记录指向文档保留地址 `192.0.2.20`。
- `_minecraft._tcp.game` SRV 记录指向 `game.example.com`，示例端口为 `25565`。

以上地址仅用于说明，部署时替换为自己的域名和游戏节点地址。客户端是否自动使用 SRV 取决于客户端版本和解析缓存；排查连接问题时同时核对 A、SRV、云安全组和容器监听。

## 变更前备份要求

修改 `server.properties`、Mod、端口、镜像、Compose 或重建容器前，至少备份：

1. `/srv/minecraft/example-world` 世界目录及权限文件。
2. `docker inspect minecraft-example` 输出和容器启动参数。
3. 当前镜像 digest、最近日志和配置文件校验值。
4. 回滚命令和备份保存位置。

备份未完成校验前，不停止或重启目标容器。

## 备份脚本用法

[backup-minecraft.sh](backup-minecraft.sh) 要求显式传入输出目录，以及每个容器名和世界目录，不会猜测当前服务器运行了哪些实例：

```bash
bash backup-minecraft.sh /srv/argus-backups minecraft-example /srv/minecraft/example-world
```

多个实例在后面继续追加 `容器名 世界目录` 参数对，各世界目录的末级名称须不同。先核对参数和目录，再执行并校验生成的 `SHA256SUMS`。备份包含容器环境配置和日志，可能含凭据，只能保存到受限位置，不应提交到 Git。

脚本只读采集 `docker inspect`、镜像信息、最近日志和世界目录压缩包，默认不会停止容器。它不能替代停服后的一致性备份；涉及重要世界时仍应先安排维护窗口并确认恢复演练。
