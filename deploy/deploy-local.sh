#!/bin/bash
# 本地Docker Compose部署脚本
# Redeploy the stack locally using the newly-built images.
# Usage: ./deploy/deploy-local.sh [IMAGE_TAG]
set -euo pipefail

IMAGE_TAG="${1:-latest}"
COMPOSE_FILE="docker-compose.yml"

echo "=== Local deployment — image tag: ${IMAGE_TAG} ==="

# 更新backend和frontend使用新镜像
# Override image tags via env vars read by docker-compose.yml
export BACKEND_TAG="${IMAGE_TAG}"
export FRONTEND_TAG="${IMAGE_TAG}"

# 重启服务（不影响kafka/redis/zookeeper）
# Only recreate backend & frontend; leave infra services running
docker compose -f "${COMPOSE_FILE}" up -d --no-deps backend frontend

echo "=== Deployment complete ==="
docker compose -f "${COMPOSE_FILE}" ps
