# 낙관적 락 vs 비관적 락 입찰 스트레스 테스트

이 테스트는 Kubernetes의 Auction Service 3개 replica 앞단(Ingress/Service)으로 요청을 보내며, 서로 다른 경매 2개에 동시에 입찰합니다. `LOCK_MODE`는 결과 라벨일 뿐 백엔드 락을 바꾸지 않습니다. 락 구현을 배포한 뒤 같은 조건으로 각각 실행해야 합니다.

현재 기준 사용자 구성은 다음과 같습니다.

- 사용자 1: 두 경매 상품의 판매자. k6 토큰 파일에서 제외
- 사용자 2·3: 두 경매에 동시에 참여하는 입찰자. 두 액세스 토큰만 k6에 입력
- `VUS_PER_AUCTION=2`: 상품 A에 입찰자 2명, 상품 B에도 같은 입찰자 2명
- k6에는 총 4 VU가 보이지만 백엔드의 고유 입찰 회원은 2명

## 1. 테스트 원칙

비교 시 다음 조건을 고정합니다.

- 같은 replica 수(3), pod resource request/limit, HPA 설정
- 같은 DB 인스턴스, connection pool, isolation level
- 같은 경매 조건(시작가, 최소 증가액, 종료까지 남은 시간)
- 같은 k6 실행 위치와 프로필
- 경매 하나 안에서는 VU마다 서로 다른 입찰자 토큰 사용(같은 두 토큰은 다른 경매 시나리오에서 재사용)
- 각 락 방식마다 새 경매 2개를 만들거나 DB snapshot을 복원
- 낙관/비관 실행 순서를 번갈아 최소 3회 반복

한 번의 경매를 낙관적 락으로 올린 뒤 가격이 오른 상태에서 그대로 비관적 락 테스트에 재사용하면 공정한 비교가 아닙니다.

## 2. 사전 준비

### 도구 확인

```bash
k6 version
kubectl version --client
```

macOS에서 k6가 없다면 `brew install k6`로 설치합니다.

### Kubernetes 3개 replica 확인

아래 namespace와 label은 실제 값으로 바꿉니다.

```bash
kubectl -n <namespace> get deploy <auction-deployment>
kubectl -n <namespace> get pods -l app=<auction-label> -o wide
kubectl -n <namespace> rollout status deploy/<auction-deployment>
```

`READY 3/3`, 세 pod 모두 `Running`/`Ready`인지 확인합니다. 테스트 중 재시작 여부도 전후로 기록합니다.

```bash
kubectl -n <namespace> get pods -l app=<auction-label> \
  -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,NODE:.spec.nodeName'
```

Service가 세 endpoint를 모두 가리키는지도 확인합니다.

```bash
kubectl -n <namespace> get endpointslices -l kubernetes.io/service-name=<auction-service> -o wide
```

### 테스트 경매와 토큰

두 경매는 `LIVE` 상태이고 종료까지 테스트 시간보다 충분히 길어야 합니다. 시작가와 `minIncrement`를 같게 맞추는 편이 좋습니다.

실제 토큰 파일은 커밋하지 않습니다. 예시를 복사하고 VU별 액세스 토큰을 넣습니다.

```bash
cp load-tests/k6/tokens.example.json /tmp/biddy-k6-tokens.json
chmod 600 /tmp/biddy-k6-tokens.json
```

현재 시나리오는 입찰자 토큰 2개와 `VUS_PER_AUCTION=2`를 권장합니다. 총 VU는 A/B 합계 4이지만, 사용자 2와 3이 두 상품에 각각 참여하는 구조입니다. VU를 더 높이면 같은 두 토큰이 반복 사용되므로 고유 사용자 테스트가 아니라 동일 회원의 다중 세션 스트레스 테스트가 됩니다.

## 3. 연결 확인(smoke)

현재 프런트 설정에서 추정되는 AWS 주소를 쓰는 예입니다. 당일 실제 Ingress 주소가 다르면 반드시 바꿉니다.

```bash
BASE_URL=https://43.202.187.240.nip.io/api/v1 \
AUCTION_A_ID=<auction-a-id> \
AUCTION_B_ID=<auction-b-id> \
TOKENS_FILE=/tmp/biddy-k6-tokens.json \
LOCK_MODE=optimistic \
PROFILE=smoke \
VUS_PER_AUCTION=2 \
./load-tests/k6/run-lock-test.sh
```

사전 점검은 두 경매 상세 조회가 200인지, 상태가 `LIVE`인지, `currentBid`/`startPrice`와 양수 `minIncrement`가 있는지 확인합니다. API에 `minIncrement`가 없다면 `BID_STEP=1000`처럼 직접 지정합니다.

TLS가 사설 인증서인 경우에만 임시로 `INSECURE_SKIP_TLS_VERIFY=true`를 사용하고 결과에 기록합니다.

기본 실패 기준은 예상 밖 오류율 1% 미만, 입찰 p95 2초 미만입니다. 서비스 SLO가 다르면 두 락 실행에 같은 `MAX_UNEXPECTED_ERROR_RATE`, `MAX_P95_MS` 값을 지정합니다.

## 4. 본 스트레스 테스트

두 실제 입찰자의 락 경합 비교에는 `load` 프로필을 `VUS_PER_AUCTION=2`로 고정해 일정 시간 반복하는 방식을 권장합니다.

```bash
BASE_URL=https://<actual-ingress-host>/api/v1 \
AUCTION_A_ID=<optimistic-auction-a-id> \
AUCTION_B_ID=<optimistic-auction-b-id> \
TOKENS_FILE=/tmp/biddy-k6-tokens.json \
LOCK_MODE=optimistic \
PROFILE=load \
VUS_PER_AUCTION=2 \
DURATION=2m \
RUN_ID=optimistic-run-01 \
./load-tests/k6/run-lock-test.sh
```

아래의 ramping `stress`는 같은 두 회원 토큰을 여러 VU가 반복 사용하는 별도 부하 실험입니다. 고유 사용자 2명의 실제 행동 비교와 구분해서 사용합니다. 기본 단계는 경매별 `5 → 15 → 30 → 50 VU`이며 두 경매 합계 최대 100 VU입니다. 운영 트래픽과 팀 합의 없이 실행하지 않습니다.

```bash
BASE_URL=https://<actual-ingress-host>/api/v1 \
AUCTION_A_ID=<optimistic-auction-a-id> \
AUCTION_B_ID=<optimistic-auction-b-id> \
TOKENS_FILE=/tmp/biddy-k6-tokens.json \
LOCK_MODE=optimistic \
PROFILE=stress \
CONFIRM_STRESS=I_UNDERSTAND \
RUN_ID=optimistic-stress-01 \
./load-tests/k6/run-lock-test.sh
```

필요하면 단계를 조정합니다. 각 숫자는 **경매 하나당 VU**입니다.

```bash
BASE_URL=https://<actual-ingress-host>/api/v1 \
AUCTION_A_ID=<auction-a-id> \
AUCTION_B_ID=<auction-b-id> \
TOKENS_FILE=/tmp/biddy-k6-tokens.json \
LOCK_MODE=optimistic \
STAGES='30s:10,2m:25,2m:50,30s:0' \
CONFIRM_STRESS=I_UNDERSTAND \
PROFILE=stress \
./load-tests/k6/run-lock-test.sh
```

비관적 락 버전을 배포하고 rollout 완료 및 데이터 초기화를 확인한 뒤 새 경매 ID로 동일하게 실행합니다.

```bash
BASE_URL=https://<actual-ingress-host>/api/v1 \
AUCTION_A_ID=<pessimistic-auction-a-id> \
AUCTION_B_ID=<pessimistic-auction-b-id> \
TOKENS_FILE=/tmp/biddy-k6-tokens.json \
LOCK_MODE=pessimistic \
PROFILE=load \
VUS_PER_AUCTION=2 \
DURATION=2m \
RUN_ID=pessimistic-run-01 \
./load-tests/k6/run-lock-test.sh
```

### 입찰 전략

- `BID_STRATEGY=refresh`(기본): 매 반복 `GET 상세 → 현재가 + 최소 증가액 POST`. replica 간 stale read와 실제 동시 경합까지 포함하는 권장 시나리오입니다.
- `BID_STRATEGY=sequence`: 시작 현재가에 실행 순번만큼 증가액을 더해 바로 POST합니다. 상세 조회 부하를 제외한 보조 측정용이며, 요청 도착 순서가 바뀌면 낮은 금액이 업무 거절될 수 있습니다.

두 락 비교에서는 반드시 같은 전략을 사용합니다.

## 5. 서버 관측

k6만으로 요청이 정확히 세 pod에 분산됐는지는 증명할 수 없습니다. 다음을 함께 봅니다.

- pod별 request rate와 p95/p99
- pod CPU/memory, restart, throttling
- DB active/waiting connection, lock wait/deadlock
- 낙관적 락 예외/재시도 횟수
- Hikari pool pending/timeout
- 5xx와 409의 백엔드 오류 코드

테스트 요청에는 `X-K6-Test-Run: <RUN_ID>`가 붙으므로 로그에서 회차를 필터링할 수 있습니다.

```bash
kubectl -n <namespace> logs -l app=<auction-label> --since=10m --prefix \
  | grep 'optimistic-run-01'
```

Ingress 또는 애플리케이션이 처리 pod 이름을 `X-Pod-Name` 응답 헤더로 내려주면 스크립트가 `pod_hits{pod:...}`도 수집합니다. 헤더명이 다르면 `POD_HEADER`로 지정합니다. 외부 시계열 출력(Prometheus/InfluxDB)을 붙이지 않은 기본 요약에서는 pod별 breakdown이 표시되지 않으므로 Kubernetes/관측 대시보드가 기준입니다.

## 6. 결과 비교

각 실행은 `load-tests/k6/results/<RUN_ID>-summary.json`을 생성합니다.

```bash
node load-tests/k6/compare-results.mjs \
  load-tests/k6/results/optimistic-run-01-summary.json \
  load-tests/k6/results/pessimistic-run-01-summary.json
```

주요 판정 지표는 다음과 같습니다.

| 지표 | 의미 |
|---|---|
| `bid_accepted` | 실제 2xx 입찰 비율. 높을수록 처리 성공량이 많음 |
| `bid_latency` p95/p99 | 락 대기/재시도가 꼬리 지연에 미친 영향 |
| `lock_conflicts` | HTTP 409 수. 서버가 409를 락 충돌로 사용할 때만 직접 의미가 있음 |
| `business_rejections` | 400/422. stale 가격 또는 입찰 규칙 거절 가능 |
| `unexpected_errors` | 5xx, 인증 실패 등 예상하지 않은 상태 비율 |
| `detail_errors` / `detail_latency` | replica 조회 실패나 외부 네트워크 지연을 락 지연과 구분 |
| A/B 성공 편차 | 한 상품의 hot lock이 다른 상품 처리에 영향을 주는지 확인 |

판정 우선순위는 데이터 정합성(최종가/낙찰/입찰 이력) → 5xx·deadlock 없음 → 처리량/성공률 → p95/p99입니다. 성능이 빨라도 최종 입찰가와 이력 순서가 어긋나면 실패입니다.

테스트 후 두 경매마다 다음 불변조건을 백엔드/DB에서 확인합니다.

- 최종 `currentBid`가 성공 응답 중 최고 유효 금액과 일치
- 동일 경매의 성공 입찰 금액이 단조 증가
- 입찰 이력 건수가 성공 처리 건수와 일치(비동기 저장이면 최종 수렴 후 확인)
- 중복 입찰 이력과 유실 없음
- A 경매 입찰이 B 경매의 락 대기로 직렬화되지 않음

스크립트는 종료 후 기본 1초를 기다린 다음 최종 상태를 조회합니다. 비동기 이력 반영이 더 느리면 두 실행에 동일하게 `FINAL_STATE_WAIT=5`처럼 늘립니다. 비교 도구는 `(최종 bidCount - 최초 bidCount) == k6의 2xx 성공 수`인지 1차로 함께 출력합니다.

`409`와 `400/422`의 의미가 백엔드 구현마다 다를 수 있으므로, 첫 smoke에서 `DEBUG=true`로 오류 본문의 코드/메시지를 확인하고 서버 로그와 맞춥니다. 액세스 토큰은 출력하지 않습니다.

## 7. 권장 실행 순서

1. 낙관적 락 배포, Ready replica 3개 확인
2. 테스트 경매 A/B와 토큰 준비
3. smoke 실행 후 오류 분류 확인
4. stress 1회, cooldown 및 DB/메트릭 보존
5. 같은 조건의 새 경매로 2회 더 실행
6. 비관적 락 배포와 rollout 확인
7. 동일한 smoke/stress 3회 반복
8. 가능하면 실행 순서를 O-P-P-O-O-P처럼 섞어 시간대 편향 완화
9. 결과 표와 서버 메트릭, 정합성 검증을 한 보고서에 기록
