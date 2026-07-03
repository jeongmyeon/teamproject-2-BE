# Biddy AWS EKS 배포 체크리스트

전체 배포 과정을 순서대로 정리한 체크리스트입니다.

## Phase 1: AWS 인프라 구축 (약 1~2시간)

### 1. EKS 클러스터 생성

- [ ] `eksctl` 설치 및 AWS 자격 증명 설정
- [ ] `eks-cluster-config.yaml` 작성
- [ ] EKS 클러스터 생성 (15~20분 소요)
  ```bash
  eksctl create cluster -f eks-cluster-config.yaml
  ```
- [ ] kubectl 컨텍스트 확인
  ```bash
  kubectl get nodes
  ```

### 2. AWS Load Balancer Controller 설치

- [ ] OIDC Provider 연결
- [ ] IAM Policy 생성
- [ ] Service Account 생성
- [ ] Helm으로 Controller 설치
- [ ] 설치 확인
  ```bash
  kubectl get deployment -n kube-system aws-load-balancer-controller
  ```

### 3. EBS CSI Driver 설치

- [ ] IAM Role 생성
- [ ] EBS CSI Driver Addon 설치
- [ ] 설치 확인
  ```bash
  kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-ebs-csi-driver
  ```

### 4. RDS PostgreSQL 생성

- [ ] DB Subnet Group 생성
- [ ] Security Group 생성 (포트 5432 허용)
- [ ] RDS 인스턴스 생성 (Multi-AZ, db.t3.medium)
- [ ] 엔드포인트 확인 및 기록
- [ ] 데이터베이스 초기화
  ```sql
  CREATE DATABASE biddy_member;
  CREATE DATABASE biddy_product;
  CREATE DATABASE biddy_order;
  CREATE DATABASE biddy_auction;
  CREATE DATABASE biddy_payment;
  ```

### 5. ElastiCache Redis 생성

- [ ] Cache Subnet Group 생성
- [ ] Security Group 생성 (포트 6379 허용)
- [ ] Redis 클러스터 생성 (cache.t3.small, Multi-AZ)
- [ ] Primary 엔드포인트 확인 및 기록

### 6. MSK Kafka 생성

- [ ] MSK 클러스터 설정 파일 작성
- [ ] MSK 클러스터 생성 (3 brokers, kafka.t3.small)
- [ ] Bootstrap 서버 확인 및 기록
  ```bash
  aws kafka get-bootstrap-brokers --cluster-arn <arn>
  ```

### 7. ECR 리포지토리 생성

- [ ] 각 서비스별 리포지토리 생성
  ```bash
  for service in discovery config apigateway member product order auction payment recommendation; do
    aws ecr create-repository --repository-name biddy/$service --region ap-northeast-2
  done
  ```
- [ ] 리포지토리 URI 확인

### 8. ACM SSL 인증서 발급 (HTTPS용)

- [ ] ACM에서 인증서 요청 (api.biddy.example.com)
- [ ] DNS 검증 완료
- [ ] 인증서 ARN 확인 및 기록

---

## Phase 2: 이미지 빌드 및 푸시 (약 30분)

### 1. 애플리케이션 빌드

- [ ] Gradle 빌드
  ```bash
  ./gradlew clean build -x test
  ```

### 2. Docker 이미지 빌드 및 푸시

- [ ] ECR 로그인
  ```bash
  aws ecr get-login-password --region ap-northeast-2 | \
    docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com
  ```
- [ ] Discovery 이미지 빌드 및 푸시
- [ ] Config 이미지 빌드 및 푸시
- [ ] API Gateway 이미지 빌드 및 푸시
- [ ] Member 이미지 빌드 및 푸시
- [ ] Product 이미지 빌드 및 푸시
- [ ] Order 이미지 빌드 및 푸시
- [ ] Auction 이미지 빌드 및 푸시
- [ ] Payment 이미지 빌드 및 푸시
- [ ] (선택) Recommendation 이미지 빌드 및 푸시

---

## Phase 3: Kubernetes 설정 파일 업데이트 (약 20분)

### 1. ConfigMap 업데이트 (`k8s-aws/config/configmap.yaml`)

- [ ] `POSTGRES_HOST`: RDS 엔드포인트로 교체
- [ ] `REDIS_HOST`: ElastiCache 엔드포인트로 교체
- [ ] `KAFKA_BOOTSTRAP_SERVERS`: MSK Bootstrap 서버로 교체
- [ ] `IMAGE_BASE_URL`: 실제 도메인으로 교체

### 2. Secret 업데이트 (`k8s-aws/config/secrets.yaml`)

- [ ] `POSTGRES_PASSWORD`: RDS 비밀번호로 교체
- [ ] `JWT_SECRET`: 강력한 시크릿으로 교체
- [ ] `MAIL_USERNAME`, `MAIL_PASSWORD`: Gmail 앱 비밀번호 설정
- [ ] `TOSS_PAYMENTS_SECRET_KEY`: Toss Payments 시크릿 키 설정

### 3. Ingress 업데이트 (`k8s-aws/gateway/ingress.yaml`)

- [ ] `alb.ingress.kubernetes.io/certificate-arn`: ACM 인증서 ARN으로 교체
- [ ] `host`: 실제 도메인으로 교체 (api.biddy.example.com)

### 4. 모든 Deployment 이미지 태그 업데이트

- [ ] `YOUR_ACCOUNT_ID`를 실제 AWS Account ID로 교체
- [ ] 이미지 태그를 실제 버전으로 교체 (예: v1.0.0)

---

## Phase 4: Kubernetes 리소스 배포 (약 20분)

### 1. 네임스페이스 생성

- [ ] 네임스페이스 배포
  ```bash
  kubectl apply -f k8s-aws/namespaces.yaml
  ```
- [ ] 확인
  ```bash
  kubectl get ns
  ```

### 2. ConfigMap 및 Secret 배포

- [ ] ConfigMap 배포
  ```bash
  kubectl apply -f k8s-aws/config/configmap.yaml
  ```
- [ ] Secret 배포
  ```bash
  kubectl apply -f k8s-aws/config/secrets.yaml
  ```
- [ ] 확인
  ```bash
  kubectl get cm -n biddy-services
  kubectl get secret -n biddy-services
  ```

### 3. 인프라 서비스 배포

- [ ] Discovery 배포
  ```bash
  kubectl apply -f k8s-aws/services/discovery.yaml
  ```
- [ ] Discovery Pod Ready 상태 확인 (약 1~2분)
  ```bash
  kubectl wait --for=condition=ready pod -l app=discovery -n biddy-services --timeout=300s
  ```
- [ ] Config 배포
  ```bash
  kubectl apply -f k8s-aws/services/config.yaml
  ```

### 4. 도메인 서비스 배포

- [ ] Member 배포
  ```bash
  kubectl apply -f k8s-aws/services/member.yaml
  ```
- [ ] Product 배포
  ```bash
  kubectl apply -f k8s-aws/services/product.yaml
  ```
- [ ] Order 배포
  ```bash
  kubectl apply -f k8s-aws/services/order.yaml
  ```
- [ ] Auction 배포
  ```bash
  kubectl apply -f k8s-aws/services/auction.yaml
  ```
- [ ] Payment 배포
  ```bash
  kubectl apply -f k8s-aws/services/payment.yaml
  ```
- [ ] (선택) Recommendation 배포
  ```bash
  kubectl apply -f k8s-aws/services/recommendation.yaml
  ```
- [ ] 모든 Pod Ready 확인
  ```bash
  kubectl get pods -n biddy-services -w
  ```

### 5. API Gateway 및 Ingress 배포

- [ ] API Gateway 배포
  ```bash
  kubectl apply -f k8s-aws/gateway/apigateway.yaml
  ```
- [ ] Ingress 배포 (ALB 자동 생성)
  ```bash
  kubectl apply -f k8s-aws/gateway/ingress.yaml
  ```
- [ ] ALB 생성 확인 (약 3~5분)
  ```bash
  kubectl get ingress -n biddy-gateway -w
  ```

### 6. ALB 도메인 확인

- [ ] ALB DNS 이름 확인
  ```bash
  kubectl get ingress biddy-ingress -n biddy-gateway \
    -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
  ```
- [ ] ALB DNS 기록 (예: k8s-biddygat-xxxxx.ap-northeast-2.elb.amazonaws.com)

---

## Phase 5: DNS 설정 (약 10분)

### Route53 A 레코드 생성

- [ ] Route53 Hosted Zone 선택
- [ ] A 레코드 생성 (Alias)
  - Name: `api.biddy.example.com`
  - Type: A (Alias to Application Load Balancer)
  - Value: ALB DNS 이름
- [ ] DNS 전파 확인 (5~10분)
  ```bash
  dig api.biddy.example.com
  nslookup api.biddy.example.com
  ```

---

## Phase 6: 이미지 스토리지 설정 (선택, 약 30분)

### EFS 설정 (Product 이미지 공유용)

- [ ] EFS CSI Driver 설치
  ```bash
  kubectl apply -k "github.com/kubernetes-sigs/aws-efs-csi-driver/deploy/kubernetes/overlays/stable/?ref=master"
  ```
- [ ] IAM Policy 및 Service Account 생성
- [ ] EFS 파일시스템 생성
- [ ] Security Group 설정 (NFS 포트 2049 허용)
- [ ] 각 AZ에 마운트 타겟 생성
- [ ] `k8s-aws/storage/efs-setup.yaml` 파일의 파일시스템 ID 업데이트
- [ ] StorageClass, PV, PVC 배포
  ```bash
  kubectl apply -f k8s-aws/storage/efs-setup.yaml
  ```
- [ ] Product 서비스 재시작
  ```bash
  kubectl rollout restart deployment/product -n biddy-services
  ```

---

## Phase 7: 모니터링 설정 (선택, 약 20분)

### Prometheus & Grafana 배포

- [ ] Monitoring 네임스페이스 생성
  ```bash
  kubectl apply -f k8s-aws/monitoring/namespace.yaml
  ```
- [ ] Prometheus 배포
  ```bash
  kubectl apply -f k8s-aws/monitoring/prometheus.yaml
  ```
- [ ] Grafana 배포
  ```bash
  kubectl apply -f k8s-aws/monitoring/grafana.yaml
  ```
- [ ] Grafana LoadBalancer 외부 IP 확인
  ```bash
  kubectl get svc grafana -n biddy-monitoring
  ```
- [ ] Grafana 접속 (http://<EXTERNAL-IP>:3000)
- [ ] 기본 계정으로 로그인 (admin / admin1234)
- [ ] 대시보드 Import (ID: 315, 6417, 4701)

---

## Phase 8: 동작 확인 (약 10분)

### 1. API 엔드포인트 테스트

- [ ] Health Check
  ```bash
  curl https://api.biddy.example.com/actuator/health
  ```
- [ ] 회원가입 테스트
  ```bash
  curl -X POST https://api.biddy.example.com/api/members/signup \
    -H "Content-Type: application/json" \
    -d '{"email":"test@biddy.com","password":"password1234","nickname":"test","phone":"01012345678"}'
  ```
- [ ] 로그인 테스트
  ```bash
  curl -X POST https://api.biddy.example.com/api/members/login \
    -H "Content-Type: application/json" \
    -d '{"email":"test@biddy.com","password":"password1234"}'
  ```

### 2. 서비스 상태 확인

- [ ] 모든 Pod 상태 확인
  ```bash
  kubectl get pods --all-namespaces
  ```
- [ ] Service 확인
  ```bash
  kubectl get svc --all-namespaces
  ```
- [ ] Ingress 확인
  ```bash
  kubectl get ingress -n biddy-gateway
  ```
- [ ] HPA 확인
  ```bash
  kubectl get hpa -n biddy-services
  ```

### 3. Eureka 대시보드 확인

- [ ] Port-forward로 Eureka 접속
  ```bash
  kubectl port-forward svc/discovery 8761:8761 -n biddy-services
  ```
- [ ] 브라우저에서 http://localhost:8761 접속
- [ ] 모든 서비스 등록 확인

### 4. 로그 확인

- [ ] Auction 서비스 로그
  ```bash
  kubectl logs -f deployment/auction -n biddy-services --tail=50
  ```
- [ ] API Gateway 로그
  ```bash
  kubectl logs -f deployment/apigateway -n biddy-gateway --tail=50
  ```

---

## Phase 9: 프로덕션 최적화 (선택)

### 1. 리소스 제한 조정

- [ ] CPU/Memory 사용량 모니터링 (1주일)
- [ ] requests/limits 값 최적화

### 2. HPA 튜닝

- [ ] 실제 트래픽 패턴 분석
- [ ] minReplicas, maxReplicas, targetUtilization 조정

### 3. Security Group 강화

- [ ] 불필요한 포트 차단
- [ ] Egress 규칙 제한

### 4. 백업 설정

- [ ] RDS 자동 백업 확인 (7일 보존)
- [ ] EBS 스냅샷 스케줄 설정
- [ ] ECR 이미지 라이프사이클 정책 설정

### 5. 알람 설정

- [ ] CloudWatch 알람 (CPU > 80%, Memory > 90%)
- [ ] SNS 토픽 생성 및 이메일 구독
- [ ] Grafana Alert 설정

---

## Phase 10: CI/CD 파이프라인 (선택)

### GitHub Actions 설정

- [ ] `.github/workflows/deploy-eks.yml` 작성
- [ ] GitHub Secrets 설정
  - `AWS_ACCESS_KEY_ID`
  - `AWS_SECRET_ACCESS_KEY`
- [ ] 테스트 배포 실행

---

## 비용 확인

### 월별 예상 비용 (ap-northeast-2)

- [ ] EKS Control Plane: $73
- [ ] EC2 Worker Nodes: $60~90
- [ ] RDS: $50~150
- [ ] ElastiCache: $15~60
- [ ] MSK: $120~180
- [ ] NAT Gateway: $32~64
- [ ] ALB: $22
- [ ] EBS: $10~30
- [ ] 데이터 전송: 변동
- **총 예상: $382~$689/월**

### 비용 절감 전략

- [ ] Spot Instance 활용 (Worker Node)
- [ ] RDS/ElastiCache 예약 인스턴스 검토 (1년 약정)
- [ ] CloudWatch 로그 보존 기간 단축
- [ ] 불필요한 EBS 볼륨 삭제

---

## 트러블슈팅 체크리스트

### Pod가 Pending 상태

- [ ] `kubectl describe pod <pod-name> -n biddy-services`로 원인 확인
- [ ] Insufficient CPU/Memory → Worker Node 추가
- [ ] Node Selector 불일치 → nodeSelector 확인

### RDS 연결 실패

- [ ] Security Group에서 5432 포트 허용 확인
- [ ] EKS Worker Node SG가 RDS SG에 추가되었는지 확인
- [ ] VPC 서브넷 확인

### ALB Health Check 실패

- [ ] Target Group 상태 확인
- [ ] Pod의 readinessProbe 경로 확인 (/actuator/health)
- [ ] Security Group에서 ALB → Pod 트래픽 허용 확인

### Kafka 연결 실패

- [ ] MSK Security Group에서 9092 포트 허용 확인
- [ ] Bootstrap 서버 주소 정확한지 확인
- [ ] VPC 서브넷 및 라우팅 테이블 확인

---

## 롤백 계획

### 배포 실패 시

- [ ] 이전 이미지 태그로 롤백
  ```bash
  kubectl rollout undo deployment/<service-name> -n biddy-services
  ```
- [ ] 롤백 상태 확인
  ```bash
  kubectl rollout status deployment/<service-name> -n biddy-services
  ```

### 인프라 삭제 시 순서

1. Kubernetes 리소스 삭제
2. EKS 클러스터 삭제
3. RDS 스냅샷 생성 후 삭제
4. ElastiCache 삭제
5. MSK 삭제
6. EFS 삭제
7. ECR 리포지토리 삭제
8. VPC 및 Security Group 정리

---

## 완료 확인

- [ ] 모든 서비스 정상 동작
- [ ] HTTPS 접속 가능
- [ ] 모니터링 대시보드 정상 표시
- [ ] 비용 알람 설정 완료
- [ ] 백업 정책 확인
- [ ] 문서화 완료

---

**배포 완료 시간**: 약 3~4시간 (인프라 생성 대기 시간 포함)

**주요 대기 시간**:
- EKS 클러스터 생성: 15~20분
- RDS 생성: 10~15분
- MSK 생성: 20~30분
- ALB 생성: 3~5분
- DNS 전파: 5~10분
