# Biddy Kubernetes Deployment Draft

이 폴더는 기존 `docker-compose.yml`을 Kubernetes manifest로 옮기기 위한 초안입니다.

## 구성 원칙

- 외부에는 `apigateway`만 노출합니다.
- `member`, `product`, `order`, `auction`, `payment`는 ClusterIP Service로 내부 통신만 합니다.
- 공통 설정은 `base/common/configmap.yaml`에 둡니다.
- 비밀번호, JWT, 메일, Toss secret은 `base/common/secret-template.yaml`을 실제 값으로 교체해서 사용합니다.
- `CHANGE_ME/biddy-*.latest` 이미지는 ECR, Docker Hub, GHCR 같은 실제 registry 주소로 바꿔야 합니다.

## 적용 전 필수 수정

1. `base/common/secret-template.yaml`의 `CHANGE_ME` 값을 실제 AWS env 값으로 교체합니다.
2. `base/common/configmap.yaml`의 `IMAGE_BASE_URL`을 실제 API 도메인으로 바꿉니다.
3. `base/ingress.yaml`의 `api.example.com`을 실제 도메인으로 바꿉니다.
4. 모든 `CHANGE_ME/biddy-...:latest` 이미지를 실제 registry 이미지로 바꿉니다.
5. 운영에서는 `emptyDir` 대신 StorageClass/PVC 또는 외부 DB/S3 사용을 검토합니다.

## 적용

```bash
kubectl apply -k k8s/base
kubectl get pods -n biddy -o wide
kubectl get svc -n biddy
kubectl get ingress -n biddy
```

## master와 worker 역할

### master/control-plane

- Kubernetes API Server
- scheduler
- controller-manager
- etcd
- `kubectl apply`를 받는 중앙 제어 지점
- manifest 파일을 직접 배치하는 곳이라기보다, manifest를 API Server에 적용하는 노드

### worker

- 실제 애플리케이션 Pod 실행
- `apigateway`, `member`, `product`, `order`, `auction`, `payment`
- `postgres`, `redis`, `kafka`도 현재 초안에서는 worker에서 실행
- kubelet, container runtime이 Pod를 받아 컨테이너로 실행

일반적으로 파일을 worker마다 복사하지 않습니다. manifest는 Git/repo에서 관리하고 master의 API Server에 한 번 적용하면, Kubernetes가 worker에 알아서 Pod를 배치합니다.

## 서비스 내부 주소

Kubernetes 안에서는 서비스 이름이 DNS가 됩니다.

```text
postgres:5432
redis:6379
kafka:9092
discovery:8761
config:8888
member:8081
product:8082
order:8083
auction:8084
payment:8085
apigateway:8000
```

브라우저/프론트엔드는 각 도메인 서비스를 직접 호출하지 말고 `apigateway`로 들어오게 하는 구성이 현재 Biddy MSA 구조와 가장 잘 맞습니다.

