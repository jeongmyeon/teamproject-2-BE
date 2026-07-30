# 260730 경매 비관적 락 테스트 명세와 예측값

작성일: 2026-07-30 (Asia/Seoul)

## 1. 범위와 기준점

이번 테스트는 **Auction Service의 입찰 처리만** 대상으로 한다. Product, Payment, Order 및 다른 도메인의 코드나 부하는 변경하지 않는다.

| 항목 | 값 |
|---|---|
| 기준 브랜치 | `origin/develop` |
| 기준 SHA | `4492cbb` |
| 비관적 테스트 브랜치 | `test/pessimistic-lock-260730` |
| 최종 목표 | 테스트 종료 후 경매 코드가 기준 SHA의 낙관적 락 상태와 동일 |
| API 계약 | 입찰 커밋 성공 `201`, 업무 검증 실패 기존 `4xx` 유지 |
| 공통 기능 | Kafka 개선과 커밋 후 WebSocket 발행 유지 |

## 2. 비관적 variant 구현 명세

| 구분 | 비관적 테스트 상태 |
|---|---|
| 경매 조회 | `findByIdForUpdate()` |
| DB 락 | JPA `PESSIMISTIC_WRITE`, PostgreSQL `SELECT ... FOR UPDATE` |
| 트랜잭션 | 입찰 저장과 경매 갱신을 하나의 `REQUIRES_NEW`, `READ_COMMITTED` 트랜잭션에서 처리 |
| 검증 시점 | 행 락 획득 후 최신 경매 상태·최소 입찰가 검증 |
| 낙관적 재시도 | 실행 경로에서 비활성 |
| `Auction.version` | DB 컬럼은 유지하되 JPA `@Version`은 제거 |
| flush | Bid INSERT와 Auction UPDATE를 성공 응답 전에 flush |
| WebSocket | 트랜잭션 커밋 이후 1회 발행, 발행 실패는 커밋 결과를 되돌리지 않음 |

`version` 컬럼을 삭제하지 않는 이유는 낙관적 락 복구 시 스키마 변경 없이 현재 상태로 돌아가기 위해서다. AWS 데이터의 기존 version 값은 유지될 수 있으나, 비관적 실행 중 **version 증가량은 0**이어야 한다.

## 3. 배포 전 검증 결과

2026-07-30 16:13 KST에 경매 모듈의 비관적 락 관련 테스트를 실행했다.

```bash
cd auction
bash gradlew test \
  --tests 'com.biddy.auction.bid.application.service.BidServicePessimisticLockTest' \
  --tests 'com.biddy.auction.bid.application.service.BidTransactionServiceTest' \
  --tests 'com.biddy.auction.bid.PessimisticLockBidServiceTest'
```

| 테스트 묶음 | 건수 | 실패 | 결과 |
|---|---:|---:|---|
| 비관적 락 통합·동시성 | 6 | 0 | PASS |
| 입찰 오케스트레이터 | 3 | 0 | PASS |
| 입찰 트랜잭션 | 5 | 0 | PASS |
| 합계 | 14 | 0 | PASS |

이어서 `bash gradlew test`로 경매 모듈 전체 회귀 테스트 75개를 실행했고 실패·오류 없이 PASS했다.

통합 테스트 관측:

- 500개 동시 요청 시 커밋 31건, 업무 거절 469건, 예외 0건
- 성공 건수 = 신규 Bid 행 수 = `Auction.bidCount` 증가량
- 최종 `currentBid` = 신규 Bid의 최고 금액
- 비관적 variant에서 `version` 증가 없음

이 수치는 로컬 정합성 테스트 결과이며 AWS 성능 예측값으로 사용하지 않는다.

## 4. AWS 실행 조건

낙관적 실행과 동일하게 상품별 2 VU, 전체 4 VU로 두 경매만 테스트한다.

| 항목 | Smoke | 본 테스트 |
|---|---:|---:|
| Profile | smoke | load |
| 경매 수 | 2 | 2 |
| 경매별 VU | 2 | 2 |
| 전체 VU | 4 | 4 |
| 시간 | 15초 | 2분 |
| think time | 0.1초 | 0.1초 |
| 입찰 전략 | 상세 조회 후 `currentBid + minIncrement` | 동일 |

비관적 실행에는 낙관적 실행에서 가격이 변경된 `A-E290D`, `A-139EB`를 재사용하지 않는다. 시작가, 최소 증가액, 종료 여유 시간이 같은 신규 경매 두 개를 사용한다.

## 5. 사전 예측값

아래 범위는 낙관적 실측값을 기준으로 한 **가설**이며 합격 기준이 아니다. 비관적 락은 같은 금액의 두 요청을 직렬화하므로 한 요청이 성공하고 뒤 요청은 최신 최소 금액 검증에서 거절되는 약 50:50 패턴을 예상한다.

| 측정값 | Smoke 예측 | 2분 본 테스트 예측 | 판정 방법 |
|---|---:|---:|---|
| 입찰 시도 | 80~110 | 650~850 | 환경 편차 기록 |
| `2xx` 성공 | 40~55 | 320~430 | DB 증가량과 정확히 일치해야 함 |
| 성공률 | 45~55% | 45~55% | 참고 범위 |
| `400/422` 업무 거절 | 45~55% | 45~55% | 동일 현재가 경쟁의 정상 결과 |
| `409` | 0 | 0 | 발생 시 낙관적 처리 잔존 여부 조사 |
| 예상 밖 오류율 | 0%, 최대 1% 미만 | 0%, 최대 1% 미만 | 1% 이상이면 중단 |
| 입찰 p95 | 600~1,200 ms | 700~1,200 ms | 필수 임계값 2,000 ms 미만 |
| 입찰 p99 | 900~1,800 ms | 900~1,700 ms | 낙관적 결과와 비교 기록 |
| 유효 성공 처리량 | 참고만 | 2.7~3.5 bid/s | 3회 중앙값 전에는 우열 확정 금지 |
| version delta | 0 | 0 | 비관적 variant 필수 |
| deadlock/pool timeout | 0 | 0 | 1건이라도 원인 분석 |

DB의 `wait_event_type='Lock'` 또는 transaction lock 대기는 경쟁 순간에 관찰될 수 있다. lock wait 자체는 비관적 락의 예상 동작이며, 지속 증가·timeout·deadlock은 정상 결과가 아니다.

## 6. 데이터 정합성 필수식

두 경매 A/B 각각 다음 식을 만족해야 한다.

```text
k6 2xx 성공 수
= 최종 bidCount - 최초 bidCount
= 최종 Bid 행 수 - 최초 Bid 행 수

최종 currentBid
= 최초 currentBid + (k6 2xx 성공 수 × minIncrement)

MAX(신규 Bid.amount)
= 최종 currentBid

version 증가량
= 0
```

합계만 맞고 한 경매가 불일치하면 FAIL이다.

## 7. 단계별 실행과 중단 조건

1. 비관적 PR 병합 후 Auction deployment의 image SHA/digest 확인
2. Auction pod `3/3 Ready`, 동일 digest, restart 0 확인
3. 동일 조건 신규 경매 두 개의 실행 전 상태 저장
4. 15초 Smoke 실행
5. Smoke 정합성 PASS일 때만 2분 본 테스트 실행
6. k6 JSON, pod/DB 지표, 실행 후 상태를 `TEST_RECORD.md`에 기록
7. 결과 저장 후 낙관적 복구 PR 진행

즉시 중단:

- 예상 밖 5xx/network error
- k6 성공 수와 DB delta 불일치
- PostgreSQL `too many clients`, deadlock 또는 Hikari connection timeout
- Pod restart/OOMKilled 또는 `3/3 Ready` 이탈
- 한 경매만 처리 중단하거나 테스트 중 `ENDED` 전환

## 8. 낙관적 복구 명세

비관적 전환 코드는 테스트 문서와 별도 커밋으로 관리한다. 테스트 완료 후 당시 최신 `origin/develop`에서 복구 브랜치를 만들고 **비관적 코드 커밋만** `git revert`한다. 결과 문서 커밋은 되돌리지 않는다.

복구 후 필수 확인:

- `Auction.version`에 `@Version` 존재
- 입찰 조회가 일반 `findById()` 사용
- 낙관적 충돌 최대 3회 새 트랜잭션 재시도
- `PESSIMISTIC_WRITE` 입찰 실행 경로에서 제거
- 전체 경매 테스트 PASS
- 복구 PR 병합 후 AWS Auction pod `3/3 Ready`
- 최종 로컬 브랜치를 최신 `develop`로 전환하고 `origin/develop`과 SHA 일치 확인
