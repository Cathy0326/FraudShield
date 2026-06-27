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

# 启动整个stack；已健康运行的kafka/redis/zookeeper不会被重启，
# 但首次运行（如全新的Jenkins dind环境）时会被创建，backend才能解析到它们
# Bring up the whole stack; already-healthy kafka/redis/zookeeper are left alone,
# but get created on a first run (e.g. a fresh Jenkins dind env) so backend can resolve them
docker compose -f "${COMPOSE_FILE}" up -d backend frontend prometheus grafana

echo "=== Deployment complete ==="
docker compose -f "${COMPOSE_FILE}" ps
