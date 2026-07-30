#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${TEST_DIR}/../.." && pwd)"
ENV_FILE="${TEST_DIR}/test.local.env"
CREDENTIALS_FILE="${TEST_DIR}/credentials.local.json"
TOKENS_FILE="${TEST_DIR}/tokens.local.json"
RESULT_DIR="${TEST_DIR}/results"
REQUESTED_LOCK_MODE="${1:-}"

if [[ "${REQUESTED_LOCK_MODE}" != "optimistic" && "${REQUESTED_LOCK_MODE}" != "pessimistic" ]]; then
  echo "사용법: ./run-test.sh optimistic|pessimistic" >&2
  exit 2
fi

cleanup() {
  local test_status=$?
  set +e
  AUTH_BASE_URL="${AUTH_BASE_URL:-}" \
  CREDENTIALS_FILE="${CREDENTIALS_FILE}" \
  TOKENS_FILE="${TOKENS_FILE}" \
  node "${TEST_DIR}/cleanup-secrets.mjs"
  exit "${test_status}"
}
trap cleanup EXIT

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "test.local.env가 없습니다. test.env.example을 복사해 경매 ID를 입력하세요." >&2
  exit 2
fi

if [[ ! -f "${CREDENTIALS_FILE}" ]]; then
  echo "credentials.local.json이 없습니다. 입찰자 user2/user3 계정을 입력하세요." >&2
  exit 2
fi

chmod 600 "${ENV_FILE}" "${CREDENTIALS_FILE}"
set -a
source "${ENV_FILE}"
set +a

for name in AUTH_BASE_URL BASE_URL AUCTION_A_ID AUCTION_B_ID; do
  if [[ -z "${!name:-}" ]]; then
    echo "${name} 값이 비어 있습니다." >&2
    exit 2
  fi
done

AUTH_BASE_URL="${AUTH_BASE_URL}" \
CREDENTIALS_FILE="${CREDENTIALS_FILE}" \
TOKENS_FILE="${TOKENS_FILE}" \
node "${TEST_DIR}/prepare-tokens.mjs"

mkdir -p "${RESULT_DIR}"
RUN_ID="${RUN_ID:-260730-${REQUESTED_LOCK_MODE}-run-01}"

BASE_URL="${BASE_URL}" \
AUCTION_A_ID="${AUCTION_A_ID}" \
AUCTION_B_ID="${AUCTION_B_ID}" \
TOKENS_FILE="${TOKENS_FILE}" \
LOCK_MODE="${REQUESTED_LOCK_MODE}" \
PROFILE="${PROFILE:-load}" \
VUS_PER_AUCTION="${VUS_PER_AUCTION:-2}" \
DURATION="${DURATION:-2m}" \
THINK_TIME="${THINK_TIME:-0.1}" \
FINAL_STATE_WAIT="${FINAL_STATE_WAIT:-1}" \
MAX_P95_MS="${MAX_P95_MS:-2000}" \
MAX_UNEXPECTED_ERROR_RATE="${MAX_UNEXPECTED_ERROR_RATE:-0.01}" \
RUN_ID="${RUN_ID}" \
RESULT_DIR="${RESULT_DIR}" \
"${REPO_DIR}/load-tests/k6/run-lock-test.sh"
