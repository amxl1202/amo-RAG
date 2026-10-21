#!/usr/bin/env bash
# 本地开发辅助:确保 pgvector 容器在跑,并做首次初始化(vector / pg_trgm 扩展)。
# 应用自身启动时通过 schema.sql 自动建表,无需手动执行 DDL。
#
# 关于"老是要人工介入":Docker Desktop 是 GUI 程序,脚本(以及 AI)无法替你启动它。
# 所以这里把等待与诊断做足 —— 你点一下 Start,剩下的事脚本自己等。
#   DAEMON_WAIT_SECONDS  等 Docker 引擎就绪的上限(默认 45 秒)
#   READY_WAIT_SECONDS   等数据库接受连接的上限(默认 60 秒)
set -euo pipefail

CONTAINER=rag-pg
DB_USER=postgres
DB_NAME=rag_kb
DB_PASSWORD=rag123456
IMAGE=pgvector/pgvector:pg16
HOST_PORT=5433

DAEMON_WAIT_SECONDS=${DAEMON_WAIT_SECONDS:-45}
READY_WAIT_SECONDS=${READY_WAIT_SECONDS:-60}

log() { printf '[dev] %s\n' "$*"; }

# 等 Docker 引擎。刚点过 Start 时 docker info 会连续失败 20~30 秒,
# 旧版脚本在这里直接退出 —— 用户看到的就是"点了 Start 还是失败"。
wait_for_daemon() {
  local waited=0
  while [ "$waited" -lt "$DAEMON_WAIT_SECONDS" ]; do
    if docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
      log "Docker 引擎就绪(等待 ${waited}s)"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# 等数据库接受连接。两个退出条件:超时,或容器中途退出。
# 后者必须立刻报 —— 旧版的 `until` 循环在容器崩溃时会永远卡住,看起来像"卡死"。
wait_until_ready() {
  local waited=0
  while [ "$waited" -lt "$READY_WAIT_SECONDS" ]; do
    if docker exec "$CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
      log "数据库就绪(等待 ${waited}s)"
      return 0
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
      log "容器在等待期间退出了,最近日志:"
      docker logs --tail 30 "$CONTAINER" 2>&1 || true
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  log "数据库 ${READY_WAIT_SECONDS}s 内没有就绪,最近日志:"
  docker logs --tail 30 "$CONTAINER" 2>&1 || true
  return 1
}

if ! wait_for_daemon; then
  cat <<MSG
[dev] Docker 引擎 ${DAEMON_WAIT_SECONDS}s 内没有就绪。这一步只能人工完成:
[dev]   1) 看任务栏右下角的 Docker 图标;没有就启动 Docker Desktop
[dev]   2) 等它显示 "Engine running"(不是 "Starting")
[dev]   3) 再跑一次本脚本;想等更久:DAEMON_WAIT_SECONDS=180 ./scripts/dev-db.sh
[dev] Docker Desktop 起不来时最常见的是 WSL2 后端问题:
[dev]   wsl --update   然后在 Docker 设置里 Restart
MSG
  exit 1
fi

if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  log "容器已在运行"
else
  if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    log "启动已有容器 ${CONTAINER}(上次多半是 Docker 关闭时被一起停掉的)"
    if ! docker start "$CONTAINER" >/dev/null; then
      log "启动失败。最近日志:"
      docker logs --tail 30 "$CONTAINER" 2>&1 || true
      log "反复失败时可重建: docker rm -f ${CONTAINER} && ./scripts/dev-db.sh"
      log "注意:数据在镜像自带的匿名卷里,容器一删这个卷就变成孤儿,新容器拿不到 ——"
      log "      也就是说库里的文档与向量会丢(语料文件本身在 data/files,不受影响)。"
      exit 1
    fi
  else
    log "创建容器 ${CONTAINER}(端口 ${HOST_PORT})"
    docker run -d --name "$CONTAINER" \
      -p "${HOST_PORT}:5432" \
      -e POSTGRES_PASSWORD="$DB_PASSWORD" \
      -e POSTGRES_DB="$DB_NAME" \
      "$IMAGE"
  fi
fi

wait_until_ready

log "启用 pgvector 扩展(幂等)"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" \
  -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null

log "安装 pg_trgm(关键词检索用,幂等)"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" \
  -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" >/dev/null

log "数据库就绪: jdbc:postgresql://localhost:${HOST_PORT}/${DB_NAME}"
