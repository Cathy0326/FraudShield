#!/bin/bash
# Azure Container Apps 部署脚本 (Microsoft AI Agents Hackathon推荐方案)
# Deploy to Azure Container Apps — recommended for Microsoft AI Agents Hackathon.
#
# 前置条件 (Prerequisites):
#   1. az login (Azure CLI已登录)
#   2. 环境变量已设置 (env vars set):
#      ACR_NAME, RESOURCE_GROUP, CONTAINER_APP_ENV
#      AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_KEY, JWT_SECRET
#
# 快速开始 (Quick start):
#   az login
#   export ACR_NAME=fraudshieldacr
#   export RESOURCE_GROUP=fraudshield-rg
#   export CONTAINER_APP_ENV=fraudshield-env
#   ./deploy/deploy-azure.sh latest
set -euo pipefail

IMAGE_TAG="${1:-latest}"

# ── Configuration (override via env vars) ─────────────────────────────────
ACR_NAME="${ACR_NAME:-fraudshield5393acr}"
RESOURCE_GROUP="${RESOURCE_GROUP:-fraudshield-rg}"
# fraudshield-rg already exists in swedencentral (Azure for Students subscription
# placed it there) — must match, since Azure rejects a --location that conflicts
# with an existing resource group.
LOCATION="${LOCATION:-swedencentral}"
CONTAINER_APP_ENV="${CONTAINER_APP_ENV:-fraudshield-env}"
ACR_URI="${ACR_NAME}.azurecr.io"

BACKEND_IMAGE="${ACR_URI}/fraudshield-backend:${IMAGE_TAG}"
FRONTEND_IMAGE="${ACR_URI}/fraudshield-frontend:${IMAGE_TAG}"

echo "=== Azure deployment — tag: ${IMAGE_TAG} ==="
echo "    ACR:  ${ACR_URI}"
echo "    RG:   ${RESOURCE_GROUP}"
echo "    Env:  ${CONTAINER_APP_ENV}"

# ── Step 1: Create resource group (idempotent) ─────────────────────────────
echo "[1/6] Ensuring resource group exists..."
az group create \
    --name "${RESOURCE_GROUP}" \
    --location "${LOCATION}" \
    --output none

# ── Step 2: Create ACR (idempotent) ───────────────────────────────────────
echo "[2/6] Ensuring Azure Container Registry exists..."
az acr create \
    --resource-group "${RESOURCE_GROUP}" \
    --name "${ACR_NAME}" \
    --sku Basic \
    --admin-enabled true \
    --output none 2>/dev/null || echo "ACR already exists"

# ── Step 3: Login and push images ─────────────────────────────────────────
# Skip retag+push when ${BACKEND_IMAGE}/${FRONTEND_IMAGE} already exist locally — CI (Jenkins)
# builds and pushes them under their final ACR-tagged names directly in earlier pipeline stages.
# Manual/local runs build a plain "fraudshield-backend:latest" tag instead, so retag from that.
echo "[3/6] Pushing images to ACR..."
az acr login --name "${ACR_NAME}"

if ! docker image inspect "${BACKEND_IMAGE}" >/dev/null 2>&1; then
    docker tag fraudshield-backend:latest "${BACKEND_IMAGE}"
fi
if ! docker image inspect "${FRONTEND_IMAGE}" >/dev/null 2>&1; then
    docker tag fraudshield-frontend:latest "${FRONTEND_IMAGE}"
fi

docker push "${BACKEND_IMAGE}"
docker push "${FRONTEND_IMAGE}"

# ── Step 4: Create Container Apps environment (idempotent) ─────────────────
echo "[4/6] Ensuring Container Apps environment exists..."
az containerapp env create \
    --name "${CONTAINER_APP_ENV}" \
    --resource-group "${RESOURCE_GROUP}" \
    --location "${LOCATION}" \
    --output none 2>/dev/null || echo "Environment already exists"

# ── Step 5: Deploy backend ─────────────────────────────────────────────────
echo "[5/6] Deploying backend..."
az containerapp create \
    --name fraudshield-backend \
    --resource-group "${RESOURCE_GROUP}" \
    --environment "${CONTAINER_APP_ENV}" \
    --image "${BACKEND_IMAGE}" \
    --registry-server "${ACR_URI}" \
    --target-port 8080 \
    --ingress external \
    --cpu 0.5 \
    --memory 1.0Gi \
    --min-replicas 1 \
    --max-replicas 3 \
    --env-vars \
        "AZURE_OPENAI_ENDPOINT=${AZURE_OPENAI_ENDPOINT:-}" \
        "AZURE_OPENAI_KEY=secretref:openai-key" \
        "AZURE_OPENAI_DEPLOYMENT=${AZURE_OPENAI_DEPLOYMENT:-gpt-4.1-mini}" \
        "JWT_SECRET=secretref:jwt-secret" \
        "AI_ENABLED=true" \
        "SPRING_DATA_REDIS_HOST=${REDIS_HOST:-localhost}" \
        "SPRING_DATA_REDIS_PORT=${REDIS_PORT:-6380}" \
        "SPRING_DATA_REDIS_PASSWORD=secretref:redis-key" \
        "SPRING_DATA_REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-true}" \
        "KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}" \
        "KAFKA_SECURITY_PROTOCOL=${KAFKA_SECURITY_PROTOCOL:-PLAINTEXT}" \
        "KAFKA_SASL_MECHANISM=PLAIN" \
        "KAFKA_SASL_JAAS_CONFIG=secretref:kafka-jaas-config" \
    --output none 2>/dev/null || \
az containerapp update \
    --name fraudshield-backend \
    --resource-group "${RESOURCE_GROUP}" \
    --image "${BACKEND_IMAGE}" \
    --output none

# ── Step 6: Deploy frontend ────────────────────────────────────────────────
# BACKEND_HOST is the backend app's name — Container Apps gives every app in the same
# environment an internal DNS entry resolvable by name, so nginx proxies to it directly
# without a public-internet round trip (see nginx.conf.template / BACKEND_HOST envsubst).
echo "[6/6] Deploying frontend..."

az containerapp create \
    --name fraudshield-frontend \
    --resource-group "${RESOURCE_GROUP}" \
    --environment "${CONTAINER_APP_ENV}" \
    --image "${FRONTEND_IMAGE}" \
    --registry-server "${ACR_URI}" \
    --target-port 80 \
    --ingress external \
    --cpu 0.25 \
    --memory 0.5Gi \
    --min-replicas 1 \
    --max-replicas 2 \
    --env-vars \
        "BACKEND_HOST=fraudshield-backend" \
    --output none 2>/dev/null || \
az containerapp update \
    --name fraudshield-frontend \
    --resource-group "${RESOURCE_GROUP}" \
    --image "${FRONTEND_IMAGE}" \
    --set-env-vars \
        "BACKEND_HOST=fraudshield-backend" \
    --output none

# ── Done: print URLs ───────────────────────────────────────────────────────
echo ""
echo "=== Deployment complete! ==="
BACKEND_FQDN=$(az containerapp show \
    --name fraudshield-backend \
    --resource-group "${RESOURCE_GROUP}" \
    --query "properties.configuration.ingress.fqdn" \
    --output tsv 2>/dev/null || echo "N/A")
FRONTEND_FQDN=$(az containerapp show \
    --name fraudshield-frontend \
    --resource-group "${RESOURCE_GROUP}" \
    --query "properties.configuration.ingress.fqdn" \
    --output tsv 2>/dev/null || echo "N/A")

echo "    Backend:  https://${BACKEND_FQDN}"
echo "    Frontend: https://${FRONTEND_FQDN}"
echo ""
echo "Next: add secrets via 'az containerapp secret set'"
echo "  az containerapp secret set --name fraudshield-backend \\"
echo "    --resource-group ${RESOURCE_GROUP} \\"
echo "    --secrets openai-key=\${AZURE_OPENAI_KEY} jwt-secret=\${JWT_SECRET} redis-key=\${REDIS_KEY} kafka-jaas-config=\${KAFKA_SASL_JAAS_CONFIG}"
