# Biddy MSA AWS EKS 배포 가이드

## 문서 정보
- **프로젝트**: Biddy 실시간 경매 플랫폼
- **버전**: 1.0
- **작성일**: 2026-07-02
- **대상**: AWS EKS (Elastic Kubernetes Service) 프로덕션 배포

---

## 1. AWS EKS 개요

### 1-1. EKS란?

Amazon Elastic Kubernetes Service(EKS)는 AWS에서 제공하는 관리형 Kubernetes 서비스입니다.
- Kubernetes Control Plane 자동 관리
- AWS 서비스 (RDS, ElastiCache, MSK) 통합
- IAM 기반 인증 및 권한 관리
- AWS Load Balancer 자동 프로비저닝

### 1-2. AWS 서비스 구성

| 서비스 | 용도 | Biddy 적용 |
|--------|------|-----------|
| **EKS** | Kubernetes 클러스터 | 애플리케이션 오케스트레이션 |
| **RDS (PostgreSQL)** | 관리형 데이터베이스 | 5개 도메인 DB |
| **ElastiCache (Redis)** | 관리형 캐시 | Watch 캐시 |
| **MSK (Kafka)** | 관리형 Kafka | 이벤트 메시징 |
| **ECR** | 컨테이너 레지스트리 | Docker 이미지 저장 |
| **ALB** | 애플리케이션 로드밸런서 | Ingress 트래픽 분산 |
| **EBS** | 블록 스토리지 | PersistentVolume |
| **CloudWatch** | 모니터링 | 로그 및 메트릭 수집 |
| **Route53** | DNS | 도메인 관리 |
| **VPC** | 네트워크 | 격리된 네트워크 환경 |

---

## 2. 아키텍처

### 2-1. AWS 인프라 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                       Route 53                              │
│              (biddy.example.com)                            │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│                  Application Load Balancer                  │
│              (Ingress Controller 연동)                       │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│                      VPC (10.0.0.0/16)                      │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │              EKS Cluster (v1.28)                      │ │
│  │                                                       │ │
│  │  ┌──────────────────────────────────────────────┐   │ │
│  │  │   Public Subnet (10.0.1.0/24, 10.0.2.0/24)   │   │ │
│  │  │        - ALB                                  │   │ │
│  │  │        - NAT Gateway                          │   │ │
│  │  └──────────────────────────────────────────────┘   │ │
│  │                                                       │ │
│  │  ┌──────────────────────────────────────────────┐   │ │
│  │  │  Private Subnet (10.0.10.0/24, 10.0.11.0/24) │   │ │
│  │  │                                              │   │ │
│  │  │  [Worker Nodes (t3.medium x 3)]              │   │ │
│  │  │    - API Gateway Pod (x2)                    │   │ │
│  │  │    - Member Pod (x2)                         │   │ │
│  │  │    - Product Pod (x2)                        │   │ │
│  │  │    - Order Pod (x2)                          │   │ │
│  │  │    - Auction Pod (x3)                        │   │ │
│  │  │    - Payment Pod (x2)                        │   │ │
│  │  │    - Discovery Pod (x1)                      │   │ │
│  │  │    - Config Pod (x1)                         │   │ │
│  │  └──────────────────────────────────────────────┘   │ │
│  │                                                       │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │   Private Subnet (10.0.20.0/24, 10.0.21.0/24)        │  │
│  │                                                       │  │
│  │   - RDS PostgreSQL (Multi-AZ)                        │  │
│  │   - ElastiCache Redis (Cluster Mode)                 │  │
│  │   - MSK Kafka (3 brokers)                            │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 2-2. 가용 영역(AZ) 구성

```
┌────────────────────────────────────────────────────────┐
│                     Region: ap-northeast-2 (Seoul)     │
│                                                        │
│  ┌──────────────────┐  ┌──────────────────┐          │
│  │   AZ-A (2a)      │  │   AZ-C (2c)      │          │
│  │                  │  │                  │          │
│  │ - Worker Node 1  │  │ - Worker Node 2  │          │
│  │ - RDS Primary    │  │ - RDS Standby    │          │
│  │ - Redis Node 1   │  │ - Redis Node 2   │          │
│  │ - Kafka Broker 1 │  │ - Kafka Broker 2 │          │
│  └──────────────────┘  └──────────────────┘          │
│                                                        │
│           ┌──────────────────┐                        │
│           │   AZ-B (2b)      │                        │
│           │                  │                        │
│           │ - Worker Node 3  │                        │
│           │ - Kafka Broker 3 │                        │
│           └──────────────────┘                        │
└────────────────────────────────────────────────────────┘
```

---

## 3. 사전 준비

### 3-1. AWS CLI 설치 및 설정

```bash
# AWS CLI 설치
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# 인증 설정
aws configure
# AWS Access Key ID: YOUR_ACCESS_KEY
# AWS Secret Access Key: YOUR_SECRET_KEY
# Default region: ap-northeast-2
# Default output format: json

# 확인
aws sts get-caller-identity
```

### 3-2. kubectl 및 eksctl 설치

```bash
# kubectl 설치
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# eksctl 설치 (EKS 클러스터 관리 도구)
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# 버전 확인
kubectl version --client
eksctl version
```

### 3-3. IAM 권한 설정

필요한 IAM 권한:
- `AmazonEKSClusterPolicy`
- `AmazonEKSWorkerNodePolicy`
- `AmazonEC2ContainerRegistryReadOnly`
- `AmazonEKS_CNI_Policy`

---

## 4. EKS 클러스터 생성

### 4-1. eksctl로 클러스터 생성

```yaml
# eks-cluster-config.yaml
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: biddy-eks-cluster
  region: ap-northeast-2
  version: "1.28"

vpc:
  cidr: 10.0.0.0/16
  nat:
    gateway: Single  # 비용 절감 시 Single, 고가용성 시 HighlyAvailable

availabilityZones:
  - ap-northeast-2a
  - ap-northeast-2c

managedNodeGroups:
  - name: biddy-workers
    instanceType: t3.medium
    desiredCapacity: 3
    minSize: 2
    maxSize: 6
    volumeSize: 30
    ssh:
      allow: true
    privateNetworking: true
    iam:
      withAddonPolicies:
        imageBuilder: true
        autoScaler: true
        ebs: true
        efs: true
        albIngress: true
        cloudWatch: true

addons:
  - name: vpc-cni
  - name: coredns
  - name: kube-proxy

cloudWatch:
  clusterLogging:
    enableTypes:
      - api
      - audit
      - authenticator
      - controllerManager
      - scheduler
```

```bash
# 클러스터 생성 (약 15~20분 소요)
eksctl create cluster -f eks-cluster-config.yaml

# 클러스터 확인
kubectl get nodes
kubectl cluster-info

# 컨텍스트 확인
kubectl config current-context
# 출력: your-username@biddy-eks-cluster.ap-northeast-2.eksctl.io
```

### 4-2. AWS Load Balancer Controller 설치

```bash
# IAM Policy 생성
curl -o iam-policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.7.0/docs/install/iam_policy.json

aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam-policy.json

# OIDC Provider 생성
eksctl utils associate-iam-oidc-provider \
  --region ap-northeast-2 \
  --cluster biddy-eks-cluster \
  --approve

# Service Account 생성
eksctl create iamserviceaccount \
  --cluster=biddy-eks-cluster \
  --namespace=kube-system \
  --name=aws-load-balancer-controller \
  --attach-policy-arn=arn:aws:iam::YOUR_ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy \
  --override-existing-serviceaccounts \
  --region ap-northeast-2 \
  --approve

# Helm으로 설치
helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=biddy-eks-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

# 확인
kubectl get deployment -n kube-system aws-load-balancer-controller
```

### 4-3. EBS CSI Driver 설치 (PersistentVolume용)

```bash
# IAM Role 생성
eksctl create iamserviceaccount \
  --name ebs-csi-controller-sa \
  --namespace kube-system \
  --cluster biddy-eks-cluster \
  --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
  --approve \
  --role-name AmazonEKS_EBS_CSI_DriverRole

# EBS CSI Driver 설치
eksctl create addon \
  --name aws-ebs-csi-driver \
  --cluster biddy-eks-cluster \
  --service-account-role-arn arn:aws:iam::YOUR_ACCOUNT_ID:role/AmazonEKS_EBS_CSI_DriverRole \
  --force

# 확인
kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-ebs-csi-driver
```

---

## 5. RDS PostgreSQL 생성

### 5-1. RDS Subnet Group 생성

```bash
aws rds create-db-subnet-group \
  --db-subnet-group-name biddy-db-subnet-group \
  --db-subnet-group-description "Biddy RDS Subnet Group" \
  --subnet-ids subnet-xxxxx subnet-yyyyy \
  --tags Key=Name,Value=biddy-db-subnet-group
```

### 5-2. Security Group 생성

```bash
# RDS 보안 그룹 생성
aws ec2 create-security-group \
  --group-name biddy-rds-sg \
  --description "Biddy RDS Security Group" \
  --vpc-id vpc-xxxxx

# EKS Worker Node에서 접근 허용
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 5432 \
  --source-group sg-yyyyy  # EKS Worker Node SG
```

### 5-3. RDS PostgreSQL 인스턴스 생성

```bash
# RDS 생성 (Multi-AZ, db.t3.medium)
aws rds create-db-instance \
  --db-instance-identifier biddy-postgres \
  --db-instance-class db.t3.medium \
  --engine postgres \
  --engine-version 16.1 \
  --master-username biddy \
  --master-user-password 'YourSecurePassword123!' \
  --allocated-storage 100 \
  --storage-type gp3 \
  --storage-encrypted \
  --multi-az \
  --db-subnet-group-name biddy-db-subnet-group \
  --vpc-security-group-ids sg-xxxxx \
  --backup-retention-period 7 \
  --preferred-backup-window "03:00-04:00" \
  --preferred-maintenance-window "mon:04:00-mon:05:00" \
  --enable-cloudwatch-logs-exports '["postgresql"]' \
  --tags Key=Name,Value=biddy-postgres

# RDS 엔드포인트 확인 (약 10분 후)
aws rds describe-db-instances \
  --db-instance-identifier biddy-postgres \
  --query 'DBInstances[0].Endpoint.Address' \
  --output text
# 출력 예: biddy-postgres.xxxxx.ap-northeast-2.rds.amazonaws.com
```

### 5-4. RDS 데이터베이스 초기화

```bash
# PostgreSQL 클라이언트 설치
sudo apt-get install postgresql-client

# RDS 접속
export RDS_ENDPOINT="biddy-postgres.xxxxx.ap-northeast-2.rds.amazonaws.com"
psql -h $RDS_ENDPOINT -U biddy -d postgres

# 데이터베이스 생성
CREATE DATABASE biddy_member;
CREATE DATABASE biddy_product;
CREATE DATABASE biddy_order;
CREATE DATABASE biddy_auction;
CREATE DATABASE biddy_payment;

# 확인
\l
\q
```

---

## 6. ElastiCache Redis 생성

### 6-1. ElastiCache Subnet Group 생성

```bash
aws elasticache create-cache-subnet-group \
  --cache-subnet-group-name biddy-redis-subnet-group \
  --cache-subnet-group-description "Biddy Redis Subnet Group" \
  --subnet-ids subnet-xxxxx subnet-yyyyy
```

### 6-2. Security Group 생성

```bash
# Redis 보안 그룹
aws ec2 create-security-group \
  --group-name biddy-redis-sg \
  --description "Biddy Redis Security Group" \
  --vpc-id vpc-xxxxx

# EKS Worker Node에서 접근 허용
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 6379 \
  --source-group sg-yyyyy  # EKS Worker Node SG
```

### 6-3. ElastiCache Redis 클러스터 생성

```bash
# Redis 클러스터 생성 (cache.t3.small, Cluster Mode Disabled)
aws elasticache create-replication-group \
  --replication-group-id biddy-redis \
  --replication-group-description "Biddy Redis Cluster" \
  --engine redis \
  --engine-version 7.0 \
  --cache-node-type cache.t3.small \
  --num-cache-clusters 2 \
  --cache-subnet-group-name biddy-redis-subnet-group \
  --security-group-ids sg-xxxxx \
  --at-rest-encryption-enabled \
  --transit-encryption-enabled \
  --automatic-failover-enabled

# Redis 엔드포인트 확인
aws elasticache describe-replication-groups \
  --replication-group-id biddy-redis \
  --query 'ReplicationGroups[0].NodeGroups[0].PrimaryEndpoint.Address' \
  --output text
# 출력 예: biddy-redis.xxxxx.ng.0001.apne2.cache.amazonaws.com
```

---

## 7. MSK (Managed Kafka) 생성

### 7-1. MSK 클러스터 생성

```bash
# MSK 설정 파일 생성
cat > msk-cluster-config.json <<EOF
{
  "ClusterName": "biddy-kafka",
  "KafkaVersion": "3.5.1",
  "NumberOfBrokerNodes": 3,
  "BrokerNodeGroupInfo": {
    "InstanceType": "kafka.t3.small",
    "ClientSubnets": [
      "subnet-xxxxx",
      "subnet-yyyyy",
      "subnet-zzzzz"
    ],
    "SecurityGroups": ["sg-xxxxx"],
    "StorageInfo": {
      "EbsStorageInfo": {
        "VolumeSize": 100
      }
    }
  },
  "EncryptionInfo": {
    "EncryptionInTransit": {
      "ClientBroker": "TLS_PLAINTEXT",
      "InCluster": true
    }
  },
  "EnhancedMonitoring": "DEFAULT",
  "OpenMonitoring": {
    "Prometheus": {
      "JmxExporter": {
        "EnabledInBroker": true
      },
      "NodeExporter": {
        "EnabledInBroker": true
      }
    }
  }
}
EOF

# MSK 클러스터 생성 (약 20~30분 소요)
aws kafka create-cluster --cli-input-json file://msk-cluster-config.json

# 클러스터 ARN 확인
aws kafka list-clusters --query 'ClusterInfoList[0].ClusterArn' --output text

# Bootstrap 서버 확인
aws kafka get-bootstrap-brokers --cluster-arn arn:aws:kafka:ap-northeast-2:ACCOUNT_ID:cluster/biddy-kafka/xxxxx
# 출력 예: b-1.biddy-kafka.xxxxx.c2.kafka.ap-northeast-2.amazonaws.com:9092,b-2...
```

---

## 8. ECR (Elastic Container Registry) 설정

### 8-1. ECR 리포지토리 생성

```bash
# 각 서비스별 리포지토리 생성
for service in discovery config apigateway member product order auction payment; do
  aws ecr create-repository \
    --repository-name biddy/$service \
    --region ap-northeast-2 \
    --image-scanning-configuration scanOnPush=true
done

# 확인
aws ecr describe-repositories --query 'repositories[*].repositoryUri' --output table
```

### 8-2. Docker 이미지 빌드 및 푸시

```bash
# ECR 로그인
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com

# 프로젝트 빌드
./gradlew clean build -x test

# 이미지 빌드 및 푸시
export ECR_REGISTRY="YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com"
export VERSION="v1.0.0"

# Discovery
docker build -t $ECR_REGISTRY/biddy/discovery:$VERSION ./discovery
docker push $ECR_REGISTRY/biddy/discovery:$VERSION

# API Gateway
docker build -t $ECR_REGISTRY/biddy/apigateway:$VERSION ./apigateway
docker push $ECR_REGISTRY/biddy/apigateway:$VERSION

# Auction
docker build -t $ECR_REGISTRY/biddy/auction:$VERSION ./auction
docker push $ECR_REGISTRY/biddy/auction:$VERSION

# ... (나머지 서비스도 동일)

# 이미지 확인
aws ecr describe-images --repository-name biddy/auction
```

---

## 9. Kubernetes 리소스 배포

### 9-1. 네임스페이스 및 ConfigMap

```yaml
# k8s-aws/namespaces.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: biddy-services
---
apiVersion: v1
kind: Namespace
metadata:
  name: biddy-gateway
```

```yaml
# k8s-aws/config/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: biddy-common-config
  namespace: biddy-services
data:
  TZ: "Asia/Seoul"
  EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE: "http://discovery.biddy-services.svc.cluster.local:8761/eureka/"
  KAFKA_BOOTSTRAP_SERVERS: "b-1.biddy-kafka.xxxxx.c2.kafka.ap-northeast-2.amazonaws.com:9092,b-2.biddy-kafka.xxxxx.c2.kafka.ap-northeast-2.amazonaws.com:9092"
  POSTGRES_HOST: "biddy-postgres.xxxxx.ap-northeast-2.rds.amazonaws.com"
  POSTGRES_PORT: "5432"
  REDIS_HOST: "biddy-redis.xxxxx.ng.0001.apne2.cache.amazonaws.com"
  REDIS_PORT: "6379"
```

```yaml
# k8s-aws/config/secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: biddy-secrets
  namespace: biddy-services
type: Opaque
stringData:
  POSTGRES_USER: "biddy"
  POSTGRES_PASSWORD: "YourSecurePassword123!"
  JWT_SECRET: "devcourse6devcourse6devcourse6devcourse6"
  MAIL_USERNAME: "your_email@gmail.com"
  MAIL_PASSWORD: "your_app_password"
  TOSS_PAYMENTS_SECRET_KEY: "test_sk_xxxxx"
```

### 9-2. Discovery 배포

```yaml
# k8s-aws/services/discovery.yaml
apiVersion: v1
kind: Service
metadata:
  name: discovery
  namespace: biddy-services
spec:
  selector:
    app: discovery
  ports:
    - port: 8761
      targetPort: 8761
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: discovery
  namespace: biddy-services
spec:
  replicas: 1
  selector:
    matchLabels:
      app: discovery
  template:
    metadata:
      labels:
        app: discovery
    spec:
      containers:
      - name: discovery
        image: YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/discovery:v1.0.0
        ports:
        - containerPort: 8761
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8761
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8761
          initialDelaySeconds: 30
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
```

### 9-3. Auction 서비스 배포 (RDS, Redis, MSK 연동)

```yaml
# k8s-aws/services/auction.yaml
apiVersion: v1
kind: Service
metadata:
  name: auction
  namespace: biddy-services
spec:
  selector:
    app: auction
  ports:
    - port: 8084
      targetPort: 8084
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: auction
  namespace: biddy-services
spec:
  replicas: 3
  selector:
    matchLabels:
      app: auction
  template:
    metadata:
      labels:
        app: auction
    spec:
      containers:
      - name: auction
        image: YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/auction:v1.0.0
        ports:
        - containerPort: 8084
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
        - name: KAFKA_BOOTSTRAP_SERVERS
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: KAFKA_BOOTSTRAP_SERVERS
        - name: POSTGRES_HOST
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: POSTGRES_HOST
        - name: POSTGRES_PORT
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: POSTGRES_PORT
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: POSTGRES_USER
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: POSTGRES_PASSWORD
        - name: AUCTION_DB
          value: "biddy_auction"
        - name: REDIS_HOST
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: REDIS_HOST
        - name: REDIS_PORT
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: REDIS_PORT
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: JWT_SECRET
        - name: TZ
          value: "Asia/Seoul"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8084
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8084
          initialDelaySeconds: 60
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: auction-hpa
  namespace: biddy-services
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: auction
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### 9-4. API Gateway 배포 (ALB 연동)

```yaml
# k8s-aws/gateway/apigateway.yaml
apiVersion: v1
kind: Service
metadata:
  name: apigateway
  namespace: biddy-gateway
spec:
  selector:
    app: apigateway
  ports:
    - port: 8000
      targetPort: 8000
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apigateway
  namespace: biddy-gateway
spec:
  replicas: 2
  selector:
    matchLabels:
      app: apigateway
  template:
    metadata:
      labels:
        app: apigateway
    spec:
      containers:
      - name: apigateway
        image: YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/apigateway:v1.0.0
        ports:
        - containerPort: 8000
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          value: "http://discovery.biddy-services.svc.cluster.local:8761/eureka/"
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: JWT_SECRET
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8000
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8000
          initialDelaySeconds: 60
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
```

### 9-5. Ingress (ALB 자동 생성)

```yaml
# k8s-aws/gateway/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: biddy-ingress
  namespace: biddy-gateway
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/healthcheck-path: /actuator/health
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}, {"HTTPS": 443}]'
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:ap-northeast-2:ACCOUNT_ID:certificate/xxxxx  # ACM 인증서
    alb.ingress.kubernetes.io/ssl-redirect: '443'
spec:
  ingressClassName: alb
  rules:
  - host: api.biddy.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: apigateway
            port:
              number: 8000
```

### 9-6. 배포 실행

```bash
# 네임스페이스
kubectl apply -f k8s-aws/namespaces.yaml

# ConfigMap 및 Secret
kubectl apply -f k8s-aws/config/

# 서비스 배포
kubectl apply -f k8s-aws/services/discovery.yaml
kubectl apply -f k8s-aws/services/config.yaml
sleep 30

# 도메인 서비스
kubectl apply -f k8s-aws/services/member.yaml
kubectl apply -f k8s-aws/services/product.yaml
kubectl apply -f k8s-aws/services/order.yaml
kubectl apply -f k8s-aws/services/auction.yaml
kubectl apply -f k8s-aws/services/payment.yaml

# Gateway
kubectl apply -f k8s-aws/gateway/apigateway.yaml
kubectl apply -f k8s-aws/gateway/ingress.yaml

# 확인
kubectl get pods --all-namespaces
kubectl get svc --all-namespaces
kubectl get ingress -n biddy-gateway
```

---

## 10. Route53 DNS 설정

### 10-1. ALB 도메인 연결

```bash
# Ingress에서 ALB DNS 확인
kubectl get ingress -n biddy-gateway -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}'
# 출력 예: k8s-biddygat-biddying-xxxxx.ap-northeast-2.elb.amazonaws.com

# Route53에서 A 레코드 생성 (Alias)
aws route53 change-resource-record-sets \
  --hosted-zone-id Z1234567890ABC \
  --change-batch '{
    "Changes": [{
      "Action": "CREATE",
      "ResourceRecordSet": {
        "Name": "api.biddy.example.com",
        "Type": "A",
        "AliasTarget": {
          "HostedZoneId": "Z3W03O7B5YMIYP",
          "DNSName": "k8s-biddygat-biddying-xxxxx.ap-northeast-2.elb.amazonaws.com",
          "EvaluateTargetHealth": false
        }
      }
    }]
  }'

# DNS 확인 (전파 시간 5~10분)
dig api.biddy.example.com
curl https://api.biddy.example.com/actuator/health
```

---

## 11. CloudWatch 모니터링

### 11-1. Container Insights 활성화

```bash
# CloudWatch Agent 설치
kubectl apply -f https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/quickstart/cwagent-fluentd-quickstart.yaml

# 확인
kubectl get pods -n amazon-cloudwatch
```

### 11-2. CloudWatch 대시보드

AWS 콘솔 → CloudWatch → Container Insights에서 다음 확인:
- Pod CPU/Memory 사용률
- Node 리소스 사용률
- 서비스별 요청 수 및 응답 시간
- 로그 스트림 (Pod별)

---

## 12. 비용 최적화

### 12-1. 비용 절감 전략

| 항목 | 개발 환경 | 프로덕션 환경 |
|------|----------|-------------|
| **EKS Worker Node** | t3.medium x 2 | t3.medium x 3~6 + Spot Instance |
| **RDS** | db.t3.small (Single-AZ) | db.t3.medium (Multi-AZ) |
| **ElastiCache** | cache.t3.micro | cache.t3.small (Cluster) |
| **MSK** | kafka.t3.small x 2 | kafka.m5.large x 3 |
| **NAT Gateway** | Single NAT | HA (Multi-AZ) |
| **ALB** | 1개 | 1개 (충분) |

### 12-2. 월별 예상 비용 (ap-northeast-2)

#### 개발 환경
```
- EKS Control Plane: $73
- EC2 (t3.medium x 2): $60
- RDS (db.t3.small): $50
- ElastiCache (cache.t3.micro): $15
- MSK (kafka.t3.small x 2): $120
- NAT Gateway: $32
- ALB: $22
- EBS (100GB): $10
──────────────────────────
합계: 약 $382/월
```

#### 프로덕션 환경
```
- EKS Control Plane: $73
- EC2 (t3.medium x 3): $90
- RDS (db.t3.medium, Multi-AZ): $150
- ElastiCache (cache.t3.small, Cluster): $60
- MSK (kafka.t3.small x 3): $180
- NAT Gateway (HA): $64
- ALB: $22
- EBS (300GB): $30
- CloudWatch: $20
──────────────────────────
합계: 약 $689/월
```

### 12-3. 비용 절감 팁

```bash
# Spot Instance 사용 (최대 90% 절감)
eksctl create nodegroup \
  --cluster biddy-eks-cluster \
  --name biddy-spot-workers \
  --instance-types t3.medium,t3a.medium \
  --spot \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 5

# RDS 예약 인스턴스 (1년 약정 시 40% 절감)
# ElastiCache 예약 노드 (1년 약정 시 30% 절감)

# MSK 대신 자체 Kafka StatefulSet 사용 (개발 환경)
# - 월 $120 절감

# NAT Gateway 대신 NAT Instance (비추천, 관리 복잡)
```

---

## 13. 운영 가이드

### 13-1. 클러스터 스케일링

```bash
# Worker Node 수동 스케일링
eksctl scale nodegroup \
  --cluster biddy-eks-cluster \
  --name biddy-workers \
  --nodes 5

# Cluster Autoscaler 설치 (자동 스케일링)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/autoscaler/master/cluster-autoscaler/cloudprovider/aws/examples/cluster-autoscaler-autodiscover.yaml

# 설정 수정
kubectl -n kube-system edit deployment cluster-autoscaler
# --node-group-auto-discovery=asg:tag=k8s.io/cluster-autoscaler/enabled,k8s.io/cluster-autoscaler/biddy-eks-cluster
```

### 13-2. 롤링 업데이트

```bash
# 새 이미지 빌드 및 푸시
docker build -t $ECR_REGISTRY/biddy/auction:v1.1.0 ./auction
docker push $ECR_REGISTRY/biddy/auction:v1.1.0

# Deployment 이미지 업데이트
kubectl set image deployment/auction \
  auction=$ECR_REGISTRY/biddy/auction:v1.1.0 \
  -n biddy-services

# 롤아웃 상태 확인
kubectl rollout status deployment/auction -n biddy-services

# 롤백 (문제 발생 시)
kubectl rollout undo deployment/auction -n biddy-services
```

### 13-3. RDS 백업 및 복구

```bash
# 수동 스냅샷 생성
aws rds create-db-snapshot \
  --db-instance-identifier biddy-postgres \
  --db-snapshot-identifier biddy-postgres-snapshot-$(date +%Y%m%d)

# 스냅샷에서 복구
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier biddy-postgres-restored \
  --db-snapshot-identifier biddy-postgres-snapshot-20260702

# 자동 백업 확인
aws rds describe-db-snapshots \
  --db-instance-identifier biddy-postgres
```

### 13-4. 로그 확인

```bash
# Pod 로그
kubectl logs -f deployment/auction -n biddy-services

# CloudWatch Logs Insights 쿼리
# AWS 콘솔 → CloudWatch → Logs Insights
fields @timestamp, @message
| filter kubernetes.namespace_name = "biddy-services"
| filter kubernetes.container_name = "auction"
| filter @message like /ERROR/
| sort @timestamp desc
| limit 100
```

---

## 14. 보안 설정

### 14-1. IAM Role for Service Account (IRSA)

```bash
# S3 접근 권한이 필요한 경우 (이미지 업로드)
eksctl create iamserviceaccount \
  --name product-sa \
  --namespace biddy-services \
  --cluster biddy-eks-cluster \
  --attach-policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess \
  --approve

# Deployment에 ServiceAccount 추가
spec:
  template:
    spec:
      serviceAccountName: product-sa
```

### 14-2. Network Policy

```yaml
# k8s-aws/network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-from-gateway
  namespace: biddy-services
spec:
  podSelector:
    matchLabels:
      app: auction
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: biddy-gateway
    ports:
    - protocol: TCP
      port: 8084
```

### 14-3. Secret 암호화 (KMS)

```bash
# KMS 키 생성
aws kms create-key --description "EKS Secret Encryption"

# EKS 클러스터에 암호화 적용
aws eks associate-encryption-config \
  --cluster-name biddy-eks-cluster \
  --encryption-config '[{"resources":["secrets"],"provider":{"keyArn":"arn:aws:kms:ap-northeast-2:ACCOUNT_ID:key/xxxxx"}}]'
```

---

## 15. 트러블슈팅

### 15-1. Pod가 Pending 상태

```bash
# 이유 확인
kubectl describe pod <pod-name> -n biddy-services
# 원인: Insufficient CPU/Memory, Node Selector 불일치

# 해결: Worker Node 추가 또는 리소스 제한 조정
eksctl scale nodegroup --cluster biddy-eks-cluster --name biddy-workers --nodes 5
```

### 15-2. RDS 연결 실패

```bash
# Security Group 확인
aws ec2 describe-security-groups --group-ids sg-xxxxx

# EKS Worker Node SG 확인
kubectl get nodes -o jsonpath='{.items[0].spec.providerID}' | cut -d'/' -f5
aws ec2 describe-instances --instance-ids i-xxxxx --query 'Reservations[0].Instances[0].SecurityGroups'

# RDS SG에 EKS Worker Node SG 추가
aws ec2 authorize-security-group-ingress \
  --group-id <rds-sg> \
  --protocol tcp \
  --port 5432 \
  --source-group <eks-worker-sg>
```

### 15-3. ALB Health Check 실패

```bash
# Target Group 확인
aws elbv2 describe-target-health --target-group-arn <tg-arn>

# Health Check 경로 확인
kubectl get ingress -n biddy-gateway -o yaml
# alb.ingress.kubernetes.io/healthcheck-path: /actuator/health

# Pod Health Check 확인
kubectl get pods -n biddy-gateway -o yaml | grep -A5 readinessProbe
```

---

## 16. CI/CD with GitHub Actions

```yaml
# .github/workflows/deploy-eks.yml
name: Deploy to AWS EKS

on:
  push:
    branches: [main]

env:
  AWS_REGION: ap-northeast-2
  ECR_REGISTRY: YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com
  EKS_CLUSTER: biddy-eks-cluster

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3

    - name: Configure AWS credentials
      uses: aws-actions/configure-aws-credentials@v2
      with:
        aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
        aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
        aws-region: ${{ env.AWS_REGION }}

    - name: Login to Amazon ECR
      id: login-ecr
      uses: aws-actions/amazon-ecr-login@v1

    - name: Build and push Docker images
      run: |
        ./gradlew clean build -x test

        docker build -t $ECR_REGISTRY/biddy/auction:${{ github.sha }} ./auction
        docker push $ECR_REGISTRY/biddy/auction:${{ github.sha }}

        docker tag $ECR_REGISTRY/biddy/auction:${{ github.sha }} $ECR_REGISTRY/biddy/auction:latest
        docker push $ECR_REGISTRY/biddy/auction:latest

    - name: Update kubeconfig
      run: |
        aws eks update-kubeconfig --name $EKS_CLUSTER --region $AWS_REGION

    - name: Deploy to EKS
      run: |
        kubectl set image deployment/auction \
          auction=$ECR_REGISTRY/biddy/auction:${{ github.sha }} \
          -n biddy-services

        kubectl rollout status deployment/auction -n biddy-services
```

---

## 17. 클러스터 삭제

```bash
# EKS 클러스터 삭제 (모든 리소스 삭제)
eksctl delete cluster --name biddy-eks-cluster --region ap-northeast-2

# RDS 삭제
aws rds delete-db-instance \
  --db-instance-identifier biddy-postgres \
  --skip-final-snapshot

# ElastiCache 삭제
aws elasticache delete-replication-group \
  --replication-group-id biddy-redis \
  --no-retain-primary-cluster

# MSK 삭제
aws kafka delete-cluster --cluster-arn <cluster-arn>

# ECR 이미지 삭제
for repo in discovery config apigateway member product order auction payment; do
  aws ecr delete-repository --repository-name biddy/$repo --force
done
```

---

## 18. 참고 자료

- [AWS EKS 공식 문서](https://docs.aws.amazon.com/eks/)
- [eksctl 공식 문서](https://eksctl.io/)
- [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/)
- [RDS Best Practices](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_BestPractices.html)
- [MSK Developer Guide](https://docs.aws.amazon.com/msk/latest/developerguide/what-is-msk.html)
- [EKS Workshop](https://www.eksworkshop.com/)

---

**문서 버전**: 1.0
**최종 수정일**: 2026-07-02
**작성자**: Biddy Dev Team
