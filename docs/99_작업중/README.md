# 작업 중 문서

> 테스트 전략 실행 및 결과 정리

---

## 문서 목록

### Phase 1: 문서와 코드 정렬 ✅

1. **[Phase1_API_경로_분석_결과.md](./Phase1_API_경로_분석_결과.md)**
   - 실제 Controller 경로 vs 테스트 문서 경로 비교
   - API Gateway 라우팅 규칙 분석
   - 불일치 사항 및 수정 방안

2. **[Phase1_환경_스펙.md](./Phase1_환경_스펙.md)**
   - K8s 클러스터 구성
   - EC2 Docker 외부 서비스 스펙
   - 네트워크 아키텍처
   - 데이터베이스 스키마

3. **[Phase1_완료_요약.md](./Phase1_완료_요약.md)**
   - Phase 1 작업 내용 정리
   - 생성된 파일 목록
   - 완료 조건 체크리스트
   - Phase 2 준비 사항

---

### Phase 2: 테스트 실행 및 결과 🔄

4. **[Phase2_테스트_실행_결과.md](./Phase2_테스트_실행_결과.md)** ⭐ NEW
   - K6 테스트 실행 결과 정리
   - Smoke Test, Business Rules Test, Concurrency Test 결과
   - 발견된 이슈 및 해결 방안
   - 테스트 로그 및 메트릭

5. **[다음_단계_실행_가이드.md](./다음_단계_실행_가이드.md)** ⭐ NEW
   - 테스트 실행을 위한 단계별 가이드
   - 환경 구성 방법 (K8s, PostgreSQL, Kafka)
   - 트러블슈팅 가이드
   - Phase 3 준비 사항

---

## 문서 구조

```
docs/99_작업중/
├── README.md                           # 본 문서
│
├── Phase1_API_경로_분석_결과.md         # Phase 1: API 경로 분석
├── Phase1_환경_스펙.md                  # Phase 1: 환경 설정
├── Phase1_완료_요약.md                  # Phase 1: 완료 요약
│
├── Phase2_테스트_실행_결과.md           # Phase 2: 테스트 결과
└── 다음_단계_실행_가이드.md             # Phase 2: 실행 가이드
```

---

## Phase별 진행 상황

### ✅ Phase 1: 문서와 코드 정렬 (완료)

**목표**: 올바른 API 경로와 환경 확인

**완료 항목**:
- [x] API 경로 분석 (Controller vs 문서)
- [x] 환경 스펙 문서화
- [x] Smoke Test 스크립트 작성
- [x] 테스트 데이터 SQL 작성

**산출물**:
- K6 테스트 스크립트 4개
- API 경로 분석 문서
- 환경 스펙 문서

---

### 🔄 Phase 2: 비즈니스 규칙 검증 (진행 중)

**목표**: 비즈니스 규칙이 실제로 작동하는지 확인

**현재 상태**:
- [x] 테스트 스크립트 작성 완료
- [x] 테스트 결과 문서 템플릿 작성
- [x] 실행 가이드 작성
- [ ] 서비스 실행 환경 구성
- [ ] 테스트 데이터 생성
- [ ] 테스트 실행 및 결과 수집

**차단 요인**:
- Auction Service 미실행 (port 8084)
- API Gateway 미실행 (port 8000)
- 테스트 데이터 미생성

**다음 작업**: [다음_단계_실행_가이드.md](./다음_단계_실행_가이드.md) 참조

---

### ⏸️ Phase 3: API Contract Tests (대기)

**목표**: API/Event 계약 준수 확인

**준비 사항**:
- Kafka Event Schema 정의
- Consumer Driven Contract 작성
- Order Service 연동 테스트

---

## 빠른 시작

### 현재 진행 중인 작업 파악

```bash
# 문서 확인
cat docs/99_작업중/Phase2_테스트_실행_결과.md

# 다음 단계 가이드 확인
cat docs/99_작업중/다음_단계_실행_가이드.md
```

### 테스트 실행 (서비스가 준비된 경우)

```bash
# Step 1: 서비스 상태 확인
kubectl get pods -n biddy
lsof -i :8084  # Auction Service
lsof -i :8000  # API Gateway

# Step 2: 포트 포워딩 (K8s 사용 시)
kubectl port-forward -n biddy svc/auction-service 8084:8080 &
kubectl port-forward -n biddy svc/api-gateway 8000:8080 &

# Step 3: 테스트 실행
cd k6-tests
k6 run scripts/01_auction_smoke_test.js

# Step 4: 결과 확인 및 문서 업데이트
# docs/99_작업중/Phase2_테스트_실행_결과.md 업데이트
```

---

## 관련 파일

### K6 테스트 스크립트

```
k6-tests/
├── README.md                               # K6 테스트 가이드
├── scripts/
│   ├── 00_smoke_test.js                   # 전체 서비스 Smoke Test
│   ├── 01_auction_smoke_test.js           # Auction Service Smoke Test
│   ├── 02_auction_business_rules_test.js  # 비즈니스 규칙 테스트
│   ├── 03_auction_concurrency_test.js     # 동시성 테스트
│   ├── setup_auction_test_data.sql        # 테스트 데이터 SQL
│   └── create_outbox_table.sql            # Outbox 테이블 생성 SQL
├── results/                                # 테스트 결과 저장
└── data/                                   # 테스트 데이터
```

### 코드 변경사항

```
auction/
├── src/main/java/com/biddy/auction/
│   ├── auction/infra/kafka/
│   │   └── AuctionEndedEventProducer.java  # 수정됨
│   └── outbox/                              # 새로 추가됨 (Outbox 패턴)
└── src/main/resources/
    └── application.yaml                     # Kafka 설정 추가
```

---

## 테스트 전략 원칙

> 출처: [테스트 전략 문서](../02_설계/10_테스트_전략_검토와_진행_방향.md)

1. **문서와 코드 먼저 정렬** (Phase 1)
   - 기능·계약이 틀린 상태에서 테스트하면 원인 파악이 어려움
   - 실제 Controller 경로 기준으로 테스트 작성

2. **비즈니스 규칙 검증** (Phase 2)
   - 입찰 증분, 경매 상태, 데이터 일관성 확인
   - 성능보다 정확성이 우선

3. **계약 테스트** (Phase 3)
   - API 스펙, Event 스키마 준수 확인
   - Consumer Driven Contract

4. **통합 테스트** (Phase 4)
   - 서비스 간 연동 확인
   - 인프라 정상 동작 확인

5. **복구 테스트** (Phase 5)
   - 장애 상황 대응
   - Outbox 패턴 동작 확인

6. **성능 테스트** (Phase 6)
   - 위의 모든 Phase 완료 후 진행
   - 잘못된 시스템을 빠르게 만들지 않기

---

## 참고 자료

- [테스트 전략 문서](../02_설계/10_테스트_전략_검토와_진행_방향.md)
- [성능 부하 테스트 가이드](../02_설계/07_성능_부하_테스트_실전_가이드.md)
- [경매 서비스 상세 설계](../02_설계/05_경매서비스_상세설계.md)
- [K6 테스트 README](../../k6-tests/README.md)

---

## 업데이트 로그

- **2026-07-15**: Phase 2 테스트 결과 문서 및 실행 가이드 추가
- **2026-07-14**: Phase 1 완료, API 경로 분석 및 환경 스펙 문서화 완료
- **2026-07-14**: 문서 디렉토리 생성, Phase 1 작업 시작

---

## 기여 방법

### 테스트 실행 후 결과 업데이트

1. 테스트 실행
2. 결과 캡처 (성공/실패, 로그)
3. `Phase2_테스트_실행_결과.md` 업데이트
4. 발견된 이슈 기록
5. 다음 단계 계획 수정

### 새로운 Phase 진행 시

1. `PhaseN_<작업명>.md` 문서 생성
2. 본 README 업데이트 (문서 목록, 진행 상황)
3. 관련 테스트 스크립트 작성
4. 결과 문서화

---

## 문의

문서 내용이나 테스트 실행에 대한 질문은:
- 테스트 전략 문서 참조
- Phase별 상세 문서 확인
- 실행 가이드 트러블슈팅 섹션 참조