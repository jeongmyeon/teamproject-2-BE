#!/bin/bash

##############################################
# 간단 마이그레이션: 마스터 → 워커 노드
# Docker 이미지 + 데이터 전체 이동
##############################################

set -e

WORKER_IP="10.0.19.195"
WORKER_USER="ubuntu"
SSH_KEY="${SSH_KEY:-~/.ssh/id_rsa}"

echo "======================================"
echo " 간단 마이그레이션"
echo " 워커 노드: $WORKER_IP"
echo "======================================"
echo ""

echo "이 방법은 워커 노드에 새로 설치하는 방식입니다."
echo "마스터 노드의 데이터는 별도로 백업 후 수동 복구하세요."
echo ""

# 1. 워커 노드에 셋업 스크립트 전송
echo "[1/3] 워커 노드에 셋업 파일 전송..."

# 임시 디렉토리에 파일 준비
TEMP_DIR="/tmp/biddy-worker-setup"
mkdir -p $TEMP_DIR

# Docker Compose 파일 복사
cp k8s/worker-node-docker-compose.yml $TEMP_DIR/docker-compose.yml

# 셋업 스크립트 생성 (간소화 버전)
cat > $TEMP_DIR/setup.sh << 'SETUP_SCRIPT'
#!/bin/bash
set -e

echo "======================================"
echo " 워커 노드 인프라 서비스 설치"
echo "======================================"

# Docker 확인
if ! command -v docker &> /dev/null; then
    echo "Docker 설치 중..."
    curl -fsSL https://get.docker.com -o get-docker.sh
    sudo sh get-docker.sh
    sudo usermod -aG docker $USER
    echo "Docker 설치 완료!"
    echo "⚠️  로그아웃 후 다시 로그인하여 Docker 권한을 활성화하세요."
    exit 0
fi

echo "Docker 버전: $(docker --version)"

# 작업 디렉토리
WORK_DIR="$HOME/biddy-infra"
mkdir -p $WORK_DIR
cd $WORK_DIR

# docker-compose.yml이 현재 디렉토리에 있다고 가정
if [ ! -f docker-compose.yml ]; then
    echo "❌ docker-compose.yml 파일이 없습니다."
    exit 1
fi

echo ""
echo "서비스 시작..."
docker compose up -d

echo ""
echo "서비스 상태 확인..."
sleep 10
docker compose ps

echo ""
echo "✅ 설치 완료!"
echo ""
echo "서비스 관리 명령어:"
echo "  로그 확인: docker compose logs -f"
echo "  재시작: docker compose restart"
echo "  중지: docker compose down"
SETUP_SCRIPT

chmod +x $TEMP_DIR/setup.sh

# 워커 노드로 전송
echo "파일 전송 중..."
scp -i $SSH_KEY -r $TEMP_DIR/* $WORKER_USER@$WORKER_IP:~/biddy-infra/

echo "✅ 파일 전송 완료"

# 2. 워커 노드에서 설치 실행
echo ""
echo "[2/3] 워커 노드에서 설치 실행..."
echo ""

ssh -i $SSH_KEY -t $WORKER_USER@$WORKER_IP << 'REMOTE_INSTALL'
cd ~/biddy-infra
chmod +x setup.sh
./setup.sh
REMOTE_INSTALL

# 3. 완료 안내
echo ""
echo "======================================"
echo " 설치 완료!"
echo "======================================"
echo ""
echo "✅ 워커 노드 ($WORKER_IP)에 인프라 서비스가 시작되었습니다."
echo ""
echo "다음 단계:"
echo ""
echo "1. K8s External Service 업데이트 (로컬에서 실행):"
echo "   kubectl apply -f k8s/base/external-services/"
echo ""
echo "2. Endpoint 확인:"
echo "   kubectl get endpoints -n biddy"
echo ""
echo "3. 데이터 복구가 필요한 경우:"
echo "   - PostgreSQL: pg_dump/pg_restore 사용"
echo "   - Redis: dump.rdb 파일 복사"
echo "   - Kafka: 토픽 재생성"
echo ""
echo "워커 노드 SSH 접속:"
echo "   ssh -i $SSH_KEY $WORKER_USER@$WORKER_IP"