#!/usr/bin/env bash
# Argus MySQL 逻辑备份模板。密码只从运行时环境 MYSQL_PWD 读取，不写入文件。
set -Eeuo pipefail

: "${MYSQL_PWD:?请先在当前 shell 设置 MYSQL_PWD}"
output_root="${1:-/srv/argus-backups/mysql}"
stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$output_root"
dump="$output_root/argus-${stamp}.sql.gz"

mysqldump --single-transaction --quick --routines --events \
  --host="${MYSQL_HOST:-127.0.0.1}" \
  --port="${MYSQL_PORT:-3306}" \
  --user="${MYSQL_USER:-argus}" \
  "${MYSQL_DATABASE:-argus}" | gzip -n >"$dump"
sha256sum "$dump" | tee "$dump.sha256"
