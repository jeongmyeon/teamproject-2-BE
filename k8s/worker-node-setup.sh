#!/bin/bash

##############################################
# 워커 노드 인프라 서비스 셋업 스크립트
# PostgreSQL, Redis, Kafka, Elasticsearch
##############################################

set -e

WORKER_IP="10.0.19.195"

echo "======================================"
echo " 워커 노드 인프라 서비스 셋업"
echo " 워커 노드 IP: $WORKER_IP"
echo "======================================"
echo ""

# 1. Docker 설치 확인
echo "[1/5] Docker 설치 확인..."
if ! command -v docker &> /dev/null; then
    echo "Docker가 설치되지 않았습니다. 설치를 시작합니다..."

    # Docker 설치 (Ubuntu/Debian)
    sudo apt-get update
    sudo apt-get install -y ca-certificates curl gnupg

    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg

    echo \
      "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
      "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | \
      sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    sudo apt-get update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    # 현재 사용자를 docker 그룹에 추가
    sudo usermod -aG docker $USER

    echo "Docker 설치 완료!"
else
    echo "Docker가 이미 설치되어 있습니다: $(docker --version)"
fi

# 2. Docker Compose 설치 확인
echo ""
echo "[2/5] Docker Compose 설치 확인..."
if ! docker compose version &> /dev/null; then
    echo "Docker Compose가 설치되지 않았습니다."
    exit 1
else
    echo "Docker Compose가 설치되어 있습니다: $(docker compose version)"
fi

# 3. 작업 디렉토리 생성
echo ""
echo "[3/5] 작업 디렉토리 생성..."
WORK_DIR="$HOME/biddy-infra"
mkdir -p $WORK_DIR
cd $WORK_DIR

# 4. Docker Compose 파일 복사
echo ""
echo "[4/5] Docker Compose 파일 배치..."
cat > docker-compose.yml << 'COMPOSE_EOF'
version: '3.8'

services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: biddy-postgres
    restart: unless-stopped
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: biddy
      POSTGRES_PASSWORD: biddy1234
      POSTGRES_DB: biddy
    volumes:
      - postgres-data:/var/lib/postgresql/data
    command: >
      postgres
      -c max_connections=200
      -c shared_buffers=256MB
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U biddy"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: biddy-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --maxmemory 512mb --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: bitnami/kafka:3.6
    container_name: biddy-kafka
    restart: unless-stopped
    ports:
      - "9092:9092"
      - "9093:9093"
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://WORKER_IP_PLACEHOLDER:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_HEAP_OPTS: "-Xmx512M -Xms512M"
    volumes:
      - kafka-data:/bitnami/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "kafka-broker-api-versions.sh --bootstrap-server localhost:9092 || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    container_name: biddy-elasticsearch
    restart: unless-stopped
    ports:
      - "9200:9200"
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9200/_cluster/health || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5

volumes:
  postgres-data:
  redis-data:
  kafka-data:
  elasticsearch-data:
COMPOSE_EOF

# 워커 노드 IP로 치환
sed -i "s/WORKER_IP_PLACEHOLDER/$WORKER_IP/g" docker-compose.yml

echo "Docker Compose 파일이 생성되었습니다: $WORK_DIR/docker-compose.yml"

# 5. 서비스 시작
echo ""
echo "[5/5] 인프라 서비스 시작..."
docker compose up -d

echo ""
echo "======================================"
echo " 서비스 시작 완료!"
echo "======================================"
echo ""
echo "서비스 상태 확인:"
docker compose ps

echo ""
echo "로그 확인 명령어:"
echo "  docker compose logs -f"
echo ""
echo "서비스 중지 명령어:"
echo "  docker compose down"
echo ""
echo "서비스 재시작 명령어:"
echo "  docker compose restart"