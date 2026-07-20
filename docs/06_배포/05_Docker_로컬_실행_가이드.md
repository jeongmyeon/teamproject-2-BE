# Biddy MSA Docker 로컬 실행 가이드

> 로컬 테스트 전용 문서 (git에 올리지 않음)

---

## 1. 사전 조건

- Docker Desktop 실행 중
- Java 21, Gradle 설치
- 로컬 PostgreSQL 서비스 **중지** (포트 5432 충돌 방지)

```powershell
# 로컬 PostgreSQL 중지
Stop-Service -Name 'postgresql-x64-18'

# 포트 충돌 확인
netstat -ano | findstr ":5432" | findstr "LISTENING"
```

---

## 2. 빌드

```bash
# 전체 빌드
./gradlew clean build -x test

# auction만 빌드
./gradlew :auction:build -x test

# 특정 서비스 제외
./gradlew clean build -x test -x :payment:build -x :product:build
```

---

## 3. Docker 실행

### 전체 한번에

```bash
docker compose up --build -d
```

### 단계별 (안정적)

```bash
# 1단계: 인프라 (15~20초 대기)
docker compose up -d postgres redis kafka discovery

# 2단계: 서비스
docker compose up -d config apigateway member product order auction payment
```

### 특정 서비스만 재배포

```bash
./gradlew :auction:build -x test && docker compose up --build -d auction
./gradlew :product:build -x test && docker compose up --build -d product
./gradlew :apigateway:build -x test && docker compose up --build -d apigateway
```

---

## 4. 상태 확인

```bash
# 전체 컨테이너
docker compose ps

# 서비스 로그
docker compose logs auction                    # 전체
docker compose logs auction --tail 20          # 마지막 20줄
docker compose logs auction -f                 # 실시간
docker compose logs auction | grep "Warm-up"   # 특정 키워드
```

---

## 5. 서비스 포트

| 서비스 | 포트 | URL |
|--------|------|-----|
| postgres | 5432 | - |
| redis | 6379 | - |
| kafka | 9092 | - |
| discovery | 8761 | http://localhost:8761 |
| config | 8888 | - |
| apigateway | 8000 | http://localhost:8000 |
| member | 8081 | - |
| product | 8082 | - |
| order | 8083 | - |
| auction | 8084 | http://localhost:8084/swagger-ui/index.html |
| payment | 8085 | - |
| frontend | 5173 | http://localhost:5173 (수동 실행) |

---

## 6. 회원 생성

```bash
# 이메일 인증 레코드 생성
docker exec biddy-postgres psql -U biddy -d biddy_member -c "
INSERT INTO public.email_verification (email, token, expired_at, verified_at, created_at) VALUES
('test@biddy.com', 'token1', NOW() + INTERVAL '1 day', NOW(), NOW()),
('buyer2@biddy.com', 'token2', NOW() + INTERVAL '1 day', NOW(), NOW());
"

# 회원가입
curl -X POST http://localhost:8000/api/members/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"test@biddy.com","password":"password1234","nickname":"test","phone":"01012345678"}'

curl -X POST http://localhost:8000/api/members/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer2@biddy.com","password":"password1234","nickname":"buyer2","phone":"01098765432"}'

# 로그인 확인
curl -X POST http://localhost:8000/api/members/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@biddy.com","password":"password1234"}'
```

---

## 7. 경매 시드 데이터 (productId: Long)

```bash
docker exec biddy-postgres psql -U biddy -d biddy_auction -c "
TRUNCATE TABLE bid, auction_watch, auction CASCADE;

INSERT INTO auction (auction_id, product_id, seller_id, start_price, min_increment, current_bid, bid_count, watcher_count, status, starts_at, ends_at, created_at, updated_at) VALUES
('A-EAR01', 101, 1, 1000, 100, 2500, 8, 0, 'LIVE', NOW()-INTERVAL '2 days', NOW()+INTERVAL '3 days', NOW()-INTERVAL '2 days', NOW()),
('A-PEN01', 102, 2, 500, 50, 1200, 6, 0, 'LIVE', NOW()-INTERVAL '3 days', NOW()+INTERVAL '2 days', NOW()-INTERVAL '3 days', NOW()),
('A-MUG01', 103, 1, 2000, 200, 5000, 5, 0, 'LIVE', NOW()-INTERVAL '1 day', NOW()+INTERVAL '5 days', NOW()-INTERVAL '1 day', NOW()),
('A-SOC01', 104, 2, 3000, 500, 7500, 4, 0, 'LIVE', NOW()-INTERVAL '4 days', NOW()+INTERVAL '1 day', NOW()-INTERVAL '4 days', NOW()),
('A-TOY01', 105, 1, 800, 100, 3200, 7, 0, 'LIVE', NOW()-INTERVAL '5 days', NOW()+INTERVAL '12 hours', NOW()-INTERVAL '5 days', NOW()),
('A-KEY01', 106, 2, 1500, 100, 4500, 5, 0, 'ENDED', NOW()-INTERVAL '10 days', NOW()-INTERVAL '3 days', NOW()-INTERVAL '10 days', NOW()-INTERVAL '3 days'),
('A-CUP01', 107, 1, 2000, 200, 6000, 6, 0, 'ENDED', NOW()-INTERVAL '7 days', NOW()-INTERVAL '1 day', NOW()-INTERVAL '7 days', NOW()-INTERVAL '1 day'),
('A-BAG01', 108, 2, 500, 50, 1800, 8, 0, 'ENDED', NOW()-INTERVAL '14 days', NOW()-INTERVAL '7 days', NOW()-INTERVAL '14 days', NOW()-INTERVAL '7 days');

INSERT INTO bid (auction_id, bidder_id, amount, bid_at) VALUES
('A-EAR01',1,1100,NOW()-INTERVAL '47h'),('A-EAR01',2,1300,NOW()-INTERVAL '46h'),
('A-EAR01',1,1500,NOW()-INTERVAL '24h'),('A-EAR01',2,1700,NOW()-INTERVAL '21h'),
('A-EAR01',1,1900,NOW()-INTERVAL '18h'),('A-EAR01',2,2100,NOW()-INTERVAL '12h'),
('A-EAR01',1,2300,NOW()-INTERVAL '6h'),('A-EAR01',2,2500,NOW()-INTERVAL '3h'),
('A-PEN01',1,550,NOW()-INTERVAL '71h'),('A-PEN01',2,650,NOW()-INTERVAL '48h'),
('A-PEN01',1,750,NOW()-INTERVAL '43h'),('A-PEN01',2,900,NOW()-INTERVAL '24h'),
('A-PEN01',1,1050,NOW()-INTERVAL '12h'),('A-PEN01',2,1200,NOW()-INTERVAL '6h'),
('A-MUG01',1,2200,NOW()-INTERVAL '20h'),('A-MUG01',2,2600,NOW()-INTERVAL '18h'),
('A-MUG01',1,3200,NOW()-INTERVAL '14h'),('A-MUG01',2,3800,NOW()-INTERVAL '10h'),
('A-MUG01',1,5000,NOW()-INTERVAL '6h');

INSERT INTO auction_watch (auction_id, member_id, created_at) VALUES
('A-EAR01',1,NOW()-INTERVAL '2 days'),('A-EAR01',2,NOW()-INTERVAL '1 day'),
('A-PEN01',1,NOW()-INTERVAL '3 days'),('A-MUG01',2,NOW()-INTERVAL '1 day'),
('A-SOC01',1,NOW()-INTERVAL '3 days'),('A-TOY01',2,NOW()-INTERVAL '4 days');
"

# Redis Warm-up 반영
docker compose restart auction
```

---

## 8. API 테스트

```bash
# JWT 토큰 획득
TOKEN=$(curl -s -X POST http://localhost:8000/api/members/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@biddy.com","password":"password1234"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# 경매 피드 (비인증 OK)
curl -s http://localhost:8084/api/v1/auctions?page=0

# 입찰 (JWT 필수)
curl -s -X POST http://localhost:8084/api/v1/auctions/A-EAR01/bids \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"amount": 2600}'

# 관심 토글 (JWT 필수)
curl -s -X POST http://localhost:8084/api/v1/auctions/A-EAR01/watch \
  -H "Authorization: Bearer $TOKEN"

# 판매자 즉시 종료 (JWT 필수, 판매자만)
curl -s -X POST http://localhost:8084/api/v1/auctions/A-EAR01/close \
  -H "Authorization: Bearer $TOKEN"

# 닉네임 조회 (비인증 OK)
curl -s http://localhost:8000/api/members/1/nickname
```

---

## 9. Kafka 확인

```bash
# 토픽 목록
docker exec biddy-kafka sh -c "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"

# auction.ended 메시지
docker exec biddy-kafka sh -c "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic auction.ended --from-beginning --max-messages 5 --timeout-ms 5000"

# product.auction.registered 메시지
docker exec biddy-kafka sh -c "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic product.auction.registered --from-beginning --max-messages 5 --timeout-ms 5000"
```

---

## 10. Redis 확인

```bash
docker exec biddy-redis redis-cli ping                          # PONG
docker exec biddy-redis redis-cli KEYS "watch:*"                # 전체 키
docker exec biddy-redis redis-cli SMEMBERS "watch:user:1"       # 회원1 관심 경매
docker exec biddy-redis redis-cli GET "watch:auction:A-EAR01:count"  # 경매 관심 수
```

---

## 11. DB 접속 (DBeaver)

| 항목 | 값 |
|------|-----|
| Host | localhost |
| Port | 5432 |
| User | biddy |
| Password | biddy1234 |

| DB | 서비스 |
|----|--------|
| biddy_auction | 경매 |
| biddy_member | 회원 |
| biddy_product | 상품 |
| biddy_order | 주문 |
| biddy_payment | 결제 |

```bash
# DB 직접 조회
docker exec biddy-postgres psql -U biddy -d biddy_auction -c "SELECT auction_id, product_id, status FROM auction;"
docker exec biddy-postgres psql -U biddy -d biddy_member -c "SELECT id, email, nickname FROM public.member;"
```

---

## 12. 종료

```bash
docker compose down          # 중지 (데이터 유지)
docker compose down -v       # 중지 + 볼륨 삭제 (DB 초기화)
docker compose stop auction  # 특정 서비스만 중지
```

---

## 13. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 503 Service Unavailable | Eureka 미등록 (30초 소요) | 30초 대기 |
| 5432 포트 충돌 | 로컬 PostgreSQL 실행 중 | `Stop-Service postgresql-x64-18` |
| 401 Unauthorized | JWT 만료 (30분) | 재로그인 |
| 403 Forbidden | POST에 JWT 누락 | Authorization 헤더 확인 |
| SecurityConfig 빈 충돌 | 동일 이름 Config 2개 | 중복 파일 삭제 |
| Kafka startPrice null | 프론트에서 경매 필드 미전송 | startPrice, minIncrement, endsAt 확인 |
| product_id UUID 에러 | 이전 시드 데이터 | TRUNCATE 후 Long 타입으로 재삽입 |
| Scheduler 무한 쿼리 로그 | AuctionCloseScheduler 정상 동작 | show-sql: false 설정 |
| Redis Warm-up 0건 | auction_watch 비어있음 | 시드 데이터 후 restart |
