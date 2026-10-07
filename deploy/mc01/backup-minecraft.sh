#!/usr/bin/env bash
# Argus Minecraft 服务器只读备份脚本
# 默认不停止容器、不执行 docker exec，不修改世界数据。
set -Eeuo pipefail

# 用法：backup-minecraft.sh OUTPUT_ROOT CONTAINER WORLD_DIR [CONTAINER WORLD_DIR ...]
# 所有目标必须由调用者明确提供，不内置真实容器或世界目录。
if (( $# < 3 || ($# - 1) % 2 != 0 )); then
  printf 'Usage: %s OUTPUT_ROOT CONTAINER WORLD_DIR [CONTAINER WORLD_DIR ...]\n' "$0" >&2
  exit 2
fi

output_root="$1"
shift
containers=()
worlds=()
declare -A world_names=()
while (( $# > 0 )); do
  container="$1"
  world="${2%/}"
  if [[ ! "$container" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ || ! -d "$world" ]]; then
    printf 'Invalid container name or missing world directory.\n' >&2
    exit 2
  fi
  name="$(basename "$world")"
  if [[ -n "${world_names[$name]:-}" || "$world" != /* || "$world" == / ]]; then
    printf 'World directories must be absolute, non-root paths with distinct final names.\n' >&2
    exit 2
  fi
  world_names["$name"]=1
  containers+=("$container")
  worlds+=("$world")
  shift 2
done

stamp="$(date +%Y%m%d-%H%M%S)"
output_dir="${output_root}/${stamp}"
mkdir -p "$output_dir"

for container in "${containers[@]}"; do
  docker inspect "$container" >"$output_dir/${container}.inspect.json"
  image="$(docker inspect -f '{{.Config.Image}}' "$container")"
  docker image inspect "$image" >"$output_dir/${container}.image.json"
  docker logs --tail 2000 "$container" >"$output_dir/${container}.log.txt" 2>&1 || true
done

for world in "${worlds[@]}"; do
  if [[ -d "$world" ]]; then
    name="$(basename "$world")"
    tar --numeric-owner --xattrs -czf "$output_dir/${name}.tar.gz" -C "$(dirname "$world")" "$name"
  fi
done

docker ps --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' >"$output_dir/docker-ps.txt"
(
  cd "$output_dir"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
)
