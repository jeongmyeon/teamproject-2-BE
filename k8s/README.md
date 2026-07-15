# Biddy Kubernetes Deployment Draft

이 폴더는 Biddy 애플리케이션을 Kubernetes에 배포하기 위한 manifest입니다.
현재 구조에서는 Spring 서비스는 Kubernetes Pod로 띄우고, PostgreSQL/Redis/Kafka는 EC2 안의 Docker 컨테이너로 직접 띄운 뒤 Kubernetes Service/Endpoints로 연결합니다.

## 구성 원칙

- 외부에는 `apigateway`만 노출합니다.
- `member`, `product`, `order`, `auction`, `payment`는 ClusterIP Service로 내부 통신만 합니다.
- 공통 설정은 `base/common/configmap.yaml`에 둡니다.
- `postgres`, `redis`, `kafka` 이름은 Kubernetes 내부 DNS 이름이지만, 실제 대상은 `base/external-services/*-external.yaml`의 Endpoint IP입니다.
- 비밀번호, JWT, 메일, Toss secret은 `base/common/secret-template.yaml`을 실제 값으로 교체해서 사용합니다.

## 적용 전 필수 수정

1. EC2에서 `k8s/run-docker-services.sh`를 실행해 PostgreSQL(pgvector), Redis, Kafka Docker 컨테이너를 띄웁니다.
2. `base/external-services/*-external.yaml`의 Endpoint IP가 EC2 Private IP와 같은지 확인합니다.
3. 클러스터의 `biddy-secret`에 실제 AWS env 값을 넣습니다. 특히 Docker Postgres와 맞게 `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD=postgres123`이어야 하며, 상품 임베딩을 쓰려면 `OPENAI_API_KEY`도 필요합니다.
4. `base/common/configmap.yaml`의 `IMAGE_BASE_URL`을 실제 API 도메인으로 바꿉니다.
5. `base/ingress.yaml`의 `api.example.com`을 실제 도메인으로 바꿉니다.
6. 기존에 Kubernetes 내부 Postgres/Redis/Kafka Deployment를 올려둔 적이 있다면 replicas를 0으로 내려 외부 Docker 컨테이너만 쓰게 합니다.

## 적용

```bash
kubectl apply -k k8s/base
kubectl get pods -n biddy -o wide
kubectl get svc -n biddy
kubectl get endpoints postgres redis kafka -n biddy -o wide
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
- `postgres`, `redis`, `kafka`는 Kubernetes Pod가 아니라 EC2 Docker 컨테이너로 실행
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
