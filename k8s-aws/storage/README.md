# Biddy 이미지 스토리지 설정

상품 이미지를 여러 Pod에서 공유하기 위한 AWS EFS(Elastic File System) 설정입니다.

## 왜 EFS가 필요한가?

Kubernetes에서 여러 Pod가 동일한 이미지 파일에 접근해야 할 때:
- **EBS (gp3)**: ReadWriteOnce만 지원 → 1개 Pod만 접근 가능
- **EFS**: ReadWriteMany 지원 → 여러 Pod 동시 접근 가능

Product 서비스가 HPA로 스케일아웃될 때 모든 Pod가 동일한 이미지 디렉토리를 공유해야 합니다.

## 사전 준비

### 1. EFS CSI Driver 설치

```bash
# EFS CSI Driver 배포
kubectl apply -k "github.com/kubernetes-sigs/aws-efs-csi-driver/deploy/kubernetes/overlays/stable/?ref=master"

# 설치 확인
kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-efs-csi-driver
```

### 2. IAM 정책 생성

```bash
# EFS CSI Driver용 IAM 정책
curl -o iam-policy-efs.json https://raw.githubusercontent.com/kubernetes-sigs/aws-efs-csi-driver/master/docs/iam-policy-example.json

aws iam create-policy \
  --policy-name AmazonEKS_EFS_CSI_Driver_Policy \
  --policy-document file://iam-policy-efs.json

# Service Account에 IAM 역할 연결
eksctl create iamserviceaccount \
  --cluster=biddy-eks-cluster \
  --namespace=kube-system \
  --name=efs-csi-controller-sa \
  --attach-policy-arn=arn:aws:iam::YOUR_ACCOUNT_ID:policy/AmazonEKS_EFS_CSI_Driver_Policy \
  --approve \
  --region=ap-northeast-2
```

## EFS 파일시스템 생성

### 방법 A: AWS Console

1. EFS 콘솔 → Create file system
2. Name: `biddy-images-efs`
3. VPC: EKS 클러스터와 동일한 VPC 선택
4. Availability: Regional (Multi-AZ)
5. Performance mode: General Purpose
6. Throughput mode: Bursting
7. Encryption: 활성화

### 방법 B: AWS CLI

```bash
# 1. EFS 파일시스템 생성
aws efs create-file-system \
  --performance-mode generalPurpose \
  --throughput-mode bursting \
  --encrypted \
  --tags Key=Name,Value=biddy-images-efs \
  --region ap-northeast-2

# 출력에서 FileSystemId 확인: fs-xxxxx

# 2. EKS Worker Node Security Group 확인
kubectl get nodes -o wide
aws ec2 describe-instances --instance-ids i-xxxxx \
  --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' \
  --output text

# 3. EFS Security Group 생성
aws ec2 create-security-group \
  --group-name biddy-efs-sg \
  --description "Security group for Biddy EFS" \
  --vpc-id vpc-xxxxx

# 4. EKS Worker Node에서 NFS 접근 허용 (포트 2049)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 2049 \
  --source-group sg-yyyyy  # EKS Worker Node SG

# 5. 각 AZ에 마운트 타겟 생성
aws efs create-mount-target \
  --file-system-id fs-xxxxx \
  --subnet-id subnet-xxxxx \
  --security-groups sg-xxxxx

aws efs create-mount-target \
  --file-system-id fs-xxxxx \
  --subnet-id subnet-yyyyy \
  --security-groups sg-xxxxx

# 6. 마운트 타겟 상태 확인
aws efs describe-mount-targets \
  --file-system-id fs-xxxxx
```

## Kubernetes 리소스 배포

### 1. StorageClass 및 PV/PVC 생성

`efs-setup.yaml` 파일의 `fs-xxxxx`를 실제 EFS 파일시스템 ID로 교체:

```yaml
parameters:
  fileSystemId: fs-0123456789abcdef  # 실제 ID로 교체
```

```bash
# 배포
kubectl apply -f efs-setup.yaml

# 확인
kubectl get sc efs-sc
kubectl get pv biddy-images-pv
kubectl get pvc biddy-images-pvc -n biddy-services
```

### 2. Product 서비스에 EFS 연결

`k8s-aws/services/product.yaml`에 이미 EFS PVC가 설정되어 있습니다:

```yaml
volumes:
- name: product-images
  persistentVolumeClaim:
    claimName: biddy-images-pvc  # EFS PVC 사용

volumeMounts:
- name: product-images
  mountPath: /app/images
```

Product 서비스 재배포:

```bash
kubectl rollout restart deployment/product -n biddy-services
```

## 테스트

### 1. EFS 마운트 확인

```bash
# Product Pod에 접속
kubectl exec -it deployment/product -n biddy-services -- /bin/sh

# 마운트 확인
df -h | grep /app/images
ls -la /app/images

# 테스트 파일 생성
echo "EFS Test" > /app/images/test.txt
exit
```

### 2. 다른 Pod에서 동일 파일 확인

```bash
# 다른 Product Pod에서 확인 (HPA로 여러 개일 경우)
kubectl get pods -n biddy-services -l app=product
kubectl exec -it <다른-pod-이름> -n biddy-services -- cat /app/images/test.txt
# 출력: EFS Test
```

### 3. 이미지 업로드 테스트

```bash
# 상품 이미지 업로드 API 호출
TOKEN=$(kubectl exec -it deployment/member -n biddy-services -- \
  curl -s -X POST http://localhost:8081/api/members/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@biddy.com","password":"password1234"}' \
  | jq -r '.accessToken')

# 이미지 업로드
curl -X POST http://api.biddy.example.com/api/products \
  -H "Authorization: Bearer $TOKEN" \
  -F "image=@sample.jpg" \
  -F "name=Test Product" \
  -F "price=10000"

# 모든 Product Pod에서 이미지 확인
kubectl exec -it deployment/product -n biddy-services -- ls -la /app/images
```

## S3 대안 (추천)

프로덕션 환경에서는 EFS 대신 **S3**를 권장합니다:

### EFS vs S3 비교

| 항목 | EFS | S3 |
|------|-----|-----|
| **비용** | 높음 ($0.30/GB/월) | 낮음 ($0.023/GB/월) |
| **성능** | 낮은 지연시간 | 높은 처리량 |
| **확장성** | 제한적 | 무제한 |
| **CDN 연동** | 어려움 | CloudFront 쉬운 연동 |
| **백업** | 수동 | 자동 버전 관리 |

### S3 사용 시 변경사항

1. **Product 서비스 코드 수정**
   - AWS SDK for Java (S3) 추가
   - 이미지 업로드 시 S3에 직접 업로드
   - URL: `https://biddy-images.s3.ap-northeast-2.amazonaws.com/{key}`

2. **IAM Role for Service Account (IRSA)**
   ```bash
   eksctl create iamserviceaccount \
     --name product-sa \
     --namespace biddy-services \
     --cluster biddy-eks-cluster \
     --attach-policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess \
     --approve
   ```

3. **Deployment 수정**
   ```yaml
   spec:
     template:
       spec:
         serviceAccountName: product-sa
   ```

4. **EFS PVC 제거**
   ```yaml
   # volumeMounts, volumes 섹션 제거
   ```

## 비용 예상

### EFS 비용 (ap-northeast-2)
- 저장 용량: $0.30/GB/월
- 100GB 이미지: **$30/월**
- 데이터 전송: 첫 1GB 무료, 이후 $0.09/GB

### S3 비용 (ap-northeast-2)
- 저장 용량: $0.023/GB/월
- 100GB 이미지: **$2.30/월**
- 데이터 전송: 첫 10TB 무료 (CloudFront 사용 시)

**결론**: 이미지 스토리지는 S3 사용 권장 (약 93% 비용 절감)

## 문제 해결

### EFS 마운트 실패

```bash
# Pod 이벤트 확인
kubectl describe pod <pod-name> -n biddy-services

# EFS CSI Driver 로그 확인
kubectl logs -f daemonset/efs-csi-node -n kube-system

# 일반적인 원인:
# 1. Security Group에서 NFS(2049) 포트 미허용
# 2. EFS 마운트 타겟이 없는 AZ에 Pod 배치
# 3. 잘못된 파일시스템 ID
```

### 성능 저하

```bash
# EFS 처리량 모드 변경 (Bursting → Provisioned)
aws efs update-file-system \
  --file-system-id fs-xxxxx \
  --throughput-mode provisioned \
  --provisioned-throughput-in-mibps 100
```

## 삭제

```bash
# PVC 삭제
kubectl delete pvc biddy-images-pvc -n biddy-services

# PV 삭제
kubectl delete pv biddy-images-pv

# StorageClass 삭제
kubectl delete sc efs-sc

# EFS 파일시스템 삭제 (AWS Console 또는 CLI)
aws efs delete-file-system --file-system-id fs-xxxxx
```

## 참고 자료

- [AWS EFS CSI Driver](https://github.com/kubernetes-sigs/aws-efs-csi-driver)
- [EFS 사용자 가이드](https://docs.aws.amazon.com/efs/latest/ug/)
- [EKS Storage Best Practices](https://aws.github.io/aws-eks-best-practices/scalability/docs/data-plane/#amazon-efs)
