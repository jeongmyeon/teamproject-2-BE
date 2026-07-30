#!/usr/bin/env node
import { readFileSync } from "node:fs";

const [leftPath, rightPath] = process.argv.slice(2);
if (!leftPath || !rightPath) {
  console.error("사용법: node compare-results.mjs <optimistic-summary.json> <pessimistic-summary.json>");
  process.exit(2);
}

const reports = [load(leftPath), load(rightPath)];
const fields = [
  ["입찰 시도", "bid_attempts", "count", "count"],
  ["입찰 성공", "bid_accepted", "passes", "count"],
  ["입찰 성공률", "bid_accepted", "rate", "percent"],
  ["409 충돌", "lock_conflicts", "count", "count"],
  ["400/422 거절", "business_rejections", "count", "count"],
  ["429 제한", "throttled_requests", "count", "count"],
  ["예상 밖 오류율", "unexpected_errors", "rate", "percent"],
  ["상세 조회 오류", "detail_errors", "count", "count"],
  ["입찰 평균 지연", "bid_latency", "avg", "ms"],
  ["입찰 p95", "bid_latency", "p(95)", "ms"],
  ["입찰 p99", "bid_latency", "p(99)", "ms"],
  ["상세 조회 p95", "detail_latency", "p(95)", "ms"],
  ["전체 HTTP p95", "http_req_duration", "p(95)", "ms"],
  ["경매 A 성공", "auction_a_accepted", "count", "count"],
  ["경매 B 성공", "auction_b_accepted", "count", "count"],
  ["경매 A 최초가", "initial_current_bid_a", "value", "count"],
  ["경매 B 최초가", "initial_current_bid_b", "value", "count"],
  ["경매 A 최초 입찰 수", "initial_bid_count_a", "value", "count"],
  ["경매 B 최초 입찰 수", "initial_bid_count_b", "value", "count"],
  ["경매 A 최종가", "final_current_bid_a", "value", "count"],
  ["경매 B 최종가", "final_current_bid_b", "value", "count"],
  ["경매 A 최종 입찰 수", "final_bid_count_a", "value", "count"],
  ["경매 B 최종 입찰 수", "final_bid_count_b", "value", "count"],
  ["최종 상태 조회 오류", "final_state_errors", "count", "count"],
];

const labels = reports.map((report) => `${report.metadata.lockMode} (${report.metadata.profile})`);
console.log(`| 지표 | ${labels[0]} | ${labels[1]} |`);
console.log("|---|---:|---:|");
for (const [label, metric, key, format] of fields) {
  const values = reports.map((report) => report.metrics?.[metric]?.values?.[key] ?? 0);
  console.log(`| ${label} | ${display(values[0], format)} | ${display(values[1], format)} |`);
}

console.log("\n해석 시 성공률만 보지 말고 p95/p99, 409, 5xx/예상 밖 오류, 두 경매 간 성공 편차를 함께 확인하세요.");
for (const report of reports) {
  const mode = report.metadata.lockMode;
  const aDelta = value(report, "final_bid_count_a", "value") - value(report, "initial_bid_count_a", "value");
  const bDelta = value(report, "final_bid_count_b", "value") - value(report, "initial_bid_count_b", "value");
  const aAccepted = value(report, "auction_a_accepted", "count");
  const bAccepted = value(report, "auction_b_accepted", "count");
  console.log(`${mode} 1차 정합성: A 이력 증가=${aDelta}/k6 성공=${aAccepted}, B 이력 증가=${bDelta}/k6 성공=${bAccepted}`);
}

function load(path) {
  try {
    const report = JSON.parse(readFileSync(path, "utf8"));
    if (!report.metadata || !report.metrics) throw new Error("k6 비교 결과 형식이 아닙니다.");
    return report;
  } catch (error) {
    console.error(`${path} 읽기 실패: ${error.message}`);
    process.exit(1);
  }
}

function display(value, format) {
  if (value === undefined || value === null) return "-";
  if (format === "percent") return `${(value * 100).toFixed(2)}%`;
  if (format === "ms") return `${Number(value).toFixed(2)} ms`;
  return String(value);
}

function value(report, metric, key) {
  return Number(report.metrics?.[metric]?.values?.[key] ?? 0);
}
