#!/bin/bash
# AWS ECR + EC2 部署脚本
# Deploy to AWS ECR (push) then SSH into EC2 to pull and restart.
#
# 前置条件 (Prerequisites):
#   1. aws configure (AWS CLI已配置)
#   2. 环境变量已设置 (env vars set):
#      AWS_ACCOUNT_ID, AWS_REGION, EC2_HOST, EC2_KEY_FILE
#      JWT_SECRET, AZURE_OPENAI_KEY (optional)
#
# 快速开始 (Quick start):
#   export AWS_ACCOUNT_ID=123456789012
#   export AWS_REGION=eu-west-1
#   export EC2_HOST=ec2-user@1.2.3.4
#   export EC2_KEY_FILE=~/.ssh/fraudshield-key.pem
#   ./deploy/deploy-aws.sh latest
set -euo pipefail

IMAGE_TAG="${1:-latest}"

# ── Configuration ──────────────────────────────────────────────────────────
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:?Set AWS_ACCOUNT_ID}"
AWS_REGION="${AWS_REGION:-eu-west-1}"
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
EC2_HOST="${EC2_HOST:?Set EC2_HOST (e.g. ec2-user@1.2.3.4)}"
EC2_KEY_FILE="${EC2_KEY_FILE:?Set EC2_KEY_FILE path to .pem}"

BACKEND_IMAGE="${ECR_URI}/fraudshield-backend:${IMAGE_TAG}"
FRONTEND_IMAGE="${ECR_URI}/fraudshield-frontend:${IMAGE_TAG}"

echo "=== AWS deployment — tag: ${IMAGE_TAG} ==="
echo "    ECR: ${ECR_URI}"
echo "    EC2: ${EC2_HOST}"

# ── Step 1: Create ECR repos (idempotent) ─────────────────────────────────
echo "[1/4] Ensuring ECR repositories exist..."
for REPO in fraudshield-backend fraudshield-frontend; do
    aws ecr describe-repositories --repository-names "${REPO}" \
        --region "${AWS_REGION}" > /dev/null 2>&1 || \
    aws ecr create-repository --repository-name "${REPO}" \
        --region "${AWS_REGION}" --output none
done

# ── Step 2: Login and push ─────────────────────────────────────────────────
echo "[2/4] Pushing images to ECR..."
aws ecr get-login-password --region "${AWS_REGION}" | \
    docker login --username AWS --password-stdin "${ECR_URI}"

docker tag fraudshield-backend:latest  "${BACKEND_IMAGE}"
docker tag fraudshield-frontend:latest "${FRONTEND_IMAGE}"
docker push "${BACKEND_IMAGE}"
docker push "${FRONTEND_IMAGE}"

# ── Step 3: Deploy to EC2 via SSH ─────────────────────────────────────────
echo "[3/4] Deploying to EC2..."
ssh -i "${EC2_KEY_FILE}" -o StrictHostKeyChecking=no "${EC2_HOST}" << REMOTE
set -e

# Login to ECR from EC2
aws ecr get-login-password --region ${AWS_REGION} | \
    docker login --username AWS --password-stdin ${ECR_URI}

# Pull new images
docker pull ${BACKEND_IMAGE}
docker pull ${FRONTEND_IMAGE}

# Restart backend
docker stop fraudshield-backend 2>/dev/null || true
docker rm   fraudshield-backend 2>/dev/null || true
docker run -d --name fraudshield-backend \
    --restart unless-stopped \
    -p 8080:8080 \
    -e JWT_SECRET='${JWT_SECRET:-change-me}' \
    -e AZURE_OPENAI_ENDPOINT='${AZURE_OPENAI_ENDPOINT:-}' \
    -e AZURE_OPENAI_KEY='${AZURE_OPENAI_KEY:-}' \
    -e AI_ENABLED=true \
    -e SPRING_DATA_REDIS_HOST=host-gateway \
    ${BACKEND_IMAGE}

# Restart frontend
docker stop fraudshield-frontend 2>/dev/null || true
docker rm   fraudshield-frontend 2>/dev/null || true
docker run -d --name fraudshield-frontend \
    --restart unless-stopped \
    -p 3000:80 \
    ${FRONTEND_IMAGE}

echo "--- Running containers ---"
docker ps --filter "name=fraudshield"
REMOTE

echo ""
echo "[4/4] Done!"
echo "    Backend:  http://${EC2_HOST##*@}:8080/health"
echo "    Frontend: http://${EC2_HOST##*@}:3000"
