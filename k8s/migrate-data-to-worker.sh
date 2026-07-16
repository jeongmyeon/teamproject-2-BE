#!/bin/bash

##############################################
# 마스터 노드 → 워커 노드 데이터 마이그레이션
# PostgreSQL, Redis, Kafka, Elasticsearch
##############################################

set -e

MASTER_IP="10.0.30.99"
WORKER_IP="10.0.19.195"
MASTER_USER="ubuntu"
WORKER_USER="ubuntu"
SSH_KEY="${SSH_KEY:-~/.ssh/id_rsa}"

echo "======================================"
echo " 데이터 마이그레이션"
echo " 마스터: $MASTER_IP"
echo " 워커: $WORKER_IP"
echo "======================================"
echo ""

# 색상 코드
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 사전 확인
echo -e "${YELLOW}[1/5] 사전 확인...${NC}"
echo "마스터 노드에 Docker 컨테이너가 실행 중인지 확인합니다."
echo ""

read -p "마스터 노드에서 데이터를 백업하시겠습니까? (y/n): " confirm
if [ "$confirm" != "y" ]; then
    echo "작업을 취소합니다."
    exit 0
fi

# 2. 마스터 노드에서 데이터 백업
echo ""
echo -e "${YELLOW}[2/5] 마스터 노드에서 데이터 백업...${NC}"

# 백업 디렉토리 생성 (마스터 노드)
ssh -i $SSH_KEY $MASTER_USER@$MASTER_IP << 'MASTER_BACKUP'
set -e
echo "백업 디렉토리 생성..."
mkdir -p ~/biddy-backup

echo "PostgreSQL 백업..."
if docker ps --format '{{.Names}}' | grep -q postgres; then
    docker exec biddy-postgres pg_dumpall -U biddy > ~/biddy-backup/postgres_backup.sql
    echo "✅ PostgreSQL 백업 완료: ~/biddy-backup/postgres_backup.sql"
else
    echo "⚠️  PostgreSQL 컨테이너가 실행 중이지 않습니다."
fi

echo ""
echo "Redis 백업..."
if docker ps --format '{{.Names}}' | grep -q redis; then
    # Redis SAVE 명령으로 RDB 생성
    docker exec biddy-redis redis-cli SAVE
    # dump.rdb 파일 복사
    docker cp biddy-redis:/data/dump.rdb ~/biddy-backup/redis_dump.rdb
    echo "✅ Redis 백업 완료: ~/biddy-backup/redis_dump.rdb"
else
    echo "⚠️  Redis 컨테이너가 실행 중이지 않습니다."
fi

echo ""
echo "Kafka 데이터 디렉토리 확인..."
if docker ps --format '{{.Names}}' | grep -q kafka; then
    # Kafka는 볼륨 데이터를 그대로 사용
    echo "ℹ️  Kafka는 새로운 브로커로 시작합니다 (토픽/데이터는 재생성 필요)"
else
    echo "⚠️  Kafka 컨테이너가 실행 중이지 않습니다."
fi

echo ""
echo "Elasticsearch 스냅샷 (선택사항)..."
if docker ps --format '{{.Names}}' | grep -q elasticsearch; then
    echo "ℹ️  Elasticsearch는 인덱스를 재생성합니다."
else
    echo "⚠️  Elasticsearch 컨테이너가 실행 중이지 않습니다."
fi

echo ""
echo "백업 파일 목록:"
ls -lh ~/biddy-backup/
MASTER_BACKUP

echo -e "${GREEN}✅ 마스터 노드 백업 완료${NC}"

# 3. 워커 노드에 데이터 전송
echo ""
echo -e "${YELLOW}[3/5] 워커 노드로 데이터 전송...${NC}"

echo "PostgreSQL 백업 파일 전송..."
scp -i $SSH_KEY $MASTER_USER@$MASTER_IP:~/biddy-backup/postgres_backup.sql /tmp/postgres_backup.sql
scp -i $SSH_KEY /tmp/postgres_backup.sql $WORKER_USER@$WORKER_IP:~/postgres_backup.sql
echo "✅ PostgreSQL 백업 전송 완료"

echo ""
echo "Redis 백업 파일 전송..."
scp -i $SSH_KEY $MASTER_USER@$MASTER_IP:~/biddy-backup/redis_dump.rdb /tmp/redis_dump.rdb
scp -i $SSH_KEY /tmp/redis_dump.rdb $WORKER_USER@$WORKER_IP:~/redis_dump.rdb
echo "✅ Redis 백업 전송 완료"

# 4. 워커 노드에 서비스 시작 및 복구
echo ""
echo -e "${YELLOW}[4/5] 워커 노드에서 서비스 시작 및 데이터 복구...${NC}"

ssh -i $SSH_KEY $WORKER_USER@$WORKER_IP << 'WORKER_RESTORE'
set -e

cd ~/biddy-infra

echo "Docker Compose로 서비스 시작..."
docker compose up -d

echo "서비스 시작 대기 (30초)..."
sleep 30

echo ""
echo "PostgreSQL 데이터 복구..."
if [ -f ~/postgres_backup.sql ]; then
    echo "백업 파일을 PostgreSQL에 복원합니다..."
    cat ~/postgres_backup.sql | docker exec -i biddy-postgres psql -U biddy
    echo "✅ PostgreSQL 데이터 복구 완료"
else
    echo "⚠️  PostgreSQL 백업 파일이 없습니다."
fi

echo ""
echo "Redis 데이터 복구..."
if [ -f ~/redis_dump.rdb ]; then
    echo "Redis 컨테이너 중지..."
    docker compose stop redis

    echo "dump.rdb 파일 복사..."
    docker cp ~/redis_dump.rdb biddy-redis:/data/dump.rdb

    echo "Redis 재시작..."
    docker compose start redis

    echo "✅ Redis 데이터 복구 완료"
else
    echo "⚠️  Redis 백업 파일이 없습니다."
fi

echo ""
echo "서비스 상태 확인..."
docker compose ps

echo ""
echo "서비스 로그 확인 (마지막 20줄)..."
docker compose logs --tail=20
WORKER_RESTORE

echo -e "${GREEN}✅ 워커 노드 데이터 복구 완료${NC}"

# 5. 검증
echo ""
echo -e "${YELLOW}[5/5] 데이터 마이그레이션 검증...${NC}"

echo "PostgreSQL 연결 테스트..."
ssh -i $SSH_KEY $WORKER_USER@$WORKER_IP "docker exec biddy-postgres psql -U biddy -c '\l'"

echo ""
echo "Redis 연결 테스트..."
ssh -i $SSH_KEY $WORKER_USER@$WORKER_IP "docker exec biddy-redis redis-cli PING"

echo ""
echo "Kafka 상태 확인..."
ssh -i $SSH_KEY $WORKER_USER@$WORKER_IP "docker exec biddy-kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 || echo 'Kafka 시작 중...'"

echo ""
echo -e "${GREEN}======================================"
echo " 마이그레이션 완료!"
echo "======================================${NC}"
echo ""
echo "다음 단계:"
echo "1. K8s External Service 업데이트:"
echo "   kubectl apply -f k8s/base/external-services/"
echo ""
echo "2. 애플리케이션 Pod 재시작:"
echo "   kubectl rollout restart deployment -n biddy"
echo ""
echo "3. 마스터 노드 정리 (선택):"
echo "   ssh $MASTER_USER@$MASTER_IP 'cd ~/<docker-compose-dir> && docker compose down'"