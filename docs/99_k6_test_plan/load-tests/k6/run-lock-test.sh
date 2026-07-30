#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULT_DIR="${RESULT_DIR:-${SCRIPT_DIR}/results}"
LOCK_MODE="${LOCK_MODE:-optimistic}"
PROFILE="${PROFILE:-smoke}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-${LOCK_MODE}-${PROFILE}}"
RESULT_NAME="${RESULT_NAME:-${RUN_ID}}"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6가 없습니다. https://grafana.com/docs/k6/latest/set-up/install-k6/ 를 참고해 설치하세요." >&2
  exit 127
fi

if [[ -z "${AUCTION_A_ID:-}" || -z "${AUCTION_B_ID:-}" ]]; then
  echo "AUCTION_A_ID와 AUCTION_B_ID를 지정해야 합니다." >&2
  exit 2
fi

if [[ -z "${TOKENS_FILE:-}" && -z "${ACCESS_TOKEN:-}" ]]; then
  echo "TOKENS_FILE 또는 ACCESS_TOKEN을 지정해야 합니다." >&2
  exit 2
fi

mkdir -p "${RESULT_DIR}"

echo "실행: mode=${LOCK_MODE}, profile=${PROFILE}, run=${RUN_ID}"
echo "대상: ${BASE_URL:-http://localhost:8000/api/v1}, auctions=${AUCTION_A_ID},${AUCTION_B_ID}"

export RESULT_DIR LOCK_MODE PROFILE RUN_ID RESULT_NAME
k6 run "${SCRIPT_DIR}/bid-lock-stress.js"

echo "결과: ${RESULT_DIR}/${RESULT_NAME}-summary.json"
