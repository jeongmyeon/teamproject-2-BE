#!/usr/bin/env node
import { chmodSync, readFileSync, writeFileSync } from "node:fs";

const authBaseUrl = (process.env.AUTH_BASE_URL || "").replace(/\/$/, "");
const credentialsPath = process.env.CREDENTIALS_FILE;
const tokensPath = process.env.TOKENS_FILE;

if (!authBaseUrl || !credentialsPath || !tokensPath) {
  stop("AUTH_BASE_URL, CREDENTIALS_FILE, TOKENS_FILE이 필요합니다.");
}

let bidders;
try {
  bidders = JSON.parse(readFileSync(credentialsPath, "utf8")).bidders;
} catch (error) {
  stop(`계정 파일을 읽을 수 없습니다: ${error.message}`);
}

if (!Array.isArray(bidders) || bidders.length !== 2) {
  stop("입찰자 계정은 사용자 2와 3, 정확히 2개여야 합니다.");
}

const tokens = [];
for (const [index, bidder] of bidders.entries()) {
  if (!bidder?.email || !bidder?.password) {
    stop(`입찰자 ${index + 2}의 email/password가 비어 있습니다.`);
  }

  const response = await fetch(`${authBaseUrl}/members/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: bidder.email, password: bidder.password }),
  });

  if (!response.ok) {
    stop(`${bidder.name || `user${index + 2}`} 로그인 실패: HTTP ${response.status}`);
  }

  let body;
  try {
    body = await response.json();
  } catch {
    stop(`${bidder.name || `user${index + 2}`} 로그인 응답이 JSON이 아닙니다.`);
  }

  const accessToken = body?.accessToken || body?.data?.accessToken || body?.result?.accessToken;
  if (!accessToken) {
    stop(`${bidder.name || `user${index + 2}`} 로그인 응답에 accessToken이 없습니다.`);
  }
  tokens.push(accessToken);
}

writeFileSync(tokensPath, `${JSON.stringify({ tokens }, null, 2)}\n`, { mode: 0o600 });
chmodSync(tokensPath, 0o600);
console.log("입찰자 2명의 임시 토큰을 준비했습니다. 토큰 값은 출력하지 않습니다.");

function stop(message) {
  console.error(message);
  process.exit(1);
}
