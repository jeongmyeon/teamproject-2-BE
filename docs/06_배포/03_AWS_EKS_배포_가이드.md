# Biddy K8s 2-Node 클러스터 배포 가이드

## 문서 정보
- **프로젝트**: Biddy 실시간 경매 플랫폼
- **버전**: 2.0
- **작성일**: 2026-07-07
- **대상**: AWS EC2 기반 Self-managed Kubernetes (2 nodes)
- **구성**: t3.large (마스터+워커) + t3.medium (워커)

---

## 1. 아키텍처 개요

### 1-1. 인프라 구성

```
┌─────────────────────────────────────────────────────────────┐
│                    AWS VPC (10.0.0.0/16)                    │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           Application Load Balancer (ALB)            │  │
│  │                  (Public Subnet)                      │  │
│  └────────────────────┬─────────────────────────────────┘  │
│                       │                                     │
│  ┌────────────────────▼─────────────────────────────────┐  │
│  │              Private Subnet (10.0.1.0/24)            │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────┐    │  │
│  │  │  Node 1 (t3.large - Master + Worker)       │    │  │
│  │  │  - Control Plane (API Server, etcd, etc)   │    │  │
│  │  │  - Worker Pods:                            │    │  │
│  │  │    * API Gateway (x2)                      │    │  │
│  │  │    * Auction (x2)                          │    │  │
│  │  │    * Discovery (x1)                        │    │  │
│  │  │    * PostgreSQL (StatefulSet)              │    │  │
│  │  │    * Kafka (StatefulSet)                   │    │  │
│  │  └─────────────────────────────────────────────┘    │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────┐    │  │
│  │  │  Node 2 (t3.medium - Worker)               │    │  │
│  │  │  - Worker Pods:                            │    │  │
│  │  │    * Member (x2)                           │    │  │
│  │  │    * Product (x2)                          │    │  │
│  │  │    * Order (x2)                            │    │  │
│  │  │    * Payment (x2)                          │    │  │
│  │  │    * Redis (StatefulSet)                   │    │  │
│  │  └─────────────────────────────────────────────┘    │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 1-2. 노드 리소스 배분

| 노드 | 인스턴스 타입 | vCPU | RAM | 역할 | 주요 워크로드 |
|------|--------------|------|-----|------|--------------|
| **Node 1** | t3.large | 2 | 8GB | Master + Worker | Control Plane + 무거운 서비스 (Auction, Kafka, PostgreSQL) |
| **Node 2** | t3.medium | 2 | 4GB | Worker | 경량 서비스 (Member, Product, Order, Payment, Redis) |

### 1-3. Pod 배치 전략

| 서비스 | Replicas | 배치 노드 | 이유 |
|--------|----------|-----------|------|
| API Gateway | 2 | Node 1 | 트래픽 집중, 메모리 여유 필요 |
| Auction | 2 | Node 1 | 실시간 입찰, WebSocket 연결 많음 |
| Member | 2 | Node 2 | 상대적으로 경량 |
| Product | 2 | Node 2 | 상대적으로 경량 |
| Order | 2 | Node 2 | 상대적으로 경량 |
| Payment | 2 | Node 2 | 상대적으로 경량 |
| Discovery | 1 | Node 1 | 단일 인스턴스, 안정성 중요 |
| PostgreSQL | 1 | Node 1 | Stateful, 로컬 디스크 사용 |
| Kafka | 1 | Node 1 | Stateful, 메모리 많이 사용 |
| Redis | 1 | Node 2 | 메모리 사용량 적음 |

---

## 2. AWS 인프라 사전 준비 (AWS 담당자)

### 2-1. VPC 및 서브넷 구성

```bash
# VPC 생성
aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --region ap-northeast-2 \
  --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=biddy-vpc}]'

# 퍼블릭 서브넷 (ALB용)
aws ec2 create-subnet \
  --vpc-id vpc-xxxxx \
  --cidr-block 10.0.0.0/24 \
  --availability-zone ap-northeast-2a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=biddy-public-subnet}]'

# 프라이빗 서브넷 (K8s 노드용)
aws ec2 create-subnet \
  --vpc-id vpc-xxxxx \
  --cidr-block 10.0.1.0/24 \
  --availability-zone ap-northeast-2a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=biddy-private-subnet}]'

# 인터넷 게이트웨이
aws ec2 create-internet-gateway \
  --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=biddy-igw}]'

aws ec2 attach-internet-gateway \
  --vpc-id vpc-xxxxx \
  --internet-gateway-id igw-xxxxx

# NAT Gateway (프라이빗 서브넷 외부 통신용)
aws ec2 allocate-address --domain vpc
aws ec2 create-nat-gateway \
  --subnet-id subnet-xxxxx \
  --allocation-id eipalloc-xxxxx
```

### 2-2. 보안 그룹 생성

```bash
# K8s 노드 보안 그룹
aws ec2 create-security-group \
  --group-name biddy-k8s-nodes \
  --description "Biddy K8s Nodes Security Group" \
  --vpc-id vpc-xxxxx

# 노드 간 통신 허용 (모든 포트)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol all \
  --source-group sg-xxxxx

# SSH 접근 (관리자 IP만)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 22 \
  --cidr YOUR_IP/32

# Kubernetes API Server (6443)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 6443 \
  --cidr 10.0.0.0/16

# NodePort 범위 (30000-32767)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 30000-32767 \
  --cidr 0.0.0.0/0
```

### 2-3. EC2 인스턴스 생성

```bash
# 마스터 노드 (t3.large)
aws ec2 run-instances \
  --image-id ami-0c9c942bd7bf113a2 \
  --instance-type t3.large \
  --key-name your-key-pair \
  --security-group-ids sg-xxxxx \
  --subnet-id subnet-xxxxx \
  --private-ip-address 10.0.1.10 \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":50,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=biddy-k8s-master},{Key=Role,Value=master}]'

# 워커 노드 (t3.medium)
aws ec2 run-instances \
  --image-id ami-0c9c942bd7bf113a2 \
  --instance-type t3.medium \
  --key-name your-key-pair \
  --security-group-ids sg-xxxxx \
  --subnet-id subnet-xxxxx \
  --private-ip-address 10.0.1.11 \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=biddy-k8s-worker1},{Key=Role,Value=worker}]'
```

---

## 3. Kubernetes 클러스터 구성 (AWS 담당자)

### 3-1. 공통 설정 (모든 노드)

```bash
# 모든 노드에서 실행
ssh -i your-key.pem ubuntu@<NODE_IP>

# 시스템 업데이트
sudo apt-get update && sudo apt-get upgrade -y

# 컨테이너 런타임 설치 (containerd)
sudo apt-get install -y containerd
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml
sudo systemctl restart containerd
sudo systemctl enable containerd

# Kubernetes 패키지 설치
sudo apt-get install -y apt-transport-https ca-certificates curl
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /' | sudo tee /etc/apt/sources.list.d/kubernetes.list

sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl

# 스왑 비활성화 (Kubernetes 요구사항)
sudo swapoff -a
sudo sed -i '/ swap / s/^/#/' /etc/fstab

# 커널 모듈 로드
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter

# sysctl 설정
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sudo sysctl --system
```

### 3-2. 마스터 노드 초기화 (Node 1 - t3.large)

```bash
# 마스터 노드에서만 실행
sudo kubeadm init \
  --pod-network-cidr=192.168.0.0/16 \
  --apiserver-advertise-address=10.0.1.10 \
  --node-name=biddy-k8s-master

# 출력된 join 명령어를 복사해두세요!
# kubeadm join 10.0.1.10:6443 --token xxxxx --discovery-token-ca-cert-hash sha256:xxxxx

# kubectl 설정
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config

# CNI 플러그인 설치 (Calico)
kubectl apply -f https://docs.projectcalico.org/manifests/calico.yaml

# 마스터 노드도 워커로 사용 (taint 제거)
kubectl taint nodes biddy-k8s-master node-role.kubernetes.io/control-plane:NoSchedule-

# 노드 확인
kubectl get nodes
```

### 3-3. 워커 노드 조인 (Node 2 - t3.medium)

```bash
# 워커 노드에서 실행
sudo kubeadm join 10.0.1.10:6443 \
  --token xxxxx \
  --discovery-token-ca-cert-hash sha256:xxxxx \
  --node-name=biddy-k8s-worker1

# 마스터 노드에서 확인
kubectl get nodes
# NAME                STATUS   ROLES           AGE   VERSION
# biddy-k8s-master    Ready    control-plane   5m    v1.28.x
# biddy-k8s-worker1   Ready    <none>          1m    v1.28.x
```

### 3-4. 노드 라벨링 (Pod 배치 제어)

```bash
# 마스터 노드에 라벨 추가
kubectl label nodes biddy-k8s-master node-type=large
kubectl label nodes biddy-k8s-master workload=heavy

# 워커 노드에 라벨 추가
kubectl label nodes biddy-k8s-worker1 node-type=medium
kubectl label nodes biddy-k8s-worker1 workload=light

# 확인
kubectl get nodes --show-labels
```

---

## 4. 스토리지 구성 (AWS 담당자)

### 4-1. StorageClass 생성 (로컬 EBS 볼륨)

```yaml
# k8s/storage/storageclass.yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-storage
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
```

```bash
kubectl apply -f k8s/storage/storageclass.yaml
```

### 4-2. PersistentVolume 생성 (각 노드)

**Node 1 (마스터) - PostgreSQL & Kafka용**

```yaml
# k8s/storage/pv-postgres.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: postgres-pv
spec:
  capacity:
    storage: 20Gi
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/postgres
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - biddy-k8s-master
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: kafka-pv
spec:
  capacity:
    storage: 15Gi
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/kafka
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - biddy-k8s-master
```

**Node 2 (워커) - Redis용**

```yaml
# k8s/storage/pv-redis.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: redis-pv
spec:
  capacity:
    storage: 5Gi
  accessModes:
    - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: local-storage
  local:
    path: /data/redis
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - biddy-k8s-worker1
```

**디렉토리 생성 (각 노드에서 실행)**

```bash
# Node 1 (마스터)
sudo mkdir -p /data/postgres /data/kafka
sudo chmod 777 /data/postgres /data/kafka

# Node 2 (워커)
sudo mkdir -p /data/redis
sudo chmod 777 /data/redis
```

```bash
# PV 생성
kubectl apply -f k8s/storage/
kubectl get pv
```

---

## 5. 네임스페이스 및 ConfigMap 생성

### 5-1. 네임스페이스

```yaml
# k8s/base/namespaces.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: biddy-infra
---
apiVersion: v1
kind: Namespace
metadata:
  name: biddy-gateway
---
apiVersion: v1
kind: Namespace
metadata:
  name: biddy-services
```

### 5-2. ConfigMap (공통 설정)

```yaml
# k8s/base/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: biddy-common-config
  namespace: biddy-services
data:
  TZ: "Asia/Seoul"
  EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE: "http://discovery.biddy-infra.svc.cluster.local:8761/eureka/"
  KAFKA_BOOTSTRAP_SERVERS: "kafka.biddy-infra.svc.cluster.local:9092"
  POSTGRES_HOST: "postgres.biddy-infra.svc.cluster.local"
  POSTGRES_PORT: "5432"
  REDIS_HOST: "redis.biddy-infra.svc.cluster.local"
  REDIS_PORT: "6379"
```

### 5-3. Secret (민감 정보)

```yaml
# k8s/base/secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: biddy-secrets
  namespace: biddy-services
type: Opaque
stringData:
  POSTGRES_USER: "biddy"
  POSTGRES_PASSWORD: "biddy1234"
  JWT_SECRET: "devcourse6devcourse6devcourse6devcourse6"
  MAIL_USERNAME: "your_email@gmail.com"
  MAIL_PASSWORD: "your_app_password"
  TOSS_PAYMENTS_SECRET_KEY: "test_sk_xxxxx"
```

```bash
kubectl apply -f k8s/base/
```

---

## 6. 도메인 담당자용 - 서비스별 배포 가이드

> **각 도메인 담당자는 자신의 서비스 YAML만 수정하여 배포합니다.**

### 6-1. Member 서비스 담당자

**파일 경로**: `k8s/services/member.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: member
  namespace: biddy-services
spec:
  selector:
    app: member
  ports:
    - port: 8081
      targetPort: 8081
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: member
  namespace: biddy-services
spec:
  replicas: 2
  selector:
    matchLabels:
      app: member
  template:
    metadata:
      labels:
        app: member
    spec:
      # Node 2 (워커)에만 배포
      nodeSelector:
        workload: light
      containers:
      - name: member
        image: your-registry/biddy-member:latest
        ports:
        - containerPort: 8081
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
        - name: POSTGRES_HOST
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: POSTGRES_HOST
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
        - name: MEMBER_DB
          value: "biddy_member"
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: JWT_SECRET
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8081
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8081
          initialDelaySeconds: 60
          periodSeconds: 5
```

**배포 명령어**:
```bash
kubectl apply -f k8s/services/member.yaml
kubectl get pods -n biddy-services -l app=member
kubectl logs -f deployment/member -n biddy-services
```

---

### 6-2. Product 서비스 담당자

**파일 경로**: `k8s/services/product.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: product
  namespace: biddy-services
spec:
  selector:
    app: product
  ports:
    - port: 8082
      targetPort: 8082
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: product
  namespace: biddy-services
spec:
  replicas: 2
  selector:
    matchLabels:
      app: product
  template:
    metadata:
      labels:
        app: product
    spec:
      nodeSelector:
        workload: light
      containers:
      - name: product
        image: your-registry/biddy-product:latest
        ports:
        - containerPort: 8082
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
        - name: PRODUCT_DB
          value: "biddy_product"
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8082
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8082
          initialDelaySeconds: 60
          periodSeconds: 5
```

**배포 명령어**:
```bash
kubectl apply -f k8s/services/product.yaml
kubectl get pods -n biddy-services -l app=product
```

---

### 6-3. Auction 서비스 담당자

**파일 경로**: `k8s/services/auction.yaml`

```yaml
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
  replicas: 2
  selector:
    matchLabels:
      app: auction
  template:
    metadata:
      labels:
        app: auction
    spec:
      # Node 1 (마스터)에만 배포 (무거운 워크로드)
      nodeSelector:
        workload: heavy
      containers:
      - name: auction
        image: your-registry/biddy-auction:latest
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
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: JWT_SECRET
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
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
```

**배포 명령어**:
```bash
kubectl apply -f k8s/services/auction.yaml
kubectl get pods -n biddy-services -l app=auction -o wide
# Node 1에만 배포되는지 확인
```

---

### 6-4. Order 서비스 담당자

**파일 경로**: `k8s/services/order.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: order
  namespace: biddy-services
spec:
  selector:
    app: order
  ports:
    - port: 8083
      targetPort: 8083
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order
  namespace: biddy-services
spec:
  replicas: 2
  selector:
    matchLabels:
      app: order
  template:
    metadata:
      labels:
        app: order
    spec:
      nodeSelector:
        workload: light
      containers:
      - name: order
        image: your-registry/biddy-order:latest
        ports:
        - containerPort: 8083
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
        - name: ORDER_DB
          value: "biddy_order"
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8083
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8083
          initialDelaySeconds: 60
          periodSeconds: 5
```

---

### 6-5. Payment 서비스 담당자

**파일 경로**: `k8s/services/payment.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: payment
  namespace: biddy-services
spec:
  selector:
    app: payment
  ports:
    - port: 8085
      targetPort: 8085
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payment
  namespace: biddy-services
spec:
  replicas: 2
  selector:
    matchLabels:
      app: payment
  template:
    metadata:
      labels:
        app: payment
    spec:
      nodeSelector:
        workload: light
      containers:
      - name: payment
        image: your-registry/biddy-payment:latest
        ports:
        - containerPort: 8085
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
        - name: PAYMENT_DB
          value: "biddy_payment"
        - name: TOSS_PAYMENTS_SECRET_KEY
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: TOSS_PAYMENTS_SECRET_KEY
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8085
          initialDelaySeconds: 90
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8085
          initialDelaySeconds: 60
          periodSeconds: 5
```

---

## 7. 인프라 서비스 배포 (AWS 담당자)

### 7-1. PostgreSQL (StatefulSet - Node 1)

```yaml
# k8s/infra/postgres.yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: biddy-infra
spec:
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
  clusterIP: None  # Headless Service
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: biddy-infra
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      nodeSelector:
        workload: heavy  # Node 1에만 배포
      containers:
      - name: postgres
        image: postgres:16-alpine
        ports:
        - containerPort: 5432
        env:
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: POSTGRES_USER
              namespace: biddy-services
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: POSTGRES_PASSWORD
              namespace: biddy-services
        - name: POSTGRES_DB
          value: "biddy"
        - name: PGDATA
          value: "/var/lib/postgresql/data/pgdata"
        volumeMounts:
        - name: postgres-data
          mountPath: /var/lib/postgresql/data
        - name: init-scripts
          mountPath: /docker-entrypoint-initdb.d
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
      volumes:
      - name: init-scripts
        configMap:
          name: postgres-init-scripts
  volumeClaimTemplates:
  - metadata:
      name: postgres-data
    spec:
      accessModes: ["ReadWriteOnce"]
      storageClassName: local-storage
      resources:
        requests:
          storage: 20Gi
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgres-init-scripts
  namespace: biddy-infra
data:
  init.sql: |
    CREATE DATABASE biddy_member;
    CREATE DATABASE biddy_product;
    CREATE DATABASE biddy_order;
    CREATE DATABASE biddy_auction;
    CREATE DATABASE biddy_payment;
```

### 7-2. Redis (StatefulSet - Node 2)

```yaml
# k8s/infra/redis.yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: biddy-infra
spec:
  selector:
    app: redis
  ports:
    - port: 6379
      targetPort: 6379
  clusterIP: None
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  namespace: biddy-infra
spec:
  serviceName: redis
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      nodeSelector:
        workload: light  # Node 2에 배포
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
        volumeMounts:
        - name: redis-data
          mountPath: /data
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "200m"
  volumeClaimTemplates:
  - metadata:
      name: redis-data
    spec:
      accessModes: ["ReadWriteOnce"]
      storageClassName: local-storage
      resources:
        requests:
          storage: 5Gi
```

### 7-3. Kafka (StatefulSet - Node 1)

```yaml
# k8s/infra/kafka.yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka
  namespace: biddy-infra
spec:
  selector:
    app: kafka
  ports:
    - port: 9092
      targetPort: 9092
  clusterIP: None
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: biddy-infra
spec:
  serviceName: kafka
  replicas: 1
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      nodeSelector:
        workload: heavy  # Node 1에 배포
      containers:
      - name: kafka
        image: apache/kafka:3.7.0
        ports:
        - containerPort: 9092
        - containerPort: 9093
        env:
        - name: KAFKA_NODE_ID
          value: "1"
        - name: KAFKA_PROCESS_ROLES
          value: "broker,controller"
        - name: KAFKA_LISTENERS
          value: "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093"
        - name: KAFKA_ADVERTISED_LISTENERS
          value: "PLAINTEXT://kafka.biddy-infra.svc.cluster.local:9092"
        - name: KAFKA_CONTROLLER_LISTENER_NAMES
          value: "CONTROLLER"
        - name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP
          value: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
        - name: KAFKA_CONTROLLER_QUORUM_VOTERS
          value: "1@kafka.biddy-infra.svc.cluster.local:9093"
        - name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR
          value: "1"
        - name: CLUSTER_ID
          value: "biddy-kafka-cluster-001"
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        volumeMounts:
        - name: kafka-data
          mountPath: /var/lib/kafka/data
  volumeClaimTemplates:
  - metadata:
      name: kafka-data
    spec:
      accessModes: ["ReadWriteOnce"]
      storageClassName: local-storage
      resources:
        requests:
          storage: 15Gi
```

### 7-4. Discovery (Eureka - Node 1)

```yaml
# k8s/infra/discovery.yaml
apiVersion: v1
kind: Service
metadata:
  name: discovery
  namespace: biddy-infra
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
  namespace: biddy-infra
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
      nodeSelector:
        workload: heavy
      containers:
      - name: discovery
        image: your-registry/biddy-discovery:latest
        ports:
        - containerPort: 8761
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8761
          initialDelaySeconds: 60
          periodSeconds: 10
```

### 7-5. API Gateway (Node 1)

```yaml
# k8s/gateway/apigateway.yaml
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
      nodePort: 30000  # NodePort로 외부 노출
  type: NodePort
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
      nodeSelector:
        workload: heavy
      containers:
      - name: apigateway
        image: your-registry/biddy-apigateway:latest
        ports:
        - containerPort: 8000
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          value: "http://discovery.biddy-infra.svc.cluster.local:8761/eureka/"
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: JWT_SECRET
              namespace: biddy-services
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8000
          initialDelaySeconds: 90
          periodSeconds: 10
```

---

## 8. 전체 배포 순서 (AWS 담당자)

### 8-1. 인프라 먼저 배포

```bash
# 1. 네임스페이스, ConfigMap, Secret
kubectl apply -f k8s/base/

# 2. 스토리지
kubectl apply -f k8s/storage/

# 3. PostgreSQL
kubectl apply -f k8s/infra/postgres.yaml
kubectl wait --for=condition=ready pod -l app=postgres -n biddy-infra --timeout=300s

# 4. Redis
kubectl apply -f k8s/infra/redis.yaml
kubectl wait --for=condition=ready pod -l app=redis -n biddy-infra --timeout=300s

# 5. Kafka
kubectl apply -f k8s/infra/kafka.yaml
kubectl wait --for=condition=ready pod -l app=kafka -n biddy-infra --timeout=300s

# 6. Discovery
kubectl apply -f k8s/infra/discovery.yaml
kubectl wait --for=condition=ready pod -l app=discovery -n biddy-infra --timeout=300s
```

### 8-2. 애플리케이션 배포

```bash
# 7. API Gateway
kubectl apply -f k8s/gateway/apigateway.yaml

# 8. 도메인 서비스 (병렬 배포 가능)
kubectl apply -f k8s/services/member.yaml
kubectl apply -f k8s/services/product.yaml
kubectl apply -f k8s/services/order.yaml
kubectl apply -f k8s/services/auction.yaml
kubectl apply -f k8s/services/payment.yaml

# 9. 전체 상태 확인
kubectl get pods --all-namespaces -o wide
```

---

## 9. ALB 구성 (외부 접근)

### 9-1. Target Group 생성

```bash
# API Gateway NodePort로 타겟 그룹 생성
aws elbv2 create-target-group \
  --name biddy-api-gateway-tg \
  --protocol HTTP \
  --port 30000 \
  --vpc-id vpc-xxxxx \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 30

# 노드 등록
aws elbv2 register-targets \
  --target-group-arn arn:aws:elasticloadbalancing:... \
  --targets Id=i-master-instance-id Id=i-worker-instance-id
```

### 9-2. Application Load Balancer 생성

```bash
aws elbv2 create-load-balancer \
  --name biddy-alb \
  --subnets subnet-public-1 subnet-public-2 \
  --security-groups sg-alb \
  --scheme internet-facing

# 리스너 추가
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:... \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=arn:...
```

---

## 10. 운영 가이드

### 10-1. 모니터링

```bash
# 노드 리소스 확인
kubectl top nodes

# Pod 리소스 확인
kubectl top pods --all-namespaces

# 특정 서비스 로그
kubectl logs -f deployment/auction -n biddy-services

# 이벤트 확인
kubectl get events -n biddy-services --sort-by='.lastTimestamp'
```

### 10-2. 서비스 업데이트 (도메인 담당자)

```bash
# 이미지 빌드 및 푸시
docker build -t your-registry/biddy-auction:v1.1.0 ./auction
docker push your-registry/biddy-auction:v1.1.0

# Deployment 이미지 업데이트
kubectl set image deployment/auction \
  auction=your-registry/biddy-auction:v1.1.0 \
  -n biddy-services

# 롤아웃 상태 확인
kubectl rollout status deployment/auction -n biddy-services

# 롤백 (문제 발생 시)
kubectl rollout undo deployment/auction -n biddy-services
```

### 10-3. 스케일링

```bash
# 수동 스케일링
kubectl scale deployment auction --replicas=3 -n biddy-services

# 확인
kubectl get pods -n biddy-services -l app=auction -o wide
```

### 10-4. 디버깅

```bash
# Pod 내부 접속
kubectl exec -it <pod-name> -n biddy-services -- /bin/bash

# 포트포워딩 (로컬 테스트)
kubectl port-forward svc/auction 8084:8084 -n biddy-services

# Pod 상세 정보
kubectl describe pod <pod-name> -n biddy-services
```

---

## 11. 비용 최적화

### 11-1. 월별 예상 비용

```
- EC2 t3.large (마스터+워커): $60/월
- EC2 t3.medium (워커): $30/월
- EBS 50GB + 30GB: $8/월
- ALB: $22/월
- 데이터 전송: $10/월
─────────────────────────
합계: 약 $130/월
```

### 11-2. 비용 절감 팁

1. **Spot Instance 활용** (워커 노드만)
   - t3.medium 워커를 Spot으로 전환 → 70% 절감

2. **예약 인스턴스** (1년 약정)
   - t3.large 1년 약정 → 40% 절감

3. **EBS 최적화**
   - gp3 사용 (gp2 대비 20% 저렴)

---

## 12. 추가 권장사항

### 12-1. 필수 추가 작업

1. **Metrics Server 설치** (HPA 사용 시)
```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

2. **Ingress Controller 설치** (선택사항)
```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.8.1/deploy/static/provider/aws/deploy.yaml
```

3. **Prometheus + Grafana** (모니터링)
```bash
# Helm으로 설치
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
```

### 12-2. 백업 전략

```bash
# PostgreSQL 백업 (CronJob)
kubectl apply -f k8s/jobs/postgres-backup-cronjob.yaml

# etcd 백업 (마스터 노드)
sudo ETCDCTL_API=3 etcdctl snapshot save /backup/etcd-$(date +%Y%m%d).db \
  --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt \
  --cert=/etc/kubernetes/pki/etcd/server.crt \
  --key=/etc/kubernetes/pki/etcd/server.key
```

---

## 13. 트러블슈팅

### 13-1. Pod가 Pending 상태

```bash
# 원인 확인
kubectl describe pod <pod-name> -n biddy-services

# 일반적 원인:
# 1. 리소스 부족 → 노드 스케일링 또는 리소스 제한 조정
# 2. PV 없음 → PV 생성 확인
# 3. NodeSelector 불일치 → 라벨 확인
```

### 13-2. 노드 간 통신 실패

```bash
# CNI 플러그인 확인
kubectl get pods -n kube-system -l k8s-app=calico-node

# 재시작
kubectl delete pod -n kube-system -l k8s-app=calico-node
```

### 13-3. 서비스 디스커버리 실패

```bash
# DNS 확인
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup auction.biddy-services.svc.cluster.local

# CoreDNS 확인
kubectl get pods -n kube-system -l k8s-app=kube-dns
```

---

## 14. 참고 자료

- [Kubernetes 공식 문서](https://kubernetes.io/docs/)
- [kubeadm 설치 가이드](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/)
- [Calico CNI](https://docs.projectcalico.org/)
- [AWS ALB 설정](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/)

---

**문서 버전**: 2.0
**최종 수정일**: 2026-07-07
**작성자**: Biddy Dev Team
