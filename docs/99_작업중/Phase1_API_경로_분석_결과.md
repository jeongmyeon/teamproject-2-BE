# Phase 1: API 경로 분석 결과

> 작성일: 2026-07-14
> 목적: 테스트 문서와 실제 Controller 코드의 API 경로 일치 여부 확인

---

## 분석 개요

테스트 전략 문서(10_테스트_전략_검토와_진행_방향.md)의 권고사항에 따라:
- **실제 Controller 파일의 API 경로 확인**
- **테스트 문서(07_성능_부하_테스트_실전_가이드.md)와 비교**
- **불일치 사항 식별 및 수정 방안 제시**

---

## 실제 API 경로 (Controller 기준)

### 1. Member Service

**AuthController** (`member/src/main/java/com/biddy/memberservice/presentation/controller/AuthController.java`)
```java
@RestController
@RequestMapping("/api/members")
public class AuthController {
    @PostMapping("/signup")        // POST /api/members/signup
    @PostMapping("/login")          // POST /api/members/login
    @PostMapping("/reissue")        // POST /api/members/reissue
    @PostMapping("/logout")         // POST /api/members/logout
    @PostMapping("/email/send")     // POST /api/members/email/send
    @GetMapping("/email/verify")    // GET  /api/members/email/verify
}
```

---

### 2. Product Service

**ProductController** (`product/src/main/java/com/biddy/productservice/presentation/controller/ProductController.java`)
```java
@RestController
@RequestMapping("${api.init}/products")  // ${api.init} = /api
public class ProductController {
    @GetMapping                     // GET  /api/products
    @PostMapping                    // POST /api/products
    @GetMapping("/{id}")            // GET  /api/products/{id}
    @PutMapping("/{id}")            // PUT  /api/products/{id}
    @DeleteMapping("/{id}")         // DELETE /api/products/{id}
    @PostMapping("/{id}/like")      // POST /api/products/{id}/like
    @DeleteMapping("/{id}/like")    // DELETE /api/products/{id}/like
}
```

**설정값**: `api.init=/api` (API Gateway 라우팅 규칙에서 확인)

---

### 3. Order Service

**OrderController** (`order/src/main/java/com/biddy/order/order/presentation/controller/OrderController.java`)
```java
@RestController
@RequestMapping("${api.init}/order")  // ${api.init} = /api
public class OrderController {
    @PostMapping("/create")         // POST /api/order/create
    @GetMapping("/list")            // GET  /api/order/list
    @GetMapping("/info")            // GET  /api/order/info
    @PutMapping("/statusChange")    // PUT  /api/order/statusChange
    @PutMapping("/cancel")          // PUT  /api/order/cancel
    @PutMapping("/complete")        // PUT  /api/order/complete
}
```

**CartController** (`order/src/main/java/com/biddy/order/cart/presentation/controller/CartController.java`)
- 별도 확인 필요 (테스트 문서에서 사용 여부 확인)

---

### 4. Auction Service

**AuctionController** (`auction/src/main/java/com/biddy/auction/auction/presentation/AuctionController.java`)
```java
@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionController {
    @GetMapping                     // GET  /api/v1/auctions
    @GetMapping("/{auctionId}")     // GET  /api/v1/auctions/{auctionId}
    @PostMapping("/{auctionId}/close")      // POST /api/v1/auctions/{auctionId}/close
    @GetMapping("/{auctionId}/result")      // GET  /api/v1/auctions/{auctionId}/result
}
```

**BidController** (`auction/src/main/java/com/biddy/auction/bid/presentation/BidController.java`)
```java
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
public class BidController {
    @PostMapping                    // POST /api/v1/auctions/{auctionId}/bids
    @GetMapping                     // GET  /api/v1/auctions/{auctionId}/bids
}
```

---

## API Gateway 라우팅 규칙

**apigateway/src/main/resources/application.yml**
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: member-service
          uri: lb://MEMBER-SERVICE
          predicates:
            - Path=/api/members/**

        - id: product-service
          uri: lb://PRODUCT-SERVICE
          predicates:
            - Path=/api/products/**

        - id: order-service
          uri: lb://ORDER-SERVICE
          predicates:
            - Path=/api/orders/**, /api/order/**, /api/cart/**

        - id: auction-service
          uri: lb://AUCTION-SERVICE
          predicates:
            - Path=/api/auctions/**, /api/v1/auctions/**, /api/v1/members/me/watches/**, /api/v1/members/me/bids/**
```

**주요 발견**:
- Auction Service는 `/api/auctions/**`와 `/api/v1/auctions/**` 둘 다 라우팅 지원
- 그러나 **실제 Controller는 `/api/v1/auctions`만 사용**

---

## 불일치 사항

### ❌ 문제: Auction/Bid API 경로

**테스트 문서** (07_성능_부하_테스트_실전_가이드.md)
```javascript
// Line 393
const res = http.post(`${BASE_URL}/api/auctions/${auctionId}/bids`, payload, params);

// Line 488
http.post(`${BASE_URL}/api/auctions/${auctionId}/bids`, payload, {
```

**실제 코드** (BidController)
```java
@RequestMapping("/api/v1/auctions/{auctionId}/bids")  // /api/v1/ 사용
```

**영향**:
- 문서대로 `/api/auctions/{auctionId}/bids` 호출 시 → API Gateway는 라우팅하지만 Controller가 매핑되지 않아 **404 에러** 발생 가능
- API Gateway에서 `/api/auctions/**`를 `/api/v1/auctions/**`로 rewrite하지 않는 한 실패

---

## 권장 수정사항

### 1. 테스트 스크립트 수정 (권장)

**수정 전**:
```javascript
http.post(`${BASE_URL}/api/auctions/${auctionId}/bids`, payload, params);
```

**수정 후**:
```javascript
http.post(`${BASE_URL}/api/v1/auctions/${auctionId}/bids`, payload, params);
```

**이유**:
- 실제 Controller 경로를 따라야 함 (테스트 전략 문서 원칙)
- API Gateway 라우팅에 의존하지 않는 명시적 경로 사용

---

### 2. 또는 API Gateway Rewrite 추가 (대안)

```yaml
- id: auction-service-legacy
  uri: lb://AUCTION-SERVICE
  predicates:
    - Path=/api/auctions/**
  filters:
    - RewritePath=/api/auctions/(?<segment>.*), /api/v1/auctions/$\{segment}
```

**장단점**:
- 장점: 레거시 API 호환성 유지
- 단점: 불필요한 rewrite 오버헤드, 경로 혼란

---

## Phase 1 완료 체크리스트

- [x] 각 서비스의 Controller 파일에서 실제 API 경로 확인
- [x] API Gateway 라우팅 규칙 확인
- [x] 테스트 문서와 실제 코드 비교
- [x] 불일치 사항 식별 및 문서화
- [ ] Smoke test 작성 (실제 경로 기준)
- [ ] 환경 스펙 문서화 (CPU, 메모리, Pod 수 등)

---

## 다음 단계

1. **Smoke test 작성**: 실제 Controller 경로 기준으로 기본 API 호출 테스트
2. **환경 스펙 문서화**: K8s 클러스터, EC2 Docker, 데이터베이스 설정 정보
3. **테스트 데이터 격리**: Run ID 기반 데이터 생성 및 정리
4. **인증 Fixture**: 테스트용 JWT 토큰 생성 스크립트

**완료 조건** (테스트 전략 문서 인용):
> "새 환경에서 문서만 보고 smoke test가 성공하며 두 번 실행해도 결과가 깨지지 않는다."