#!/bin/bash
set -e

#######################################
# Biddy Docker Services Startup Script
# AWS EC2에서 실행할 스크립트
#######################################

echo "=========================================="
echo "Biddy Docker Services 시작 스크립트"
echo "=========================================="

# 1. EC2 Private IP 확인
echo ""
echo "[1/6] EC2 Private IP 확인 중..."
EC2_IP=$(hostname -I | awk '{print $1}')
echo "✓ EC2 Private IP: $EC2_IP"

if [ -z "$EC2_IP" ]; then
    echo "❌ Error: EC2 IP를 확인할 수 없습니다."
    exit 1
fi

# 2. 작업 디렉토리 생성
echo ""
echo "[2/6] 작업 디렉토리 설정 중..."
mkdir -p ~/biddy-docker/init-db
cd ~/biddy-docker

# 3. 데이터베이스 초기화 SQL 스크립트 생성
echo ""
echo "[3/6] PostgreSQL 초기화 스크립트 생성 중..."
cat > init-db/01-create-databases.sql <<'EOF'
CREATE DATABASE biddy_member;
\connect biddy_member
CREATE SCHEMA IF NOT EXISTS member_biddy;

CREATE DATABASE biddy_product;
CREATE DATABASE biddy_order;
CREATE DATABASE biddy_auction;
CREATE DATABASE biddy_payment;
EOF

echo "✓ 초기화 스크립트 생성 완료: ~/biddy-docker/init-db/01-create-databases.sql"

# 4. 기존 컨테이너 정리 (있다면)
echo ""
echo "[4/6] 기존 컨테이너 정리 중..."
docker rm -f biddy-postgres biddy-redis biddy-kafka 2>/dev/null || true
echo "✓ 기존 컨테이너 정리 완료"

# 5. Docker 컨테이너 실행
echo ""
echo "[5/6] Docker 컨테이너 실행 중..."

# PostgreSQL
echo "  - PostgreSQL 시작 중..."
docker run -d \
  --name biddy-postgres \
  --restart unless-stopped \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres123 \
  -e POSTGRES_DB=postgres \
  -p 5432:5432 \
  -v postgres-data:/var/lib/postgresql/data \
  -v ~/biddy-docker/init-db:/docker-entrypoint-initdb.d \
  postgres:16-alpine

echo "  ✓ PostgreSQL 컨테이너 시작 완료"
sleep 5

# Redis
echo "  - Redis 시작 중..."
docker run -d \
  --name biddy-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  -v redis-data:/data \
  redis:7-alpine

echo "  ✓ Redis 컨테이너 시작 완료"
sleep 3

# Kafka
echo "  - Kafka 시작 중..."
docker run -d \
  --name biddy-kafka \
  --restart unless-stopped \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$EC2_IP:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=biddy-kafka-cluster-001 \
  -p 9092:9092 \
  apache/kafka:3.7.0

echo "  ✓ Kafka 컨테이너 시작 완료"
echo "  ℹ Kafka가 완전히 시작되려면 20-30초 정도 걸립니다..."
sleep 20

# 6. 연결 테스트
echo ""
echo "[6/6] 연결 테스트 중..."

# PostgreSQL 테스트
echo "  - PostgreSQL 테스트..."
if docker exec biddy-postgres pg_isready -U postgres > /dev/null 2>&1; then
    echo "    ✓ PostgreSQL 연결 성공"

    # 데이터베이스 생성 확인
    echo "  - 데이터베이스 목록 확인..."
    docker exec biddy-postgres psql -U postgres -c "\l" | grep biddy_ && \
        echo "    ✓ 모든 데이터베이스 생성 확인" || \
        echo "    ⚠ 데이터베이스 생성 확인 필요"
else
    echo "    ❌ PostgreSQL 연결 실패"
fi

# Redis 테스트
echo "  - Redis 테스트..."
if docker exec biddy-redis redis-cli ping | grep -q PONG; then
    echo "    ✓ Redis 연결 성공"
else
    echo "    ❌ Redis 연결 실패"
fi

# Kafka 테스트
echo "  - Kafka 테스트..."
if docker exec biddy-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
    --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    echo "    ✓ Kafka 연결 성공"
else
    echo "    ⚠ Kafka가 아직 시작 중이거나 연결 실패 (30초 후 재시도 권장)"
fi

# 최종 상태 확인
echo ""
echo "=========================================="
echo "실행 중인 컨테이너 목록:"
echo "=========================================="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep biddy

echo ""
echo "=========================================="
echo "✓ Docker 서비스 시작 완료!"
echo "=========================================="
echo ""
echo "다음 단계:"
echo "1. K8s 외부 서비스 Endpoint 배포"
echo "   kubectl apply -k k8s/base/external-services/"
echo ""
echo "2. K8s 내부 서비스 제거 (StatefulSet/Deployment)"
echo "   kubectl scale statefulset kafka --replicas=0 -n biddy"
echo "   kubectl scale deployment redis --replicas=0 -n biddy"
echo "   kubectl scale deployment postgres --replicas=0 -n biddy"
echo ""
echo "3. 애플리케이션 Pod 재시작"
echo "   kubectl rollout restart deployment -n biddy"
echo ""
echo "연결 정보:"
echo "  EC2 IP: $EC2_IP"
echo "  PostgreSQL: $EC2_IP:5432 (user: postgres, password: postgres123)"
echo "  Redis: $EC2_IP:6379"
echo "  Kafka: $EC2_IP:9092"
echo ""