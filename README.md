# Biddy

온라인 경매 플랫폼 **Biddy**의 백엔드 프로젝트입니다. Spring Cloud 기반 MSA(Microservices Architecture)로 구성되어 있으며, 실시간 입찰·경매, 상품 검색/추천, 채팅, 주문·결제까지 이커머스/경매 서비스 전반을 다룹니다.

> 이 저장소는 [prgrms-be-adv-devcourse/beadv6_6_frontal_BE](https://github.com/prgrms-be-adv-devcourse/beadv6_6_frontal_BE)를 개인 학습/보관 목적으로 복사한 저장소입니다.

## 서비스 구성

| 모듈 | 설명 |
| --- | --- |
| `discovery` | Eureka 기반 서비스 디스커버리 |
| `config` | Spring Cloud Config 서버 |
| `apigateway` | API 게이트웨이 (라우팅, 인증 필터) |
| `member` | 회원 가입/로그인, JWT 인증·인가 |
| `product` | 상품 CRUD, pgvector 임베딩, Elasticsearch 연동 |
| `search` | 키워드 검색, AI 추천, 자동완성 |
| `auction` | 실시간 입찰, Transactional Outbox, WebSocket |
| `chat` | WebSocket/STOMP 기반 실시간 채팅 |
| `chatbot` | 챗봇 서비스 |
| `order` | 일반 주문 / 경매 낙찰 주문, 구매 확정 |
| `payment` | Toss Payments 연동, 결제 승인/취소/환불, 정산 |
| `common` | 서비스 공통 모듈 |
| `frontend` | React 18 + Vite 프론트엔드 |

## 기술 스택

- **Backend**: Spring Boot, Spring Cloud (Gateway, Config, Eureka)
- **Data**: PostgreSQL(pgvector), Redis
- **Messaging**: Kafka
- **Infra**: Docker Compose, Kubernetes(k8s)
- **Frontend**: React 18, Vite

## 실행 방법

```bash
# 전체 서비스 (인프라 + MSA) 도커 컴포즈 실행
docker-compose up -d

# 개별 모듈 빌드
./gradlew :member:build
```

## 문서

프로젝트 기획, 상세 설계, 아키텍처, 배포 가이드 등은 [`docs/`](./docs) 디렉터리를 참고하세요. ([`docs/README.md`](./docs/README.md)에 전체 문서 목차가 정리되어 있습니다.)

각 서비스별 상세 설명은 해당 모듈 디렉터리의 README를 참고하세요. (예: [`auction/README.md`](./auction/README.md), [`member/README.md`](./member/README.md) 등)
