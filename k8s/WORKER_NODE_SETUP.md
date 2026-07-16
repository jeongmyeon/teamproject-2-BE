# 워커 노드 인프라 서비스 이동 가이드

> 마스터 노드 메모리 부족 해결: PostgreSQL, Redis, Kafka, Elasticsearch를 워커 노드로 이동

---

## 배경

### 문제점
- **마스터 노드 메모리 부족**: 인프라 서비스(PostgreSQL, Redis, Kafka)가 마스터 노드에서 실행되어 메모리 압박 발생
- **리소스 불균형**: 워커 노드는 여유가 있으나 마스터 노드만 과부하

### 해결 방안
- 인프라 서비스를 **워커 노드(10.0.19.195)**로 이동
- K8s Pod들은 워커 노드의 인프라 서비스를 External Service로 접근
- 마스터 노드는 K8s 제어 평면에만 집중

---

## 아키텍처

### Before (마스터 노드 과부하)
```
┌─────────────────────────────────┐
│ 마스터 노드 (10.0.30.99)         │
│  - K8s Control Plane            │
│  - PostgreSQL ← 메모리 압박!    │
│  - Redis                        │
│  - Kafka                        │
│  - Elasticsearch                │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│ 워커 노드 (10.0.19.195)          │
│  - K8s Pods (여유 있음)         │
└─────────────────────────────────┘
```

### After (리소스 균형)
```
┌─────────────────────────────────┐
│ 마스터 노드 (10.0.30.99)         │
│  - K8s Control Plane            │
│  ✅ 메모리 여유 확보!            │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│ 워커 노드 (10.0.19.195)          │
│  - K8s Pods                     │
│  - PostgreSQL (Docker)          │
│  - Redis (Docker)               │
│  - Kafka (Docker)               │
│  - Elasticsearch (Docker)       │
└─────────────────────────────────┘
```

---

## 설치 가이드

### 1. 워커 노드에 SSH 접속

```bash
ssh -i your-key.pem ubuntu@<워커노드-Public-IP>
```

### 2. 셋업 스크립트 복사 및 실행

#### 방법 1: 직접 스크립트 실행 (권장)

```bash
# 스크립트 다운로드
curl -O https://raw.githubusercontent.com/prgrms-be-adv-devcourse/beadv6_6_frontal_BE/main/k8s/worker-node-setup.sh

# 실행 권한 부여
chmod +x worker-node-setup.sh

# 실행
./worker-node-setup.sh
```

#### 방법 2: 수동 설치

```bash
# 1. 작업 디렉토리 생성
mkdir -p ~/biddy-infra
cd ~/biddy-infra

# 2. Docker Compose 파일 다운로드
curl -O https://raw.githubusercontent.com/prgrms-be-adv-devcourse/beadv6_6_frontal_BE/main/k8s/worker-node-docker-compose.yml

# 3. docker-compose.yml로 이름 변경
mv worker-node-docker-compose.yml docker-compose.yml

# 4. 서비스 시작
docker compose up -d
```

### 3. 서비스 상태 확인

```bash
# 컨테이너 상태 확인
docker compose ps

# 로그 확인
docker compose logs -f

# 개별 서비스 로그
docker compose logs -f postgres
docker compose logs -f redis
docker compose logs -f kafka
docker compose logs -f elasticsearch
```

---

## 서비스 구성

### PostgreSQL
- **포트**: 5432
- **이미지**: pgvector/pgvector:pg16
- **메모리**: shared_buffers=256MB
- **연결**: max_connections=200

### Redis
- **포트**: 6379
- **이미지**: redis:7-alpine
- **메모리**: maxmemory=512mb
- **정책**: allkeys-lru

### Kafka
- **포트**: 9092 (Broker), 9093 (Controller)
- **이미지**: bitnami/kafka:3.6
- **메모리**: JVM Heap 512MB
- **모드**: KRaft (Zookeeper 불필요)

### Elasticsearch
- **포트**: 9200 (HTTP), 9300 (Transport)
- **이미지**: elasticsearch:8.11.0
- **메모리**: JVM Heap 512MB
- **보안**: 비활성화 (내부 전용)

---

## K8s 연동

### External Service 설정

K8s Pod들은 다음 External Service를 통해 워커 노드의 인프라에 접근합니다:

```yaml
# postgres-external.yaml
apiVersion: v1
kind: Endpoints
metadata:
  name: postgres
  namespace: biddy
subsets:
  - addresses:
      - ip: 10.0.19.195  # 워커 노드 Private IP
    ports:
      - port: 5432
```

### 자동 적용

K8s External Service 설정은 이미 변경되어 있으므로, **배포만 하면 자동 연동**됩니다:

```bash
# K8s 설정 적용 (마스터 노드 또는 로컬에서)
kubectl apply -f k8s/base/external-services/
```

---

## 운영 명령어

### 서비스 관리

```bash
# 서비스 시작
docker compose up -d

# 서비스 중지
docker compose down

# 서비스 재시작
docker compose restart

# 특정 서비스 재시작
docker compose restart postgres
docker compose restart redis
docker compose restart kafka
```

### 로그 확인

```bash
# 전체 로그 (실시간)
docker compose logs -f

# 특정 서비스 로그
docker compose logs -f postgres

# 최근 100줄만
docker compose logs --tail=100 kafka
```

### 리소스 모니터링

```bash
# 컨테이너 리소스 사용량
docker stats

# 디스크 사용량
docker system df
```

### 데이터베이스 접속

```bash
# PostgreSQL 접속
docker exec -it biddy-postgres psql -U biddy -d biddy

# Redis 접속
docker exec -it biddy-redis redis-cli

# Elasticsearch 상태 확인
curl http://localhost:9200/_cluster/health?pretty

# Kafka 토픽 목록
docker exec -it biddy-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

## 트러블슈팅

### 서비스가 시작되지 않는 경우

```bash
# 로그 확인
docker compose logs <service-name>

# 컨테이너 재시작
docker compose restart <service-name>

# 컨테이너 재생성
docker compose up -d --force-recreate <service-name>
```

### 메모리 부족

```bash
# 사용하지 않는 컨테이너/이미지 정리
docker system prune -a
```

### 포트 충돌

```bash
# 포트 사용 중인 프로세스 확인
sudo netstat -tlnp | grep :5432
sudo netstat -tlnp | grep :6379
sudo netstat -tlnp | grep :9092
```

### K8s Pod에서 연결 실패

```bash
# External Service Endpoint 확인
kubectl get endpoints postgres redis kafka elasticsearch -n biddy

# Pod에서 직접 연결 테스트
kubectl run -it --rm debug --image=busybox --restart=Never -- sh
# 컨테이너 안에서
nc -zv 10.0.19.195 5432
nc -zv 10.0.19.195 6379
nc -zv 10.0.19.195 9092
```

---

## 데이터 백업

### PostgreSQL

```bash
# 모든 데이터베이스 백업
docker exec biddy-postgres pg_dumpall -U biddy > backup.sql

# 특정 데이터베이스 백업
docker exec biddy-postgres pg_dump -U biddy biddy_auction > backup_auction.sql

# 복구
cat backup.sql | docker exec -i biddy-postgres psql -U biddy
```

### Redis

```bash
# RDB 스냅샷 생성
docker exec biddy-redis redis-cli SAVE

# 백업 파일 위치
docker exec biddy-redis ls -lh /data/dump.rdb
```

---

## 마이그레이션 체크리스트

### 사전 준비
- [x] 워커 노드 Private IP 확인 (10.0.19.195)
- [x] 워커 노드에 Docker 설치
- [x] K8s External Service YAML 업데이트

### 워커 노드 설정
- [ ] SSH 접속
- [ ] worker-node-setup.sh 실행
- [ ] 서비스 정상 동작 확인 (`docker compose ps`)

### K8s 연동
- [ ] External Service 배포 (`kubectl apply -f k8s/base/external-services/`)
- [ ] Endpoint IP 확인 (`kubectl get endpoints -n biddy`)
- [ ] Pod 재시작 또는 자동 재연결 대기

### 검증
- [ ] 애플리케이션 Pod 로그 확인
- [ ] PostgreSQL 연결 테스트
- [ ] Redis 연결 테스트
- [ ] Kafka 연결 테스트
- [ ] Elasticsearch 연결 테스트

### 마스터 노드 정리 (선택)
- [ ] 마스터 노드의 기존 Docker 컨테이너 중지
- [ ] 메모리 사용량 확인

---

## 참고 자료

- [Docker Compose 공식 문서](https://docs.docker.com/compose/)
- [Kubernetes External Services](https://kubernetes.io/docs/concepts/services-networking/service/#services-without-selectors)
- [PostgreSQL Docker Hub](https://hub.docker.com/_/postgres)
- [Redis Docker Hub](https://hub.docker.com/_/redis)
- [Bitnami Kafka](https://hub.docker.com/r/bitnami/kafka)

---

**작성일**: 2026-07-16
**작성자**: DevOps Team