# Biddy 고도화 계획 및 기술 가이드

> 작성일: 2026-07-03
> 목적: MSA 기반 Biddy 서비스의 성능, 안정성, 데이터 정합성, 운영 관리 고도화 방안 및 각 기술에 대한 이해도 향상

---

## 목차

1. [고도화 개요](#1-고도화-개요)
2. [인프라 고도화](#2-인프라-고도화)
3. [성능 및 확장성 개선](#3-성능-및-확장성-개선)
4. [데이터 정합성 및 안정성](#4-데이터-정합성-및-안정성)
5. [품질 관리 및 테스트](#5-품질-관리-및-테스트)
6. [모니터링 및 로깅](#6-모니터링-및-로깅)
7. [사용자 경험 개선](#7-사용자-경험-개선)
8. [AI 기능 확장](#8-ai-기능-확장)
9. [도메인별 적용 계획](#9-도메인별-적용-계획)
10. [단계별 로드맵](#10-단계별-로드맵)

---

## 1. 고도화 개요

### 1.1 현재 상태

Biddy는 Member, Product, Auction, Order, Payment 서비스로 분리된 MSA 구조이며, Kafka 이벤트 기반 통신, PostgreSQL DB, Redis 캐시, WebSocket 실시간 통신을 사용하고 있습니다.

**현재 구조의 한계:**
- Docker Compose 기반 배포 → 스케일링 어려움
- 단일 서버 구조 → 트래픽 증가 시 병목
- 이벤트 처리 실패 시 복구 메커니즘 부족
- 분산 환경에서 로그 추적 어려움
- 성능 병목 지점 파악 어려움

### 1.2 고도화 목표

| 목표 | 현재 | 목표 | 개선 방향 |
|-----|------|------|----------|
| **가용성** | 95% | 99.9% | Kubernetes 자동 복구 + Multi-PG |
| **응답 시간** | 500ms | 100ms | Redis 캐싱 + DB 쿼리 최적화 |
| **처리량** | 100 TPS | 1,000 TPS | 수평 확장 + 병렬 처리 |
| **데이터 정합성** | 부분적 | 100% | Outbox Pattern + DLQ |
| **장애 감지** | 수동 (10분+) | 자동 (30초) | Prometheus + Grafana |

---

## 2. 인프라 고도화

### 2.1 Kubernetes 기반 배포 및 운영

#### 개념 설명

**Kubernetes(K8s)** 는 컨테이너화된 애플리케이션을 자동으로 배포, 스케일링, 관리하는 오픈소스 플랫폼입니다.

**핵심 개념:**

```
[Kubernetes 구조]

┌─────────────────────────────────────────┐
│         Kubernetes Cluster              │
│                                          │
│  ┌────────────────────────────────┐    │
│  │  Master Node (Control Plane)   │    │
│  │  - API Server                  │    │
│  │  - Scheduler                   │    │
│  │  - Controller Manager          │    │
│  │  - etcd (상태 저장소)           │    │
│  └────────────────────────────────┘    │
│                                          │
│  ┌──────────────┐  ┌──────────────┐    │
│  │ Worker Node  │  │ Worker Node  │    │
│  │              │  │              │    │
│  │ ┌──────────┐ │  │ ┌──────────┐ │    │
│  │ │   Pod    │ │  │ │   Pod    │ │    │
│  │ │ Product  │ │  │ │ Auction  │ │    │
│  │ │ Service  │ │  │ │ Service  │ │    │
│  │ └──────────┘ │  │ └──────────┘ │    │
│  │              │  │              │    │
│  │ ┌──────────┐ │  │ ┌──────────┐ │    │
│  │ │   Pod    │ │  │ │   Pod    │ │    │
│  │ │ Member   │ │  │ │ Payment  │ │    │
│  │ │ Service  │ │  │ │ Service  │ │    │
│  │ └──────────┘ │  │ └──────────┘ │    │
│  └──────────────┘  └──────────────┘    │
└─────────────────────────────────────────┘
```

**주요 리소스:**

| 리소스 | 설명 | Biddy 활용 |
|--------|------|-----------|
| **Pod** | 컨테이너 실행 단위 | 각 서비스(Member, Product 등)를 Pod로 실행 |
| **Deployment** | Pod 복제본 관리 | replica 수 조절로 스케일링 |
| **Service** | 로드밸런싱 | 여러 Pod로 요청 분산 |
| **ConfigMap** | 설정 관리 | DB URL, Kafka 주소 등 환경 설정 |
| **Secret** | 민감 정보 관리 | JWT Secret, DB 비밀번호 |

#### Docker Compose vs Kubernetes 비교

```
[Docker Compose - 현재]
docker-compose up
→ 모든 서비스가 단일 호스트에서 실행
→ 스케일링: docker-compose up --scale product=3 (수동)
→ 장애 복구: 수동 재시작 필요

[Kubernetes - 목표]
kubectl apply -f k8s/
→ 여러 노드에 분산 실행
→ 스케일링: kubectl scale deployment product --replicas=3
→ 장애 복구: Pod 죽으면 자동 재시작
```

#### Biddy 적용 계획

**Phase 1: Deployment 및 Service 작성**

```yaml
# product-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: product-service
spec:
  replicas: 2  # 초기 2개 Pod
  selector:
    matchLabels:
      app: product
  template:
    metadata:
      labels:
        app: product
    spec:
      containers:
      - name: product
        image: biddy/product-service:latest
        ports:
        - containerPort: 8082
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: DB_URL
          valueFrom:
            configMapKeyRef:
              name: db-config
              key: product-db-url
        resources:
          requests:
            cpu: "500m"     # 0.5 CPU
            memory: "512Mi"
          limits:
            cpu: "1000m"    # 1 CPU
            memory: "1Gi"
---
apiVersion: v1
kind: Service
metadata:
  name: product-service
spec:
  selector:
    app: product
  ports:
  - protocol: TCP
    port: 8082
    targetPort: 8082
  type: ClusterIP  # 내부 통신
```

**Phase 2: 로드밸런싱 검증**

```bash
# 1. Product 서비스 replica 3개로 증가
kubectl scale deployment product-service --replicas=3

# 2. Pod 목록 확인
kubectl get pods -l app=product

# 출력:
# NAME                              READY   STATUS
# product-service-5f6b7c8d9-abc12   1/1     Running
# product-service-5f6b7c8d9-def34   1/1     Running
# product-service-5f6b7c8d9-ghi56   1/1     Running

# 3. 반복 요청으로 로드밸런싱 확인
for i in {1..10}; do
  curl http://product-service:8082/api/products
  echo "Request $i sent"
done

# 4. 각 Pod 로그 확인 (요청이 분산되었는지)
kubectl logs -f product-service-5f6b7c8d9-abc12
kubectl logs -f product-service-5f6b7c8d9-def34
kubectl logs -f product-service-5f6b7c8d9-ghi56

# 예상 결과: 10개 요청이 3개 Pod에 고르게 분산 (약 3-4개씩)
```

**Phase 3: 자동 복구 검증**

```bash
# 1. 특정 Pod 강제 삭제
kubectl delete pod product-service-5f6b7c8d9-abc12

# 2. ReplicaSet이 새 Pod 자동 생성 확인
kubectl get pods -l app=product -w

# 출력:
# product-service-5f6b7c8d9-abc12   1/1   Terminating
# product-service-5f6b7c8d9-xyz99   0/1   Pending
# product-service-5f6b7c8d9-xyz99   1/1   Running

# → 서비스는 계속 정상 동작 (다른 2개 Pod가 처리)
```

---

### 2.2 서비스별 자원 할당 관리

#### 개념 설명

**Kubernetes Resources (requests/limits)**

| 항목 | 설명 |
|------|------|
| **requests** | Pod를 스케줄링할 때 보장받는 최소 자원 |
| **limits** | Pod가 사용할 수 있는 최대 자원 (초과 시 OOM Kill) |

**예시:**
```yaml
resources:
  requests:
    cpu: "500m"      # 0.5 CPU 코어
    memory: "512Mi"  # 512 MB 메모리
  limits:
    cpu: "1000m"     # 최대 1 CPU
    memory: "1Gi"    # 최대 1 GB
```

**동작 원리:**
1. Kubernetes Scheduler는 requests를 기준으로 Pod를 배치할 노드 선택
2. Pod는 최대 limits까지 자원 사용 가능
3. CPU limit 초과 시: Throttling (느려짐)
4. Memory limit 초과 시: OOM Kill (Pod 재시작)

#### Biddy 서비스별 자원 할당 전략

```yaml
# 1. Member Service (인증 - 가벼움)
resources:
  requests:
    cpu: "300m"
    memory: "384Mi"
  limits:
    cpu: "600m"
    memory: "768Mi"

# 2. Product Service (조회 많음 - 중간)
resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"

# 3. Auction Service (WebSocket + 입찰 폭주 - 높음)
resources:
  requests:
    cpu: "700m"
    memory: "768Mi"
  limits:
    cpu: "1500m"
    memory: "1536Mi"

# 4. Payment Service (정산 배치 - 가장 높음)
resources:
  requests:
    cpu: "700m"
    memory: "768Mi"
  limits:
    cpu: "1500m"
    memory: "1536Mi"
  env:
  - name: SETTLEMENT_THREAD_POOL_SIZE
    value: "3"  # 정산 배치 병렬 처리 스레드 수

# 5. Order Service (중간)
resources:
  requests:
    cpu: "500m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "1Gi"
```

**자원 할당 튜닝 지표:**

| 서비스 | 모니터링 지표 | 임계값 | 조치 |
|--------|--------------|--------|------|
| Product | CPU 사용률 | >80% | replica 증가 |
| Auction | 메모리 사용률 | >85% | limit 증가 |
| Payment | 배치 처리 시간 | >30분 | thread pool 증가 |

---

### 2.3 Horizontal Pod Autoscaler (HPA)

#### 개념 설명

HPA는 CPU/메모리 사용률을 모니터링하여 **자동으로 replica 수를 조절**하는 기능입니다.

```
[HPA 동작 원리]

현재 상태: Product Service replica=2, CPU 사용률 평균 85%

HPA 설정: CPU > 70% 시 스케일 아웃

1분 후: HPA가 replica를 2 → 3으로 증가
        CPU 사용률 하락 (85% → 60%)

트래픽 감소 후: HPA가 replica를 3 → 2로 감소 (cooldown 5분)
```

#### Biddy 적용

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: product-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: product-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

**시나리오:**
```
평소: replica=2 (CPU 50%)
할인 이벤트: 트래픽 5배 증가
  → HPA가 replica=10까지 자동 증가
  → CPU 사용률 70% 유지
이벤트 종료: 트래픽 정상화
  → HPA가 replica=2로 자동 감소
```

---

## 3. 성능 및 확장성 개선

### 3.1 Redis 및 TTL 적용

#### 개념 설명

**Redis** 는 In-Memory Key-Value 저장소로, 디스크 기반 DB보다 **100~1000배 빠른 조회 성능**을 제공합니다.

**TTL (Time To Live)** 은 데이터의 자동 만료 시간을 설정하는 기능입니다.

```
[Redis 없이 - 기존]
상품 상세 조회 → PostgreSQL 조회 (50ms)

[Redis 캐싱 - 개선]
1차 조회 → Redis 조회 (1ms) → 캐시 히트
2차 조회 → Redis 조회 (1ms) → 캐시 히트
...
TTL 만료 → Redis에서 삭제
다음 조회 → Redis 미스 → PostgreSQL 조회 → Redis에 저장
```

#### Redis 사용 패턴

| 패턴 | 설명 | Biddy 활용 |
|------|------|-----------|
| **Cache-Aside** | 읽기 전에 캐시 확인, 미스 시 DB 조회 후 캐시 저장 | 상품 상세, 회원 프로필 |
| **Write-Through** | 쓰기 시 DB + 캐시 동시 저장 | 경매 최고가 갱신 |
| **Write-Behind** | 쓰기 시 캐시만 저장, 비동기로 DB 저장 | 조회수, 인기 검색어 |

#### Biddy 적용 시나리오

**1. 상품 상세 캐시 (Cache-Aside)**

```java
@Service
public class ProductQueryService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProductRepository productRepository;

    public ProductDetailResponse getProductDetail(UUID productId) {
        String cacheKey = "product:detail:" + productId;

        // 1. Redis 조회
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.info("Cache HIT for product {}", productId);
            return deserialize(cached);
        }

        // 2. Cache MISS → DB 조회
        log.info("Cache MISS for product {}", productId);
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        ProductDetailResponse response = ProductDetailResponse.from(product);

        // 3. Redis에 저장 (TTL 1시간)
        redisTemplate.opsForValue().set(
            cacheKey,
            serialize(response),
            Duration.ofHours(1)
        );

        return response;
    }

    // 상품 수정 시 캐시 무효화
    @CacheEvict(value = "product:detail", key = "#productId")
    public void updateProduct(UUID productId, ProductUpdateRequest request) {
        // 업데이트 로직
    }
}
```

**2. 결제 대기 상태 (TTL 10분)**

```java
public class PaymentWaitingService {

    public void createPendingPayment(UUID orderId, BigDecimal amount) {
        String key = "payment:pending:" + orderId;

        // TTL 10분으로 저장
        redisTemplate.opsForValue().set(
            key,
            amount.toString(),
            Duration.ofMinutes(10)
        );
    }

    @Scheduled(fixedRate = 60000) // 1분마다
    public void checkExpiredPayments() {
        // TTL 만료된 주문은 자동 취소
        // Redis Keyspace Notification 활용
    }
}
```

**3. 로그아웃 토큰 블랙리스트 (TTL = 토큰 만료 시간)**

```java
public class JwtBlacklistService {

    public void logout(String token) {
        long expirationMs = jwtTokenProvider.getExpiration(token);
        Duration ttl = Duration.ofMillis(expirationMs);

        // 토큰 만료 시간까지 블랙리스트에 저장
        redisTemplate.opsForValue().set(
            "jwt:blacklist:" + token,
            "revoked",
            ttl
        );
    }

    public boolean isBlacklisted(String token) {
        return redisTemplate.hasKey("jwt:blacklist:" + token);
    }
}
```

**4. 경매 현재 최고가 (Write-Through)**

```java
public class AuctionBidService {

    public void placeBid(String auctionId, BigDecimal amount, Long bidderId) {
        // 1. DB 저장
        Auction auction = auctionRepository.findById(auctionId);
        auction.updateCurrentBid(amount, bidderId);
        auctionRepository.save(auction);

        // 2. Redis 동시 저장 (TTL 경매 종료 시간까지)
        String key = "auction:current-bid:" + auctionId;
        redisTemplate.opsForValue().set(
            key,
            amount.toString(),
            Duration.between(LocalDateTime.now(), auction.getEndsAt())
        );

        // 3. WebSocket으로 실시간 브로드캐스트
        webSocketService.broadcast(auctionId, BidUpdate.of(amount, bidderId));
    }

    public BigDecimal getCurrentBid(String auctionId) {
        String key = "auction:current-bid:" + auctionId;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            return new BigDecimal(cached);
        }

        // Cache Miss → DB 조회
        Auction auction = auctionRepository.findById(auctionId);
        return auction.getCurrentBid();
    }
}
```

#### Redis 장애 대응

**문제:** Redis 서버 다운 시 전체 서비스 중단?

**해법:** Fallback 전략

```java
@Service
public class ResilientCacheService {

    public ProductDetailResponse getProductDetail(UUID productId) {
        try {
            // Redis 시도
            return getFromCache(productId);
        } catch (RedisConnectionException e) {
            log.warn("Redis unavailable, fallback to DB", e);
            // Fallback: DB 직접 조회
            return getFromDatabase(productId);
        }
    }
}
```

**검증 계획:**

```bash
# 1. Redis 서버 중단
docker stop redis

# 2. 상품 조회 요청 (DB fallback 동작 확인)
curl http://localhost:8082/api/products/123

# 예상 로그:
# WARN - Redis unavailable, fallback to DB
# 응답 시간: 1ms → 50ms (느리지만 정상 동작)

# 3. Redis 재시작
docker start redis

# 4. 상품 조회 요청 (Redis 재연결 확인)
curl http://localhost:8082/api/products/123

# 예상 로그:
# INFO - Cache MISS for product 123
# INFO - Stored in Redis with TTL 1h
# 응답 시간: 50ms (첫 요청)

# 5. 두 번째 조회
curl http://localhost:8082/api/products/123

# 예상 로그:
# INFO - Cache HIT for product 123
# 응답 시간: 1ms (캐싱 복구 완료)
```

---

### 3.2 정산 배치 병렬 처리 고도화

#### 개념 설명

**배치 처리 (Batch Processing)** 는 대량의 데이터를 일괄 처리하는 작업입니다. Biddy에서는 판매자별 정산을 매월 1일 02:00에 일괄 처리합니다.

**문제:** 순차 처리 시 시간 과다

```
[순차 처리 - 단일 스레드]
정산 대상 10,000건
1건당 평균 100ms 소요
→ 총 1,000초 (약 17분)

정산 대상 100,000건
→ 총 10,000초 (약 2시간 46분)
```

**해법:** Thread Pool 병렬 처리

```
[병렬 처리 - 3 스레드]
정산 대상 10,000건
3개 스레드가 동시 처리
→ 총 333초 (약 5.5분) - 3배 빠름
```

#### Java Thread Pool 개념

```java
// Thread Pool 생성
ExecutorService executor = Executors.newFixedThreadPool(3);

// 작업 제출
for (Settlement settlement : settlements) {
    executor.submit(() -> {
        processSettlement(settlement);
    });
}

// 모든 작업 완료 대기
executor.shutdown();
executor.awaitTermination(1, TimeUnit.HOURS);
```

**Thread Pool 크기 선택:**

| 작업 유형 | 권장 크기 | 이유 |
|----------|----------|------|
| CPU 집약적 (계산) | CPU 코어 수 | 1.5 ~ 2 |\n| I/O 집약적 (DB, API) | CPU 코어 수 * 2 ~ 4 | I/O 대기 시간 활용 |

#### Biddy 적용

**설정 외부화:**

```yaml
# application-prod.yml
settlement:
  batch:
    thread-pool-size: 3      # Kubernetes ConfigMap으로 관리
    chunk-size: 100          # 한 번에 처리할 건수
    timeout-minutes: 120
```

**병렬 처리 구현:**

```java
@Configuration
public class SettlementBatchConfig {

    @Value("${settlement.batch.thread-pool-size:3}")
    private int threadPoolSize;

    @Bean
    public ExecutorService settlementExecutor() {
        return Executors.newFixedThreadPool(threadPoolSize);
    }
}

@Service
public class SettlementBatchService {

    @Autowired
    private ExecutorService settlementExecutor;

    @Autowired
    private SettlementRepository settlementRepository;

    @Scheduled(cron = "0 0 2 1 * ?") // 매월 1일 02:00
    public void runMonthlySettlement() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);

        log.info("=== 정산 배치 시작: {} ===", lastMonth);
        long startTime = System.currentTimeMillis();

        // 1. 정산 대상 조회
        List<Settlement> targets = settlementRepository
            .findPendingSettlements(lastMonth);

        log.info("정산 대상: {} 건", targets.size());

        // 2. 병렬 처리
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = targets.stream()
            .map(settlement -> CompletableFuture.runAsync(() -> {
                try {
                    processSettlement(settlement);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("정산 실패: {}", settlement.getId(), e);
                    failureCount.incrementAndGet();
                }
            }, settlementExecutor))
            .toList();

        // 3. 모든 작업 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .join();

        long endTime = System.currentTimeMillis();
        long durationSeconds = (endTime - startTime) / 1000;

        log.info("=== 정산 배치 완료 ===");
        log.info("처리 시간: {} 초", durationSeconds);
        log.info("성공: {} 건", successCount.get());
        log.info("실패: {} 건", failureCount.get());
        log.info("TPS: {}", targets.size() / durationSeconds);
    }

    private void processSettlement(Settlement settlement) {
        // 1. 정산 금액 계산
        BigDecimal totalSales = orderRepository
            .sumBySellerAndMonth(settlement.getSellerId(), settlement.getMonth());

        BigDecimal commission = totalSales.multiply(settlement.getCommissionRate());
        BigDecimal netAmount = totalSales.subtract(commission);

        settlement.setTotalSales(totalSales);
        settlement.setCommission(commission);
        settlement.setNetAmount(netAmount);
        settlement.setStatus(SettlementStatus.CALCULATED);

        // 2. 송금 처리
        transferService.transfer(settlement);

        settlement.setStatus(SettlementStatus.COMPLETED);
        settlementRepository.save(settlement);

        // 3. 세금계산서 발행
        taxInvoiceService.issue(settlement);
    }
}
```

#### 성능 비교 테스트

**테스트 시나리오:**

```
조건:
- 정산 대상: 10,000건
- 1건당 평균 처리 시간: 100ms (DB 조회 + 송금 API + 세금계산서)

변수: Thread Pool Size (1, 2, 3, 5)
```

**예상 결과:**

| Thread Pool Size | 총 처리 시간 | TPS | CPU 사용률 | DB 커넥션 |
|------------------|-------------|-----|-----------|----------|
| 1 (순차) | 16분 40초 | 10 | 30% | 1~2 |
| 2 | 8분 30초 | 20 | 50% | 2~3 |
| 3 | 5분 40초 | 30 | 70% | 3~4 |
| 5 | 4분 10초 | 40 | 90% | 5~7 |

**최적값 선정:**
- Thread Pool Size = 3
- 이유: 처리 시간 충분히 빠름 (5분 40초), CPU/DB 부하 적정 수준

---

## 4. 데이터 정합성 및 안정성

### 4.1 Outbox Pattern

#### 개념 설명

**문제:** 분산 트랜잭션 - DB 저장 성공, Kafka 발행 실패

```
[시나리오: 경매 상품 등록]

1. Product Service: 상품 DB 저장 ✅
2. Product Service: Kafka 이벤트 발행 ❌ (네트워크 오류)

결과:
- Product DB에는 경매 상품 존재
- Auction Service는 이벤트를 못 받아 경매 미생성
→ 데이터 불일치!
```

**Outbox Pattern 해법:**

```
[Outbox Pattern]

1. 비즈니스 데이터 + 이벤트를 하나의 트랜잭션으로 저장
   products 테이블: INSERT
   outbox_events 테이블: INSERT (같은 트랜잭션)

2. 별도 Publisher가 outbox_events 조회 → Kafka 발행

3. 발행 성공 시 outbox_events에서 삭제
```

**장점:**
- DB 저장과 이벤트 발행의 원자성 보장
- Kafka 장애 시에도 이벤트 유실 없음
- 재시도 가능

#### Biddy 적용

**1. Outbox Events 테이블**

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,  -- 'PRODUCT', 'AUCTION', 'ORDER' 등
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,     -- 'ProductCreated', 'AuctionWon' 등
    payload JSONB NOT NULL,                -- 이벤트 데이터 (JSON)
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,                -- Kafka 발행 완료 시간
    status VARCHAR(20) NOT NULL            -- 'PENDING', 'PUBLISHED', 'FAILED'
);

CREATE INDEX idx_outbox_status ON outbox_events(status, created_at);
```

**2. Product Service: 상품 등록 + Outbox 저장**

```java
@Service
public class ProductCommandService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Transactional
    public ProductCreateResponse createProduct(ProductCreateRequest request) {
        // 1. 상품 저장
        Product product = Product.create(request);
        productRepository.save(product);

        // 2. Outbox 이벤트 저장 (같은 트랜잭션)
        if (product.isAuctionProduct()) {
            OutboxEvent event = OutboxEvent.builder()
                .aggregateType("PRODUCT")
                .aggregateId(product.getId())
                .eventType("ProductCreatedForAuction")
                .payload(Json.toJson(ProductCreatedEvent.from(product)))
                .status(OutboxEventStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

            outboxEventRepository.save(event);
        }

        return ProductCreateResponse.from(product);
    }
}
```

**3. Outbox Publisher (별도 스케줄러)**

```java
@Component
public class OutboxEventPublisher {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedRate = 5000) // 5초마다
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository
            .findTop100ByStatusOrderByCreatedAtAsc(OutboxEventStatus.PENDING);

        for (OutboxEvent event : pendingEvents) {
            try {
                // Kafka 발행
                String topic = getTopicByEventType(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload());

                // 발행 성공 → 상태 업데이트
                event.setStatus(OutboxEventStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                outboxEventRepository.save(event);

                log.info("Outbox event published: {}", event.getId());

            } catch (Exception e) {
                log.error("Failed to publish outbox event: {}", event.getId(), e);
                event.setStatus(OutboxEventStatus.FAILED);
                outboxEventRepository.save(event);
            }
        }
    }

    // 24시간 이상 PUBLISHED 이벤트는 삭제 (정리)
    @Scheduled(cron = "0 0 3 * * ?") // 매일 03:00
    public void cleanupPublishedEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(1);
        outboxEventRepository.deleteByStatusAndPublishedAtBefore(
            OutboxEventStatus.PUBLISHED,
            threshold
        );
    }
}
```

**검증 시나리오:**

```
1. Kafka 서버 중단
2. 경매 상품 등록 요청 (productType=AUCTION)
3. products 테이블 확인 → 상품 존재 ✅
4. outbox_events 테이블 확인 → 이벤트 PENDING ✅
5. Kafka 재시작
6. 5초 후 outbox_events 확인 → 이벤트 PUBLISHED ✅
7. Auction Service 확인 → 경매 생성됨 ✅

결과: Kafka 장애 중에도 이벤트 유실 없이 정상 처리됨
```

---

### 4.2 Kafka DLQ (Dead Letter Queue)

#### 개념 설명

**문제:** Consumer에서 이벤트 처리 실패 시 메시지 유실

```
[시나리오: 결제 완료 이벤트]

1. Payment Service: PaymentCompletedEvent 발행 ✅
2. Order Service: 이벤트 수신
3. Order Service: 주문 상태 업데이트 중 예외 발생 ❌
   (예: DB 연결 끊김, 잘못된 데이터 등)

결과:
- 결제는 완료됨
- 주문 상태는 여전히 PENDING
- 이벤트는 소실됨 (재처리 불가)
→ 데이터 불일치!
```

**DLQ (Dead Letter Queue) 해법:**

```
[정상 흐름]
payment.completed Topic
  → Order Consumer 처리 성공 ✅
  → Kafka Commit

[실패 흐름]
payment.completed Topic
  → Order Consumer 처리 실패 ❌ (3회 재시도)
  → DLQ Topic으로 이동
  → 수동 확인 후 재처리
```

#### Biddy 적용

**1. Kafka DLQ 설정**

```java
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // DLQ 설정
        factory.setCommonErrorHandler(
            new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate()),
                new FixedBackOff(1000L, 3L) // 1초 간격으로 3회 재시도
            )
        );

        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

**2. Consumer with DLQ**

```java
@Component
public class PaymentEventConsumer {

    @Autowired
    private OrderService orderService;

    // 정상 Topic
    @KafkaListener(topics = "payment.completed", groupId = "order-service")
    public void handlePaymentCompleted(ConsumerRecord<String, String> record) {
        PaymentCompletedEvent event = Json.fromJson(record.value(), PaymentCompletedEvent.class);

        try {
            // 주문 상태 업데이트
            orderService.markAsPaid(event.getOrderId());
            log.info("Payment completed event processed: {}", event.getOrderId());

        } catch (Exception e) {
            log.error("Failed to process payment event: {}", event.getOrderId(), e);
            throw e; // DLQ로 이동
        }
    }

    // DLQ Topic
    @KafkaListener(topics = "payment.completed.DLT", groupId = "order-service-dlq")
    public void handleDLQ(ConsumerRecord<String, String> record) {
        log.error("=== DLQ Message Received ===");
        log.error("Offset: {}", record.offset());
        log.error("Partition: {}", record.partition());
        log.error("Key: {}", record.key());
        log.error("Value: {}", record.value());

        // Slack/이메일 알림
        alertService.sendAlert("DLQ Message Received", record.value());

        // DB에 저장 (수동 재처리용)
        dlqRepository.save(DLQRecord.from(record));
    }
}
```

**3. DLQ 재처리 Admin API**

```java
@RestController
@RequestMapping("/admin/dlq")
public class DLQAdminController {

    @Autowired
    private DLQRepository dlqRepository;

    @Autowired
    private OrderService orderService;

    // DLQ 목록 조회
    @GetMapping
    public List<DLQRecord> listDLQ() {
        return dlqRepository.findAll();
    }

    // 수동 재처리
    @PostMapping("/{id}/retry")
    public void retryDLQ(@PathVariable Long id) {
        DLQRecord record = dlqRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("DLQ record not found"));

        PaymentCompletedEvent event = Json.fromJson(record.getPayload(), PaymentCompletedEvent.class);

        try {
            // 재처리
            orderService.markAsPaid(event.getOrderId());

            // DLQ에서 삭제
            dlqRepository.delete(record);

            log.info("DLQ message successfully retried: {}", id);

        } catch (Exception e) {
            log.error("DLQ retry failed: {}", id, e);
            throw e;
        }
    }
}
```

**검증 시나리오:**

```
1. Order Service의 DB 연결 일시적 차단
2. Payment Service에서 결제 완료 이벤트 발행
3. Order Consumer 로그 확인:
   - 1차 시도 실패
   - 2차 시도 실패 (1초 후)
   - 3차 시도 실패 (1초 후)
   - DLQ로 이동
4. DLQ Topic 확인 → 메시지 존재 ✅
5. Slack 알림 수신 ✅
6. DB 연결 복구
7. Admin API로 재처리: POST /admin/dlq/{id}/retry
8. 주문 상태 확인 → PAID ✅

결과: 실패한 이벤트도 DLQ를 통해 유실 없이 복구 가능
```

---

### 4.3 멱등성 처리

#### 개념 설명

**멱등성 (Idempotency)** 은 동일한 요청을 여러 번 실행해도 결과가 같음을 보장하는 속성입니다.

**문제:**

```
[시나리오: 결제 중복]

사용자가 "결제하기" 버튼을 빠르게 2번 클릭
→ 서버에 2개의 결제 요청 도착
→ 같은 주문에 대해 2번 결제 처리됨
→ 사용자는 2배 청구됨!
```

**해법:** Idempotency Key 패턴

```
[Idempotency Key 흐름]

1. 클라이언트가 UUID 생성 (idempotency_key)
2. 서버가 idempotency_key로 이미 처리된 요청인지 확인
3. 처음 요청 → 정상 처리 후 결과 저장
4. 중복 요청 → 저장된 결과 반환 (재처리 안 함)
```

#### Biddy 적용

**1. Idempotent Requests 테이블**

```sql
CREATE TABLE idempotent_requests (
    idempotency_key VARCHAR(36) PRIMARY KEY,
    member_id BIGINT NOT NULL,
    endpoint VARCHAR(200) NOT NULL,  -- '/api/payments'
    request_body JSONB,
    response_body JSONB,
    status_code INT,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL    -- 24시간 후
);

CREATE INDEX idx_idempotent_expires ON idempotent_requests(expires_at);
```

**2. Idempotency Filter**

```java
@Component
@Order(1)
public class IdempotencyFilter extends OncePerRequestFilter {

    @Autowired
    private IdempotentRequestRepository repository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
        throws ServletException, IOException {

        // POST 요청만 적용
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");

        if (idempotencyKey == null) {
            response.sendError(400, "Idempotency-Key header required");
            return;
        }

        // 이미 처리된 요청인지 확인
        Optional<IdempotentRequest> existing = repository
            .findById(idempotencyKey);

        if (existing.isPresent()) {
            // 저장된 응답 반환
            IdempotentRequest cached = existing.get();
            response.setStatus(cached.getStatusCode());
            response.getWriter().write(cached.getResponseBody());
            log.info("Idempotency HIT: {}", idempotencyKey);
            return;
        }

        // 신규 요청 → 정상 처리
        ResponseCapturingWrapper wrapper = new ResponseCapturingWrapper(response);
        filterChain.doFilter(request, wrapper);

        // 결과 저장 (24시간)
        repository.save(IdempotentRequest.builder()
            .idempotencyKey(idempotencyKey)
            .memberId(getCurrentMemberId())
            .endpoint(request.getRequestURI())
            .responseBody(wrapper.getCapturedResponse())
            .statusCode(wrapper.getStatus())
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build()
        );

        log.info("Idempotency MISS: {}", idempotencyKey);
    }
}
```

**3. 클라이언트 사용 (프론트엔드)**

```javascript
// 결제 버튼 클릭
async function processPayment(orderId, amount) {
    // UUID 생성
    const idempotencyKey = crypto.randomUUID();

    const response = await fetch('/api/payments', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey  // 필수!
        },
        body: JSON.stringify({
            orderId: orderId,
            amount: amount
        })
    });

    if (!response.ok) {
        // 네트워크 타임아웃 등으로 실패
        // 동일 Idempotency-Key로 재시도
        const retryResponse = await fetch('/api/payments', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey  // 같은 Key!
            },
            body: JSON.stringify({
                orderId: orderId,
                amount: amount
            })
        });

        // 서버는 이미 처리된 요청이므로 저장된 응답 반환
        // → 중복 결제 방지됨 ✅
    }

    return response.json();
}
```

**검증 시나리오:**

```
1. 프론트에서 결제 요청 (idempotency-key: abc-123)
2. 서버 로그:
   - "Idempotency MISS: abc-123"
   - 결제 처리 ✅
   - DB에 저장 (status_code: 200)

3. 네트워크 타임아웃 가정
4. 프론트에서 동일 요청 재시도 (same idempotency-key: abc-123)
5. 서버 로그:
   - "Idempotency HIT: abc-123"
   - 저장된 응답 반환 (재처리 안 함) ✅

6. DB 결제 테이블 확인 → 1건만 존재 ✅
```

---

## 5. 품질 관리 및 테스트

### 5.1 테스트 케이스 작성

#### 단위 테스트 (Unit Test)

**목적:** 개별 메서드/클래스가 올바르게 동작하는지 검증

**Biddy 주요 테스트 대상:**

```java
// 1. Product Service - 경매 상품 분기 처리
@Test
void 경매_상품_등록시_Outbox_이벤트_저장됨() {
    // given
    ProductCreateRequest request = ProductCreateRequest.builder()
        .name("롤렉스 서브마리너")
        .productType(ProductType.AUCTION)
        .build();

    // when
    ProductCreateResponse response = productService.createProduct(request);

    // then
    assertThat(response.getProductType()).isEqualTo(ProductType.AUCTION);

    OutboxEvent event = outboxEventRepository
        .findByAggregateIdAndEventType(response.getProductId(), "ProductCreatedForAuction");
    assertThat(event).isNotNull();
    assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
}

// 2. Auction Service - 입찰 금액 검증
@Test
void 최소_증가액보다_낮은_입찰은_거부됨() {
    // given
    Auction auction = auctionRepository.save(Auction.builder()
        .startPrice(new BigDecimal("1000000"))
        .currentBid(new BigDecimal("1200000"))
        .minIncrement(new BigDecimal("50000"))
        .build());

    BidCommand command = BidCommand.builder()
        .auctionId(auction.getId())
        .amount(new BigDecimal("1220000")) // 최소 1,250,000 필요
        .bidderId(123L)
        .build();

    // when & then
    assertThatThrownBy(() -> bidService.placeBid(command))
        .isInstanceOf(InvalidBidAmountException.class)
        .hasMessageContaining("최소 증가액");
}

// 3. Order Service - 주문 상태 전이
@Test
void PENDING_주문은_PAID로만_전이_가능() {
    // given
    Order order = Order.create(createOrderRequest());
    assertThat(order.getStatus()).isEqualTo(OrderState.PENDING);

    // when
    order.markAsPaid();

    // then
    assertThat(order.getStatus()).isEqualTo(OrderState.PAID);
}

@Test
void PENDING_주문을_SHIPPED로_직접_전이하면_예외_발생() {
    // given
    Order order = Order.create(createOrderRequest());

    // when & then
    assertThatThrownBy(() -> order.markAsShipped())
        .isInstanceOf(InvalidStateTransitionException.class);
}

// 4. Payment Service - 결제 성공/실패
@Test
void 잔액_부족시_결제_실패() {
    // given
    Member member = memberRepository.save(Member.builder()
        .balance(new BigDecimal("500000"))
        .build());

    PaymentRequest request = PaymentRequest.builder()
        .memberId(member.getId())
        .amount(new BigDecimal("1000000"))  // 잔액보다 큼
        .build();

    // when & then
    assertThatThrownBy(() -> paymentService.charge(request))
        .isInstanceOf(InsufficientBalanceException.class);
}
```

#### 통합 테스트 (Integration Test)

**목적:** 여러 컴포넌트 (Controller, Service, Repository)가 함께 동작하는지 검증

```java
@SpringBootTest
@AutoConfigureMockMvc
class ProductIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void 경매_상품_등록_E2E_테스트() throws Exception {
        // when
        mockMvc.perform(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + getAccessToken())
                .content("""
                    {
                        "name": "롤렉스 서브마리너",
                        "price": 5000000,
                        "productType": "AUCTION",
                        "category": "WATCHES"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.productType").value("AUCTION"));

        // then
        List<Product> products = productRepository.findAll();
        assertThat(products).hasSize(1);
        assertThat(products.get(0).getProductType()).isEqualTo(ProductType.AUCTION);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("ProductCreatedForAuction");
    }
}
```

#### 이벤트 테스트 (Kafka)

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"product.created"})
class ProductEventTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProductEventConsumer consumer; // Auction Service

    @Test
    void 상품_생성_이벤트_발행_및_수신_테스트() throws Exception {
        // given
        ProductCreatedEvent event = ProductCreatedEvent.builder()
            .productId(UUID.randomUUID())
            .sellerId(123L)
            .startPrice(new BigDecimal("1000000"))
            .build();

        // when
        kafkaTemplate.send("product.created", Json.toJson(event));

        // then (1초 대기)
        Thread.sleep(1000);

        // Auction Service에서 경매 생성 확인
        Auction auction = auctionRepository.findByProductId(event.getProductId());
        assertThat(auction).isNotNull();
        assertThat(auction.getStartPrice()).isEqualTo(event.getStartPrice());
    }
}
```

---

### 5.2 스트레스 테스트 (JMeter)

#### 개념 설명

**JMeter** 는 서버에 대량의 요청을 보내어 성능을 측정하는 도구입니다.

**측정 지표:**

| 지표 | 설명 | 목표값 |
|------|------|--------|
| **TPS** (Transactions Per Second) | 초당 처리 요청 수 | 100+ |
| **평균 응답 시간** | 모든 요청의 평균 | <200ms |
| **P95 응답 시간** | 95%의 요청이 이 시간 내에 완료 | <500ms |
| **에러율** | 실패한 요청 비율 | <1% |

#### JMeter 시나리오

**1. 상품 목록 조회 (읽기 부하)**

```
Thread Group:
  - Number of Threads: 100 (동시 사용자)
  - Ramp-up Period: 10s (10초 동안 100명씩 증가)
  - Loop Count: 10 (각 사용자가 10번 요청)

HTTP Request:
  - Method: GET
  - Path: /api/products
  - Query: page=0&size=20

Duration: 10분
```

**예상 결과:**

| 조건 | TPS | 평균 응답 시간 | P95 | 에러율 |
|------|-----|---------------|-----|--------|
| Redis 없음 | 50 | 400ms | 800ms | 0% |
| Redis 캐싱 적용 | 200 | 50ms | 100ms | 0% |

**2. 경매 입찰 (쓰기 부하)**

```
Thread Group:
  - Number of Threads: 50
  - Ramp-up Period: 5s
  - Loop Count: 20

HTTP Request:
  - Method: POST
  - Path: /api/auctions/${AUCTION_ID}/bids
  - Body: {"amount": ${RANDOM_AMOUNT}}

Duration: 5분
```

**예상 결과:**

| 조건 | TPS | 평균 응답 시간 | P95 | 에러율 |
|------|-----|---------------|-----|--------|
| 순차 처리 | 20 | 100ms | 200ms | 0% |
| Redis Stream 적용 | 100 | 20ms | 50ms | 0% |

**3. 정산 배치 (병렬 처리)**

```
정산 대상: 10,000건
```

| Thread Pool Size | 처리 시간 | TPS | CPU | Memory |
|------------------|----------|-----|-----|--------|
| 1 | 16분 40초 | 10 | 30% | 500MB |
| 3 | 5분 40초 | 30 | 70% | 700MB |
| 5 | 4분 10초 | 40 | 90% | 900MB |

---

### 5.3 SonarQube 코드 품질 관리

#### 개념 설명

**SonarQube** 는 코드 정적 분석 도구로, 버그, 보안 취약점, 코드 스멜, 중복 코드를 자동 검출합니다.

**핵심 지표:**

| 지표 | 설명 | 목표 |
|------|------|------|
| **Bugs** | 실제 버그 (NPE, 무한 루프 등) | 0 |
| **Vulnerabilities** | 보안 취약점 (SQL Injection 등) | 0 |
| **Code Smells** | 유지보수 어려운 코드 | <100 |
| **Test Coverage** | 테스트 커버리지 | >80% |
| **Duplications** | 중복 코드 비율 | <3% |

#### Biddy CI/CD 통합

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [develop, main]
  pull_request:
    branches: [develop, main]

jobs:
  sonarqube:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Run Tests
        run: ./gradlew test jacocoTestReport

      - name: SonarQube Scan
        run: |
          ./gradlew sonarqube \
            -Dsonar.projectKey=biddy \
            -Dsonar.host.url=${{ secrets.SONAR_HOST_URL }} \
            -Dsonar.login=${{ secrets.SONAR_TOKEN }}

      - name: Quality Gate Check
        run: |
          # Quality Gate 통과하지 못하면 빌드 실패
          ./gradlew sonarqube --stacktrace
```

**Quality Gate 설정:**

```
조건:
- Coverage: >= 80%
- Bugs: = 0
- Vulnerabilities: = 0
- Code Smells: < 100
- Duplications: < 3%

→ 모든 조건 만족 시에만 PR merge 허용
```

---

## 6. 모니터링 및 로깅

### 6.1 Prometheus + Grafana 모니터링

#### 개념 설명

**Prometheus** 는 시계열 데이터를 수집하는 모니터링 시스템입니다.
**Grafana** 는 Prometheus 데이터를 시각화하는 대시보드 도구입니다.

```
[모니터링 구조]

┌──────────────┐
│ Product Svc  │──┐
└──────────────┘  │
                  │  /actuator/prometheus (메트릭 노출)
┌──────────────┐  │
│ Auction Svc  │──┼─→ ┌────────────┐      ┌─────────┐
└──────────────┘  │   │ Prometheus │ ───→ │ Grafana │
                  │   │ (수집/저장) │      │(시각화)  │
┌──────────────┐  │   └────────────┘      └─────────┘
│ Payment Svc  │──┘
└──────────────┘
```

#### Biddy 적용

**1. Spring Actuator 설정**

```yaml
# application.yml (모든 서비스)
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${spring.profiles.active}
    export:
      prometheus:
        enabled: true
```

**2. Prometheus 설정**

```yaml
# prometheus.yml
global:
  scrape_interval: 15s  # 15초마다 메트릭 수집

scrape_configs:
  - job_name: 'product-service'
    static_configs:
      - targets: ['product-service:8082']

  - job_name: 'auction-service'
    static_configs:
      - targets: ['auction-service:8084']

  - job_name: 'payment-service'
    static_configs:
      - targets: ['payment-service:8085']
```

**3. Grafana 대시보드**

**Dashboard 1: 시스템 개요**

```
[패널 1] 서비스 상태
- Product: UP (2 replicas)
- Auction: UP (3 replicas)
- Payment: UP (1 replica)
- Order: DOWN ❌ (알림!)

[패널 2] CPU 사용률 (실시간 그래프)
Product:  ████░░░░░░ 40%
Auction:  ███████░░░ 70%
Payment:  █████░░░░░ 50%

[패널 3] 메모리 사용률
Product:  512MB / 1GB
Auction:  900MB / 1.5GB
Payment:  600MB / 1GB

[패널 4] 초당 요청 수 (RPS)
Product: 120 req/s
Auction: 80 req/s
Payment: 20 req/s
```

**Dashboard 2: 비즈니스 메트릭**

```
[패널 1] 실시간 경매 수
LIVE: 1,234
ENDED: 567

[패널 2] 분당 입찰 수 (실시간 그래프)
10:00 - 45 bids
10:01 - 52 bids
10:02 - 61 bids
10:03 - 58 bids

[패널 3] 낙찰률 (최근 24시간)
총 경매: 1,000
낙찰: 780
낙찰률: 78%

[패널 4] 결제 성공률
성공: 95%
실패: 5% ⚠️
```

**Dashboard 3: 에러 추적**

```
[패널 1] 5xx 에러 (서비스별)
Product: 2 errors (last 1h)
Auction: 15 errors ⚠️
Payment: 0 errors ✅

[패널 2] 최근 에러 로그
10:05:23 - Auction Service - BidProcessingException
  → Message: Insufficient balance
  → Stack: com.biddy.auction.BidService:42

10:03:45 - Product Service - RedisConnectionException
  → Message: Connection refused
```

**Dashboard 4: 데이터베이스**

```
[패널 1] DB 커넥션 풀
Active: 5 / 20
Idle: 10
Pending: 0 ✅

[패널 2] 쿼리 성능 (P95)
Product SELECT: 12ms
Auction UPDATE: 25ms
Payment INSERT: 8ms

[패널 3] 슬로우 쿼리 (>100ms)
SELECT * FROM auctions WHERE status='LIVE'
→ 150ms (인덱스 누락!) ⚠️
```

**알림 설정:**

```yaml
# Grafana Alert Rule
alert: HighErrorRate
expr: rate(http_server_requests_total{status=~"5.."}[5m]) > 0.01
for: 2m
annotations:
  summary: "서비스 에러율 높음"
  description: "{{ $labels.application }}의 5xx 에러율이 1% 초과"
labels:
  severity: critical

# Slack 알림
channel: #biddy-alerts
message: |
  🚨 Critical Alert: {{ .CommonAnnotations.summary }}
  Service: {{ .GroupLabels.application }}
  Description: {{ .CommonAnnotations.description }}
```

---

### 6.2 ELK 로그 고도화 및 분산 추적

#### 개념 설명

**ELK Stack:**
- **Elasticsearch**: 로그 저장 및 검색 엔진
- **Logstash**: 로그 수집 및 변환
- **Kibana**: 로그 시각화

**분산 추적 (Distributed Tracing):**

MSA에서 하나의 요청이 여러 서비스를 거치는 전체 흐름을 추적하는 기법입니다.

```
[분산 추적 예시]

사용자 요청: "결제하기"
Trace ID: abc-123-def-456

Span 1: Gateway (5ms)
  → Span 2: Order Service (50ms)
    → Span 3: Payment Service (120ms)
      → Span 4: Kafka (2ms)
        → Span 5: Order Service (10ms)

총 처리 시간: 187ms
병목: Payment Service (120ms)
```

#### Biddy 적용

**1. Logback 설정 (JSON 로그)**

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>
                {"application":"${spring.application.name}",
                 "environment":"${spring.profiles.active}"}
            </customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

**출력 예시:**

```json
{
  "timestamp": "2026-07-03T10:15:30.123Z",
  "level": "ERROR",
  "application": "payment-service",
  "environment": "prod",
  "traceId": "abc-123-def-456",
  "spanId": "span-3",
  "message": "Payment failed: Insufficient balance",
  "exception": "com.biddy.payment.InsufficientBalanceException",
  "stack_trace": "...",
  "orderId": "order-789",
  "memberId": 12345
}
```

**2. Elasticsearch 인덱싱**

```
Logstash → Elasticsearch 인덱스 생성

인덱스명: biddy-logs-2026.07.03

필드:
- timestamp (date)
- level (keyword)
- application (keyword)
- traceId (keyword) ← 분산 추적용
- message (text)
- exception (keyword)
- orderId (keyword)
- memberId (long)
```

**3. Kibana 검색 쿼리**

```
# 특정 주문의 전체 로그 검색
orderId: "order-789"

# 특정 사용자의 에러 로그
memberId: 12345 AND level: ERROR

# 특정 Trace ID의 전체 흐름 (분산 추적)
traceId: "abc-123-def-456"

결과:
[10:15:25] Gateway       - Request received
[10:15:26] Order Service - Order created
[10:15:27] Payment Svc   - Payment processing
[10:15:28] Payment Svc   - ERROR: Insufficient balance
[10:15:28] Order Service - Order marked as FAILED
```

**4. Spring Cloud Sleuth (분산 추적)**

```yaml
# application.yml
spring:
  sleuth:
    sampler:
      probability: 1.0  # 100% 추적 (운영 환경은 0.1 권장)
  zipkin:
    base-url: http://zipkin:9411
```

**Zipkin 대시보드:**

```
Trace ID: abc-123-def-456

Timeline:
┌────────────────────────────────────────┐
│ Gateway (5ms)                          │
│   └─ Order Service (50ms)              │
│       └─ Payment Service (120ms) ⚠️    │
│           └─ Kafka (2ms)               │
│               └─ Order Service (10ms)  │
└────────────────────────────────────────┘

총 시간: 187ms
병목: Payment Service
```

---

## 7. 사용자 경험 개선

### 7.1 BFF (Backend For Frontend)

#### 개념 설명

**BFF** 는 프론트엔드 화면 중심으로 데이터를 조합해주는 백엔드 계층입니다.

**문제:**

```
[상품 상세 화면에 필요한 데이터]

1. 상품 정보 (Product Service)
2. 판매자 정보 (Member Service)
3. 경매 정보 (Auction Service)
4. 내가 관심 등록했는지 (Auction Service)

→ 프론트가 4번 API 호출 필요
→ 네트워크 왕복 4회
→ 로딩 느림
```

**BFF 해법:**

```
[BFF 적용]

프론트: GET /bff/products/123
→ BFF가 내부적으로 4개 서비스 호출
→ 데이터 조합 후 한 번에 응답

프론트: 1번 API 호출로 완료 ✅
```

#### Biddy 적용

**BFF 서비스 구성:**

```java
@RestController
@RequestMapping("/bff")
public class ProductDetailBFFController {

    @Autowired
    private ProductClient productClient;

    @Autowired
    private MemberClient memberClient;

    @Autowired
    private AuctionClient auctionClient;

    @GetMapping("/products/{productId}")
    public ProductDetailPageResponse getProductDetailPage(
        @PathVariable UUID productId,
        @AuthenticationPrincipal Long memberId
    ) {
        // 병렬 호출 (CompletableFuture)
        CompletableFuture<ProductDetailResponse> productFuture =
            CompletableFuture.supplyAsync(() -> productClient.getProduct(productId));

        CompletableFuture<MemberProfileResponse> sellerFuture =
            CompletableFuture.supplyAsync(() -> {
                ProductDetailResponse product = productFuture.join();
                return memberClient.getProfile(product.getSellerId());
            });

        CompletableFuture<AuctionDetailResponse> auctionFuture =
            CompletableFuture.supplyAsync(() -> auctionClient.getByProductId(productId));

        CompletableFuture<Boolean> watchedFuture =
            CompletableFuture.supplyAsync(() -> {
                if (memberId == null) return false;
                return auctionClient.isWatched(productId, memberId);
            });

        // 모든 호출 완료 대기
        CompletableFuture.allOf(productFuture, sellerFuture, auctionFuture, watchedFuture)
            .join();

        // 조합
        return ProductDetailPageResponse.builder()
            .product(productFuture.join())
            .seller(sellerFuture.join())
            .auction(auctionFuture.join())
            .isWatched(watchedFuture.join())
            .build();
    }
}
```

**응답 예시:**

```json
{
  "product": {
    "id": "uuid-123",
    "name": "롤렉스 서브마리너",
    "price": 5000000,
    "images": ["url1", "url2"]
  },
  "seller": {
    "id": 10,
    "username": "watch_collector",
    "rating": 4.8,
    "totalSales": 45
  },
  "auction": {
    "auctionId": "A-FNF97",
    "currentBid": 7200000,
    "bidCount": 12,
    "endsAt": "2026-07-05T15:00:00",
    "status": "LIVE"
  },
  "isWatched": true
}
```

**효과:**

| 항목 | BFF 없이 | BFF 적용 |
|------|---------|---------|
| API 호출 수 | 4회 | 1회 |
| 네트워크 왕복 | 4회 | 1회 |
| 응답 시간 | 200ms (순차) | 60ms (병렬) |
| 프론트 복잡도 | 높음 | 낮음 |

---

## 8. AI 기능 확장

### 8.1 RAG (Retrieval-Augmented Generation)

#### 개념 설명

**RAG** 는 LLM(Large Language Model)이 답변 생성 전에 **관련 문서를 먼저 검색**하여 답변의 정확도를 높이는 기법입니다.

```
[일반 LLM]
사용자: "Biddy 환불 정책은?"
→ LLM: (학습 데이터 기반 추측) "일반적으로 7일 이내..."

[RAG 기반 LLM]
사용자: "Biddy 환불 정책은?"
1. Vector DB에서 "환불 정책" 문서 검색
2. 검색된 문서를 LLM에 제공
3. LLM: (검색된 문서 기반) "Biddy는 구매 후 3일 이내에 상품에 이상이 있을 경우 전액 환불이 가능합니다..."
```

**구조:**

```
┌───────────────┐
│   사용자      │
└───────┬───────┘
        │ "환불 정책은?"
        ▼
┌───────────────┐
│  RAG System   │
│               │
│  1. 검색      │──→ ┌──────────────┐
│               │    │  Vector DB   │
│               │◀── │ (Pinecone,   │
│  2. LLM 호출  │    │  Chroma)     │
│               │    └──────────────┘
│  3. 답변 생성 │
└───────┬───────┘
        │
        ▼
    "검색된 문서 기반 답변"
```

#### Biddy RAG 적용 시나리오

**1. FAQ 챗봇**

```
사용자: "경매 낙찰 후 배송은 언제 되나요?"

RAG 흐름:
1. "경매 낙찰 배송" 키워드로 FAQ 문서 검색
2. 검색 결과:
   - "경매 낙찰 후 판매자가 상품을 발송하면..."
   - "일반적으로 낙찰 후 3~5일 소요..."
3. LLM 답변 생성:
   "경매에서 낙찰되면 판매자가 결제 확인 후 상품을 발송합니다.
    일반적으로 낙찰 후 3~5일 이내에 배송이 시작되며,
    택배사에 따라 2~3일 후 수령 가능합니다."
```

**2. 상품 추천 AI**

```
사용자: "빈티지 시계 찾고 있어요"

RAG 흐름:
1. Vector DB에서 유사 상품 검색
   - "빈티지" + "시계" 임베딩
   - 코사인 유사도 계산
2. 검색 결과:
   - 롤렉스 서브마리너 1965년
   - 오메가 스피드마스터 1969년
   - 세이코 5 1970년
3. LLM 답변:
   "현재 Biddy에서 경매 중인 빈티지 시계를 추천드립니다:
    1. 롤렉스 서브마리너 (1965년) - 현재가 720만원
    2. 오메가 스피드마스터 (1969년) - 현재가 450만원
    3. 세이코 5 (1970년) - 현재가 15만원
    롤렉스는 희소성이 높아 투자 가치가 있습니다."
```

**3. 경매 입찰 도우미**

```
사용자: "이 시계에 800만원 입찰해도 괜찮을까요?"

RAG 흐름:
1. 현재 경매 데이터 검색
   - 현재가: 720만원
   - 입찰 수: 12회
   - 종료 시간: 2시간 남음
2. 유사 상품 과거 낙찰가 검색
   - 롤렉스 서브마리너 1965년 과거 낙찰가: 850~920만원
3. LLM 답변:
   "현재가 720만원이고 2시간 남았으므로 추가 입찰 가능성이 높습니다.
    과거 유사 상품 낙찰가는 평균 880만원이므로
    800만원 입찰은 적정 가격입니다.
    다만 마감 임박 시 스나이핑 입찰이 있을 수 있으니
    자동 입찰(Proxy Bid)을 최대 900만원까지 설정하시길 권장합니다."
```

**4. 판매글 작성 도우미**

```
사용자: "롤렉스 서브마리너 판매글 써줘"

RAG 흐름:
1. 상품 정보 검색
   - 브랜드: Rolex
   - 모델: Submariner
   - 연식: 1965년
2. 우수 판매글 템플릿 검색
3. LLM 답변:
   "제목: [진품 감정완료] 롤렉스 서브마리너 1965년 빈티지

    상품 설명:
    - 브랜드: Rolex
    - 모델: Submariner
    - 제조년도: 1965년
    - 상태: B등급 (미세한 사용감, 작동 정상)
    - 보증서: 있음
    - 박스: 없음

    이 시계는 1965년에 제조된 롤렉스 서브마리너로,
    빈티지 시계 애호가들 사이에서 높은 가치를 인정받고 있습니다.
    전문 감정사의 진품 감정을 완료하였으며 감정서가 포함됩니다.

    시작가: 500만원
    즉시구매가: 900만원"
```

#### 기술 스택

```
1. Vector DB: Pinecone, Chroma, Weaviate
2. Embedding Model: OpenAI text-embedding-ada-002
3. LLM: GPT-4, Claude 3
4. Framework: LangChain
```

**구현 예시:**

```python
from langchain.vectorstores import Pinecone
from langchain.embeddings import OpenAIEmbeddings
from langchain.chat_models import ChatOpenAI
from langchain.chains import RetrievalQA

# 1. Vector DB 초기화
vectorstore = Pinecone.from_existing_index(
    index_name="biddy-faq",
    embedding=OpenAIEmbeddings()
)

# 2. RAG Chain 생성
qa_chain = RetrievalQA.from_chain_type(
    llm=ChatOpenAI(model="gpt-4"),
    retriever=vectorstore.as_retriever(search_kwargs={"k": 3}),
    return_source_documents=True
)

# 3. 질문 응답
result = qa_chain({"query": "환불 정책은?"})

print(result["result"])
# → "Biddy는 구매 후 3일 이내에..."

print(result["source_documents"])
# → [Document(page_content="환불 정책: ...", metadata={...})]
```

---

## 9. 도메인별 적용 계획

### Member 도메인

| 고도화 항목 | 적용 내용 |
|-----------|----------|
| Kubernetes | replica=2, HPA (CPU > 50% 시 스케일) |
| Redis | 로그아웃 토큰 블랙리스트 (TTL = 토큰 만료 시간) |
| 멱등성 | 회원가입, 예치금 충전 API |
| 테스트 | 로그인 성공/실패, JWT 발급, 예치금 차감 |

### Product 도메인

| 고도화 항목 | 적용 내용 |
|-----------|----------|
| Kubernetes | replica=3, HPA (CPU > 70% 시 스케일) |
| Redis | 상품 상세 캐시 (TTL 1시간), 재고 관리 (Lua Script) |
| Outbox Pattern | 경매 상품 등록 시 이벤트 저장 |
| Elasticsearch | 상품 검색 (한글 형태소 분석, 자동완성) |
| 테스트 | 경매 상품 등록, 재고 차감 동시성 테스트 |

### Auction 도메인

| 고도화 항목 | 적용 내용 |
|-----------|----------|
| Kubernetes | replica=3, HPA (CPU > 70% 시 스케일), CPU limit 높게 |
| Redis | 현재 최고가 캐시 (Write-Through), Redis Stream 입찰 처리 |
| 멱등성 | 입찰 API (중복 입찰 방지) |
| Rate Limiting | 초당 5회 입찰 제한 (사용자별) |
| 테스트 | 입찰 금액 검증, 경매 종료 처리, WebSocket 알림 |

### Order 도메인

| 고도화 항목 | 적용 내용 |
|-----------|----------|
| Kubernetes | replica=2, HPA (CPU > 60% 시 스케일) |
| Saga Pattern | 주문 생성 (재고 차감 → 주문 저장 → 결제) |
| State Machine | 주문 상태 전이 관리 (PENDING → PAID → SHIPPED → DELIVERED) |
| Kafka DLQ | 결제 완료 이벤트 처리 실패 시 DLQ |
| 테스트 | 주문 생성, 상태 전이, Saga 보상 트랜잭션 |

### Payment 도메인

| 고도화 항목 | 적용 내용 |
|-----------|----------|
| Kubernetes | replica=1, CPU/Memory limit 높게 (정산 배치) |
| Thread Pool | 정산 배치 병렬 처리 (pool size=3) |
| Redis | 결제 대기 상태 (TTL 10분) |
| Idempotency | 결제 API (Idempotency-Key 헤더) |
| Multi-PG Fallback | Toss Payments 실패 시 NICE Payments |
| 테스트 | 결제 성공/실패, 정산 계산, 멱등성 검증 |

---

## 10. 단계별 로드맵

### Phase 1: 인프라 기반 구축 (1개월)

**주차별 계획:**

| 주차 | 작업 | 담당 |
|------|------|------|
| 1주차 | Kubernetes Deployment/Service YAML 작성 | DevOps |
| 1주차 | ConfigMap/Secret 설정 | DevOps |
| 2주차 | Prometheus + Grafana 구축 | DevOps |
| 2주차 | 기본 대시보드 3종 구성 | DevOps |
| 3주차 | ELK Stack 구축 (Elasticsearch, Logstash, Kibana) | DevOps |
| 3주차 | Spring Actuator 전체 서비스 적용 | Backend |
| 4주차 | HPA 설정 및 로드밸런싱 검증 | DevOps |
| 4주차 | 알림 설정 (Slack 연동) | DevOps |

**완료 기준:**
- [ ] 모든 서비스가 Kubernetes에서 정상 실행
- [ ] Grafana에서 실시간 메트릭 확인 가능
- [ ] Kibana에서 모든 서비스 로그 검색 가능
- [ ] CPU 70% 초과 시 HPA 자동 스케일 아웃 동작

---

### Phase 2: 성능 개선 (1개월)

| 주차 | 작업 | 담당 |
|------|------|------|
| 1주차 | Redis 캐싱 (상품 상세, 경매 최고가) | Backend |
| 1주차 | Redis TTL 설정 (결제 대기, 로그아웃 토큰) | Backend |
| 2주차 | Elasticsearch 상품 검색 구축 | Backend |
| 2주차 | 자동완성, 유사 상품 추천 | Backend |
| 3주차 | 정산 배치 Thread Pool 병렬 처리 | Backend |
| 3주차 | JMeter 성능 테스트 (before/after 비교) | QA |
| 4주차 | Redis Stream 입찰 처리 (옵션) | Backend |
| 4주차 | 이미지 최적화 (리사이징, WebP) | Backend |

**완료 기준:**
- [ ] 상품 조회 응답 시간 500ms → 50ms
- [ ] 정산 배치 처리 시간 50% 단축
- [ ] Elasticsearch 검색 속도 10배 향상
- [ ] JMeter 테스트 TPS 100+ 달성

---

### Phase 3: 데이터 정합성 (3주)

| 주차 | 작업 | 담당 |
|------|------|------|
| 1주차 | Outbox Pattern 구현 (Product → Auction) | Backend |
| 1주차 | Outbox Publisher 스케줄러 | Backend |
| 2주차 | Kafka DLQ 설정 (모든 Consumer) | Backend |
| 2주차 | DLQ Admin API 구현 | Backend |
| 3주차 | Idempotency Key 필터 (결제, 주문, 입찰) | Backend |
| 3주차 | 멱등성 검증 테스트 | QA |

**완료 기준:**
- [ ] Kafka 장애 시 이벤트 유실 없음 (Outbox)
- [ ] Consumer 실패 시 DLQ로 이동 확인
- [ ] 중복 결제 방지 (Idempotency Key) 검증 완료

---

### Phase 4: 품질 및 운영 (3주)

| 주차 | 작업 | 담당 |
|------|------|------|
| 1주차 | 단위 테스트 작성 (Coverage 80%) | Backend |
| 1주차 | 통합 테스트 작성 | Backend |
| 2주차 | SonarQube CI/CD 통합 | DevOps |
| 2주차 | Quality Gate 설정 | DevOps |
| 3주차 | JMeter 스트레스 테스트 | QA |
| 3주차 | 병목 지점 파악 및 튜닝 | Backend + DevOps |

**완료 기준:**
- [ ] 테스트 커버리지 80% 이상
- [ ] SonarQube Quality Gate 통과
- [ ] Bugs = 0, Vulnerabilities = 0
- [ ] JMeter 10분 부하 테스트 에러율 <1%

---

### Phase 5: 고급 기능 (2개월)

| 주차 | 작업 | 담당 |
|------|------|------|
| 1~2주 | Saga Pattern (Order 생성) | Backend |
| 3~4주 | State Machine (Order 상태 관리) | Backend |
| 5~6주 | BFF (상품 상세, 마이페이지) | Backend |
| 7~8주 | RAG 기반 FAQ 챗봇 | Backend + AI |

**완료 기준:**
- [ ] Saga 보상 트랜잭션 정상 동작
- [ ] 잘못된 상태 전이 차단됨
- [ ] BFF로 프론트 API 호출 수 50% 감소
- [ ] RAG 챗봇 답변 정확도 90%+

---

## 11. 예상 효과 요약

### 성능 개선

| 지표 | 현재 | 목표 | 개선 방법 |
|-----|------|------|----------|
| 상품 조회 응답 시간 | 500ms | 50ms | Redis 캐싱, Elasticsearch |
| 입찰 처리량 | 100 TPS | 1,000 TPS | Redis Stream, Event Sourcing |
| 정산 배치 시간 | 16분 40초 | 5분 40초 | Thread Pool (size=3) |
| 검색 속도 | 500ms | 50ms | Elasticsearch + 한글 형태소 |

### 안정성 개선

| 지표 | 현재 | 목표 | 개선 방법 |
|-----|------|------|----------|
| 가용성 | 95% | 99.9% | Kubernetes HPA, Multi-PG |
| 데이터 정합성 | 부분적 | 100% | Outbox Pattern, DLQ |
| 중복 결제 방지 | 없음 | 100% | Idempotency Key |
| 장애 감지 시간 | 10분+ | 30초 | Prometheus + Grafana 알림 |

### 비즈니스 개선

| 지표 | 예상 효과 |
|-----|----------|
| 검색 만족도 | Elasticsearch로 50% 향상 |
| 전환율 | BFF로 20% 증가 (로딩 속도 개선) |
| 경매 참여율 | Proxy Bidding으로 30% 증가 |
| 고객 문의 감소 | RAG 챗봇으로 40% 감소 |

---

## 12. 학습 리소스

각 기술에 대한 팀원 이해도 향상을 위한 추천 자료입니다.

### Kubernetes

- [Kubernetes 공식 튜토리얼](https://kubernetes.io/ko/docs/tutorials/)
- [쿠버네티스 입문 (강의)](https://www.inflearn.com/course/%EC%BF%A0%EB%B2%84%EB%84%A4%ED%8B%B0%EC%8A%A4-%EC%9E%85%EB%AC%B8)
- 실습: Minikube로 로컬 클러스터 구축

### Redis

- [Redis University (무료)](https://university.redis.com/)
- [Redis 실전 가이드 (책)](https://www.yes24.com/Product/Goods/92219427)
- 실습: Docker로 Redis 실행 후 캐싱 구현

### Kafka

- [Kafka 공식 문서](https://kafka.apache.org/documentation/)
- [아파치 카프카 애플리케이션 프로그래밍 (책)](https://www.yes24.com/Product/Goods/99122569)
- 실습: Kafka로 Producer/Consumer 구현

### Elasticsearch

- [Elasticsearch 공식 가이드](https://www.elastic.co/guide/kr/elasticsearch/reference/current/index.html)
- 실습: 한글 형태소 분석기(Nori) 적용

### Prometheus + Grafana

- [Prometheus 입문 (강의)](https://www.youtube.com/watch?v=7gW5pSM6dlU)
- [Grafana 대시보드 튜토리얼](https://grafana.com/tutorials/)

### RAG

- [LangChain 공식 문서](https://python.langchain.com/docs/get_started/introduction)
- [RAG 실전 가이드 (블로그)](https://blog.langchain.dev/retrieval-augmented-generation-rag/)

---

**작성자:** 개발팀
**검토 필요:** CTO, DevOps Lead, 각 도메인 리드
**다음 액션:** Phase 1 킥오프 미팅 일정 수립
