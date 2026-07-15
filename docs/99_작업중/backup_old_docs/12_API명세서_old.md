# Biddy API 명세서

> Swagger UI에서 자동 생성된 문서를 기반으로 주요 API를 정리합니다.
>
> Swagger 접속: `http://localhost:80XX/swagger-ui/index.html`

## API Gateway 라우팅

| 경로 패턴 | 대상 서비스 |
|---|---|
| `/api/members/**` | Member (:8081) |
| `/api/products/**` | Product (:8082) |
| `/api/orders/**` | Order (:8083) |
| `/api/auctions/**` | Auction (:8084) |
| `/api/payments/**` | Payment (:8085) |

---

## Member API

| Method | URI | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/members/signup` | 회원가입 | | |
| POST | `/api/members/login` | 로그인 | | |
| GET | `/api/members/{id}` | 회원 조회 | | |

## Product API

| Method | URI | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/products` | 상품 등록 | | |
| GET | `/api/products` | 상품 목록 | | |
| GET | `/api/products/{id}` | 상품 상세 | | |

## Auction API

| Method | URI | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/auctions` | 경매 등록 | | |
| POST | `/api/auctions/{id}/bid` | 입찰 | | |
| GET | `/api/auctions/{id}` | 경매 상세 | | |

## Order API

| Method | URI | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/orders` | 주문 생성 | | |
| GET | `/api/orders` | 주문 목록 | | |

## Payment API

| Method | URI | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/payments` | 결제 처리 | | |
| POST | `/api/payments/{id}/refund` | 환불 | | |
