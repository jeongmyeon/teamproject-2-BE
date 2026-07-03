# Biddy AWS EKS Kubernetes 배포 파일

이 디렉토리는 Biddy MSA를 AWS EKS에 배포하기 위한 Kubernetes 매니페스트 파일을 포함합니다.

## 디렉토리 구조

```
k8s-aws/
├── README.md                        # 이 파일
├── namespaces.yaml                  # 네임스페이스 정의 (biddy-services, biddy-gateway)
├── config/                          # 설정 파일
│   ├── configmap.yaml               # 공통 설정 (RDS, Redis, MSK 엔드포인트)
│   └── secrets.yaml                 # 민감 정보 (비밀번호, API 키)
├── services/                        # 도메인 서비스
│   ├── discovery.yaml               # Eureka Discovery Service
│   ├── config.yaml                  # Spring Cloud Config Server
│   ├── member.yaml                  # Member Service (회원, 인증)
│   ├── product.yaml                 # Product Service (상품, 이미지)
│   ├── order.yaml                   # Order Service (주문, 장바구니)
│   ├── auction.yaml                 # Auction Service (경매, 입찰)
│   ├── payment.yaml                 # Payment Service (결제, 환불)
│   ├── recommendation.yaml          # Recommendation Service (추천)
│   └── recommendation-README.md     # 추천 서비스 상세 가이드
├── gateway/                         # API Gateway 및 Ingress
│   ├── apigateway.yaml              # API Gateway Deployment (HPA 포함)
│   └── ingress.yaml                 # ALB Ingress (HTTPS, SSL)
├── storage/                         # 스토리지 설정
│   ├── efs-setup.yaml               # EFS StorageClass, PV/PVC (이미지 공유)
│   └── README.md                    # EFS 설정 가이드 (vs S3 비교)
└── monitoring/                      # 모니터링 스택
    ├── namespace.yaml               # biddy-monitoring 네임스페이스
    ├── prometheus.yaml              # Prometheus (메트릭 수집)
    ├── grafana.yaml                 # Grafana (대시보드)
    └── README.md                    # 모니터링 설정 가이드
```

## 사전 준비

### 1. AWS 리소스 생성

다음 AWS 리소스를 먼저 생성해야 합니다:

- **EKS 클러스터**: `eksctl create cluster -f eks-cluster-config.yaml`
- **RDS PostgreSQL**: Multi-AZ, db.t3.medium
- **ElastiCache Redis**: Cluster Mode, cache.t3.small
- **MSK Kafka**: 3 brokers, kafka.t3.small
- **ECR 레지스트리**: 각 서비스별 리포지토리
- **ACM 인증서**: HTTPS용 SSL 인증서

### 2. 환경 변수 설정

`config/configmap.yaml`과 `config/secrets.yaml`의 다음 값을 실제 값으로 교체하세요:

#### ConfigMap (`config/configmap.yaml`)
- `POSTGRES_HOST`: RDS 엔드포인트
- `REDIS_HOST`: ElastiCache 엔드포인트
- `KAFKA_BOOTSTRAP_SERVERS`: MSK Bootstrap 서버

#### Secret (`config/secrets.yaml`)
- `POSTGRES_PASSWORD`: RDS 비밀번호
- `MAIL_USERNAME`, `MAIL_PASSWORD`: Gmail 앱 비밀번호
- `TOSS_PAYMENTS_SECRET_KEY`: Toss Payments 시크릿 키

#### Ingress (`gateway/ingress.yaml`)
- `alb.ingress.kubernetes.io/certificate-arn`: ACM 인증서 ARN
- `host`: 실제 도메인 (예: api.biddy.example.com)

#### Deployment 이미지 태그
모든 `*.yaml` 파일의 이미지 태그를 실제 ECR 레지스트리로 교체:
```yaml
image: YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/auction:v1.0.0
```

## 배포 순서

### 1단계: 네임스페이스 생성

```bash
kubectl apply -f namespaces.yaml
```

### 2단계: ConfigMap 및 Secret 배포

```bash
kubectl apply -f config/configmap.yaml
kubectl apply -f config/secrets.yaml
```

### 3단계: Discovery Service 배포

```bash
kubectl apply -f services/discovery.yaml

# Discovery가 Ready 상태가 될 때까지 대기 (약 1~2분)
kubectl wait --for=condition=ready pod -l app=discovery -n biddy-services --timeout=300s
```

### 4단계: 도메인 서비스 배포

```bash
# 모든 서비스 동시 배포
kubectl apply -f services/

# 상태 확인
kubectl get pods -n biddy-services -w
```

### 5단계: API Gateway 및 Ingress 배포

```bash
kubectl apply -f gateway/apigateway.yaml
kubectl apply -f gateway/ingress.yaml

# ALB 생성 확인 (약 3~5분 소요)
kubectl get ingress -n biddy-gateway -w
```

### 6단계: ALB 도메인 확인 및 Route53 설정

```bash
# ALB DNS 이름 확인
kubectl get ingress biddy-ingress -n biddy-gateway \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# Route53에서 A 레코드(Alias) 생성
# api.biddy.example.com -> ALB DNS
```

## 배포 확인

```bash
# 모든 Pod 상태 확인
kubectl get pods --all-namespaces

# 서비스 엔드포인트 확인
kubectl get svc --all-namespaces

# Ingress 확인
kubectl get ingress -n biddy-gateway

# HPA 확인
kubectl get hpa -n biddy-services

# 특정 서비스 로그 확인
kubectl logs -f deployment/auction -n biddy-services
```

## 업데이트

```bash
# 새 이미지로 업데이트
kubectl set image deployment/auction \
  auction=YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/auction:v1.1.0 \
  -n biddy-services

# 롤아웃 상태 확인
kubectl rollout status deployment/auction -n biddy-services

# 롤백 (문제 발생 시)
kubectl rollout undo deployment/auction -n biddy-services
```

## 스케일링

```bash
# 수동 스케일링
kubectl scale deployment auction --replicas=5 -n biddy-services

# HPA 확인 (자동 스케일링)
kubectl get hpa -n biddy-services -w
```

## 트러블슈팅

### Pod가 Pending 상태

```bash
kubectl describe pod <pod-name> -n biddy-services
# 원인: Insufficient CPU/Memory, Node 부족

# 해결: EKS Worker Node 추가
eksctl scale nodegroup --cluster biddy-eks-cluster --name biddy-workers --nodes 5
```

### RDS 연결 실패

```bash
# Security Group 확인
# RDS SG에 EKS Worker Node SG에서 5432 포트 접근 허용 확인
```

### ALB Health Check 실패

```bash
# Target Group 상태 확인
kubectl describe ingress biddy-ingress -n biddy-gateway

# Pod Health Check 확인
kubectl get pods -n biddy-gateway -o wide
kubectl describe pod <pod-name> -n biddy-gateway
```

## 삭제

```bash
# 모든 리소스 삭제 (역순)
kubectl delete -f gateway/
kubectl delete -f services/
kubectl delete -f config/
kubectl delete -f namespaces.yaml
```

## 참고 문서

- [AWS EKS 배포 가이드](../docs/05_배포/03_AWS_EKS_배포_가이드.md)
- [Kubernetes 도입 가이드](../docs/05_배포/02_Kubernetes_도입_가이드.md)
