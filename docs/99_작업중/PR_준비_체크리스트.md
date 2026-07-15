# PR 준비 체크리스트

## 백엔드 PR

### 브랜치: `fix/k8s-remove-duplicate-services`

### PR 제목
```
feat: Implement Transactional Outbox Pattern for Auction Events
```

### PR 설명 (템플릿)
```markdown
## 개요
경매 종료 이벤트 발행의 안정성을 높이기 위해 Transactional Outbox 패턴을 적용했습니다.

## 변경 사항

### 1. Outbox 패턴 구현
- `auction/src/main/java/com/biddy/auction/outbox/` 패키지 추가
  - `OutboxEvent` 엔티티
  - `OutboxEventRepository`
  - `OutboxRelayScheduler` (이벤트 발행 스케줄러)

### 2. AuctionEndedEventProducer 개선
- Kafka 직접 발행 → Outbox 테이블에 저장
- 비즈니스 트랜잭션과 이벤트 발행의 원자성 보장
- 실패 시 자동 재시도 메커니즘

### 3. 설정 추가
- `application.yaml`에 Outbox 스케줄러 설정 추가
  - 실행 주기: 5초 (default)
  - 최대 재시도: 5회
  - 타임아웃: 10초

## 기술적 이점

### Before (직접 Kafka 발행)
```java
kafkaTemplate.send(TOPIC, auctionId, json);
```
**문제점**:
- DB 트랜잭션 커밋 후 Kafka 발행 실패 시 데이터 불일치
- 재시도 메커니즘 없음

### After (Outbox 패턴)
```java
outboxEventRepository.save(outboxEvent);  // 같은 트랜잭션
```
**장점**:
- DB 트랜잭션과 이벤트 발행의 원자성 보장
- 자동 재시도 (최대 5회)
- 이벤트 발행 내역 추적 가능

## 테스트 방법

### 1. DB 마이그레이션
```sql
-- outbox 테이블 생성
-- k6-tests/scripts/create_outbox_table.sql 실행
```

### 2. 경매 종료 시나리오
```bash
# 1. 경매 생성
# 2. 입찰 진행
# 3. 경매 종료 (수동 또는 스케줄러)
# 4. outbox 테이블 확인
SELECT * FROM outbox WHERE aggregate_type = 'AUCTION';

# 5. Kafka 토픽 확인
kafka-console-consumer --bootstrap-server localhost:9092 --topic auction.ended --from-beginning
```

### 3. 실패 시나리오 테스트
```bash
# Kafka 서버 중단
docker stop biddy-kafka

# 경매 종료 → Outbox에 저장됨 (processed=false)

# Kafka 재시작
docker start biddy-kafka

# 5초 후 자동 재발행 확인
```

## 관련 문서

- [경매 서비스 상세 설계](docs/02_설계/05_경매서비스_상세설계.md)
- [Outbox 패턴 가이드](https://microservices.io/patterns/data/transactional-outbox.html)

## 체크리스트

- [x] Outbox 패턴 구현 완료
- [x] 스케줄러 동작 확인
- [ ] DB 마이그레이션 스크립트 실행
- [ ] 통합 테스트 작성 (선택)
- [ ] 성능 테스트 (선택)
```

---

### 포함할 파일

#### 필수 파일
```bash
# Outbox 패턴 구현
git add auction/src/main/java/com/biddy/auction/outbox/

# Producer 수정
git add auction/src/main/java/com/biddy/auction/auction/infra/kafka/AuctionEndedEventProducer.java

# 설정 추가
git add auction/src/main/resources/application.yaml
```

#### 선택 파일 (확인 필요)
```bash
# 문서 업데이트 (포함 권장)
git add docs/02_설계/05_경매서비스_상세설계.md

# Docker Compose 변경 확인
git diff docker-compose.yml
# → 관련 없는 변경이면 제외
# → Kafka 설정 등 관련 있으면 포함
```

#### 제외할 파일 (다른 PR로 분리)
```bash
# 테스트 관련 (별도 PR 권장)
# docs/99_작업중/
# k6-tests/
# monitoring/
```

---

### Git 커맨드

```bash
# 1. 상태 확인
git status

# 2. Outbox 패턴 파일 추가
git add auction/src/main/java/com/biddy/auction/outbox/
git add auction/src/main/java/com/biddy/auction/auction/infra/kafka/AuctionEndedEventProducer.java
git add auction/src/main/resources/application.yaml

# 3. 문서 추가 (선택)
git add docs/02_설계/05_경매서비스_상세설계.md

# 4. docker-compose.yml 확인 후 추가 (필요시)
git diff docker-compose.yml
# git add docker-compose.yml  # 관련 있으면 추가

# 5. 커밋
git commit -m "feat: Implement Transactional Outbox Pattern for Auction Events

- Add Outbox entity and repository
- Add OutboxRelayScheduler for automatic event publishing
- Modify AuctionEndedEventProducer to use Outbox pattern
- Add Outbox scheduler configuration in application.yaml

This ensures atomicity between business transaction and event publishing,
with automatic retry mechanism for failed events."

# 6. 푸시
git push origin fix/k8s-remove-duplicate-services
```

---

## 프론트엔드 PR

### 브랜치: `feat/add-auth-check-product-detail`

### PR 제목
```
fix: Add WebSocket URL configuration for auction real-time updates
```

### PR 설명 (템플릿)
```markdown
## 문제 상황
비로그인 사용자가 경매 상세 페이지에 접속해도 실시간 업데이트(WebSocket)가 작동하지 않는 문제

## 원인
- `VITE_WS_URL` 환경 변수가 설정되지 않아 WebSocket 연결 시도조차 안 됨
- 환경 변수 미설정 시 fallback URL도 없음

## 해결 방법

### 1. WebSocket URL Fallback 추가
```javascript
// Before
const WS_URL = import.meta.env.VITE_WS_URL

// After
const WS_URL = import.meta.env.VITE_WS_URL || "wss://43.200.204.191.nip.io/ws"
```

### 2. 설정 가이드 문서 추가
- `WEBSOCKET_SETUP.md` 작성
- Vercel 환경 변수 설정 방법 안내
- 트러블슈팅 가이드 포함

## 기술적 배경

### 타이머 vs WebSocket

**타이머** (REST API 기반):
- 페이지 로드 시 `GET /api/v1/auctions/{id}` → `endsAt` 획득
- 클라이언트 자바스크립트로 1초마다 갱신
- **WebSocket 없어도 작동** ✅

**WebSocket** (실시간 업데이트):
- 다른 사용자의 입찰 발생 시 실시간 반영
- `currentBid`, `bidCount` 업데이트
- 선택적 기능 (없어도 페이지는 정상 작동)

### 대형 경매 사이트 패턴 (eBay, Yahoo Auction)
1. REST API로 초기 데이터 로드
2. 클라이언트 타이머 실행
3. WebSocket은 입찰 업데이트만 사용
4. WebSocket 연결 실패해도 타이머는 작동

## 테스트 방법

### 로컬
```bash
# .env 파일 확인
cat .env | grep VITE_WS_URL
# VITE_WS_URL=ws://localhost:8000/ws

npm run dev
```

### Vercel 배포
1. Vercel 대시보드 → Settings → Environment Variables
2. 추가: `VITE_WS_URL = wss://43.200.204.191.nip.io/ws`
3. 재배포

### 확인 사항
- [ ] 비로그인 상태에서 경매 상세 페이지 접속
- [ ] "남은 시간" 타이머 표시 확인
- [ ] 브라우저 Console에서 WebSocket 연결 확인
- [ ] 다른 사용자 입찰 시 실시간 업데이트 확인

## 관련 이슈
- 비로그인 사용자 경매 상세 페이지 타이머 미표시 문제

## 참고 자료
- [Vite 환경 변수](https://vitejs.dev/guide/env-and-mode.html)
- [WebSocket STOMP 프로토콜](https://stomp.github.io/)
```

---

### 포함할 파일

```bash
# 필수: WebSocket Hook 수정
git add biddy_frontend/src/hooks/useAuctionWebSocket.js

# 권장: 설정 가이드
git add biddy_frontend/WEBSOCKET_SETUP.md

# 제외: IDE 설정
# .idea/ → .gitignore에 추가
```

---

### .gitignore 업데이트 (권장)

```bash
# .idea/ 제외
echo ".idea/" >> .gitignore

# 확인
git status
# .idea/가 Untracked에서 사라져야 함
```

---

### Git 커맨드

```bash
cd /Users/minya/IdeaProjects/beadv6_6_frontal_FE

# 1. .gitignore 업데이트 (선택)
echo ".idea/" >> .gitignore

# 2. 변경 파일 추가
git add biddy_frontend/src/hooks/useAuctionWebSocket.js
git add biddy_frontend/WEBSOCKET_SETUP.md

# 3. .gitignore도 추가 (위에서 수정했다면)
git add .gitignore

# 4. 커밋
git commit -m "fix: Add WebSocket URL configuration for auction real-time updates

- Add fallback URL for WebSocket connection
- Add comprehensive setup guide (WEBSOCKET_SETUP.md)
- Improve error handling when VITE_WS_URL is not configured

This ensures WebSocket connection works in both development and production,
with clear documentation for environment variable setup."

# 5. 푸시
git push origin feat/add-auth-check-product-detail
```

---

## 추가 작업 (Vercel)

### Vercel 환경 변수 설정

```
1. https://vercel.com 접속
2. 프로젝트 선택 (biddy-frontend)
3. Settings → Environment Variables
4. Add New Variable:
   - Key: VITE_WS_URL
   - Value: wss://43.200.204.191.nip.io/ws
   - Environment: Production, Preview, Development (모두 선택)
5. Save
```

**중요**: 환경 변수 추가 후 자동으로 재배포되지 않으므로:
```bash
# 빈 커밋으로 재배포 트리거
git commit --allow-empty -m "chore: Trigger Vercel redeploy for env vars"
git push
```

---

## K6 테스트 및 문서 (별도 PR 권장)

### 별도 PR: "docs: Add K6 performance testing suite and Phase 2 results"

```bash
# 새 브랜치 생성
git checkout -b docs/add-k6-tests

# 파일 추가
git add docs/99_작업중/
git add k6-tests/

# 커밋
git commit -m "docs: Add K6 performance testing suite and Phase 2 results

- Add Phase 1 completion summary and API analysis
- Add Phase 2 test result template
- Add K6 test scripts (smoke, business rules, concurrency)
- Add test execution guide and troubleshooting

Includes:
- 4 K6 test scripts for auction service
- Test data setup SQL scripts
- Comprehensive documentation for test execution
- Next steps and action items"

# 푸시
git push origin docs/add-k6-tests
```

---

## 최종 체크리스트

### 백엔드 PR
- [ ] Outbox 패턴 파일 추가 확인
- [ ] AuctionEndedEventProducer 수정 확인
- [ ] application.yaml 설정 확인
- [ ] docker-compose.yml 변경 내용 검토
- [ ] 문서 업데이트 확인
- [ ] 커밋 메시지 작성
- [ ] PR 생성 및 리뷰 요청

### 프론트엔드 PR
- [ ] useAuctionWebSocket.js 수정 확인
- [ ] WEBSOCKET_SETUP.md 추가 확인
- [ ] .gitignore 업데이트 (.idea/ 제외)
- [ ] 커밋 메시지 작성
- [ ] PR 생성 및 리뷰 요청
- [ ] Vercel 환경 변수 설정
- [ ] Vercel 재배포 확인

### 테스트/문서 PR (선택)
- [ ] K6 테스트 스크립트 추가
- [ ] 테스트 결과 문서 추가
- [ ] 별도 브랜치 생성
- [ ] PR 생성

---

## 참고 링크

- [Transactional Outbox 패턴](https://microservices.io/patterns/data/transactional-outbox.html)
- [Vite 환경 변수](https://vitejs.dev/guide/env-and-mode.html)
- [Vercel 환경 변수 설정](https://vercel.com/docs/projects/environment-variables)