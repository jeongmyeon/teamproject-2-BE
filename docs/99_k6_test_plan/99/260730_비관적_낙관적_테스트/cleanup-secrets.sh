#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${TEST_DIR}/test.local.env"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  source "${ENV_FILE}"
  set +a
fi

AUTH_BASE_URL="${AUTH_BASE_URL:-}" \
CREDENTIALS_FILE="${TEST_DIR}/credentials.local.json" \
TOKENS_FILE="${TEST_DIR}/tokens.local.json" \
node "${TEST_DIR}/cleanup-secrets.mjs"
