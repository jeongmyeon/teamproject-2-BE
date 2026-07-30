# 260730 낙관적 락·비관적 락 테스트 기록

작성일: 2026-07-30 (Asia/Seoul)

기준 develop SHA: `4492cbb`

비관적 테스트 브랜치: `test/pessimistic-lock-260730`

## 1. 테스트 목적

Kubernetes Auction Service replica 3개 환경에서 서로 다른 경매 상품 2개에 입찰자 2명이 동시에 입찰할 때 낙관적 락과 비관적 락의 다음 항목을 비교한다.

- 최종 가격과 입찰 이력의 데이터 정합성
- 성공 처리량과 충돌/업무 거절 비율
- 입찰 평균, p95, p99 지연시간
- 상품 A/B 간 처리 편차
- 5xx, 인증 오류, deadlock, connection timeout 여부

## 2. 공통 환경

| 항목 | 값 |
|---|---|
| AWS API | `https://43.202.187.240.nip.io` |
| Kubernetes namespace | `biddy` |
| Auction replica | 3개, 테스트 시작 전 Ready 3/3 확인 |
| 판매자 | 사용자 1, 두 경매 상품 등록 |
| 입찰자 | 판매자를 제외한 고유 사용자 2명 |
| 상품별 VU | 2 |
| 전체 VU | 4 |
| 입찰 전략 | `refresh`: 상세 조회 후 `currentBid + minIncrement` 입찰 |
| Smoke | 15초 |
| 본 테스트 | 2분 |
| 실패 임계값 | 예상 밖 오류율 1% 미만, 입찰 p95 2초 미만 |

계정 이메일·비밀번호·토큰은 문서와 결과 파일에 기록하지 않는다.

## 3. 낙관적 락 테스트 데이터

| 구분 | 상품 A | 상품 B |
|---|---|---|
| auctionId | `A-E290D` | `A-139EB` |
| 최초 현재가 | 2,500원 | 3,500원 |
| 최소 증가액 | 500원 | 500원 |
| 최초 bidCount | 0 | 0 |
| 상태 | LIVE | LIVE |
| 종료 시각 | 2026-08-05 08:46 | 2026-08-05 08:47 |

## 4. 낙관적 락 Smoke 결과

실행 ID: `260730-optimistic-smoke`

| 측정값 | 결과 |
|---|---:|
| 입찰 시도 | 92 |
| 2xx 성공 | 46 |
| 성공률 | 50.00% |
| HTTP 409 | 0 |
| HTTP 400/422 업무 거절 | 46 |
| 예상 밖 오류율 | 0.00% |
| 상세 조회 오류 | 0 |
| 입찰 평균 | 439.69 ms |
| 입찰 p95 | 672.24 ms |
| 입찰 p99 | 1,213.24 ms |
| 상세 조회 p95 | 274.09 ms |

정합성 확인:

| 상품 | 최초 bidCount | 최종 bidCount | 증가량 | k6 2xx 성공 | 판정 |
|---|---:|---:|---:|---:|---|
| A | 0 | 22 | 22 | 22 | 일치 |
| B | 0 | 24 | 24 | 24 | 일치 |

Smoke 판정: **PASS**

## 5. 낙관적 락 2분 본 테스트 결과

실행 ID: `260730-optimistic-run-01`

| 측정값 | 결과 |
|---|---:|
| 입찰 시도 | 758 |
| 전체 시도 처리량 | 6.23 req/s |
| 2xx 성공 | 379 |
| 유효 성공 처리량 | 3.11 bid/s |
| 성공률 | 50.00% |
| HTTP 409 | 0 |
| HTTP 400/422 업무 거절 | 379 |
| HTTP 429 | 0 |
| 예상 밖 오류율 | 0.00% |
| 상세 조회 오류 | 0 |
| 중단 iteration | 0 |
| 입찰 평균 | 436.22 ms |
| 입찰 p95 | 723.57 ms |
| 입찰 p99 | 957.57 ms |
| 입찰 최대 | 1,409.54 ms |
| 상세 조회 p95 | 206.82 ms |

상품별 처리 결과:

| 상품 | 시도 | 성공 | 성공률 | 최초 bidCount | 최종 bidCount | 증가량 | 정합성 |
|---|---:|---:|---:|---:|---:|---:|---|
| A | 378 | 189 | 50.00% | 22 | 211 | 189 | 일치 |
| B | 380 | 190 | 50.00% | 24 | 214 | 190 | 일치 |

가격 변화:

| 상품 | 본 테스트 최초가 | 최종가 | 증가액 |
|---|---:|---:|---:|
| A | 13,500원 | 108,000원 | 94,500원 |
| B | 15,500원 | 110,500원 | 95,000원 |

## 6. 낙관적 락 판정

| 검증 항목 | 결과 | 판정 |
|---|---|---|
| `bidCount 증가량 = k6 2xx 성공 수` | 두 상품 모두 정확히 일치 | PASS |
| 401/403 인증 오류 | 0건 | PASS |
| 5xx / 예상 밖 오류 | 0건 | PASS |
| 429 요청 제한 | 0건 | PASS |
| 상세 조회 오류 | 0건 | PASS |
| 입찰 p95 2초 미만 | 723.57 ms | PASS |
| 상품 A/B 처리 편차 | 성공 189회 / 190회 | PASS |
| 중단 iteration | 0건 | PASS |
| 낙관적 락 예외/재시도 횟수 | k6만으로 확인 불가 | 서버 확인 필요 |
| pod별 요청 분배 | k6 기본 요약으로 확인 불가 | Kubernetes 확인 필요 |
| DB lock wait / pool 상태 | k6만으로 확인 불가 | DB/서버 확인 필요 |

### 해석

두 사용자가 같은 현재가를 읽고 같은 입찰 금액을 거의 동시에 전송하므로 요청의 절반이 성공하고 절반이 최신가 검증에서 거절된 결과는 현재 시나리오에 부합한다.

이번 실행에서는 HTTP 409가 발생하지 않았고 동시 동일 금액의 실패가 400/422 업무 거절로 처리됐다. 따라서 HTTP 상태 코드만으로 낙관적 락 충돌 횟수를 판단할 수 없다. 서버 로그에서 optimistic lock exception, version conflict, retry 횟수를 별도로 확인해야 한다.

## 7. Kubernetes/서버 추가 확인 명령

테스트 직후 master 서버에서 실행한다.

```bash
kubectl get pods -n biddy -l app=auction -o wide
kubectl top pods -n biddy -l app=auction
kubectl logs -n biddy -l app=auction --since=15m --prefix \
  | grep -E '260730-optimistic|Optimistic|ObjectOptimisticLocking|version|retry|ERROR|Exception'
```

확인할 항목:

- 세 Auction pod가 모두 요청을 처리했는지
- 테스트 도중 pod restart가 있었는지
- optimistic lock exception과 재시도 횟수
- Hikari connection pending/timeout
- DB lock wait와 deadlock

## 8. 보안 정보 정리

| 항목 | 결과 |
|---|---|
| 사용자 2 서버 로그아웃 | HTTP 200 |
| 사용자 3 서버 로그아웃 | HTTP 200 |
| `credentials.local.json` | 삭제 확인 |
| `tokens.local.json` | 삭제 확인 |
| `test.local.env` | 경매 ID 재현을 위해 보존, 계정 정보 없음 |

비밀번호가 채팅에 노출된 이력이 있으므로 테스트 계정 비밀번호는 별도로 변경한다.

## 9. 비관적 락 테스트 계획

AWS 비관적 락 테스트는 아직 실행하지 않았다. 공정한 비교를 위해 낙관적 테스트에서 가격이 변경된 기존 경매를 재사용하지 않는다. 상세 구현·예측값·복구 조건은 `PESSIMISTIC_TEST_SPEC_260730.md`에 기록했다.

### 9.1 로컬 배포 전 검증

실행 시각: 2026-07-30 16:13 KST

| 항목 | 결과 |
|---|---|
| 경매 비관적 락 관련 테스트 | 14개 |
| 실패/오류/skip | 0 / 0 / 0 |
| PostgreSQL 동시성 통합 테스트 | PASS |
| 성공 수 = Bid 행 수 = bidCount 증가량 | PASS |
| 최종 currentBid = 최고 Bid 금액 | PASS |
| 비관적 variant version 증가 | 0, PASS |
| 경매 모듈 전체 회귀 테스트 | 75개, 실패 0, 오류 0, PASS |
| AWS 배포/Smoke/본 테스트 | 실행 전 |

### 9.2 GitHub 배포 PR

| 항목 | 값 |
|---|---|
| PR | `#170` `test(auction): 비관적 락 비교 테스트 배포` |
| 대상 | `test/pessimistic-lock-260730` → `develop` |
| 비관적 코드 커밋 | `737f115` |
| 명세·기록 커밋 | `18131f4` |
| PR 상태 | Draft, 병합 전 |
| AWS 상태 | 비관적 variant 배포 전 |

PR 병합 후 실제 배포 image SHA/digest와 Auction pod `3/3 Ready` 확인 결과를 이 절에 이어서 기록한다.

1. 사용자 1이 동일한 시작가·최소 증가액·종료시간의 새 경매 2개 생성
2. 비관적 락 배포 및 rollout 완료
3. Auction replica Ready 3/3 확인
4. 낙관적 실행과 동일한 resource/HPA/DB pool 상태 유지
5. 새 경매 ID를 `test.local.env`에 입력
6. 입찰자 계정 파일을 다시 만들고 15초 Smoke 실행
7. Smoke 통과 후 같은 토큰으로 2분 본 테스트 실행
8. 아래 비교표와 서버 지표 작성

## 10. 최종 비교표

| 측정값 | 낙관적 락 | 비관적 락 |
|---|---:|---:|
| 입찰 시도 | 758 | 실행 전 |
| 2xx 성공 | 379 | 실행 전 |
| 성공률 | 50.00% | 실행 전 |
| 409 | 0 | 실행 전 |
| 400/422 | 379 | 실행 전 |
| 예상 밖 오류율 | 0.00% | 실행 전 |
| 입찰 평균 | 436.22 ms | 실행 전 |
| 입찰 p95 | 723.57 ms | 실행 전 |
| 입찰 p99 | 957.57 ms | 실행 전 |
| 유효 성공 처리량 | 3.11 bid/s | 실행 전 |
| A 정합성 | 일치 | 실행 전 |
| B 정합성 | 일치 | 실행 전 |
| DB lock wait | 확인 필요 | 실행 전 |
| retry/exception | 확인 필요 | 실행 전 |
| deadlock/pool timeout | 서버 확인 필요 | 실행 전 |

## 11. 원본 결과 파일

- `results/260730-optimistic-smoke-summary.json`
- `results/260730-optimistic-run-01-summary.json`
- `OPTIMISTIC_RESULT_260730.md`

최종 결론은 비관적 락을 동일 조건으로 실행한 후 작성한다.
