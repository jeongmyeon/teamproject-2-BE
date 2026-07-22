# K3s 모니터링 시스템 가이드

> K3s Worker 노드에서 실행되는 Prometheus + Grafana 모니터링 스택

**업데이트:** 2026-07-22
**버전:** 2.0 (K3s Worker 배포)

---

## 🚀 빠른 시작 (5분)

### ⚡ 바로 시작하기
👉 **[지금_바로_적용하기.md](./지금_바로_적용하기.md)** ⭐ **가장 빠른 방법!**

5분만에 Worker 노드에 모니터링 시스템을 배포하고 Grafana 대시보드까지 완료!

---

## 📚 문서 목록

### 1️⃣ 필수 가이드

| 문서 | 설명 | 소요시간 |
|------|------|----------|
| **[지금_바로_적용하기.md](./지금_바로_적용하기.md)** ⭐ | Worker 노드 배포 5분 완성 가이드 | 5분 |
| [02_Grafana_대시보드_설정_가이드.md](./02_Grafana_대시보드_설정_가이드.md) | 대시보드 Import 및 설정 | 10분 |
| [03_알림_설정_가이드.md](./03_알림_설정_가이드.md) | Slack/Discord 알림 연동 | 10분 |
| [04_문제해결_가이드.md](./04_문제해결_가이드.md) | 트러블슈팅 | - |

### 2️⃣ 참고 가이드 (선택)

| 문서 | 설명 |
|------|------|
| [01_모니터링_완전_가이드.md](./01_모니터링_완전_가이드.md) | 상세 아키텍처 및 이론 |
| [02_Spring_Boot_Prometheus_설정_가이드.md](./02_Spring_Boot_Prometheus_설정_가이드.md) | 앱 메트릭 설정 (이미 완료됨) |
| [05_모니터링_확인_가이드.md](./05_모니터링_확인_가이드.md) | 시스템 검증 |

---

## 🏗️ 아키텍처 (K3s 환경)

```
┌─────────────────────────────────────────────────────────────┐
│  Master 노드 (kubectl 명령 실행)                             │
│  - K3s Control Plane                                         │
│  - SSH로 접속하여 배포 명령 실행                              │
└────────────────────────┬────────────────────────────────────┘
                         │
                         │ kubectl apply
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  Worker 노드 (Pod 실행)                                      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  biddy-monitoring Namespace                          │  │
│  │                                                       │  │
│  │  ┌─────────────┐        ┌─────────────┐             │  │
│  │  │ Prometheus  │───────→│  Grafana    │             │  │
│  │  │   (메트릭)   │        │ (대시보드)  │             │  │
│  │  │  :30090     │        │   :30300    │             │  │
│  │  └──────┬──────┘        └─────────────┘             │  │
│  │         │                                            │  │
│  └─────────┼────────────────────────────────────────────┘  │
│            │                                               │
│            │ 메트릭 수집                                   │
│            ↓                                               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Spring Boot Services (biddy-services)               │  │
│  │  - member, product, order, auction, payment          │  │
│  │  - chatbot, search                                   │  │
│  │  → /actuator/prometheus (포트 8080)                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  메모리: 5GB → 6.5-7GB (85-92%)                              │
│  Swap: 2GB 추가 필수 ⚠️                                       │
└─────────────────────────────────────────────────────────────┘
                         │
                         │ NodePort 접속
                         ↓
                 브라우저 (개발자 PC)
         http://<worker-ip>:30300 (Grafana)
         http://<worker-ip>:30090 (Prometheus)
```

---

## 📊 구성 요소

### Prometheus (메트릭 수집)
- **버전:** v2.48.0
- **Replicas:** 1개 고정 (메모리 절약)
- **메모리:** 1GB ~ 2GB
- **스토리지:** 20GB (15일 보관)
- **Alert Rules:** 10개 자동 설정 🚨
- **포트:** 30090 (NodePort)
- **접속:** `http://<worker-ip>:30090`

### Grafana (시각화)
- **버전:** 10.2.2
- **Replicas:** 1개 고정
- **메모리:** 512MB ~ 1GB
- **스토리지:** 10GB
- **계정:** admin / admin1234
- **포트:** 30300 (NodePort)
- **접속:** `http://<worker-ip>:30300`

### 자동 알림 (Alert Rules) 🚨
1. **PodDown** - 서비스 다운 2분 이상
2. **HighMemoryUsage** - 메모리 90% 이상
3. **HighCpuUsage** - CPU 90% 이상
4. **HighErrorRate** - 5xx 에러율 5% 이상
5. **HighLatency** - 응답시간 1초 이상
6. **NodeMemoryPressure** - Worker 메모리 95% 이상
7. **HighDiskUsage** - 디스크 90% 이상
8. **PrometheusTargetDown** - 타겟 다운
9. **PrometheusNotReady** - Prometheus 다운
10. **GrafanaDown** - Grafana 다운

---

## 🎯 모니터링 대상

### Spring Boot 서비스 (11개)
- discovery (Eureka)
- config (Config Server)
- apigateway (Gateway)
- member, product, order
- auction, payment
- chatbot, search ✨ NEW
- recommendation

### Kubernetes 리소스
- Nodes (Master, Worker)
- Pods (전체 네임스페이스)
- Deployments, Services
- PVC, ConfigMap

### 인프라 메트릭
- CPU, Memory, Disk
- Network I/O
- Process 상태

---

## 🔧 시스템 요구사항

### Worker 노드
- **메모리:** 7.6GB (현재 5GB 사용 → 6.5-7GB 예상)
- **Swap:** 2GB **필수** ⚠️
- **디스크:** 30GB 여유 공간
- **CPU:** 4 vCPU

### Master 노드
- kubectl 명령 실행용
- Pod는 배포되지 않음

---

## 📖 사용 시나리오

### 시나리오 1: 처음 배포 (가장 일반적)
```bash
1. 지금_바로_적용하기.md 따라 5분만에 배포
   ↓
2. Grafana 대시보드 Import (4개 추천)
   ↓
3. Slack/Discord 알림 연동 (선택)
```

### 시나리오 2: 알림만 설정
```bash
1. Prometheus에서 Alert 확인
   http://<worker-ip>:30090/alerts
   ↓
2. 03_알림_설정_가이드.md 참고
   - Slack Webhook 연동
   - Discord Webhook 연동
```

### 시나리오 3: 문제 해결
```bash
1. 04_문제해결_가이드.md 확인
   - Pod 상태 확인
   - 메모리 부족
   - Swap 설정
   - 타겟 다운
```

---

## 🌐 접속 정보

### Grafana (대시보드)
```
URL: http://<worker-node-ip>:30300
Username: admin
Password: admin1234
```

### Prometheus (메트릭)
```
URL: http://<worker-node-ip>:30090
Targets: http://<worker-node-ip>:30090/targets
Alerts: http://<worker-node-ip>:30090/alerts
```

### Worker IP 확인 방법
```bash
# Master 노드에서 실행
kubectl get nodes -o wide

# 또는
kubectl get svc -n biddy-monitoring grafana
```

---

## ✅ 체크리스트

### 배포 완료 확인
- [ ] Worker 노드에 Swap 2GB 추가
- [ ] `kubectl apply -f namespace.yaml` 성공
- [ ] Prometheus Pod Running
- [ ] Grafana Pod Running
- [ ] PVC 2개 Bound 상태

### 모니터링 동작 확인
- [ ] Prometheus Targets Up (http://워커IP:30090/targets)
- [ ] Grafana 로그인 성공 (http://워커IP:30300)
- [ ] Grafana 대시보드 4개 Import
- [ ] 메트릭 데이터 표시 확인

### 알림 설정 확인
- [ ] Prometheus Alerts 페이지 확인
- [ ] Slack/Discord Webhook 연동 (선택)
- [ ] 테스트 알림 전송 성공

---

## 🚨 빠른 문제 해결

### Pod가 Pending 상태
```bash
# PVC 상태 확인
kubectl get pvc -n biddy-monitoring

# local-path provisioner 확인
kubectl get pods -n kube-system | grep local-path
```

### 메모리 부족 (OOMKilled)
```bash
# Worker 노드에 Swap 추가 (필수!)
ssh worker-node
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

### 대시보드에 "No data"
```bash
# Prometheus Targets 확인
curl http://<worker-ip>:30090/targets

# Spring Boot 메트릭 확인
kubectl exec -it <pod-name> -n biddy-services -- curl localhost:8080/actuator/prometheus
```

---

## 📚 관련 링크

### 공식 문서
- [Prometheus 문서](https://prometheus.io/docs/)
- [Grafana 문서](https://grafana.com/docs/)
- [K3s 문서](https://docs.k3s.io/)

### Grafana 대시보드
- [대시보드 갤러리](https://grafana.com/grafana/dashboards/)
- 추천 ID: 1860, 6417, 11378, 4701

---

**문서 버전:** 2.0
**최종 업데이트:** 2026-07-22
**담당:** DevOps Team
