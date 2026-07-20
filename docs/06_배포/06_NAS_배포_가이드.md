# Biddy MSA NAS 배포 가이드

## 문서 정보
- **프로젝트**: Biddy 실시간 경매 플랫폼
- **버전**: 1.0
- **작성일**: 2026-06-30
- **대상**: Synology NAS (Container Manager / Docker Compose)

---

## 1. 시스템 요구사항

### 최소 요구사항
- **CPU**: 4 cores (MSA 11개 서비스)
- **RAM**: 8GB
- **Storage**: 20GB
- **Docker**: Docker Compose v2+

### 권장 요구사항
- **CPU**: 6+ cores
- **RAM**: 16GB
- **Storage**: 50GB+ (이미지, DB 볼륨)

---

## 2. 아키텍처

```
[React SPA :5173]
      |
[API Gateway :8000] ─── JWT 검증, CORS, 라우팅
      |
  ┌───┼───┬───┬───┐
  |   |   |   |   |
[:8081][:8082][:8083][:8084][:8085]
member product order auction payment
  |   |   |   |   |
[PostgreSQL :5432] (DB 5개)
              |
         [Kafka :9092]
              |
         [Redis :6379]
              |
      [Eureka :8761]
```

---

## 3. 사전 준비

### 3-1. Synology NAS 설정
1. 패키지 센터 → **Container Manager** 설치
2. SSH 활성화: 제어판 → 터미널 및 SNMP → SSH 서비스 활성화
3. 공유 폴더 생성: `/volume1/docker/biddy`

### 3-2. SSH 접속
```bash
ssh admin@NAS_IP -p 22
```

---

## 4. 프로젝트 클론 및 설정

```bash
# 작업 디렉토리
cd /volume1/docker/biddy

# BE 클론
git clone https://github.com/prgrms-be-adv-devcourse/beadv6_6_frontal_BE.git
cd beadv6_6_frontal_BE
git checkout develop

# FE 클론 (별도 터미널)
cd /volume1/docker/biddy
git clone https://github.com/prgrms-be-adv-devcourse/beadv6_6_frontal_FE.git
cd beadv6_6_frontal_FE
git checkout dev
```

---

## 5. 환경 변수 설정

### .env 파일 생성

```bash
cd /volume1/docker/biddy/beadv6_6_frontal_BE
cat > .env << 'EOF'
## PostgreSQL
POSTGRES_PORT=5432
POSTGRES_USER=biddy
POSTGRES_PASSWORD=biddy1234
POSTGRES_DB=biddy

## Database names
MEMBER_DB=biddy_member
PRODUCT_DB=biddy_product
ORDER_DB=biddy_order
AUCTION_DB=biddy_auction
PAYMENT_DB=biddy_payment

## Mail (회원가입 이메일 인증)
MAIL_USERNAME=your_email@gmail.com
MAIL_PASSWORD=your_app_password

## JWT
JWT_SECRET=devcourse6devcourse6devcourse6devcourse6

## Toss Payments
TOSS_PAYMENTS_SECRET_KEY=test_sk_xxxxx
TOSS_PAYMENTS_BASE_URL=https://api.tosspayments.com
EOF
```

---

## 6. 빌드 및 실행

### 6-1. Java 빌드

```bash
cd /volume1/docker/biddy/beadv6_6_frontal_BE

# 전체 빌드 (테스트 제외)
./gradlew clean build -x test
```

> **빌드 실패 시**: NAS에 JDK 21이 없으면 로컬 PC에서 빌드 후 jar 파일을 NAS로 복사
> ```bash
> # 로컬에서 빌드
> ./gradlew clean build -x test
> # NAS로 전송
> scp -r */build/libs/*.jar admin@NAS_IP:/volume1/docker/biddy/beadv6_6_frontal_BE/
> ```

### 6-2. Docker 실행

```bash
# 전체 한번에
docker compose up --build -d

# 또는 단계별 (안정적)
# 1단계: 인프라 (20초 대기)
docker compose up -d postgres redis kafka discovery
sleep 20

# 2단계: 서비스
docker compose up -d config apigateway member product order auction payment
```

### 6-3. 상태 확인

```bash
# 전체 컨테이너
docker compose ps

# 특정 서비스 로그
docker compose logs auction --tail 20
docker compose logs auction | grep "Warm-up"

# Eureka 대시보드 (브라우저)
# http://NAS_IP:8761
```

---

## 7. 프론트엔드 실행

### 방법 A: NAS에서 직접 실행

```bash
cd /volume1/docker/biddy/beadv6_6_frontal_FE/biddy_frontend

# Node.js 필요 (NAS에 없으면 방법 B 사용)
npm install -g pnpm
pnpm install
pnpm run dev --host 0.0.0.0
```

### 방법 B: 로컬 PC에서 NAS 백엔드에 연결

```bash
# 로컬 PC에서
cd beadv6_6_frontal_FE/biddy_frontend
pnpm run dev
```

`vite.config.js`의 proxy target을 NAS IP로 변경:
```js
proxy: {
  "/api": {
    target: "http://NAS_IP:8000",  // ← NAS IP
    changeOrigin: true,
  },
}
```

---

## 8. 포트 구성

| 서비스 | 포트 | 외부 노출 | 용도 |
|--------|------|:---------:|------|
| PostgreSQL | 5432 | 선택 | DB (DBeaver 접속 시) |
| Redis | 6379 | X | Watch 캐시 |
| Kafka | 9092 | X | 이벤트 메시징 |
| Eureka | 8761 | O | 서비스 디스커버리 |
| Config | 8888 | X | 설정 서버 |
| **API Gateway** | **8000** | **O** | **메인 진입점** |
| Member | 8081 | X | 회원 |
| Product | 8082 | X | 상품 |
| Order | 8083 | X | 주문 |
| **Auction** | **8084** | **O** | **경매 (Swagger, WebSocket)** |
| Payment | 8085 | X | 결제 |
| **Frontend** | **5173** | **O** | **React SPA** |

### NAS 포트포워딩 (외부 접근 시)

Synology: 제어판 → 외부 액세스 → 라우터 구성

| 외부 포트 | 내부 포트 | 프로토콜 |
|----------|----------|---------|
| 8000 | 8000 | TCP |
| 5173 | 5173 | TCP |
| 8084 | 8084 | TCP/WebSocket |

---

## 9. 초기 데이터 설정

### 9-1. 회원 생성

```bash
# 이메일 인증 레코드
docker exec biddy-postgres psql -U biddy -d biddy_member -c "
INSERT INTO public.email_verification (email, token, expired_at, verified_at, created_at) VALUES
('user1@biddy.com', 't1', NOW() + INTERVAL '1 day', NOW(), NOW()),
('user2@biddy.com', 't2', NOW() + INTERVAL '1 day', NOW(), NOW()),
('user3@biddy.com', 't3', NOW() + INTERVAL '1 day', NOW(), NOW());
"

# 회원가입
for i in 1 2 3; do
  curl -X POST http://localhost:8000/api/members/signup \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"user${i}@biddy.com\",\"password\":\"password1234\",\"nickname\":\"유저${i}\",\"phone\":\"010123400${i}\"}"
done

# 확인
docker exec biddy-postgres psql -U biddy -d biddy_member \
  -c "SELECT id, email, nickname FROM public.member;"
```

### 9-2. 경매 데이터

상품 등록(프론트) → Kafka → 경매 자동 생성 흐름으로 데이터가 생성됩니다.
수동으로 시드 데이터를 넣으려면:

```bash
docker exec biddy-postgres psql -U biddy -d biddy_auction -c "
INSERT INTO auction (auction_id, product_id, seller_id, start_price, min_increment, current_bid, bid_count, watcher_count, status, starts_at, ends_at, created_at, updated_at) VALUES
('A-TEST1', 1, 1, 1000, 100, 1000, 0, 0, 'LIVE', NOW(), NOW()+INTERVAL '7 days', NOW(), NOW()),
('A-TEST2', 2, 2, 2000, 200, 2000, 0, 0, 'LIVE', NOW(), NOW()+INTERVAL '7 days', NOW(), NOW());
"

# Redis Warm-up
docker compose restart auction
```

---

## 10. Gateway CORS 설정

외부 IP로 접근할 경우 CORS 추가 필요:

`apigateway/src/main/resources/application.yml`:
```yaml
allowedOrigins:
  - "http://localhost:5173"
  - "http://NAS_IP:5173"
  - "http://your-domain.com"
```

변경 후:
```bash
./gradlew :apigateway:build -x test
docker compose up --build -d apigateway
```

---

## 11. 운영

### 업데이트

```bash
cd /volume1/docker/biddy/beadv6_6_frontal_BE
git pull origin develop
./gradlew clean build -x test
docker compose up --build -d
```

### 서비스 재시작

```bash
# 전체
docker compose restart

# 특정 서비스
docker compose restart auction

# 재빌드 + 재시작
./gradlew :auction:build -x test && docker compose up --build -d auction
```

### 로그 확인

```bash
# 실시간
docker compose logs -f auction

# Kafka 토픽 확인
docker exec biddy-kafka sh -c "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"

# Redis 확인
docker exec biddy-redis redis-cli KEYS "watch:*"
```

---

## 12. 백업

### 자동 백업 스크립트

```bash
#!/bin/bash
# /volume1/docker/biddy/backup.sh

BACKUP_DIR="/volume1/docker/biddy/backups"
DATE=$(date +%Y%m%d_%H%M%S)
mkdir -p $BACKUP_DIR

# DB 백업 (전체)
for db in biddy_member biddy_product biddy_order biddy_auction biddy_payment; do
  docker exec biddy-postgres pg_dump -U biddy $db > $BACKUP_DIR/${db}_${DATE}.sql
done

# 상품 이미지 백업
docker cp biddy-product:/app/images $BACKUP_DIR/images_${DATE}

# 30일 이상 백업 삭제
find $BACKUP_DIR -name "*.sql" -mtime +30 -delete
find $BACKUP_DIR -type d -name "images_*" -mtime +30 -exec rm -rf {} +

echo "백업 완료: $DATE"
```

### Cron 설정 (Synology 작업 스케줄러)

제어판 → 작업 스케줄러 → 생성 → 예약된 작업 → 사용자 정의 스크립트

```
bash /volume1/docker/biddy/backup.sh
```

매일 새벽 3시 실행 설정.

---

## 13. 복구

```bash
# DB 복구
docker exec -i biddy-postgres psql -U biddy biddy_auction < backups/biddy_auction_20260630.sql

# 상품 이미지 복구
docker cp backups/images_20260630/. biddy-product:/app/images/
```

---

## 14. 종료 / 초기화

```bash
# 서비스 중지 (데이터 유지)
docker compose down

# 완전 초기화 (DB 데이터 삭제)
docker compose down -v

# Docker 이미지 정리
docker system prune -a
```

---

## 15. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 503 Service Unavailable | Eureka 등록 대기 (30초) | 30초 후 재시도 |
| 401 Unauthorized | JWT 만료 (30분) | 재로그인 |
| product_id UUID 에러 | 이전 DB 스키마 잔존 | `docker compose down -v` 후 재시작 |
| Kafka Consumer 실패 | startPrice null | 프론트에서 경매 필드 전송 확인 |
| CORS 에러 | Gateway allowedOrigins 누락 | NAS IP를 CORS에 추가 |
| 메모리 부족 | MSA 11개 동시 실행 | 불필요 서비스 중지 또는 RAM 증설 |
| 빌드 실패 | NAS에 JDK 없음 | 로컬 빌드 후 jar 복사 |
