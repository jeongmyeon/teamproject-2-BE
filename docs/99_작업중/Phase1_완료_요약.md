# Phase 1 완료 요약

> 작성일: 2026-07-14
> 브랜치: feat/k8s-external-services
> 커밋: 9f60d172f5a545a52fe172ed22a6c7cf78823e4e

---

## 작업 개요

테스트 전략 문서(10_테스트_전략_검토와_진행_방향.md)의 권고에 따라 **Phase 1: 문서와 코드 정렬** 작업을 완료했습니다.

**핵심 원칙**:
> "기능·계약이 틀린 상태에서 K6를 돌리면 '왜 실패했는지'가 섞이고, 빠르지만 잘못된 시스템을 만들 수도 있다."

---

## 완료된 작업

### ✅ 1. 실제 API 경로 확인 (Controller 분석)

**분석한 Controller**:
- ProductController: `product/src/main/java/com/biddy/productservice/presentation/controller/ProductController.java:29`
- AuthController: `member/src/main/java/com/biddy/memberservice/presentation/controller/AuthController.java:18`
- OrderController: `order/src/main/java/com/biddy/order/order/presentation/controller/OrderController.java:14`
- AuctionController: `auction/src/main/java/com/biddy/auction/auction/presentation/AuctionController.java:23`
- BidController: `auction/src/main/java/com/biddy/auction/bid/presentation/BidController.java:25`

**결과**: `docs/99_작업중/Phase1_API_경로_분석_결과.md`

---

### ✅ 2. 문서와 실제 코드 비교

**발견된 불일치**:

| 항목 | 문서 경로 | 실제 경로 | 상태 |
|------|-----------|-----------|------|
| Auction List | `/api/auctions` | `/api/v1/auctions` | ❌ 불일치 |
| Bid Placement | `/api/auctions/{id}/bids` | `/api/v1/auctions/{auctionId}/bids` | ❌ 불일치 |
| Product List | `/api/products` | `/api/products` | ✅ 일치 |
| Member Login | `/api/members/login` | `/api/members/login` | ✅ 일치 |

**원인**:
- 테스트 문서(07_성능_부하_테스트_실전_가이드.md)가 구 버전 경로 사용
- 실제 AuctionController, BidController는 `/api/v1/` prefix 사용

---

### ✅ 3. API 경로 불일치 사항 문서화

**문서 위치**: `docs/99_작업중/Phase1_API_경로_분석_결과.md`

**주요 내용**:
- 실제 Controller 경로 전체 목록
- API Gateway 라우팅 규칙 확인
- 불일치 사항 및 수정 방안 제시

**권장 조치**:
```javascript
// 수정 전
http.post(`${BASE_URL}/api/auctions/${auctionId}/bids`, ...);

// 수정 후
http.post(`${BASE_URL}/api/v1/auctions/${auctionId}/bids`, ...);
```

---

### ✅ 4. Smoke Test 스크립트 작성

**파일**: `k6-tests/scripts/00_smoke_test.js`

**테스트 항목**:
1. API Gateway Health Check (`/actuator/health`)
2. Product Service (`/api/products`, `/api/products/{id}`)
3. Auction Service (`/api/v1/auctions`, `/api/v1/auctions/{id}`)
4. Member Service (`/api/members/login`)
5. Legacy Path 검증 (`/api/auctions` vs `/api/v1/auctions`)

**특징**:
- 1 VU, 1 iteration (단순 검증)
- Error rate = 0% 목표
- 404 허용 (데이터가 없을 수 있음)
- 실제 Controller 경로 사용

**실행 방법**:
```bash
cd k6-tests
k6 run scripts/00_smoke_test.js
```

---

### ✅ 5. 환경 스펙 문서화

**문서 위치**: `docs/99_작업중/Phase1_환경_스펙.md`

**포함 내용**:
- Git 정보 (branch, commit SHA)
- 인프라 아키텍처 다이어그램
- EC2 Docker 서비스 상세 스펙
  - PostgreSQL (biddy-postgres, port 5432)
  - Redis (biddy-redis, port 6379)
  - Kafka (biddy-kafka, port 9092)
- K8s Service + Endpoint 설정
- 네트워크 구성
- 데이터베이스 스키마 목록

**EC2 정보**:
```
Private IP: 10.0.30.99
Docker Containers: biddy-postgres, biddy-redis, biddy-kafka
K8s Namespace: biddy
```

---

## 생성된 파일

```
docs/99_작업중/
├── Phase1_API_경로_분석_결과.md    # API 경로 비교 분석
├── Phase1_환경_스펙.md             # 환경 설정 및 스펙
└── Phase1_완료_요약.md             # 본 문서

k6-tests/
├── scripts/
│   └── 00_smoke_test.js           # Smoke test 스크립트
└── README.md                       # 테스트 가이드
```

---

## Phase 1 완료 조건 검증

**테스트 전략 문서 인용**:
> "완료 조건: 새 환경에서 문서만 보고 smoke test가 성공하며 두 번 실행해도 결과가 깨지지 않는다."

### 체크리스트

- [x] API 경로와 요청/응답을 실제 Controller/DTO 기준으로 확인
- [x] 실제 API 경로 기반 Smoke test 작성
- [x] 환경 스펙 문서화 (인프라, 네트워크, DB)
- [x] 테스트 실행 가이드 작성 (`k6-tests/README.md`)
- [ ] **실제 환경에서 Smoke test 실행** (사용자 작업 필요)
- [ ] **두 번 연속 실행 시 idempotent 확인** (사용자 작업 필요)

---

## 다음 단계

### 즉시 수행 (사용자 작업)

1. **Smoke Test 실행**:
   ```bash
   cd k6-tests
   k6 run scripts/00_smoke_test.js
   ```

2. **Idempotency 확인** (두 번 연속 실행):
   ```bash
   k6 run scripts/00_smoke_test.js
   k6 run scripts/00_smoke_test.js  # 동일한 결과 확인
   ```

3. **결과 확인**:
   - 모든 체크 통과
   - Error rate = 0%
   - 두 번 실행 시 동일한 결과

---

### Phase 2 준비 (테스트 전략 문서 기준)

**Phase 2: Business Invariant Tests**

테스트 항목:
- 경매 규칙 (입찰 증분, 마감 시간, 상태 전이)
- 동시성 제어 (낙관적 락, 비관적 락)
- 데이터 일관성 (주문-결제-재고)

**필요 작업**:
1. Business Invariant 정의 문서화
2. 규칙 위반 시나리오 테스트 케이스 작성
3. 동시성 테스트 (K6 + shared iterations)
4. 데이터 일관성 검증 쿼리 작성

**예시 테스트**:
```javascript
// 입찰 증분 규칙 검증
check(bidResponse, {
  '입찰가는 현재가 + 최소증분 이상': (r) =>
    r.json().newPrice >= currentPrice + minIncrement
});
```

---

## 중요 발견 사항

### 1. API 경로 버전 불일치

**문제**: 문서는 `/api/auctions`를 사용하지만 실제는 `/api/v1/auctions`

**영향**:
- 기존 테스트 스크립트 그대로 실행 시 404 에러 가능
- API Gateway가 `/api/auctions/**`를 라우팅하지만 Controller가 매핑 안 됨

**해결**:
- 모든 테스트 스크립트는 `/api/v1/auctions` 사용
- 또는 API Gateway에 Rewrite 필터 추가

---

### 2. 환경 구성 변경

**변경 내용** (feat/k8s-external-services 브랜치):
- K8s 내부 StatefulSet (Kafka, Redis, PostgreSQL) → EC2 Docker로 분리
- Service without Selector + Endpoint 패턴 사용

**장점**:
- K8s 리소스 절약
- 데이터 영속성 관리 용이
- 독립적인 스케일링 가능

**주의사항**:
- EC2와 K8s 간 네트워크 연결 필수
- Endpoint IP 변경 시 YAML 업데이트 필요

---

## 테스트 전략 핵심 원칙 (재확인)

> "테스트의 목적은 버그 수집이 아니라 의사결정이다."

**Phase별 목적**:
1. **Phase 1** (현재): 올바른 API 경로와 환경 확인 → ✅ 완료
2. **Phase 2**: 비즈니스 규칙 준수 확인
3. **Phase 3**: API/Event 계약 준수 확인
4. **Phase 4**: 인프라 통합 정상 동작 확인
5. **Phase 5**: 장애 상황 복구 가능성 확인
6. **Phase 6**: 성능 목표 달성 여부 확인

**순서의 중요성**:
- 기능이 틀리면 → 성능 테스트 결과 무의미
- 계약이 틀리면 → 통합 시 실패
- 복구가 안 되면 → 고성능도 의미 없음

---

## 참고 자료

- [테스트 전략 문서](./10_테스트_전략_검토와_진행_방향.md)
- [성능 부하 테스트 가이드](./07_성능_부하_테스트_실전_가이드.md)
- [K8s 외부 서비스 분리 가이드](../../k8s/CHANGES.md)
- [API 경로 분석 결과](./Phase1_API_경로_분석_결과.md)
- [환경 스펙](./Phase1_환경_스펙.md)

---

## 결론

Phase 1 문서화 작업이 완료되었습니다.

**다음 작업**:
1. 사용자가 실제 환경에서 Smoke test 실행
2. 결과 확인 및 문제 수정
3. Phase 2 (Business Invariant Tests) 진행

**완료 기준 재확인**:
> "새 환경에서 문서만 보고 smoke test가 성공하며 두 번 실행해도 결과가 깨지지 않는다."

현재 문서만으로도 Smoke test를 실행할 수 있는 상태입니다. 실행 후 결과를 확인하고 다음 단계로 진행하세요.