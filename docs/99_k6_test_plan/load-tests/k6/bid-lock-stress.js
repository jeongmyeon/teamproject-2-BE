import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Counter, Gauge, Rate, Trend } from "k6/metrics";
import exec from "k6/execution";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8000/api/v1").replace(/\/$/, "");
const AUCTION_A_ID = __ENV.AUCTION_A_ID || "";
const AUCTION_B_ID = __ENV.AUCTION_B_ID || "";
const LOCK_MODE = (__ENV.LOCK_MODE || "optimistic").toLowerCase();
const PROFILE = (__ENV.PROFILE || "smoke").toLowerCase();
const BID_STRATEGY = (__ENV.BID_STRATEGY || "refresh").toLowerCase();
const THINK_TIME = numberEnv("THINK_TIME", 0.1, 0);
const FINAL_STATE_WAIT = numberEnv("FINAL_STATE_WAIT", 1, 0);
const BID_STEP_OVERRIDE = numberEnv("BID_STEP", 0, 0);
const MAX_P95_MS = numberEnv("MAX_P95_MS", 2000, 1);
const MAX_UNEXPECTED_ERROR_RATE = numberEnv("MAX_UNEXPECTED_ERROR_RATE", 0.01, 0);
const RESULT_DIR = __ENV.RESULT_DIR || "load-tests/k6/results";
const RESULT_NAME = __ENV.RESULT_NAME || `${LOCK_MODE}-${PROFILE}`;
const RUN_ID = __ENV.RUN_ID || RESULT_NAME;
const POD_HEADER = (__ENV.POD_HEADER || "X-Pod-Name").toLowerCase();
const DEBUG = (__ENV.DEBUG || "false").toLowerCase() === "true";
const REQUIRE_LIVE = (__ENV.REQUIRE_LIVE || "true").toLowerCase() === "true";

const tokenFileContent = __ENV.TOKENS_FILE ? open(__ENV.TOKENS_FILE) : "";
const TOKENS = loadTokens(tokenFileContent, __ENV.ACCESS_TOKEN || "");

const bidAttempts = new Counter("bid_attempts");
const bidAccepted = new Rate("bid_accepted");
const lockConflicts = new Counter("lock_conflicts");
const businessRejections = new Counter("business_rejections");
const throttledRequests = new Counter("throttled_requests");
const unexpectedErrors = new Rate("unexpected_errors");
const detailErrors = new Counter("detail_errors");
const detailLatency = new Trend("detail_latency", true);
const bidLatency = new Trend("bid_latency", true);
const podHits = new Counter("pod_hits");

const auctionAAttempts = new Counter("auction_a_attempts");
const auctionBAttempts = new Counter("auction_b_attempts");
const auctionAAccepted = new Counter("auction_a_accepted");
const auctionBAccepted = new Counter("auction_b_accepted");
const initialCurrentBidA = new Gauge("initial_current_bid_a");
const initialCurrentBidB = new Gauge("initial_current_bid_b");
const initialBidCountA = new Gauge("initial_bid_count_a");
const initialBidCountB = new Gauge("initial_bid_count_b");
const finalCurrentBidA = new Gauge("final_current_bid_a");
const finalCurrentBidB = new Gauge("final_current_bid_b");
const finalBidCountA = new Gauge("final_bid_count_a");
const finalBidCountB = new Gauge("final_bid_count_b");
const finalStateErrors = new Counter("final_state_errors");

export const options = {
  scenarios: scenariosFor(PROFILE),
  thresholds: {
    checks: ["rate>0.99"],
    unexpected_errors: [`rate<${MAX_UNEXPECTED_ERROR_RATE}`],
    bid_latency: [`p(95)<${MAX_P95_MS}`],
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  tags: {
    test_type: "bid_lock_comparison",
    lock_mode: LOCK_MODE,
    profile: PROFILE,
  },
  insecureSkipTLSVerify: (__ENV.INSECURE_SKIP_TLS_VERIFY || "false").toLowerCase() === "true",
};

export function setup() {
  validateConfiguration();

  const token = TOKENS[0];
  const responses = http.batch([
    ["GET", `${BASE_URL}/auctions/${AUCTION_A_ID}`, null, requestParams(token, "auction_detail_a")],
    ["GET", `${BASE_URL}/auctions/${AUCTION_B_ID}`, null, requestParams(token, "auction_detail_b")],
  ]);

  const details = responses.map((response, index) => {
    const label = index === 0 ? "A" : "B";
    if (response.status !== 200) {
      fail(`경매 ${label} 사전 점검 실패: HTTP ${response.status} ${shortBody(response.body)}`);
    }
    const detail = parseAuction(response, label);
    if (REQUIRE_LIVE && detail.status !== "LIVE") {
      fail(`경매 ${label} 상태가 LIVE가 아닙니다: ${detail.status}`);
    }
    return detail;
  });

  console.log(
    `[preflight] mode=${LOCK_MODE} profile=${PROFILE} strategy=${BID_STRATEGY} ` +
      `A(id=${AUCTION_A_ID}, current=${details[0].currentBid}, step=${details[0].minIncrement}) ` +
      `B(id=${AUCTION_B_ID}, current=${details[1].currentBid}, step=${details[1].minIncrement}) ` +
      `tokens=${TOKENS.length}`,
  );

  initialCurrentBidA.add(details[0].currentBid);
  initialCurrentBidB.add(details[1].currentBid);
  initialBidCountA.add(details[0].bidCount);
  initialBidCountB.add(details[1].bidCount);

  return {
    auctions: {
      A: { id: AUCTION_A_ID, ...details[0] },
      B: { id: AUCTION_B_ID, ...details[1] },
    },
  };
}

export function teardown(data) {
  if (FINAL_STATE_WAIT > 0) sleep(FINAL_STATE_WAIT);
  const token = TOKENS[0];
  const responses = http.batch([
    ["GET", `${BASE_URL}/auctions/${data.auctions.A.id}`, null, requestParams(token, "final_detail_a")],
    ["GET", `${BASE_URL}/auctions/${data.auctions.B.id}`, null, requestParams(token, "final_detail_b")],
  ]);

  responses.forEach((response, index) => {
    const label = index === 0 ? "A" : "B";
    if (response.status !== 200) {
      finalStateErrors.add(1, { auction: label });
      console.error(`[final:${label}] HTTP ${response.status} ${shortBody(response.body)}`);
      return;
    }

    const detail = parseAuction(response, label);
    if (label === "A") {
      finalCurrentBidA.add(detail.currentBid);
      finalBidCountA.add(detail.bidCount);
    } else {
      finalCurrentBidB.add(detail.currentBid);
      finalBidCountB.add(detail.bidCount);
    }
    console.log(`[final:${label}] current=${detail.currentBid} bidCount=${detail.bidCount} status=${detail.status}`);
  });
}

export function bidAuctionA(data) {
  bidOnce("A", data.auctions.A);
}

export function bidAuctionB(data) {
  bidOnce("B", data.auctions.B);
}

function bidOnce(label, initialAuction) {
  const token = tokenForVu();
  let currentBid = initialAuction.currentBid;
  let minIncrement = initialAuction.minIncrement;

  if (BID_STRATEGY === "refresh") {
    const detailResponse = http.get(
      `${BASE_URL}/auctions/${initialAuction.id}`,
      requestParams(token, `auction_detail_${label.toLowerCase()}`, label),
    );
    detailLatency.add(detailResponse.timings.duration, { auction: label });
    recordPod(detailResponse, label);

    const detailOk = check(detailResponse, {
      [`auction ${label} detail is 200`]: (response) => response.status === 200,
    });
    if (!detailOk) {
      detailErrors.add(1, { auction: label });
      if (DEBUG) console.error(`[detail:${label}] HTTP ${detailResponse.status} ${shortBody(detailResponse.body)}`);
      return;
    }

    const detail = parseAuction(detailResponse, label);
    currentBid = detail.currentBid;
    minIncrement = detail.minIncrement;
  }

  const amount = nextBidAmount(currentBid, minIncrement, initialAuction);
  const response = http.post(
    `${BASE_URL}/auctions/${initialAuction.id}/bids`,
    JSON.stringify({ amount }),
    requestParams(token, `place_bid_${label.toLowerCase()}`, label, true),
  );

  recordBidMetrics(response, label, amount);
  if (THINK_TIME > 0) sleep(THINK_TIME);
}

function nextBidAmount(currentBid, minIncrement, initialAuction) {
  const step = BID_STEP_OVERRIDE || minIncrement || 1;
  if (BID_STRATEGY === "sequence") {
    return initialAuction.currentBid + step * (exec.scenario.iterationInTest + 1);
  }
  return currentBid + step;
}

function recordBidMetrics(response, label, amount) {
  const status = response.status;
  const accepted = status >= 200 && status < 300;
  const expected = accepted || [400, 409, 422, 429].includes(status);

  bidAttempts.add(1, { auction: label });
  bidAccepted.add(accepted, { auction: label });
  unexpectedErrors.add(!expected, { auction: label, status: String(status) });
  bidLatency.add(response.timings.duration, { auction: label });
  if (label === "A") auctionAAttempts.add(1);
  else auctionBAttempts.add(1);

  if (accepted) {
    if (label === "A") auctionAAccepted.add(1);
    else auctionBAccepted.add(1);
  } else if (status === 409) {
    lockConflicts.add(1, { auction: label });
  } else if (status === 400 || status === 422) {
    businessRejections.add(1, { auction: label });
  } else if (status === 429) {
    throttledRequests.add(1, { auction: label });
  }

  recordPod(response, label);
  check(response, {
    [`auction ${label} bid is accepted or expected rejection`]: () => expected,
  });

  if (DEBUG && !accepted) {
    console.warn(`[bid:${label}] amount=${amount} HTTP ${status} ${shortBody(response.body)}`);
  }
}

function recordPod(response, label) {
  const header = Object.entries(response.headers).find(([name]) => name.toLowerCase() === POD_HEADER);
  const pod = header?.[1];
  if (pod) podHits.add(1, { auction: label, pod: String(pod).slice(0, 80) });
}

function requestParams(token, name, auction = "preflight", isBid = false) {
  return {
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "X-K6-Test-Run": RUN_ID,
    },
    tags: { name, auction, request_kind: isBid ? "bid" : "detail" },
    responseCallback: isBid
      ? http.expectedStatuses(200, 201, 204, 400, 409, 422, 429)
      : http.expectedStatuses(200),
  };
}

function parseAuction(response, label) {
  let payload;
  try {
    payload = response.json();
  } catch (error) {
    fail(`경매 ${label} 응답이 JSON이 아닙니다: ${error.message}`);
  }

  const auction = payload?.data?.auction || payload?.data || payload?.auction || payload;
  const currentBid = Number(auction?.currentBid ?? auction?.startPrice);
  const minIncrement = Number(auction?.minIncrement ?? BID_STEP_OVERRIDE);

  if (!Number.isFinite(currentBid)) {
    fail(`경매 ${label} 응답에서 currentBid/startPrice를 찾지 못했습니다.`);
  }
  if (!Number.isFinite(minIncrement) || minIncrement <= 0) {
    fail(`경매 ${label} 응답에서 양수 minIncrement를 찾지 못했습니다. BID_STEP을 지정하세요.`);
  }

  const bidCount = Number(auction?.bidCount ?? 0);
  return {
    currentBid,
    minIncrement,
    bidCount: Number.isFinite(bidCount) ? bidCount : 0,
    status: String(auction?.status || "UNKNOWN").toUpperCase(),
  };
}

function validateConfiguration() {
  if (!AUCTION_A_ID || !AUCTION_B_ID) fail("AUCTION_A_ID와 AUCTION_B_ID를 모두 지정하세요.");
  if (AUCTION_A_ID === AUCTION_B_ID) fail("두 경매 ID는 서로 달라야 합니다.");
  if (!TOKENS.length) fail("TOKENS_FILE 또는 ACCESS_TOKEN으로 입찰자 토큰을 지정하세요.");
  if (!["optimistic", "pessimistic"].includes(LOCK_MODE)) {
    fail("LOCK_MODE는 optimistic 또는 pessimistic이어야 합니다.");
  }
  if (!["refresh", "sequence"].includes(BID_STRATEGY)) {
    fail("BID_STRATEGY는 refresh 또는 sequence이어야 합니다.");
  }
  if (!["smoke", "load", "stress", "spike"].includes(PROFILE)) {
    fail("PROFILE은 smoke, load, stress, spike 중 하나여야 합니다.");
  }
  if (["stress", "spike"].includes(PROFILE) && __ENV.CONFIRM_STRESS !== "I_UNDERSTAND") {
    fail("stress/spike 실행 전 CONFIRM_STRESS=I_UNDERSTAND를 지정하세요.");
  }
}

function tokenForVu() {
  return TOKENS[(exec.vu.idInTest - 1) % TOKENS.length];
}

function loadTokens(content, fallback) {
  const values = [];
  if (content.trim()) {
    try {
      const parsed = JSON.parse(content);
      const candidates = Array.isArray(parsed) ? parsed : parsed.tokens;
      if (!Array.isArray(candidates)) throw new Error("tokens 배열이 없습니다.");
      for (const entry of candidates) {
        const value = typeof entry === "string" ? entry : entry?.accessToken || entry?.token;
        if (value) values.push(value);
      }
    } catch (jsonError) {
      for (const line of content.split(/\r?\n/)) {
        const value = line.trim();
        if (value && !value.startsWith("#")) values.push(value);
      }
    }
  }
  if (!values.length && fallback.trim()) values.push(fallback.trim());
  return values.map((value) => value.replace(/^Bearer\s+/i, "").trim()).filter(Boolean);
}

function scenariosFor(profile) {
  const common = { gracefulStop: __ENV.GRACEFUL_STOP || "10s" };
  if (profile === "smoke") {
    const vus = integerEnv("VUS_PER_AUCTION", 2, 1);
    const duration = __ENV.DURATION || "15s";
    return {
      auction_a: { executor: "constant-vus", exec: "bidAuctionA", vus, duration, ...common },
      auction_b: { executor: "constant-vus", exec: "bidAuctionB", vus, duration, ...common },
    };
  }

  if (profile === "load") {
    const vus = integerEnv("VUS_PER_AUCTION", 10, 1);
    const duration = __ENV.DURATION || "2m";
    return {
      auction_a: { executor: "constant-vus", exec: "bidAuctionA", vus, duration, ...common },
      auction_b: { executor: "constant-vus", exec: "bidAuctionB", vus, duration, ...common },
    };
  }

  const defaultStages = profile === "spike" ? "10s:5,10s:50,30s:50,20s:0" : "30s:5,1m:15,2m:30,1m:50,30s:0";
  const stages = parseStages(__ENV.STAGES || defaultStages);
  return {
    auction_a: { executor: "ramping-vus", exec: "bidAuctionA", startVUs: 0, stages, ...common },
    auction_b: { executor: "ramping-vus", exec: "bidAuctionB", startVUs: 0, stages, ...common },
  };
}

function parseStages(value) {
  return value.split(",").map((item) => {
    const [duration, rawTarget] = item.trim().split(":");
    const target = Number(rawTarget);
    if (!duration || !Number.isInteger(target) || target < 0) {
      throw new Error(`잘못된 STAGES 항목: ${item}`);
    }
    return { duration, target };
  });
}

function integerEnv(name, fallback, min) {
  const value = Number(__ENV[name] ?? fallback);
  if (!Number.isInteger(value) || value < min) throw new Error(`${name}은 ${min} 이상의 정수여야 합니다.`);
  return value;
}

function numberEnv(name, fallback, min) {
  const value = Number(__ENV[name] ?? fallback);
  if (!Number.isFinite(value) || value < min) throw new Error(`${name}은 ${min} 이상의 숫자여야 합니다.`);
  return value;
}

function shortBody(body) {
  return String(body || "").replace(/\s+/g, " ").slice(0, 300);
}

export function handleSummary(data) {
  if (!/^[a-zA-Z0-9._-]+$/.test(RESULT_NAME)) {
    throw new Error("RESULT_NAME에는 영문, 숫자, 점, 밑줄, 하이픈만 사용할 수 있습니다.");
  }

  const report = {
    metadata: {
      generatedAt: new Date().toISOString(),
      runId: RUN_ID,
      lockMode: LOCK_MODE,
      profile: PROFILE,
      bidStrategy: BID_STRATEGY,
      baseUrl: BASE_URL,
      auctionAId: AUCTION_A_ID,
      auctionBId: AUCTION_B_ID,
      tokenCount: TOKENS.length,
    },
    metrics: data.metrics,
  };

  return {
    stdout: summaryText(data),
    [`${RESULT_DIR}/${RESULT_NAME}-summary.json`]: JSON.stringify(report, null, 2),
  };
}

function summaryText(data) {
  const metric = (name, key, fallback = 0) => data.metrics[name]?.values?.[key] ?? fallback;
  const attempts = metric("bid_attempts", "count");
  const accepted = metric("bid_accepted", "passes");
  const acceptanceRate = metric("bid_accepted", "rate") * 100;
  const conflicts = metric("lock_conflicts", "count");
  const business = metric("business_rejections", "count");
  const throttled = metric("throttled_requests", "count");
  const unexpected = metric("unexpected_errors", "rate") * 100;
  const detailErrorCount = metric("detail_errors", "count");
  const p95 = metric("bid_latency", "p(95)");
  const p99 = metric("bid_latency", "p(99)");

  return `\n=== Bid lock comparison: ${LOCK_MODE} / ${PROFILE} ===\n` +
    `attempts=${attempts} accepted=${accepted} acceptance=${acceptanceRate.toFixed(2)}%\n` +
    `conflicts(409)=${conflicts} business_rejections(400/422)=${business} throttled(429)=${throttled}\n` +
    `unexpected_error_rate=${unexpected.toFixed(2)}% detail_errors=${detailErrorCount}\n` +
    `bid_latency_p95=${p95.toFixed(2)}ms p99=${p99.toFixed(2)}ms detail_latency_p95=${metric("detail_latency", "p(95)").toFixed(2)}ms\n` +
    `auction_A attempts=${metric("auction_a_attempts", "count")} accepted=${metric("auction_a_accepted", "count")}\n` +
    `auction_B attempts=${metric("auction_b_attempts", "count")} accepted=${metric("auction_b_accepted", "count")}\n` +
    `initial_A current=${metric("initial_current_bid_a", "value")} bidCount=${metric("initial_bid_count_a", "value")}\n` +
    `initial_B current=${metric("initial_current_bid_b", "value")} bidCount=${metric("initial_bid_count_b", "value")}\n` +
    `final_A current=${metric("final_current_bid_a", "value")} bidCount=${metric("final_bid_count_a", "value")}\n` +
    `final_B current=${metric("final_current_bid_b", "value")} bidCount=${metric("final_bid_count_b", "value")}\n\n`;
}
