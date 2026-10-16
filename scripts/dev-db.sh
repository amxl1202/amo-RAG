#!/usr/bin/env bash
# 本地开发辅助:确保 pgvector 容器在跑,并做首次初始化(vector 扩展)。
# 应用自身启动时通过 schema.sql 自动建表,无需手动执行 DDL。
set -e

CONTAINER=rag-pg

if ! docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
  echo "[dev] Docker 守护进程未运行,请先启动 Docker Desktop"
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "[dev] 启动已有容器 ${CONTAINER}"
    docker start "${CONTAINER}"
  else
    echo "[dev] 创建容器 ${CONTAINER} (端口 5433)"
    docker run -d --name "${CONTAINER}" \
      -p 5433:5432 \
      -e POSTGRES_PASSWORD=rag123456 \
      -e POSTGRES_DB=rag_kb \
      pgvector/pgvector:pg16
  fi
  echo "[dev] 等待数据库就绪..."
  until docker exec "${CONTAINER}" pg_isready -U postgres -d rag_kb >/dev/null 2>&1; do
    sleep 1
  done
fi

echo "[dev] 启用 pgvector 扩展(幂等)"
docker exec "${CONTAINER}" psql -U postgres -d rag_kb \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

echo "[dev] 数据库就绪: jdbc:postgresql://localhost:5433/rag_kb"
