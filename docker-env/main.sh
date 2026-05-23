#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== 启动 Docker 中间件 ==="
cd "$SCRIPT_DIR"
docker compose down
docker compose up -d

echo "=== 等待 PostgreSQL 就绪 ==="
until docker exec postgres pg_isready -U postgres -d ragent 2>/dev/null; do
  sleep 2
done
echo "PostgreSQL 已就绪"

echo "=== 导入数据库 Schema ==="
docker exec -i postgres psql -U postgres -d ragent < "$PROJECT_DIR/resources/database/schema_pg.sql"

echo "=== 导入初始数据 ==="
docker exec -i postgres psql -U postgres -d ragent < "$PROJECT_DIR/resources/database/init_data_pg.sql"

echo "=== 等待 Ollama 就绪 ==="
until docker exec ollama ollama list 2>/dev/null; do
  sleep 2
done
echo "Ollama 已就绪"

echo "=== 拉取 Chat 模型 qwen3:8b-fp16 ==="
docker exec ollama ollama pull qwen3:8b-fp16

echo "=== 拉取 Embedding 模型 qwen3-embedding:8b-fp16 ==="
docker exec ollama ollama pull qwen3-embedding:8b-fp16

echo "=== 完成 ==="
docker ps --filter "name=postgres" --filter "name=redis" --filter "name=rmq" --filter "name=rustfs" --filter "name=ollama"
