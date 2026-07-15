# Phase 1: 환경 스펙 문서

> 작성일: 2026-07-14
> 목적: 테스트 실행 환경의 정확한 사양 및 설정 기록

---

## 코드베이스 정보

### Git Commit

```bash
# 현재 브랜치
Branch: feat/k8s-external-services

# Commit SHA
# 실행 시 확인: git rev-parse HEAD

# Recent Commits
3412095 feat: K8s 외부 서비스 분리 - Kafka, Redis, PostgreSQL을 Docker로 이전
b556acf Merge pull request #118 from prgrms-be-adv-devcourse/feature/member-outbox-pattern
8ef6825 Merge pull request #117 from prgrms-be-adv-devcourse/feature/wallet-query-optimization
```

**환경 변경 이력**:
- K8s StatefulSet (Kafka, Redis, PostgreSQL) → EC2 Docker 외부 서비스로 분리
- K8s Service + Endpoint 패턴으로 외부 서비스 연결

---

## 인프라 아키텍처

### 구성

```
┌─────────────────────────────────────────┐
│         Kubernetes Cluster              │
│                                         │
│  ┌──────────┐  ┌──────────┐            │
│  │ Gateway  │  │ Eureka   │            │
│  │  :8000   │  │  :8761   │            │
│  └──────────┘  └──────────┘            │
│                                         │
│  ┌──────────┐  ┌──────────┐            │
│  │ Member   │  │ Product  │            │
│  │ Service  │  │ Service  │            │
│  └──────────┘  └──────────┘            │
│                                         │
│  ┌──────────┐  ┌──────────┐            │
│  │ Order    │  │ Auction  │            │
│  │ Service  │  │ Service  │            │
│  └──────────┘  └──────────┘            │
│                                         │
│  └────────────────────────┘            │
└─────────────────────────────────────────┘
           │ (Service + Endpoint)
           ▼
┌─────────────────────────────────────────┐
│      AWS EC2 (Private IP: 10.0.30.99)   │
│                                         │
│  ┌──────────────┐  ┌──────────────┐   │
│  │ PostgreSQL   │  │ Redis        │   │
│  │ :5432        │  │ :6379        │   │
│  │ (Docker)     │  │ (Docker)     │   │
│  └──────────────┘  └──────────────┘   │
│                                         │
│  ┌──────────────┐                      │
│  │ Kafka        │                      │
│  │ :9092        │                      │
│  │ (Docker)     │                      │
│  └──────────────┘                      │
└─────────────────────────────────────────┘
```

---

## Docker 외부 서비스 (EC2)

### EC2 정보

```bash
# Private IP
IP: 10.0.30.99

# Region/AZ
# TODO: 실행 환경에서 확인
# aws ec2 describe-instances --instance-ids <instance-id>
```

### Docker Containers

#### 1. PostgreSQL

```bash
# Container Name
biddy-postgres

# Image
postgres:16-alpine

# Port
5432

# Credentials
User: biddy
Password: biddy1234
Database: postgres (default)

# Created Databases
- biddy_member
- biddy_product
- biddy_order
- biddy_auction
- biddy_payment

# Volumes
postgres-data:/var/lib/postgresql/data

# Restart Policy
unless-stopped
```

**초기화 스크립트**: `~/biddy-docker/init-db/01-create-databases.sql`

**연결 테스트**:
```bash
docker exec biddy-postgres pg_isready -U biddy
docker exec biddy-postgres psql -U biddy -c "\l" | grep biddy_
```

---

#### 2. Redis

```bash
# Container Name
biddy-redis

# Image
redis:7-alpine

# Port
6379

# Volumes
redis-data:/data

# Restart Policy
unless-stopped
```

**연결 테스트**:
```bash
docker exec biddy-redis redis-cli ping  # PONG
```

---

#### 3. Kafka

```bash
# Container Name
biddy-kafka

# Image
apache/kafka:3.7.0

# Port
9092

# Configuration
KAFKA_NODE_ID: 1
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://10.0.30.99:9092
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
CLUSTER_ID: biddy-kafka-cluster-001

# Restart Policy
unless-stopped
```

**연결 테스트**:
```bash
docker exec biddy-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092
```

---

## Kubernetes 리소스

### Namespace

```bash
# Namespace
biddy

# 확인
kubectl get ns biddy
```

---

### 외부 서비스 연결 (Service + Endpoint)

#### PostgreSQL

```yaml
# k8s/base/external-services/postgres-external.yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: biddy
spec:
  type: ClusterIP
  ports:
    - name: postgres
      port: 5432
      targetPort: 5432
---
apiVersion: v1
kind: Endpoints
metadata:
  name: postgres
  namespace: biddy
subsets:
  - addresses:
      - ip: 10.0.30.99
    ports:
      - name: postgres
        port: 5432
```

**Pod 내부에서 연결**: `postgres:5432`

---

#### Redis

```yaml
# k8s/base/external-services/redis-external.yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: biddy
spec:
  type: ClusterIP
  ports:
    - name: redis
      port: 6379
---
apiVersion: v1
kind: Endpoints
metadata:
  name: redis
  namespace: biddy
subsets:
  - addresses:
      - ip: 10.0.30.99
    ports:
      - name: redis
        port: 6379
```

**Pod 내부에서 연결**: `redis:6379`

---

#### Kafka

```yaml
# k8s/base/external-services/kafka-external.yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka
  namespace: biddy
spec:
  type: ClusterIP
  ports:
    - name: kafka
      port: 9092
---
apiVersion: v1
kind: Endpoints
metadata:
  name: kafka
  namespace: biddy
subsets:
  - addresses:
      - ip: 10.0.30.99
    ports:
      - name: kafka
        port: 9092
```

**Pod 내부에서 연결**: `kafka:9092`

---

### Microservices

**실행 확인**:
```bash
kubectl get pods -n biddy
kubectl get deployments -n biddy
```

**예상 Pods** (실제 환경에서 확인 필요):
```
NAME                             READY   STATUS    RESTARTS   AGE
apigateway-xxxxx                 1/1     Running   0          Xd
discovery-xxxxx                  1/1     Running   0          Xd
member-service-xxxxx             1/1     Running   0          Xd
product-service-xxxxx            1/1     Running   0          Xd
order-service-xxxxx              1/1     Running   0          Xd
auction-service-xxxxx            1/1     Running   0          Xd
payment-service-xxxxx            1/1     Running   0          Xd
```

---

## 리소스 할당

### CPU & Memory

**확인 명령**:
```bash
# Deployment 리소스 확인
kubectl get deployments -n biddy -o json | jq '.items[] | {
  name: .metadata.name,
  replicas: .spec.replicas,
  resources: .spec.template.spec.containers[0].resources
}'
```

**예상 설정** (실제 확인 필요):
```yaml
resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi
```

---

## 네트워크

### API Gateway

```bash
# External Access
Service Type: LoadBalancer or NodePort
Port: 8000

# 확인
kubectl get svc apigateway -n biddy
```

### Service Discovery

```bash
# Eureka Server
URL: http://eureka:8761/eureka/

# 등록된 서비스 확인
curl http://localhost:8761/eureka/apps
```

---

## 데이터베이스 스키마

### Member Service

```sql
Database: biddy_member
Schema: member_biddy

-- 확인
\c biddy_member
\dt member_biddy.*
```

### Product Service

```sql
Database: biddy_product

-- 확인
\c biddy_product
\dt
```

### Order Service

```sql
Database: biddy_order

-- 확인
\c biddy_order
\dt
```

### Auction Service

```sql
Database: biddy_auction

-- 확인
\c biddy_auction
\dt
```

### Payment Service

```sql
Database: biddy_payment

-- 확인
\c biddy_payment
\dt
```

---

## 테스트 환경 검증

### 필수 체크리스트

```bash
# 1. EC2 Docker 서비스 확인
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep biddy

# 2. K8s Endpoint 확인
kubectl get endpoints -n biddy postgres redis kafka

# 3. K8s Pod 확인
kubectl get pods -n biddy

# 4. API Gateway 접근 확인
curl http://<gateway-url>:8000/actuator/health

# 5. Smoke Test 실행
cd k6-tests
k6 run scripts/00_smoke_test.js
```

---

## 성능 테스트 기준 (Phase 6에서 사용)

### 목표 TPS

**TODO**: 비즈니스 요구사항 기반으로 정의 필요

**참고**: 테스트 전략 문서 인용
> "1000 TPS가 목표라는 근거가 없으면 결과 해석이 불가능하다."

**정의해야 할 항목**:
1. 예상 동시 사용자 수 (CCU)
2. 피크 시간대 TPS
3. 평균 응답시간 SLA (예: P95 < 500ms)
4. 에러율 허용 범위 (예: < 1%)

---

## 환경 스냅샷 (테스트 실행 시 기록)

```bash
# 테스트 실행 시 다음 정보 기록
date                              # 실행 시간
git rev-parse HEAD                # Commit SHA
kubectl get pods -n biddy         # Pod 상태
docker ps | grep biddy            # Docker 상태
```

**예시**:
```
Date: 2026-07-14 15:30:00 KST
Commit: 3412095abcd...
Pods: 7/7 Running
Docker: 3/3 Running (postgres, redis, kafka)
```

---

## Phase 1 완료 조건

- [x] 코드베이스 정보 기록 (branch, commit)
- [x] 인프라 아키텍처 문서화
- [x] Docker 외부 서비스 스펙 기록
- [x] K8s 리소스 구성 문서화
- [ ] 실제 환경에서 리소스 할당 확인
- [ ] 성능 목표 정의 (비즈니스 요구사항 필요)

**다음 단계**: Smoke test 실행 및 idempotency 검증