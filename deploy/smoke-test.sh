#!/bin/bash
# 部署后冒烟测试 — 验证刚部署的环境真正可用，而不仅仅是“容器在跑”
# Post-deploy smoke test — verifies the freshly deployed environment actually
# works end-to-end, not just that the containers are running.
#
# Catches the class of bug this project hit repeatedly during manual Azure
# verification (nginx Host header, stale Docker layer, CORS allowlist) —
# all of which left containers "healthy" while the app was unusable.
#
# Usage:
#   ./deploy/smoke-test.sh <backend-url> <frontend-url> <username> <password>
#   e.g. ./deploy/smoke-test.sh https://fraudshield-backend.../ https://fraudshield-frontend.../ admin secret
set -euo pipefail

if [[ $# -lt 4 ]]; then
    echo "Usage: $0 <backend-url> <frontend-url> <username> <password>" >&2
    exit 1
fi

BACKEND_URL="${1%/}"
FRONTEND_URL="${2%/}"
USERNAME="$3"
PASSWORD="$4"

FAIL=0

check() {
    local desc="$1"
    if [[ "$2" == "0" ]]; then
        echo "  [PASS] ${desc}"
    else
        echo "  [FAIL] ${desc}"
        FAIL=1
    fi
}

echo "=== Smoke test: backend=${BACKEND_URL} frontend=${FRONTEND_URL} ==="

# 1. Backend health
echo "[1/4] Backend health check..."
HEALTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${BACKEND_URL}/health" || echo "000")
check "GET /health returns 200 (got ${HEALTH_STATUS})" $([[ "${HEALTH_STATUS}" == "200" ]] && echo 0 || echo 1)

# 2. Frontend serves the SPA
echo "[2/4] Frontend reachability..."
FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${FRONTEND_URL}/" || echo "000")
check "GET / returns 200 (got ${FRONTEND_STATUS})" $([[ "${FRONTEND_STATUS}" == "200" ]] && echo 0 || echo 1)

# 3. Login through the backend directly — catches auth/CORS/DB wiring issues
echo "[3/4] Backend login..."
LOGIN_RESPONSE=$(curl -s -X POST "${BACKEND_URL}/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "${LOGIN_RESPONSE}" | grep -o '"token"[[:space:]]*:[[:space:]]*"[^"]*"' | sed -E 's/.*"([^"]+)"$/\1/')
check "POST /auth/login returns a token" $([[ -n "${TOKEN}" ]] && echo 0 || echo 1)

# 4. An authenticated request through the frontend's nginx proxy — catches
#    Host-header/proxy misconfiguration even when the backend itself is fine
echo "[4/4] Authenticated request via frontend proxy..."
if [[ -n "${TOKEN}" ]]; then
    STATS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${FRONTEND_URL}/api/risk-events/stats" \
        -H "Authorization: Bearer ${TOKEN}")
    check "GET /api/risk-events/stats via frontend proxy returns 200 (got ${STATS_STATUS})" \
        $([[ "${STATS_STATUS}" == "200" ]] && echo 0 || echo 1)
else
    echo "  [SKIP] no token from login step — skipping proxy check"
fi

echo ""
if [[ "${FAIL}" == "0" ]]; then
    echo "=== Smoke test PASSED ==="
else
    echo "=== Smoke test FAILED ==="
fi
exit "${FAIL}"
