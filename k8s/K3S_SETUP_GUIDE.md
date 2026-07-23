# K3s 클러스터 설치 및 설정 가이드

## 목차
1. [아키텍처 개요](#아키텍처-개요)
2. [사전 요구사항](#사전-요구사항)
3. [마스터 노드 설치](#마스터-노드-설치)
4. [워커 노드 설치](#워커-노드-설치)
5. [ConfigMap과 Secret 설정](#configmap과-secret-설정)
6. [서비스 배포](#서비스-배포)
7. [Ingress 설정](#ingress-설정)
8. [트러블슈팅](#트러블슈팅)

---

## 아키텍처 개요

### 클러스터 구성

```
┌─────────────────────────────────────────┐
│ Master Node (10.0.30.99)                │
│ - K3s Control Plane                     │
│ - Traefik Ingress Controller            │
│ - Infrastructure Services:              │
│   - Discovery (Eureka)                  │
│   - Config Server                       │
│   - API Gateway                         │
└─────────────────────────────────────────┘
                  │
                  │ K3s Cluster Network
                  │
┌─────────────────────────────────────────┐
│ Worker Node (10.0.19.195)               │
│ - K3s Agent                             │
│ - Business Services:                    │
│   - Member, Product, Order              │
│   - Payment, Search, Chat               │
│   - Auction, Chatbot                    │
│                                         │
│ - External Infrastructure (Docker):     │
│   - PostgreSQL (5432)                   │
│   - Redis (6379)                        │
│   - Kafka (9092)                        │
│   - Elasticsearch (9200)                │
└─────────────────────────────────────────┘
```

### 네트워크 정보

| 항목 | 값 |
|------|-----|
| 마스터 Private IP | 10.0.30.99 |
| 워커 Private IP | 10.0.19.195 |
| K3s API Server | https://10.0.30.99:6443 |
| Namespace | biddy |

---

## 사전 요구사항

### 시스템 요구사항

**마스터 노드:**
- CPU: 2 vCPU 이상
- Memory: 2GB 이상
- OS: Ubuntu 20.04/22.04 또는 Amazon Linux 2

**워커 노드:**
- CPU: 2 vCPU 이상
- Memory: 4GB 이상 (애플리케이션 + 인프라)
- OS: Ubuntu 20.04/22.04 또는 Amazon Linux 2

### AWS 보안 그룹 설정

**마스터 노드 인바운드 규칙:**
```
- 6443/tcp   (K3s API Server)        from 워커 노드
- 80/tcp     (HTTP Ingress)          from 0.0.0.0/0
- 443/tcp    (HTTPS Ingress)         from 0.0.0.0/0
- 22/tcp     (SSH)                   from 관리자 IP
```

**워커 노드 인바운드 규칙:**
```
- 10250/tcp  (Kubelet API)           from 마스터 노드
- 5432/tcp   (PostgreSQL)            from 마스터 노드
- 6379/tcp   (Redis)                 from 마스터 노드
- 9092/tcp   (Kafka)                 from 마스터 노드
- 9200/tcp   (Elasticsearch)         from 마스터 노드
- 22/tcp     (SSH)                   from 관리자 IP
```

---

## 마스터 노드 설치

### 1. K3s Server 설치

```bash
# 마스터 노드에 SSH 접속
ssh -i your-key.pem ubuntu@<마스터-퍼블릭-IP>

# K3s Server 설치
curl -sfL https://get.k3s.io | sh -

# 설치 확인
sudo systemctl status k3s

# K3s 버전 확인
k3s --version
# 예상 출력: k3s version v1.36.2+k3s1
```

### 2. kubectl 설정

```bash
# kubectl 설정 파일 복사
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $USER:$USER ~/.kube/config

# kubectl 동작 확인
kubectl get nodes
# 예상 출력:
# NAME                    STATUS   ROLES                  AGE   VERSION
# ip-10-0-30-99...        Ready    control-plane,master   1m    v1.36.2+k3s1
```

### 3. 노드 정보 확인

```bash
# 노드 레이블 확인
kubectl get nodes --show-labels

# 중요 레이블:
# - node-role.kubernetes.io/control-plane=true
# - node-role.kubernetes.io/master=true
```

### 4. Join Token 확인 (워커 노드 추가용)

```bash
# Token 확인 (워커 노드 설치 시 필요)
sudo cat /var/lib/rancher/k3s/server/node-token

# Token을 안전하게 저장하세요
```

---

## 워커 노드 설치

### 1. 기존 Docker 인프라 확인

워커 노드에는 이미 Docker로 인프라가 실행 중입니다:

```bash
# 워커 노드에 SSH 접속
ssh -i your-key.pem ubuntu@<워커-퍼블릭-IP>

# Docker 컨테이너 확인
docker ps

# 예상 출력: postgres, redis, kafka, elasticsearch 컨테이너
```

### 2. K3s Agent 설치

```bash
# K3s Agent 설치
curl -sfL https://get.k3s.io | K3S_URL=https://10.0.30.99:6443 \
  K3S_TOKEN=<마스터의_node-token> sh -

# 설치 확인
sudo systemctl status k3s-agent

# 자동 시작 설정 확인
sudo systemctl is-enabled k3s-agent
```

### 3. 마스터에서 워커 노드 확인

마스터 노드로 돌아가서:

```bash
# 노드 확인
kubectl get nodes

# 예상 출력:
# NAME                    STATUS   ROLES                  AGE   VERSION
# ip-10-0-30-99...        Ready    control-plane,master   10m   v1.36.2+k3s1
# ip-10-0-19-195...       Ready    <none>                 1m    v1.36.2+k3s1

# 노드 상세 정보
kubectl get nodes -o wide
```

### 4. 워커 노드 라벨링

```bash
# 워커 노드 이름 확인
export WORKER_NODE=$(kubectl get nodes | grep "10-0-19-195" | awk '{print $1}')

# worker 역할 라벨 추가
kubectl label nodes $WORKER_NODE node-role.kubernetes.io/worker=true

# 확인
kubectl get nodes
# 이제 ROLES에 "worker"가 표시됨
```

---

## ConfigMap과 Secret 설정

### 1. Namespace 생성

```bash
# biddy namespace 생성
kubectl create namespace biddy

# 확인
kubectl get namespaces
```

### 2. ConfigMap 생성

```bash
kubectl create configmap biddy-common-config \
  --namespace=biddy \
  --from-literal=POSTGRES_HOST=postgres \
  --from-literal=POSTGRES_PORT=5432 \
  --from-literal=POSTGRES_USER=biddy \
  --from-literal=REDIS_HOST=redis \
  --from-literal=REDIS_PORT=6379 \
  --from-literal=KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  --from-literal=MEMBER_DB=biddy_member \
  --from-literal=PRODUCT_DB=biddy_product \
  --from-literal=ORDER_DB=biddy_order \
  --from-literal=AUCTION_DB=biddy_auction \
  --from-literal=PAYMENT_DB=biddy_payment \
  --from-literal=CHAT_DB=biddy_chat \
  --from-literal=CHATBOT_DB=biddy_chatbot \
  --from-literal=SEARCH_DB=biddy_search \
  --from-literal=EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE=http://discovery:8761/eureka/ \
  --from-literal=SPRING_CLOUD_CONFIG_URI=http://config:8888 \
  --from-literal=ORDER_SERVICE_URL=http://order:8083 \
  --from-literal=PRODUCT_SERVICE_URL=http://product:8082 \
  --from-literal=TOSS_PAYMENTS_BASE_URL=https://api.tosspayments.com \
  --from-literal=IMAGE_BASE_URL=http://localhost:8082/images \
  --from-literal=PRODUCT_EMBEDDING_ENABLED=true \
  --from-literal=LLM_ENABLED=true \
  --from-literal=TZ=Asia/Seoul \
  --from-literal=JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Seoul

# 확인
kubectl get configmap -n biddy biddy-common-config
```

### 3. Secret 생성

**⚠️ 보안 경고: 아래 예제 값들은 개발/테스트용입니다. 프로덕션에서는 반드시 강력한 값으로 변경하세요!**

```bash
# 개발/테스트 환경용 (예제)
kubectl create secret generic biddy-secret \
  --namespace=biddy \
  --from-literal=POSTGRES_PASSWORD=biddy1234 \
  --from-literal=JWT_SECRET=devcourse6devcourse6devcourse6devcourse6 \
  --from-literal=MAIL_USERNAME=tlsdlcl456@gmail.com \
  --from-literal=MAIL_PASSWORD='eguo ibzw hvho dkcv' \
  --from-literal=TOSS_SECRET_KEY=test_ck_LkKEypNArW204NxaZXOjVlmeaxYG \
  --from-literal=TOSS_PAYMENTS_SECRET_KEY=test_sk_GjLJoQ1aVZYZgQ9Xogn13w6KYe2R \
  --from-literal=GEMINI_API_KEY=YOUR_GEMINI_API_KEY \
  --from-literal=OPENAI_API_KEY=YOUR_OPENAI_API_KEY

# 확인
kubectl get secret -n biddy biddy-secret
```

**프로덕션 환경 보안 체크리스트:**
- [ ] `POSTGRES_PASSWORD`: 16자 이상의 강력한 비밀번호로 변경
- [ ] `JWT_SECRET`: 최소 32자 이상의 랜덤 문자열로 변경 (예: `openssl rand -base64 32`)
- [ ] `MAIL_PASSWORD`: 실제 이메일 앱 비밀번호 사용
- [ ] `TOSS_SECRET_KEY`: Toss Payments에서 발급받은 실제 키 사용
- [ ] `TOSS_PAYMENTS_SECRET_KEY`: Toss Payments에서 발급받은 실제 시크릿 키 사용
- [ ] `GEMINI_API_KEY`: Google AI Studio에서 발급받은 실제 키
- [ ] `OPENAI_API_KEY`: OpenAI에서 발급받은 실제 키

**랜덤 비밀번호 생성 예제:**
```bash
# PostgreSQL 비밀번호 (20자)
POSTGRES_PASSWORD=$(openssl rand -base64 20)

# JWT Secret (32자)
JWT_SECRET=$(openssl rand -base64 32)

echo "생성된 비밀번호를 안전한 곳에 저장하세요!"
echo "POSTGRES_PASSWORD: $POSTGRES_PASSWORD"
echo "JWT_SECRET: $JWT_SECRET"
```

### 4. GHCR (GitHub Container Registry) Secret 생성

```bash
kubectl create secret docker-registry ghcr-secret \
  --namespace=biddy \
  --docker-server=ghcr.io \
  --docker-username=YOUR_GITHUB_USERNAME \
  --docker-password=YOUR_GITHUB_PAT \
  --docker-email=YOUR_EMAIL

# 확인
kubectl get secret -n biddy ghcr-secret
```

**GitHub Personal Access Token (PAT) 생성 방법:**
1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token → `read:packages` 권한 선택
3. 생성된 토큰을 위 명령어의 `--docker-password`에 사용

### 5. Secret/ConfigMap 확인

```bash
# 전체 Secret/ConfigMap 목록
kubectl get secrets,configmaps -n biddy

# 예상 출력:
# NAME                         TYPE                             DATA   AGE
# secret/biddy-secret          Opaque                           8      1m
# secret/ghcr-secret           kubernetes.io/dockerconfigjson   1      1m
#
# NAME                              DATA   AGE
# configmap/biddy-common-config     24     2m
```

---

## 서비스 배포

### 1. External Services 배포 (워커 노드 Docker 서비스 매핑)

```bash
# External Services 배포
kubectl apply -f k8s/base/external-services/

# 확인
kubectl get svc -n biddy

# 예상 출력:
# NAME            TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
# postgres        ClusterIP   10.43.19.47     <none>        5432/TCP   1m
# redis           ClusterIP   10.43.223.81    <none>        6379/TCP   1m
# kafka           ClusterIP   10.43.253.243   <none>        9092/TCP   1m
# elasticsearch   ClusterIP   10.43.62.59     <none>        9200/TCP   1m
```

### 2. 인프라 서비스 배포 (마스터 노드)

#### Discovery (Eureka)

```bash
kubectl apply -f k8s/base/discovery/deployment.yaml
kubectl apply -f k8s/base/discovery/service.yaml

# 상태 확인 (READY 1/1이 될 때까지 대기)
kubectl get pods -n biddy -l app=discovery -w
# Ctrl+C로 빠져나오기
```

#### Config Server

```bash
kubectl apply -f k8s/base/config/deployment.yaml
kubectl apply -f k8s/base/config/service.yaml

kubectl get pods -n biddy -l app=config -w
```

#### API Gateway

```bash
kubectl apply -f k8s/base/apigateway/deployment.yaml
kubectl apply -f k8s/base/apigateway/service.yaml

kubectl get pods -n biddy -l app=apigateway -w
```

### 3. 비즈니스 서비스 배포 (워커 노드)

#### Member Service

```bash
kubectl apply -f k8s/base/member/deployment.yaml
kubectl apply -f k8s/base/member/service.yaml

kubectl get pods -n biddy -l app=member -w
```

#### Product Service

```bash
kubectl apply -f k8s/base/product/deployment.yaml
kubectl apply -f k8s/base/product/service.yaml

kubectl get pods -n biddy -l app=product -w
```

#### Order Service

```bash
kubectl apply -f k8s/base/order/deployment.yaml
kubectl apply -f k8s/base/order/service.yaml

kubectl get pods -n biddy -l app=order -w
```

#### Payment Service

```bash
kubectl apply -f k8s/base/payment/deployment.yaml
kubectl apply -f k8s/base/payment/service.yaml

kubectl get pods -n biddy -l app=payment -w
```

#### Search Service

```bash
kubectl apply -f k8s/base/search/deployment.yaml
kubectl apply -f k8s/base/search/service.yaml

kubectl get pods -n biddy -l app=search -w
```

#### Auction Service (선택사항)

```bash
kubectl apply -f k8s/base/auction/deployment.yaml
kubectl apply -f k8s/base/auction/service.yaml

kubectl get pods -n biddy -l app=auction -w
```

#### Chat Service (선택사항)

```bash
kubectl apply -f k8s/base/chat/deployment.yaml
kubectl apply -f k8s/base/chat/service.yaml

kubectl get pods -n biddy -l app=chat -w
```

#### Chatbot Service (선택사항)

```bash
kubectl apply -f k8s/base/chatbot/deployment.yaml
kubectl apply -f k8s/base/chatbot/service.yaml

kubectl get pods -n biddy -l app=chatbot -w
```

### 4. 전체 상태 확인

```bash
# 전체 Pod 상태
kubectl get pods -n biddy

# 전체 Service 상태
kubectl get svc -n biddy

# 전체 Deployment 상태
kubectl get deployments -n biddy

# Pod가 어느 노드에서 실행 중인지 확인
kubectl get pods -n biddy -o wide
```

**예상 결과:**
- discovery, config, apigateway → 마스터 노드 (10.0.30.99)
- member, product, order, payment, search → 워커 노드 (10.0.19.195)

---

## Ingress 설정

### 1. Traefik Ingress Controller 확인

K3s는 기본적으로 Traefik을 포함합니다:

```bash
# Traefik Pod 확인
kubectl get pods -n kube-system | grep traefik

# Traefik Service 확인
kubectl get svc -n kube-system traefik

# 예상 출력:
# NAME      TYPE           CLUSTER-IP     EXTERNAL-IP   PORT(S)
# traefik   LoadBalancer   10.43.0.100    10.0.30.99    80:xxxxx/TCP,443:xxxxx/TCP
```

### 2. Ingress 리소스 배포

```bash
# Ingress 배포 (있는 경우)
kubectl apply -f k8s/base/ingress/

# Ingress 확인
kubectl get ingress -n biddy

# Ingress 상세 정보
kubectl describe ingress -n biddy
```

### 3. 외부 접근 테스트

```bash
# 마스터 노드의 퍼블릭 IP 확인
MASTER_PUBLIC_IP=<마스터-퍼블릭-IP>

# API Gateway Health Check
curl http://${MASTER_PUBLIC_IP}/actuator/health

# 또는 nip.io 도메인 사용
curl http://${MASTER_PUBLIC_IP}.nip.io/actuator/health
```

---

## 트러블슈팅

### Pod가 Pending 상태

**증상:**
```bash
kubectl get pods -n biddy
# NAME                    READY   STATUS    RESTARTS   AGE
# discovery-xxx           0/1     Pending   0          1m
```

**원인 및 해결:**

1. **nodeSelector 불일치:**
```bash
# Pod 상세 정보 확인
kubectl describe pod <pod-name> -n biddy

# Events 섹션에서 "didn't match Pod's node affinity/selector" 메시지 확인

# 해결: deployment.yaml의 nodeSelector 수정
# K3s는 node-role.kubernetes.io/control-plane=true 사용
```

2. **이미지 Pull 실패:**
```bash
# Events에서 "ImagePullBackOff" 확인
# 해결: GHCR Secret 확인
kubectl get secret ghcr-secret -n biddy
```

3. **리소스 부족:**
```bash
# 노드 리소스 확인
kubectl describe nodes

# 해결: 노드 메모리/CPU 증설 또는 Pod 리소스 요청 감소
```

### Pod가 CrashLoopBackOff

**증상:**
```bash
kubectl get pods -n biddy
# NAME                    READY   STATUS             RESTARTS   AGE
# member-xxx              0/1     CrashLoopBackOff   5          3m
```

**원인 및 해결:**

1. **로그 확인:**
```bash
# Pod 로그 확인
kubectl logs <pod-name> -n biddy --tail=100

# 이전 재시작 로그 확인
kubectl logs <pod-name> -n biddy --previous
```

2. **환경변수 누락:**
```bash
# Pod의 환경변수 확인
kubectl exec <pod-name> -n biddy -- env | grep POSTGRES

# ConfigMap/Secret 확인
kubectl get configmap biddy-common-config -n biddy -o yaml
kubectl get secret biddy-secret -n biddy -o yaml
```

3. **데이터베이스 연결 실패:**
```bash
# PostgreSQL 연결 테스트
kubectl exec <pod-name> -n biddy -- nc -zv postgres 5432

# External Service 확인
kubectl get endpoints postgres -n biddy
```

### ReadinessProbe 실패

**증상:**
```bash
kubectl get pods -n biddy
# NAME                    READY   STATUS    RESTARTS   AGE
# member-xxx              0/1     Running   0          5m
```

**원인 및 해결:**

```bash
# Pod 이벤트 확인
kubectl describe pod <pod-name> -n biddy
# Readiness probe failed: HTTP probe failed 메시지 확인

# Health Check 엔드포인트 직접 테스트
kubectl exec <pod-name> -n biddy -- curl -f http://localhost:8081/actuator/health

# 일반적인 원인:
# - 애플리케이션 시작 시간이 initialDelaySeconds보다 김
# - 데이터베이스 연결 실패
# - 필수 환경변수 누락
```

### 노드 간 통신 실패

**증상:**
워커 노드의 Pod가 마스터 노드의 Discovery 서비스에 등록되지 않음

**해결:**

```bash
# 1. 보안 그룹 확인
# 마스터 ↔ 워커 간 모든 필요한 포트가 열려있는지 확인

# 2. DNS 확인
kubectl exec <pod-name> -n biddy -- nslookup discovery

# 3. Service 연결 테스트
kubectl exec <pod-name> -n biddy -- curl http://discovery:8761/eureka/apps
```

### ConfigMap/Secret 업데이트

**ConfigMap/Secret 변경 후 Pod 재시작:**

```bash
# ConfigMap 삭제 및 재생성
kubectl delete configmap biddy-common-config -n biddy
kubectl create configmap biddy-common-config ...

# Deployment 재시작 (변경사항 적용)
kubectl rollout restart deployment/member -n biddy
kubectl rollout restart deployment/product -n biddy
# ... 모든 서비스에 대해 반복

# 또는 전체 Deployment 재시작
kubectl rollout restart deployment -n biddy
```

### 로그 및 모니터링 명령어

```bash
# 실시간 로그 확인
kubectl logs -f <pod-name> -n biddy

# 최근 100줄 로그
kubectl logs <pod-name> -n biddy --tail=100

# 모든 컨테이너 로그 (멀티 컨테이너 Pod)
kubectl logs <pod-name> -n biddy --all-containers=true

# 특정 Label의 모든 Pod 로그
kubectl logs -l app=member -n biddy --tail=50

# 이벤트 확인 (시간순 정렬)
kubectl get events -n biddy --sort-by='.lastTimestamp'

# 리소스 사용량 확인
kubectl top nodes
kubectl top pods -n biddy
```

### K3s 서비스 재시작

**마스터 노드:**
```bash
# K3s Server 재시작
sudo systemctl restart k3s

# 상태 확인
sudo systemctl status k3s

# 로그 확인
sudo journalctl -u k3s -f
```

**워커 노드:**
```bash
# K3s Agent 재시작
sudo systemctl restart k3s-agent

# 상태 확인
sudo systemctl status k3s-agent

# 로그 확인
sudo journalctl -u k3s-agent -f
```

---

## 유용한 명령어 모음

### 클러스터 정보

```bash
# 클러스터 정보
kubectl cluster-info

# 노드 정보
kubectl get nodes -o wide

# 모든 리소스 확인
kubectl get all -n biddy

# 리소스 사용량
kubectl top nodes
kubectl top pods -n biddy
```

### Pod 관리

```bash
# Pod 재시작 (Deployment 기반)
kubectl rollout restart deployment/<deployment-name> -n biddy

# Pod 강제 삭제
kubectl delete pod <pod-name> -n biddy --grace-period=0 --force

# Pod 내부 접속
kubectl exec -it <pod-name> -n biddy -- /bin/sh

# Pod 파일 복사
kubectl cp <pod-name>:/path/to/file ./local-file -n biddy
```

### 디버깅

```bash
# 네트워크 디버깅 Pod 실행
kubectl run -it --rm debug --image=nicolaka/netshoot -n biddy -- /bin/bash

# DNS 테스트
nslookup discovery
nslookup postgres

# 연결 테스트
curl http://discovery:8761/actuator/health
nc -zv postgres 5432
```

### 정리 (Cleanup)

```bash
# 특정 Deployment 삭제
kubectl delete deployment <deployment-name> -n biddy

# 전체 namespace 삭제 (주의!)
kubectl delete namespace biddy

# K3s 완전 제거 (마스터)
/usr/local/bin/k3s-uninstall.sh

# K3s 완전 제거 (워커)
/usr/local/bin/k3s-agent-uninstall.sh
```

---

## 배포 체크리스트

### 초기 설치
- [ ] 마스터 노드에 K3s Server 설치
- [ ] 워커 노드에 K3s Agent 설치
- [ ] 노드 간 통신 확인 (`kubectl get nodes`)
- [ ] 워커 노드 라벨링

### 환경 설정
- [ ] biddy namespace 생성
- [ ] ConfigMap 생성 및 확인
- [ ] Secret 생성 및 확인
- [ ] GHCR Secret 생성

### External Services
- [ ] PostgreSQL Endpoint 확인
- [ ] Redis Endpoint 확인
- [ ] Kafka Endpoint 확인
- [ ] Elasticsearch Endpoint 확인

### 서비스 배포
- [ ] Discovery 배포 및 Ready 확인
- [ ] Config 배포 및 Ready 확인
- [ ] API Gateway 배포 및 Ready 확인
- [ ] Member 배포 및 Ready 확인
- [ ] Product 배포 및 Ready 확인
- [ ] Order 배포 및 Ready 확인
- [ ] Payment 배포 및 Ready 확인
- [ ] Search 배포 및 Ready 확인

### 검증
- [ ] 모든 Pod가 Running 상태
- [ ] 모든 Pod가 Ready (1/1) 상태
- [ ] Discovery에서 모든 서비스 등록 확인
- [ ] API Gateway를 통한 Health Check 성공
- [ ] 외부 접근 테스트 성공

---

## 참고 자료

- [K3s 공식 문서](https://docs.k3s.io/)
- [Kubernetes 공식 문서](https://kubernetes.io/docs/)
- [Traefik 문서](https://doc.traefik.io/traefik/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

---

**작성일:** 2026-07-20
**K3s 버전:** v1.36.2+k3s1
**작성자:** DevOps Team