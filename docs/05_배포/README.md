# 배포 가이드

> Biddy 마이크로서비스 애플리케이션의 배포 및 운영 가이드

---

## 📚 문서 구조

### 🚀 배포 가이드 (순서대로 읽기 권장)

#### 1. [실행 가이드](./01_실행가이드.md)
- 로컬 개발 환경 실행
- IntelliJ IDEA Compound Run Configuration
- 서비스 실행 순서 및 확인

#### 2. [Kubernetes 도입 가이드](./02_Kubernetes_도입_가이드.md)
- Kubernetes 개념 및 아키텍처
- MSA에서 K8s가 필요한 이유
- K8s 주요 개념 (Pod, Service, Deployment 등)

#### 3. [AWS EKS 배포 가이드](./03_AWS_EKS_배포_가이드.md)
- AWS EKS 클러스터 생성
- kubectl 설정
- 애플리케이션 배포
- 로드 밸런서 설정

#### 4. [SSH 접속 문제해결 가이드](./04_SSH_접속_문제해결_가이드.md)
- EC2 인스턴스 SSH 접속 문제 해결
- 키페어 권한 설정
- 네트워크 문제 해결

---

### 🔧 K8s 구축 및 개선 가이드

#### [AWS 자체구축 K8s 배포 문제점 분석](./AWS_자체구축_K8s_배포_문제점_분석.md)
- EC2에 직접 K8s 구축 시 발생하는 문제
- 리소스 부족 문제
- 해결 방안

#### [AWS EKS 배포 전 문제점 분석](./AWS_EKS_배포_전_문제점_분석.md)
- EKS 선택 전 고려사항
- 비용 분석
- 장단점 비교

#### [K8s 배포 개선 가이드 (EC2 제한)](./K8s_배포_개선_가이드_EC2제한.md)
- EC2 프리티어/저사양 환경 최적화
- 리소스 제약 극복 방법
- 효율적인 배포 전략

#### [K8s 노드별 역할과 배치 가이드](./K8s_노드별_역할과_배치_가이드.md)
- Master vs Worker 노드 역할
- 서비스 배치 전략
- nodeSelector, Tolerations 설정

---

### 📊 모니터링 가이드

#### [모니터링 시스템 가이드](./모니터링/)
Prometheus + Grafana 기반 모니터링 시스템 구축 및 운영

**순서대로 읽기:**
1. [모니터링 완전 가이드](./모니터링/01_모니터링_완전_가이드.md) - 설치 및 구축
2. [Spring Boot Prometheus 설정](./모니터링/02_Spring_Boot_Prometheus_설정_가이드.md) - 앱 메트릭
3. [Grafana 빠른 시작 (5분)](./모니터링/03_Grafana_빠른_시작.md) ⭐ - 대시보드 설정
4. [Grafana 완전 가이드](./모니터링/04_Grafana_완전_가이드.md) - 고급 기능
5. [모니터링 확인 가이드](./모니터링/05_모니터링_확인_가이드.md) - 시스템 검증

---

## 🗺️ 사용 시나리오

### 시나리오 1: 로컬 개발
```
01_실행가이드.md
```
- IntelliJ에서 전체 서비스 실행
- Eureka 대시보드 확인

### 시나리오 2: AWS 클라우드 배포 (EKS)
```
1. 02_Kubernetes_도입_가이드.md (개념 이해)
   ↓
2. 03_AWS_EKS_배포_가이드.md (배포 실행)
   ↓
3. 모니터링/모니터링_완전_가이드.md (모니터링 설정)
   ↓
4. 모니터링/Grafana_빠른_시작.md (대시보드 확인)
```

### 시나리오 3: EC2 자체 구축 (비용 절감)
```
1. AWS_자체구축_K8s_배포_문제점_분석.md (사전 검토)
   ↓
2. K8s_배포_개선_가이드_EC2제한.md (최적화 방법)
   ↓
3. K8s_노드별_역할과_배치_가이드.md (배치 전략)
   ↓
4. 모니터링/모니터링_완전_가이드.md (모니터링 설정)
```

### 시나리오 4: 모니터링만 추가
이미 K8s 클러스터가 있는 경우:
```
1. 모니터링/01_모니터링_완전_가이드.md (Prometheus/Grafana 설치)
   ↓
2. 모니터링/02_Spring_Boot_Prometheus_설정_가이드.md (앱 메트릭 활성화)
   ↓
3. 모니터링/03_Grafana_빠른_시작.md (대시보드 설정 - 5분)
```

---

## 🏗️ 시스템 아키텍처

### 전체 구성도

```
┌──────────────────────────────────────────────────────────────┐
│                        AWS Cloud                             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │          Kubernetes Cluster (EKS or EC2)               │ │
│  │                                                        │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐           │ │
│  │  │ Discovery│  │  Config  │  │ API      │           │ │
│  │  │  (8761)  │  │  (8888)  │  │ Gateway  │           │ │
│  │  │          │  │          │  │  (8000)  │           │ │
│  │  └──────────┘  └──────────┘  └─────┬────┘           │ │
│  │                                     │                 │ │
│  │  ┌──────────────────────────────────┼──────────────┐ │ │
│  │  │             Business Services    │              │ │ │
│  │  │  ┌────────┐  ┌────────┐  ┌──────┴────┐  ┌────┐│ │ │
│  │  │  │ Member │  │Product │  │  Auction  │  │... ││ │ │
│  │  │  │ (8081) │  │ (8082) │  │   (8084)  │  │    ││ │ │
│  │  │  └────────┘  └────────┘  └───────────┘  └────┘│ │ │
│  │  └───────────────────────────────────────────────┘ │ │
│  │                                                     │ │
│  │  ┌─────────────────────────────────────────────────┐ │
│  │  │         Infrastructure Services                 │ │
│  │  │  ┌──────────┐  ┌──────────┐  ┌──────────┐     │ │
│  │  │  │PostgreSQL│  │  Kafka   │  │  Redis   │     │ │
│  │  │  └──────────┘  └──────────┘  └──────────┘     │ │
│  │  └─────────────────────────────────────────────────┘ │
│  │                                                       │ │
│  │  ┌─────────────────────────────────────────────────┐ │
│  │  │         Monitoring (monitoring namespace)       │ │
│  │  │  ┌──────────┐  ┌──────────┐                   │ │
│  │  │  │Prometheus│  │ Grafana  │                   │ │
│  │  │  │  (30090) │  │  (30300) │                   │ │
│  │  │  └──────────┘  └──────────┘                   │ │
│  │  └─────────────────────────────────────────────────┘ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 마이크로서비스 구성

| 서비스 | 포트 | 설명 |
|--------|------|------|
| Discovery | 8761 | Eureka Server - 서비스 디스커버리 |
| Config | 8888 | Spring Cloud Config - 중앙 설정 관리 |
| API Gateway | 8000 | Spring Cloud Gateway - API 게이트웨이 |
| Member | 8081 | 회원 서비스 |
| Product | 8082 | 상품 서비스 |
| Order | 8083 | 주문 서비스 |
| Auction | 8084 | 경매 서비스 |
| Payment | 8085 | 결제 서비스 |

---

## 🔑 주요 포트

### 애플리케이션 포트
- **8000:** API Gateway
- **8761:** Eureka Discovery
- **8888:** Config Server
- **8081-8085:** Business Services

### 모니터링 포트 (NodePort)
- **30090:** Prometheus UI
- **30300:** Grafana UI

### 인프라 포트
- **5432:** PostgreSQL
- **9092:** Kafka
- **6379:** Redis

---

## 📋 배포 체크리스트

### 사전 준비
- [ ] AWS 계정 생성
- [ ] IAM 사용자 및 권한 설정
- [ ] AWS CLI 설치 및 설정
- [ ] kubectl 설치
- [ ] Docker 이미지 빌드 및 푸시 (GHCR)

### K8s 클러스터 구축
- [ ] EKS 클러스터 생성 (또는 EC2에 K8s 설치)
- [ ] kubectl 컨텍스트 설정
- [ ] 네임스페이스 생성 (default, monitoring)
- [ ] ConfigMap 및 Secret 설정

### 애플리케이션 배포
- [ ] PostgreSQL 배포
- [ ] Kafka 배포
- [ ] Redis 배포
- [ ] Discovery 서비스 배포
- [ ] Config 서비스 배포
- [ ] Business 서비스 배포
- [ ] API Gateway 배포

### 모니터링 설정
- [ ] Prometheus 배포
- [ ] Grafana 배포
- [ ] Node Exporter 배포
- [ ] kube-state-metrics 배포
- [ ] Spring Boot 메트릭 활성화
- [ ] Grafana 대시보드 임포트

### 검증
- [ ] 모든 Pod Running 상태 확인
- [ ] Eureka 대시보드에서 서비스 등록 확인
- [ ] API Gateway를 통한 서비스 호출 테스트
- [ ] Grafana 메트릭 수집 확인

---

## 🆘 문제 해결

### SSH 접속 문제
→ [04_SSH_접속_문제해결_가이드.md](./04_SSH_접속_문제해결_가이드.md)

### K8s 리소스 부족
→ [K8s_배포_개선_가이드_EC2제한.md](./K8s_배포_개선_가이드_EC2제한.md)

### 모니터링 연결 문제
→ [모니터링/Grafana_완전_가이드.md](./모니터링/Grafana_완전_가이드.md#9-문제-해결)

### Pod 실행 실패
```bash
# Pod 상태 확인
kubectl get pods -A

# 로그 확인
kubectl logs <pod-name> -n <namespace>

# 이벤트 확인
kubectl describe pod <pod-name> -n <namespace>
```

---

## 📚 참고 자료

### 공식 문서
- [Kubernetes 공식 문서](https://kubernetes.io/docs/)
- [Spring Cloud 공식 문서](https://spring.io/projects/spring-cloud)
- [AWS EKS 문서](https://docs.aws.amazon.com/eks/)
- [Prometheus 문서](https://prometheus.io/docs/)
- [Grafana 문서](https://grafana.com/docs/)

### 관련 프로젝트 문서
- [../README.md](../../README.md) - 프로젝트 전체 README
- [../06_회의록/](../06_회의록/) - 기술 회의록 및 의사결정 기록

---

## 🔄 CI/CD

### GitHub Actions
프로젝트는 GitHub Actions를 통해 자동 배포됩니다:

1. **PR 생성** → develop 브랜치
2. **자동 빌드** → Docker 이미지 빌드
3. **GHCR Push** → GitHub Container Registry
4. **자동 배포** → Kubernetes 클러스터

자세한 내용은 `.github/workflows/` 참고

---

**최종 업데이트:** 2026-07-09
**관리:** DevOps Team
**버전:** 2.0
