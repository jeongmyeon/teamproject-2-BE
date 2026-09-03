# Biddy

**실시간 경매 플랫폼 · MSA 기반 백엔드/인프라 프로젝트**

5인 팀 · 2개월 · 11개 마이크로서비스를 AWS EC2 기반 Self-managed K3s에 직접 구축해 설계부터 실배포까지 완료했습니다.

![Java](https://img.shields.io/badge/Java-Spring%20Boot-6DB33F?logo=springboot&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-Gateway%20%7C%20Config%20%7C%20Eureka-6DB33F?logo=spring&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-4169E1?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-Outbox-231F20?logo=apachekafka&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-K3s-326CE5?logo=kubernetes&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions%20%7C%20GHCR-2088FF?logo=githubactions&logoColor=white)

> 원본 팀 저장소: [prgrms-be-adv-devcourse/beadv6_6_frontal_BE](https://github.com/prgrms-be-adv-devcourse/beadv6_6_frontal_BE)

---

## 담당 파트

| 구분 | 내용 |
| --- | --- |
| **담당 파트** | 회원(인증/인가) 서비스 · 챗봇(RAG) 서비스 · 보안 아키텍처 |
| **팀 내 역할** | 팀장 — API 명세 관리, Git 전략 수립·운영, 일정 관리, 코드 리뷰, 회의 진행 |

## 1. 프로젝트 개요

- **구성** — `apigateway`, `discovery(Eureka)`, `config`, `member`, `product`, `order`, `payment`, `auction`, `search`, `chat`, `chatbot` 총 11개 서비스로 구성된 MSA 기반 실시간 경매 플랫폼
- **인프라** — AWS EC2 위에 Kubernetes(K3s)를 직접 구축해 팀원들과 함께 서비스를 컨테이너로 배포·운영
- **담당 영역** — 회원 서비스(인증/인가), 챗봇(RAG) 서비스, API Gateway·네트워크 정책을 포함한 전사 보안 아키텍처를 직접 설계·구현
- **팀 내 역할** — 5인 팀의 팀장으로서 API 명세 관리, Git 전략, 일정 관리, 코드 리뷰, 회의 진행을 담당

## 2. 기술 스택

| 영역 | 스택 |
| --- | --- |
| **Backend** | Java, Spring Boot, Spring Cloud Gateway, Spring Cloud Config, Eureka, Spring Security, Spring Data JPA |
| **Data** | PostgreSQL (pgvector), Redis, Elasticsearch |
| **Messaging** | Apache Kafka (Outbox 패턴) |
| **AI** | Gemini API (Embedding / Generation), RAG |
| **Infra** | AWS EC2, Kubernetes (K3s), Docker, Traefik, cert-manager / Let's Encrypt, Prometheus, Grafana |
| **CI/CD** | GitHub Actions, GHCR — 빌드·이미지 푸시·배포 자동화 |
| **Test** | k6 (부하테스트) |

## 3. 시스템 아키텍처

프론트엔드(React)는 API Gateway 한 곳으로만 요청을 보내고, Gateway가 JWT 인증·인가를 처리한 뒤 각 도메인 서비스로 라우팅합니다. 서비스는 각자 독립된 PostgreSQL을 사용하며, 경매·주문·결제 서비스는 Outbox 패턴으로 Kafka에 이벤트를 발행해 서비스 간 데이터 정합성을 맞춥니다.

- Gateway 단일 진입점
- 서비스별 DB 분리
- Kafka 이벤트 스트리밍

> 상세 아키텍처 다이어그램: [`docs/02_설계/시스템아키텍처_확장안.png`](./docs/02_설계/시스템아키텍처_확장안.png)

## 4. 담당 파트 상세 및 기여도

| 담당 파트 | 기여도 | 설명 |
| --- | --- | --- |
| 회원(Member) 서비스 — 인증/인가 | 직접 설계·구현 | JWT 발급/검증, Redis 블랙리스트, Outbox 이벤트 발행 전 과정을 단독 설계·구현 |
| 챗봇(Chatbot) 서비스 — RAG | 직접 설계·구현 | 청킹 전략, 임베딩·검색 파이프라인, 환각 방지 프롬프트를 처음부터 단독 설계 |
| 보안 — API Gateway / 네트워크 | 직접 설계·구현 | GlobalFilter, 인가 정책, NetworkPolicy, TLS 파이프라인을 단독 설계·구현 |
| 인프라 — K3s 클러스터 구축 | 공동 구축 | 팀원과 함께 EC2 기반 K3s 클러스터 구성, 모니터링 스택 셋업, GitHub Actions 기반 CI/CD 파이프라인(GHCR 이미지 배포) 구축 |

### 4-1. 회원(Member) 서비스 — 인증/인가 기반 구축

> JWT Access/Refresh 발급 + Redis 블랙리스트 즉시 무효화 + Outbox 패턴으로 이벤트 정합성 확보

- **회원가입** — 이메일·닉네임 중복 검증, BCrypt(strength 10) 암호화, 정규식 기반 비밀번호 강도 검증
- **로그인** — Access Token(1시간)·Refresh Token(7일) 발급, Refresh Token은 Redis에 회원 ID 키로 저장
- **로그아웃/토큰 무효화** — Redis 블랙리스트(`jwt:blacklist:{token}`)에 등록하되 TTL을 토큰의 남은 만료 시간으로 설정해, 토큰이 자연 만료되는 시점에 블랙리스트 키도 함께 삭제되도록 설계. 별도 정리 배치 없이도 Redis 메모리가 계속 정리되는 구조
- **이벤트 발행(Outbox 패턴)** — 회원가입·탈퇴 이벤트를 DB 트랜잭션 안에서 Outbox 테이블에 함께 저장하고, 5초 주기 스케줄러가 Kafka로 릴레이. DB 커밋과 메시지 발행이 분리된 리소스라는 점에서 오는 정합성 문제(dual write)를 Outbox로 해결
- **기타** — Refresh Token 만료분 정리, 이메일 인증, 회원 탈퇴/관리자 조회(Admin) 기능 구현

### 4-2. 챗봇(Chatbot) 서비스 — RAG 파이프라인 설계·구축

> 소제목 단위 청킹 + pgvector 검색 + "근거 없으면 답변 안 함" 프롬프트 제약으로 환각 방지

플랫폼 정책/FAQ 문서를 근거로만 답하는 RAG(Retrieval-Augmented Generation) 챗봇을 처음부터 설계했습니다.

- **지식 적재** — `knowledge/*.md` 문서를 `## ` 소제목(Q&A) 단위로 청킹한 뒤 Gemini Embedding API로 벡터화하여 PostgreSQL(pgvector)에 저장
- **질의 처리** — 질문을 임베딩(task_type=QUERY)한 뒤 pgvector 코사인 유사도 검색으로 관련 문서 상위 4개(Top-K, K=4)를 찾고, 검색된 문서만 컨텍스트로 넣어 Gemini 2.5 Flash-Lite로 답변 생성
- **환각 방지** — "참고 문서에 없는 내용은 추측하지 말고 안내되지 않았다고 답하라"는 제약을 프롬프트에 명시하고, 답변에 참조한 출처 문서 목록을 함께 반환
- **운영 편의성** — 앱 기동 시 데이터가 없으면 자동 적재(`ingestIfEmpty`), 관리자용 전체 재적재(`reingest`) API 제공

### 4-3. 보안 — API Gateway 인증/인가와 네트워크 격리

> 11개 서비스 앞단 단일 GlobalFilter + 4단계 인가 정책 + NetworkPolicy로 Zero Trust 내부망 구성

- **인증 지점 일원화** — 11개 서비스를 통과하는 모든 요청이 거치는 API Gateway에 단일 GlobalFilter(`JwtAuthenticationGlobalFilter`)를 두어 JWT 검증을 한 곳에 집중
- **세분화된 인가 정책** — 단순 화이트리스트/인증 필수 이분법 대신, ① 공개 경로 ② GET 전용 optional-auth 경로(상품/경매 목록) ③ 메서드 무관 공개+개인화 경로(검색) ④ method+path 조합의 관리자 전용 규칙, 4가지를 병행 설계
- **즉시 무효화** — 로그아웃된 토큰을 Redis 블랙리스트로 Gateway 단에서 즉시 차단해 하위 서비스까지 요청이 도달하기 전에 원천 차단
- **컨텍스트 전파** — Gateway에서 검증한 `memberId`/`role`을 `X-Member-Id`, `X-Member-Role` 내부 헤더로 변환해 하위 서비스에 전달. 하위 서비스는 JWT를 재검증하지 않고 헤더를 신뢰하도록 해, 네트워크 경계 자체를 신뢰 경계로 설계
- **네트워크 계층 방어** — Kubernetes NetworkPolicy로 각 내부 서비스가 apigateway와 모니터링 네임스페이스로부터의 트래픽만 허용하도록 구성해, Gateway를 우회한 서비스 간 직접 호출을 인프라 레벨에서 차단(Zero Trust 내부망)
- **전송 구간 암호화** — cert-manager와 Let's Encrypt(HTTP-01 challenge)를 연동해 Ingress에 TLS 인증서가 자동으로 발급·갱신되는 파이프라인 구성(staging 검증 후 prod 전환)
- **부하 검증** — k6로 로그인·회원가입 트래픽 시나리오를 작성해 인증 경로가 부하 상황에서도 정상 동작하는지 검증

### 4-4. 인프라 — AWS EC2 기반 Self-managed Kubernetes (공동 구축)

> 관리형 EKS 대신 EC2 2대에 K3s 직접 구축, 상태 저장 컴포넌트는 실용적으로 K8s 밖에서 운영

- **Master 노드** — Control Plane(API Server/Scheduler/etcd) + Traefik Ingress Controller + Discovery(Eureka) + Config Server + API Gateway
- **Worker 노드** — Member/Product/Order/Payment/Search/Chat/Auction/Chatbot 등 도메인 서비스 Pod
- **상태 저장 컴포넌트 분리** — PostgreSQL(pgvector)/Redis/Kafka는 Kubernetes Pod가 아닌 EC2 Docker 컨테이너로 직접 운영하고 Kubernetes Service + Endpoints로 연결. 2노드 규모의 짧은 프로젝트에서 StatefulSet/PersistentVolume 운영 복잡도 대비 실익이 낮다고 판단한 실용적 선택
- **모니터링** — Prometheus + Grafana + node-exporter + kube-state-metrics로 클러스터/서비스 모니터링 및 알림 규칙 구성
- **CI/CD 파이프라인** — GitHub Actions로 빌드한 뒤 이미지를 GHCR에 푸시하고, K3s 클러스터에 자동 배포되는 CI/CD 파이프라인 구성

## 5. 팀 운영 — 팀장 역할

> 5인 팀의 팀장으로서 API 명세·Git 전략·일정·코드 리뷰·회의를 총괄

| 항목 | 내용 |
| --- | --- |
| API 명세 관리 | 11개 서비스 간 요청/응답 규격을 팀 공통 문서로 관리하고, 변경 발생 시 팀에 공유해 서비스 간 연동 오류를 사전에 방지 |
| Git 전략 수립·운영 | 브랜치 전략과 PR 규칙을 정하고 팀원들이 동일한 기준으로 병합하도록 운영 |
| 일정 관리 | 2개월 일정 안에서 마일스톤을 나누고 진행 상황을 점검해 일정 지연을 조율 |
| 코드 리뷰 | 팀원 PR에 대한 리뷰를 진행하며 코드 컨벤션과 설계 일관성을 맞춤 |
| 회의 진행 | 정기 회의를 진행해 진행 상황 공유, 이슈 논의, 의사결정을 조율 |

## 6. 트러블슈팅 · 설계 의사결정

### ① JWT 블랙리스트 조회 실패 시 처리 방향

| | |
| --- | --- |
| **상황** | Gateway가 매 요청마다 Redis에서 블랙리스트 여부를 확인하는 구조라, Redis 장애 시 전체 인증이 막힐 위험이 있었음 |
| **결정** | 블랙리스트 조회만 실패 시 통과(fail-open)시키고, 서명·만료 검증은 그대로 엄격하게 유지 |
| **근거** | "로그아웃했지만 토큰이 몇 분간 더 유효할 수 있다"는 낮은 리스크와 "Redis 장애로 전 서비스 로그인이 막힌다"는 높은 리스크를 비교해 가용성을 우선 |
| **한계** | 로그아웃 처리 자체가 Redis 장애와 겹치는 경우(블랙리스트 등록 호출 실패)에 대한 재시도·백필 로직은 두지 않음. 향후 개선 필요 |

### ② 인가 경로를 화이트리스트/인증 필수 이분법으로 설계하지 않은 이유

| | |
| --- | --- |
| **상황** | 상품·경매 목록은 비로그인도 조회 가능하지만 로그인 시 찜 여부 등 개인화 정보가 필요했고, 같은 경로 prefix라도 입찰·찜 등록(POST)은 인증이 반드시 필요했음 |
| **결정** | prefix 전체를 화이트리스트에 넣지 않고, "GET만 optional-auth" 목록을 별도로 두고 닉네임 조회 등은 정규식으로 정밀하게 예외 처리 |
| **근거** | prefix를 통째로 화이트리스트에 넣으면 인증이 꼭 필요한 POST 요청까지 검증이 스킵되는 보안 구멍이 생기기 때문 |
| **한계** | 규칙이 세분화될수록 정규식·경로 매핑 관리 비용이 늘어나는 트레이드오프 |

### ③ 상태 저장 컴포넌트를 Kubernetes 밖에 둔 이유

| | |
| --- | --- |
| **상황** | 2노드 소규모 클러스터에서 PostgreSQL/Kafka를 StatefulSet+PVC로 운영할지, EC2 Docker 컨테이너로 운영할지 선택이 필요했음 |
| **결정** | 외부 Docker 컨테이너로 운영하고, Kubernetes Endpoints로 연결해 애플리케이션 코드에서는 기존 서비스 이름(DNS)을 그대로 사용할 수 있게 함 |
| **근거** | 짧은 프로젝트 기간과 소규모 클러스터에서 StatefulSet/스토리지클래스 셋업 복잡도 대비 실익이 낮다고 판단 |
| **한계** | 클러스터 규모가 커지면 확장성·장애 복구 측면에서 재검토 필요 |

### ④ Outbox 패턴 도입

| | |
| --- | --- |
| **상황** | 회원가입/탈퇴 시 DB 저장과 Kafka 이벤트 발행이 분리된 리소스라, 하나만 성공하는 정합성 문제가 발생할 수 있었음 |
| **결정** | 이벤트를 DB 트랜잭션 안에서 Outbox 테이블에 먼저 저장하고, 별도 스케줄러(5초 주기)가 Kafka로 릴레이하도록 분리 |
| **근거** | DB 트랜잭션의 원자성을 이용해 이벤트 유실을 막고, Kafka 발행 실패는 재시도 가능한 구조로 분리 |
| **한계** | 5초 주기 폴링 방식이라 실시간성이 다소 떨어지며, 이벤트 발행 지연이 발생할 수 있음 |

### ⑤ K3s로 전환한 이유 (K8s 메모리 부족 이슈)

| | |
| --- | --- |
| **상황** | 초기에는 관리형이 아닌 일반 Kubernetes(K8s)로 클러스터를 구성해 진행했으나, 노드 메모리 자원이 부족해 여러 서비스 Pod가 반복적으로 재시작(크래시)되는 현상이 발생 |
| **결정** | 팀 회의를 통해 원인을 논의한 뒤, 컨트롤 플레인 구성 요소가 경량화되어 메모리 사용량이 훨씬 적은 K3s로 클러스터를 전환하기로 결정 |
| **근거** | 2노드·짧은 프로젝트 기간이라는 제약 안에서 리소스 사용량을 줄이는 것이 근본적인 해결책이라고 판단했고, K3s는 표준 K8s API와 호환되어 애플리케이션·매니페스트 변경 없이 전환 가능 |
| **한계** | 전환 이후에도 노드 자체의 물리적 메모리 한도는 그대로이므로, 서비스가 더 늘어나면 노드 증설 또는 리소스 requests/limits 재설계가 필요 |

## 7. 성과

- **서비스 규모** — 11개 마이크로서비스를 5인 팀·2개월 만에 설계부터 실배포까지 완료, AWS EC2 2대 기반 Self-managed K3s 클러스터에서 HTTPS로 실제 운영
- **인증 구조 단순화** — 11개 서비스 전체의 JWT 인증을 Gateway 1곳의 단일 GlobalFilter로 집중시켜, 서비스별 중복 인증 로직 제거 및 유지보수 지점을 11곳 → 1곳으로 축소
- **이벤트 정합성** — Outbox 패턴 + 5초 주기 스케줄러로 DB 트랜잭션과 Kafka 이벤트 발행 간 dual-write 문제를 해결, 이벤트 유실 없는 발행 구조 확보
- **인증 캐시 자동 정리** — Redis 블랙리스트에 TTL을 토큰 잔여 만료 시간으로 설정해, 별도 배치 없이 만료 토큰 키가 자동 삭제되는 구조 구현
- **네트워크 보안** — Kubernetes NetworkPolicy로 apigateway 외 모든 서비스 간 직접 호출을 차단(Zero Trust 내부망), cert-manager로 TLS 인증서 자동 발급·갱신 파이프라인 구성
- **인가 정책 세분화** — 화이트리스트/인증 필수 이분법 대신 4단계 인가 규칙을 설계해, 보안 공백 없이 비로그인 조회·개인화 응답을 동시에 지원
- **RAG 파이프라인** — 정책/FAQ 문서를 소제목 단위로 청킹, pgvector Top-4 검색과 환각 방지 프롬프트를 결합한 RAG 챗봇 파이프라인을 처음부터 설계·구축
- **성능 사전 검증** — k6로 로그인·회원가입 트래픽 시나리오를 작성해 인증 경로의 부하 동작을 사전 검증
- **배포 자동화** — GitHub Actions + GHCR 기반 CI/CD 파이프라인을 구성해, 코드 푸시 시 빌드·이미지 등록·K3s 배포가 자동으로 이어지도록 함
- **팀 운영** — 5인 팀의 팀장으로서 API 명세 관리·Git 전략·일정 관리·코드 리뷰·회의 진행을 총괄해 2개월 일정 내 11개 서비스 통합 배포를 완료

## 8. 향후 개선점

- **Refresh Token Rotation 미적용** — 현재는 Refresh Token을 Redis에 저장만 하고 재사용 탐지·로테이션 로직은 구현하지 않음. 토큰 탈취 시 대응력을 높이기 위해 향후 Refresh Token Rotation(사용 시마다 재발급, 재사용 시 전체 무효화) 도입 필요
- **RAG 챗봇 응답 품질 정량 평가** — 환각 방지를 프롬프트 제약으로 설계했으나, 별도 테스트 질의셋을 통한 정답률·환각률 측정은 아직 미진행. 향후 FAQ 기반 평가셋을 구축해 Top-K 값과 답변 품질의 상관관계를 검증할 계획
- **부하테스트 결과 정량화** — k6로 로그인·회원가입 시나리오를 검증했으나 결과 수치를 별도로 기록하지 않음. 향후 응답시간·에러율 등을 리포트로 남겨 성능 개선의 근거 자료로 활용할 계획

## 9. 진행 중인 개선 작업

프로젝트 종료 후에도 위 "향후 개선점" 중 다음 두 가지는 개인 시간을 들여 직접 검증·구현해보고 있습니다. "동작은 한다"에서 멈추지 않고 근거를 남기는 것이 목표입니다.

- **Refresh Token Rotation — 구현 완료.** 재발급마다 옛 토큰을 삭제 대신 `revoked` 처리로 남기는 family-id 기반 rotation을 도입해, 이미 사용된(탈취된) 토큰이 재사용되면 해당 계정의 모든 세션을 즉시 무효화하도록 구현했습니다. 토큰 저장도 원문 → SHA-256 해시로 전환했습니다. 단위 테스트 13개(정상 rotation, 재사용 감지·family 전체 무효화, 만료/미존재 토큰, 해시 유틸리티, 예외 매핑) 전부 통과 확인. 설계 문서: [`docs/superpowers/specs/2026-09-03-refresh-token-rotation-design.md`](./docs/superpowers/specs/2026-09-03-refresh-token-rotation-design.md) · 구현 계획: [`docs/superpowers/plans/2026-09-03-refresh-token-rotation.md`](./docs/superpowers/plans/2026-09-03-refresh-token-rotation.md)
- **RAG 챗봇 검색 정확도(Top-K) 평가 도구 — 구현 완료, 실측은 예정.** knowledge 청크 자신의 질문을 정답지 삼아 Top-K별 검색 정확도(Recall@K)를 계산하는 기능을 추가했습니다(`/api/chatbot/admin/eval/retrieval-accuracy`). self-retrieval 기반이라 실제 사용자 질문보다 낙관적인 상한선 추정치이며, 실제 수치는 로컬 환경(Docker + GEMINI_API_KEY)에서 직접 실행해 확인할 계획입니다. 단위 테스트 11개(질문 추출, Top-K별 정확도 계산, 순위 조회, 파이프라인 조립) 전부 통과 확인. 설계 문서: [`docs/superpowers/specs/2026-09-04-rag-retrieval-accuracy-eval-design.md`](./docs/superpowers/specs/2026-09-04-rag-retrieval-accuracy-eval-design.md) · 구현 계획: [`docs/superpowers/plans/2026-09-04-rag-retrieval-accuracy-eval.md`](./docs/superpowers/plans/2026-09-04-rag-retrieval-accuracy-eval.md)

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
