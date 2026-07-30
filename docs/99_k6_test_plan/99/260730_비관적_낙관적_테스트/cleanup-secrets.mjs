#!/usr/bin/env node
import { existsSync, readFileSync, unlinkSync } from "node:fs";

const authBaseUrl = (process.env.AUTH_BASE_URL || "").replace(/\/$/, "");
const credentialsPath = process.env.CREDENTIALS_FILE;
const tokensPath = process.env.TOKENS_FILE;

if (!credentialsPath || !tokensPath) {
  console.error("CREDENTIALS_FILE과 TOKENS_FILE이 필요합니다.");
  process.exit(2);
}

if (authBaseUrl && existsSync(tokensPath)) {
  try {
    const tokens = JSON.parse(readFileSync(tokensPath, "utf8")).tokens || [];
    await Promise.all(tokens.map(async (token, index) => {
      try {
        const response = await fetch(`${authBaseUrl}/members/logout`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
          },
          signal: AbortSignal.timeout(5000),
        });
        console.log(`user${index + 2} 서버 로그아웃: HTTP ${response.status}`);
      } catch (error) {
        console.warn(`user${index + 2} 서버 로그아웃 실패: ${error.message}`);
      }
    }));
  } catch (error) {
    console.warn(`임시 토큰을 읽지 못해 서버 로그아웃을 생략합니다: ${error.message}`);
  }
}

for (const path of [tokensPath, credentialsPath]) {
  if (existsSync(path)) unlinkSync(path);
}
console.log("로컬 계정·토큰 임시 파일을 삭제했습니다.");
