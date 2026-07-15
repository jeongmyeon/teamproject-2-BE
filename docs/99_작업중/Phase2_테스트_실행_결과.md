# Phase 2: 테스트 실행 결과

> 작성일: 2026-07-15
> 브랜치: fix/k8s-remove-duplicate-services
> 목적: K6 테스트 실행 결과 정리 및 분석

---

## 테스트 개요

### 테스트 환경

**인프라**:
- K8s Cluster: biddy namespace
- 외부 서비스 (EC2 Docker): PostgreSQL, Redis, Kafka
- API Gateway: localhost:8000
- Auction Service: localhost:8084

**테스트 도구**:
- K6 v2.1.0
- 테스트 스크립트 위치: `k6-tests/scripts/`

**테스트 Phase**:
- Phase 1: Smoke Tests (기본 API 연결 확인)
- Phase 2: Business Rules Tests (비즈니스 규칙 검증)
- Phase 3: Concurrency Tests (동시성 제어 검증)

---

## 실행된 테스트 목록

### 1. 00_smoke_test.js
**목적**: API Gateway를 통한 전체 서비스 연결 확인

**테스트 항목**:
- API Gateway Health Check
- Product Service API
- Auction Service API
- Member Service API
- Legacy Path 검증

**실행 명령**:
```bash
cd k6-tests
k6 run scripts/00_smoke_test.js
```

**실행 결과**: ⏸️ 미실행

**사유**:
- [ ] API Gateway 미실행 (port 8000)
- [ ] Microservices 미실행

---

### 2. 01_auction_smoke_test.js
**목적**: Auction Service 직접 접근 테스트 (API Gateway 우회)

**테스트 항목**:
- Auction Feed API (`GET /api/v1/auctions`)
- Auction Feed with Filters (status, sort, pagination)
- Auction Detail API (`GET /api/v1/auctions/{id}`)
- Bid List API (`GET /api/v1/auctions/{id}/bids`)
- Auction Result API (`GET /api/v1/auctions/{id}/result`)

**실행 명령**:
```bash
cd k6-tests
k6 run scripts/01_auction_smoke_test.js
```

**실행 결과**: ⏸️ 미실행

**사유**:
- [ ] Auction Service 미실행 (port 8084)

**예상 성공 조건**:
- Error rate = 0%
- 모든 API 접근 가능 (200 또는 404 허용)

---

### 3. 02_auction_business_rules_test.js
**목적**: 경매 비즈니스 규칙 검증

**테스트 항목**:
- **BR-001**: 입찰 금액 검증
  - 최소 증분보다 낮은 입찰 거부
  - 정확한 최소 증분 입찰 허용
- **BR-002**: 경매 상태 검증
  - 종료된 경매에 입찰 불가
- **BR-003**: 입찰 내역 무결성
  - 입찰 횟수 일치
  - 현재가 = 최고 입찰가
- **BR-004**: 경매 상태 전이
  - LIVE 경매: winner 없음
  - ENDED 경매: 상태 확인

**필요 데이터**:
- 테스트용 경매 데이터 (setup_auction_test_data.sql)
- Outbox 테이블 (create_outbox_table.sql)

**실행 명령**:
```bash
cd k6-tests

# 1. 테스트 데이터 셋업
psql -h localhost -U biddy_user -d biddy_auction < scripts/setup_auction_test_data.sql
psql -h localhost -U biddy_user -d biddy_auction < scripts/create_outbox_table.sql

# 2. 테스트 실행
k6 run scripts/02_auction_business_rules_test.js
```

**실행 결과**: ⏸️ 미실행

**사유**:
- [ ] Auction Service 미실행
- [ ] 테스트 데이터 미생성

**예상 성공 조건**:
- business_rule_violations = 0
- 모든 비즈니스 규칙 체크 통과

---

### 4. 03_auction_concurrency_test.js
**목적**: 동시 입찰 상황에서 데이터 무결성 검증

**테스트 시나리오**:
- 10명의 사용자가 동시에 입찰 시도 (10 VUs)
- 각각 다른 금액으로 입찰
- 비관적 락(Pessimistic Lock) 동작 확인

**검증 항목**:
- 입찰 횟수 일관성 (bidCount)
- 현재가가 최고가와 일치
- Race condition 미발생

**실행 명령**:
```bash
cd k6-tests

# 동시성 테스트 전용 경매 생성 필요
# A-CONC01 경매가 LIVE 상태여야 함

k6 run scripts/03_auction_concurrency_test.js
```

**실행 결과**: ⏸️ 미실행

**사유**:
- [ ] Auction Service 미실행
- [ ] 동시성 테스트용 경매 데이터 미생성

**예상 성공 조건**:
- 데이터 무결성 체크 모두 통과
- 95% 요청이 5초 이내 완료
- 락 대기 시간 측정 완료

---

## 테스트 결과 요약

### 전체 실행 상태

| 테스트 | 상태 | Error Rate | 성공 조건 | 비고 |
|--------|------|------------|-----------|------|
| 00_smoke_test.js | ⏸️ 미실행 | - | errors==0 | API Gateway 필요 |
| 01_auction_smoke_test.js | ⏸️ 미실행 | - | errors==0 | Auction Service 필요 |
| 02_auction_business_rules_test.js | ⏸️ 미실행 | - | violations==0 | 테스트 데이터 필요 |
| 03_auction_concurrency_test.js | ⏸️ 미실행 | - | p95<5s | 동시성 테스트 데이터 필요 |

---

## 발견된 이슈

### 1. 서비스 미실행
**문제**: 로컬 환경에서 서비스가 실행되지 않음

**영향**:
- 모든 K6 테스트 실행 불가
- API 연결성 확인 불가

**해결 방안**:
1. K8s 환경에서 서비스 배포 확인
   ```bash
   kubectl get pods -n biddy
   kubectl get svc -n biddy
   ```

2. 로컬 개발 환경 실행
   ```bash
   # Auction Service 로컬 실행
   cd auction
   ./gradlew bootRun

   # API Gateway 로컬 실행
   cd apigateway
   ./gradlew bootRun
   ```

3. 포트 포워딩으로 K8s 서비스 접근
   ```bash
   kubectl port-forward -n biddy svc/auction-service 8084:8080
   kubectl port-forward -n biddy svc/api-gateway 8000:8080
   ```

---

### 2. 테스트 데이터 부재
**문제**: 비즈니스 규칙 테스트용 경매 데이터 없음

**필요 데이터**:
- TEST01: 입찰 없는 LIVE 경매 (startPrice=100000, minIncrement=5000)
- TEST02: 입찰 있는 LIVE 경매 (currentBid=550000, minIncrement=10000)
- TEST03: 종료된 경매 (ENDED)
- CONC01: 동시성 테스트용 경매

**해결 방안**:
```bash
# PostgreSQL 접속
kubectl exec -it -n biddy deployment/auction-service -- bash
psql -h biddy-postgres -U biddy_user -d biddy_auction

# 테스트 데이터 삽입
\i /path/to/setup_auction_test_data.sql
\i /path/to/create_outbox_table.sql
```

또는 Admin API로 경매 생성:
```bash
# Auction 생성 API 호출
curl -X POST http://localhost:8084/api/v1/auctions \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "P-TEST01",
    "startPrice": 100000,
    "minIncrement": 5000,
    "startTime": "2026-07-15T00:00:00",
    "endTime": "2026-12-31T23:59:59"
  }'
```

---

### 3. Outbox 테이블 부재
**문제**: Auction Ended 이벤트 발행을 위한 Outbox 테이블 미생성

**영향**:
- 경매 종료 시 이벤트 발행 실패 가능
- Order Service와의 통합 이슈

**해결 방안**:
```sql
-- auction/src/main/java/com/biddy/auction/outbox/ 패키지 내용 확인
-- create_outbox_table.sql 실행

CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_outbox_processed ON outbox(processed);
```

---

## 다음 단계

### 즉시 수행 항목

1. **서비스 실행 환경 확인**
   - [ ] K8s 클러스터에서 서비스 상태 확인
   - [ ] 로컬 또는 K8s 포트 포워딩 설정
   - [ ] 외부 서비스 (PostgreSQL, Kafka) 연결 확인

2. **테스트 데이터 준비**
   - [ ] Outbox 테이블 생성
   - [ ] 테스트용 경매 데이터 생성
   - [ ] 테스트용 사용자 데이터 생성

3. **Smoke Test 실행**
   ```bash
   k6 run scripts/01_auction_smoke_test.js
   ```
   - 성공 시: Business Rules Test 진행
   - 실패 시: 에러 분석 및 수정

4. **Business Rules Test 실행**
   ```bash
   k6 run scripts/02_auction_business_rules_test.js
   ```
   - 성공 시: Concurrency Test 진행
   - 실패 시: 비즈니스 로직 검증 및 수정

5. **Concurrency Test 실행**
   ```bash
   k6 run scripts/03_auction_concurrency_test.js
   ```
   - 성공 시: 락 성능 분석
   - 실패 시: 동시성 제어 로직 개선

---

### Phase 3 준비

**목표**: API Contract Tests

**필요 작업**:
1. Kafka Event Contract 정의
   - AuctionEndedEvent 스키마 문서화
   - Event 발행/구독 테스트 작성

2. API 응답 스키마 검증
   - OpenAPI 스펙 생성
   - JSON Schema 기반 검증

3. Integration Tests
   - Auction Service → Order Service 연동
   - Event 기반 주문 생성 흐름 테스트

---

## 참고 자료

- [Phase 1 완료 요약](./Phase1_완료_요약.md)
- [API 경로 분석 결과](./Phase1_API_경로_분석_결과.md)
- [환경 스펙](./Phase1_환경_스펙.md)
- [K6 테스트 가이드](../../k6-tests/README.md)
- [테스트 전략 문서](../02_설계/10_테스트_전략_검토와_진행_방향.md)

---

## 실행 로그

### 실행 시각별 기록

#### 2026-07-15 (작성)

**상태**: 문서 작성 완료, 테스트 미실행

**이유**:
- Auction Service 미실행 (port 8084)
- API Gateway 미실행 (port 8000)

**다음 작업**:
- 서비스 실행 환경 구성
- 테스트 데이터 준비
- Smoke Test 실행

---

## 테스트 결과 상세 (실행 후 업데이트 예정)

### 01_auction_smoke_test.js 결과

```
실행 예정
```

### 02_auction_business_rules_test.js 결과

```
실행 예정
```

### 03_auction_concurrency_test.js 결과

```
실행 예정
```

---

## 결론

**현재 상태**:
- K6 테스트 스크립트 준비 완료
- 테스트 실행 환경 미구성
- 테스트 데이터 미생성

**완료 기준** (테스트 전략 문서):
> "Phase 2: 비즈니스 규칙이 실제로 작동하는지 확인"

**진행률**: 0% (스크립트 준비 완료, 실행 대기 중)

**차단 요인**:
1. 서비스 미실행
2. 테스트 데이터 부재

**해결 후 예상 일정**:
- Smoke Test: 5분
- Business Rules Test: 10분
- Concurrency Test: 15분
- 결과 분석 및 문서화: 30분