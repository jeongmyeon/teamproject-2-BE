# Biddy MSA Kubernetes 도입 가이드

## 문서 정보
- **프로젝트**: Biddy 실시간 경매 플랫폼
- **버전**: 1.0
- **작성일**: 2026-07-02
- **대상**: Kubernetes 기반 컨테이너 오케스트레이션

---

## 1. Kubernetes 개요

### 1-1. Kubernetes란?

Kubernetes(k8s)는 컨테이너화된 애플리케이션의 배포, 확장, 관리를 자동화하는 오픈소스 컨테이너 오케스트레이션 플랫폼입니다.

### 1-2. Docker Compose vs Kubernetes

| 항목 | Docker Compose | Kubernetes |
|------|----------------|------------|
| **용도** | 단일 호스트 개발/테스트 | 프로덕션급 멀티 호스트 클러스터 |
| **확장성** | 수동 스케일링 | 자동 스케일링 (HPA) |
| **고가용성** | 제한적 | 다중 노드, 자동 복구 |
| **로드밸런싱** | 기본 라운드로빈 | 고급 로드밸런싱 및 서비스 메시 |
| **배포 전략** | 재시작 | Rolling Update, Blue-Green, Canary |
| **상태 관리** | Volume 기반 | PersistentVolume, StatefulSet |
| **설정 관리** | .env 파일 | ConfigMap, Secret |
| **모니터링** | 제한적 | Prometheus, Grafana 통합 |
| **학습 곡선** | 낮음 | 높음 |

### 1-3. Kubernetes 도입 시기

다음과 같은 상황에서 Kubernetes 도입을 고려합니다:

- **트래픽 증가**: 오토스케일링이 필요한 경우
- **고가용성**: 99.9% 이상의 서비스 가용성이 필요한 경우
- **멀티 클라우드**: AWS, GCP, Azure 등 다양한 클라우드 환경 사용
- **마이크로서비스 복잡도**: 서비스가 10개 이상으로 증가
- **CI/CD 자동화**: GitOps, ArgoCD 등 자동화 배포 파이프라인 구축

---

## 2. 시스템 요구사항

### 2-1. 최소 요구사항 (개발 클러스터)

- **Master Node**: 2 CPU, 4GB RAM
- **Worker Node**: 4 CPU, 8GB RAM (최소 2개 노드 권장)
- **Storage**: 50GB+ (PersistentVolume)
- **Kubernetes**: v1.28+
- **kubectl**: 클라이언트 도구

### 2-2. 프로덕션 권장사항

- **Master Node**: 3개 (HA 구성)
- **Worker Node**: 6개 이상 (4 CPU, 16GB RAM)
- **Storage**: 200GB+ (Distributed Storage - Ceph, Longhorn)
- **LoadBalancer**: MetalLB, NGINX Ingress Controller
- **Monitoring**: Prometheus, Grafana
- **Logging**: EFK Stack (Elasticsearch, Fluentd, Kibana)

---

## 3. Kubernetes 아키텍처 설계

### 3-1. 전체 아키텍처

```
┌────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                      │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │              Ingress Controller                       │ │
│  │          (NGINX / Traefik / Istio)                   │ │
│  └────────────────┬─────────────────────────────────────┘ │
│                   │                                        │
│  ┌────────────────▼─────────────────┐                     │
│  │    API Gateway Service           │ Port 8000          │
│  │    (ClusterIP / LoadBalancer)    │                     │
│  └────────────────┬─────────────────┘                     │
│                   │                                        │
│    ┌──────────────┼──────────────┬─────────┬──────────┐  │
│    │              │              │         │          │  │
│  ┌─▼──┐  ┌───────▼───┐  ┌──────▼───┐  ┌──▼─────┐  ┌▼──┐ │
│  │Mem │  │ Product   │  │  Order   │  │Auction │  │Pay│ │
│  │ber │  │ :8082     │  │  :8083   │  │ :8084  │  │:85│ │
│  └─┬──┘  └───────┬───┘  └──────┬───┘  └──┬─────┘  └┬──┘ │
│    │             │             │         │         │    │
│    └─────────────┼─────────────┼─────────┼─────────┘    │
│                  │             │         │               │
│  ┌───────────────▼─────────────▼─────────▼──────────┐   │
│  │            Kafka Service (StatefulSet)            │   │
│  │                  :9092                            │   │
│  └───────────────────────────────────────────────────┘   │
│                                                           │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ PostgreSQL  │  │    Redis     │  │   Eureka     │   │
│  │(StatefulSet)│  │(StatefulSet) │  │  Discovery   │   │
│  │   :5432     │  │    :6379     │  │   :8761      │   │
│  └─────────────┘  └──────────────┘  └──────────────┘   │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │       Persistent Volumes (Storage Class)            │ │
│  │  - postgres-pv  - redis-pv  - kafka-pv              │ │
│  └─────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

### 3-2. 네임스페이스 구조

```yaml
# 네임스페이스 분리 전략
- biddy-infra      # PostgreSQL, Redis, Kafka, Eureka, Config
- biddy-gateway    # API Gateway
- biddy-services   # Member, Product, Order, Auction, Payment
- biddy-monitoring # Prometheus, Grafana
- biddy-logging    # EFK Stack
```

### 3-3. 리소스 매핑

| Docker Compose | Kubernetes | 용도 |
|----------------|------------|------|
| `services` | `Deployment` + `Service` | 애플리케이션 배포 |
| `volumes` | `PersistentVolume` + `PVC` | 데이터 영속성 |
| `environment` | `ConfigMap` + `Secret` | 설정 관리 |
| `depends_on` | `InitContainer` + `Readiness Probe` | 의존성 관리 |
| `healthcheck` | `Liveness Probe` + `Readiness Probe` | 헬스체크 |
| `ports` | `Service` (ClusterIP/LoadBalancer) | 네트워킹 |

---

## 4. Kubernetes 리소스 정의

### 4-1. 네임스페이스 생성

```yaml
# k8s/namespaces.yaml
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

### 4-2. ConfigMap 및 Secret

```yaml
# k8s/config/configmap.yaml
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
---
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

### 4-3. PostgreSQL (StatefulSet)

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
  clusterIP: None  # Headless Service for StatefulSet
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
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: biddy-secrets
              key: POSTGRES_PASSWORD
        - name: POSTGRES_DB
          value: "biddy"
        volumeMounts:
        - name: postgres-data
          mountPath: /var/lib/postgresql/data
        - name: init-scripts
          mountPath: /docker-entrypoint-initdb.d
        livenessProbe:
          exec:
            command: ["pg_isready", "-U", "biddy"]
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command: ["pg_isready", "-U", "biddy"]
          initialDelaySeconds: 5
          periodSeconds: 5
      volumes:
      - name: init-scripts
        configMap:
          name: postgres-init-scripts
  volumeClaimTemplates:
  - metadata:
      name: postgres-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
```

### 4-4. Redis (StatefulSet)

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
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
        volumeMounts:
        - name: redis-data
          mountPath: /data
        livenessProbe:
          exec:
            command: ["redis-cli", "ping"]
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command: ["redis-cli", "ping"]
          initialDelaySeconds: 5
          periodSeconds: 5
  volumeClaimTemplates:
  - metadata:
      name: redis-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 5Gi
```

### 4-5. Kafka (StatefulSet)

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
        livenessProbe:
          exec:
            command:
            - sh
            - -c
            - "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          exec:
            command:
            - sh
            - -c
            - "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
          initialDelaySeconds: 30
          periodSeconds: 10
```

### 4-6. Eureka Discovery (Deployment)

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
      containers:
      - name: discovery
        image: your-registry/biddy-discovery:latest
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
```

### 4-7. API Gateway (Deployment)

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
  type: LoadBalancer  # 또는 NodePort
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
        image: your-registry/biddy-apigateway:latest
        ports:
        - containerPort: 8000
        env:
        - name: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
          valueFrom:
            configMapKeyRef:
              name: biddy-common-config
              key: EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE
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

### 4-8. 도메인 서비스 (Auction 예시)

```yaml
# k8s/services/auction.yaml
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
  replicas: 3  # 오토스케일링 가능
  selector:
    matchLabels:
      app: auction
  template:
    metadata:
      labels:
        app: auction
    spec:
      initContainers:
      - name: wait-for-postgres
        image: busybox:1.35
        command: ['sh', '-c', 'until nc -z postgres.biddy-infra.svc.cluster.local 5432; do echo waiting for postgres; sleep 2; done;']
      - name: wait-for-kafka
        image: busybox:1.35
        command: ['sh', '-c', 'until nc -z kafka.biddy-infra.svc.cluster.local 9092; do echo waiting for kafka; sleep 2; done;']
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
```

### 4-9. HorizontalPodAutoscaler (오토스케일링)

```yaml
# k8s/services/auction-hpa.yaml
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
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
      - type: Pods
        value: 2
        periodSeconds: 15
      selectPolicy: Max
```

### 4-10. Ingress (NGINX)

```yaml
# k8s/gateway/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: biddy-ingress
  namespace: biddy-gateway
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
    nginx.ingress.kubernetes.io/websocket-services: "auction"
spec:
  ingressClassName: nginx
  rules:
  - host: biddy.example.com
    http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: apigateway
            port:
              number: 8000
  - host: auction.biddy.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: auction
            port:
              number: 8084
```

---

## 5. 배포 절차

### 5-1. 사전 준비

```bash
# kubectl 설치 확인
kubectl version --client

# 클러스터 연결 확인
kubectl cluster-info
kubectl get nodes

# 네임스페이스 생성
kubectl apply -f k8s/namespaces.yaml
```

### 5-2. 이미지 빌드 및 레지스트리 푸시

```bash
# Docker Hub / ECR / GCR에 이미지 푸시
./gradlew clean build -x test

# 각 서비스별 이미지 빌드 및 푸시
docker build -t your-registry/biddy-discovery:v1.0.0 ./discovery
docker push your-registry/biddy-discovery:v1.0.0

docker build -t your-registry/biddy-apigateway:v1.0.0 ./apigateway
docker push your-registry/biddy-apigateway:v1.0.0

docker build -t your-registry/biddy-member:v1.0.0 ./member
docker push your-registry/biddy-member:v1.0.0

docker build -t your-registry/biddy-auction:v1.0.0 ./auction
docker push your-registry/biddy-auction:v1.0.0

# ... (product, order, payment)
```

### 5-3. ConfigMap 및 Secret 배포

```bash
# Secret 배포 (민감 정보)
kubectl apply -f k8s/config/secrets.yaml

# ConfigMap 배포
kubectl apply -f k8s/config/configmap.yaml

# 확인
kubectl get secret -n biddy-services
kubectl get configmap -n biddy-services
```

### 5-4. 인프라 배포 (순서대로)

```bash
# 1. PostgreSQL
kubectl apply -f k8s/infra/postgres.yaml

# 2. Redis
kubectl apply -f k8s/infra/redis.yaml

# 3. Kafka
kubectl apply -f k8s/infra/kafka.yaml

# 4. Eureka Discovery
kubectl apply -f k8s/infra/discovery.yaml

# 5. Config Server
kubectl apply -f k8s/infra/config.yaml

# 상태 확인 (모두 Running 될 때까지 대기)
kubectl get pods -n biddy-infra -w
```

### 5-5. 애플리케이션 배포

```bash
# 1. API Gateway
kubectl apply -f k8s/gateway/apigateway.yaml

# 2. 도메인 서비스 (동시 배포 가능)
kubectl apply -f k8s/services/member.yaml
kubectl apply -f k8s/services/product.yaml
kubectl apply -f k8s/services/order.yaml
kubectl apply -f k8s/services/auction.yaml
kubectl apply -f k8s/services/payment.yaml

# 상태 확인
kubectl get pods -n biddy-services -w
kubectl get svc -n biddy-services
```

### 5-6. HPA 및 Ingress 배포

```bash
# HPA (오토스케일링)
kubectl apply -f k8s/services/auction-hpa.yaml

# Ingress
kubectl apply -f k8s/gateway/ingress.yaml

# 확인
kubectl get hpa -n biddy-services
kubectl get ingress -n biddy-gateway
```

---

## 6. 운영 가이드

### 6-1. 모니터링

```bash
# Pod 상태 확인
kubectl get pods --all-namespaces

# 특정 서비스 로그 확인
kubectl logs -f deployment/auction -n biddy-services

# 리소스 사용량 확인
kubectl top nodes
kubectl top pods -n biddy-services

# 이벤트 확인
kubectl get events -n biddy-services --sort-by='.lastTimestamp'
```

### 6-2. 스케일링

```bash
# 수동 스케일링
kubectl scale deployment auction --replicas=5 -n biddy-services

# HPA 확인
kubectl get hpa -n biddy-services -w

# HPA 상세 정보
kubectl describe hpa auction-hpa -n biddy-services
```

### 6-3. 롤링 업데이트

```bash
# 이미지 업데이트
kubectl set image deployment/auction auction=your-registry/biddy-auction:v1.1.0 -n biddy-services

# 롤아웃 상태 확인
kubectl rollout status deployment/auction -n biddy-services

# 롤아웃 히스토리
kubectl rollout history deployment/auction -n biddy-services

# 롤백
kubectl rollout undo deployment/auction -n biddy-services
kubectl rollout undo deployment/auction --to-revision=2 -n biddy-services
```

### 6-4. ConfigMap / Secret 업데이트

```bash
# ConfigMap 수정
kubectl edit configmap biddy-common-config -n biddy-services

# Secret 수정
kubectl edit secret biddy-secrets -n biddy-services

# Pod 재시작 (변경사항 반영)
kubectl rollout restart deployment/auction -n biddy-services
```

### 6-5. 디버깅

```bash
# Pod 내부 접속
kubectl exec -it auction-7d8f5b8c9d-abcde -n biddy-services -- /bin/sh

# 특정 컨테이너 로그 확인
kubectl logs auction-7d8f5b8c9d-abcde -c auction -n biddy-services --tail=100

# 이전 Pod 로그 (Crash 된 경우)
kubectl logs auction-7d8f5b8c9d-abcde -n biddy-services --previous

# Pod 상세 정보
kubectl describe pod auction-7d8f5b8c9d-abcde -n biddy-services

# 포트포워딩 (로컬 테스트)
kubectl port-forward svc/auction 8084:8084 -n biddy-services
```

### 6-6. DB 접속

```bash
# PostgreSQL Pod 내부 접속
kubectl exec -it postgres-0 -n biddy-infra -- psql -U biddy -d biddy_auction

# 또는 포트포워딩 후 DBeaver 연결
kubectl port-forward svc/postgres 5432:5432 -n biddy-infra
```

---

## 7. 백업 및 복구

### 7-1. PostgreSQL 백업

```bash
# CronJob으로 자동 백업
# k8s/jobs/postgres-backup-cronjob.yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: postgres-backup
  namespace: biddy-infra
spec:
  schedule: "0 3 * * *"  # 매일 새벽 3시
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: backup
            image: postgres:16-alpine
            command:
            - /bin/sh
            - -c
            - |
              pg_dump -h postgres.biddy-infra.svc.cluster.local -U biddy biddy_auction > /backup/auction_$(date +%Y%m%d).sql
            volumeMounts:
            - name: backup-volume
              mountPath: /backup
          restartPolicy: OnFailure
          volumes:
          - name: backup-volume
            persistentVolumeClaim:
              claimName: postgres-backup-pvc
```

### 7-2. 복구

```bash
# 백업 파일로 복구
kubectl exec -it postgres-0 -n biddy-infra -- psql -U biddy biddy_auction < backup/auction_20260702.sql
```

---

## 8. Helm 차트 (선택사항)

### 8-1. Helm 차트 구조

```
biddy-helm/
├── Chart.yaml
├── values.yaml
├── templates/
│   ├── namespaces.yaml
│   ├── configmap.yaml
│   ├── secrets.yaml
│   ├── infra/
│   │   ├── postgres.yaml
│   │   ├── redis.yaml
│   │   ├── kafka.yaml
│   │   └── discovery.yaml
│   ├── gateway/
│   │   ├── apigateway.yaml
│   │   └── ingress.yaml
│   └── services/
│       ├── member.yaml
│       ├── auction.yaml
│       └── ...
```

### 8-2. Helm 배포

```bash
# Helm 차트 설치
helm install biddy ./biddy-helm -n biddy-services --create-namespace

# 업데이트
helm upgrade biddy ./biddy-helm -n biddy-services

# 삭제
helm uninstall biddy -n biddy-services
```

---

## 9. CI/CD 파이프라인 (GitOps)

### 9-1. ArgoCD 사용 예시

```yaml
# argocd/biddy-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: biddy-services
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/your-org/beadv6_6_frontal_BE.git
    targetRevision: main
    path: k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: biddy-services
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
    - CreateNamespace=true
```

### 9-2. GitHub Actions 배포 파이프라인

```yaml
# .github/workflows/deploy-k8s.yml
name: Deploy to Kubernetes

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3

    - name: Build Docker images
      run: |
        ./gradlew clean build -x test
        docker build -t your-registry/biddy-auction:${{ github.sha }} ./auction
        docker push your-registry/biddy-auction:${{ github.sha }}

    - name: Deploy to Kubernetes
      run: |
        kubectl set image deployment/auction auction=your-registry/biddy-auction:${{ github.sha }} -n biddy-services
```

---

## 10. 장단점 및 고려사항

### 10-1. Kubernetes 도입 장점

- **자동 복구**: Pod 장애 시 자동 재시작
- **오토스케일링**: CPU/메모리 기반 자동 확장
- **무중단 배포**: Rolling Update, Blue-Green 배포
- **서비스 디스커버리**: 자동 DNS 기반 서비스 탐색
- **선언적 관리**: YAML로 인프라 코드화 (IaC)
- **멀티 클라우드**: 클라우드 벤더 종속성 감소

### 10-2. 단점 및 주의사항

- **학습 곡선**: Docker Compose 대비 높은 복잡도
- **초기 설정 비용**: 클러스터 구축, 모니터링 스택 구성
- **리소스 오버헤드**: Master Node, etcd 등 추가 리소스 필요
- **디버깅 복잡도**: 분산 환경에서 문제 추적 어려움

### 10-3. 도입 시기 판단

**Kubernetes 도입이 적합한 경우**:
- 트래픽이 예측 불가능하고 급격히 증가
- 99.9% 이상의 고가용성 요구
- 서비스 수가 10개 이상
- 멀티 클라우드 전략

**Docker Compose가 적합한 경우**:
- 소규모 팀 (5명 이하)
- 안정적인 트래픽 (일 방문자 1만 이하)
- 빠른 프로토타이핑
- 단일 서버 배포

---

## 11. 참고 자료

- [Kubernetes 공식 문서](https://kubernetes.io/docs/)
- [Helm 공식 문서](https://helm.sh/docs/)
- [Spring Cloud Kubernetes](https://spring.io/projects/spring-cloud-kubernetes)
- [NGINX Ingress Controller](https://kubernetes.github.io/ingress-nginx/)
- [ArgoCD](https://argo-cd.readthedocs.io/)
- [Prometheus Operator](https://prometheus-operator.dev/)

---

## 12. 부록: 주요 명령어 치트시트

```bash
# 네임스페이스
kubectl get ns
kubectl create ns biddy-services

# Pod
kubectl get pods -n biddy-services
kubectl describe pod <pod-name> -n biddy-services
kubectl logs -f <pod-name> -n biddy-services
kubectl exec -it <pod-name> -n biddy-services -- /bin/sh

# Deployment
kubectl get deploy -n biddy-services
kubectl scale deploy <name> --replicas=3 -n biddy-services
kubectl rollout status deploy/<name> -n biddy-services
kubectl rollout undo deploy/<name> -n biddy-services

# Service
kubectl get svc -n biddy-services
kubectl describe svc <svc-name> -n biddy-services
kubectl port-forward svc/<svc-name> 8084:8084 -n biddy-services

# ConfigMap / Secret
kubectl get cm -n biddy-services
kubectl get secret -n biddy-services
kubectl edit cm <cm-name> -n biddy-services

# HPA
kubectl get hpa -n biddy-services
kubectl describe hpa <hpa-name> -n biddy-services

# Ingress
kubectl get ingress -n biddy-gateway
kubectl describe ingress <ingress-name> -n biddy-gateway

# 리소스 확인
kubectl top nodes
kubectl top pods -n biddy-services

# 전체 정리
kubectl delete ns biddy-infra biddy-gateway biddy-services
```

---

**문서 버전**: 1.0
**최종 수정일**: 2026-07-02
**작성자**: Biddy Dev Team
