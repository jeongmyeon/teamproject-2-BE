# 모니터링 시스템 가이드

> Kubernetes 클러스터 및 Spring Boot 애플리케이션 모니터링을 위한 전체 가이드

---

## 📚 문서 목록 (순서대로 읽기)

### 📖 전체 설치 가이드 (처음부터 설치하는 경우)

#### 1️⃣ [모니터링 완전 가이드](./01_모니터링_완전_가이드.md)
- **대상:** Prometheus + Grafana 설치부터 시작하는 경우
- **소요 시간:** 20-30분
- **내용:**
  - 모니터링 시스템 아키텍처
  - Kubernetes에 Prometheus/Grafana 설치
  - 리소스 요구사항 및 확인
  - 설치 검증 및 문제 해결

#### 2️⃣ [Spring Boot Prometheus 설정 가이드](./02_Spring_Boot_Prometheus_설정_가이드.md)
- **대상:** Spring Boot 서비스에 메트릭 수집 설정
- **소요 시간:** 10-15분
- **내용:**
  - build.gradle 의존성 추가
  - application.yaml 설정
  - Kubernetes Deployment annotations
  - 메트릭 확인 방법

---

### 🚀 빠른 시작 (이미 설치 완료된 경우)

#### 3️⃣ [Grafana 빠른 시작 (5분)](./03_Grafana_빠른_시작.md) ⭐
- **대상:** 빠르게 Grafana 대시보드를 설정하고 싶은 경우
- **소요 시간:** 5분
- **내용:**
  - Grafana 로그인
  - Prometheus 연결
  - 필수 대시보드 3개 임포트

---

### 🔧 고급 설정 (커스터마이징 필요 시)

#### 4️⃣ [Grafana 완전 가이드](./04_Grafana_완전_가이드.md)
- **대상:** Grafana 대시보드 고급 설정 및 커스터마이징
- **소요 시간:** 30분+
- **내용:**
  - 데이터소스 연결 상세
  - 대시보드 임포트 (11개 추천 대시보드)
  - 커스텀 대시보드 생성
  - 주요 메트릭 쿼리 50+ 예시
  - 알람 설정
  - 문제 해결

---

### ✅ 확인 및 검증

#### 5️⃣ [모니터링 확인 가이드](./05_모니터링_확인_가이드.md) ⭐
- **대상:** 설치된 모니터링 시스템이 정상 작동하는지 확인
- **소요 시간:** 10-15분
- **내용:**
  - Prometheus 상태 확인 및 Targets 검증
  - Grafana 대시보드 데이터 확인
  - Spring Boot 서비스별 메트릭 확인
  - Node Exporter, kube-state-metrics 확인
  - 전체 시스템 헬스체크 스크립트

---

## 🗺️ 사용 시나리오별 가이드

### 시나리오 1: 처음부터 전체 설치
```
1. 01_모니터링_완전_가이드.md (Prometheus/Grafana 설치)
   ↓
2. 02_Spring_Boot_Prometheus_설정_가이드.md (앱 메트릭 설정)
   ↓
3. 03_Grafana_빠른_시작.md (대시보드 설정 - 5분)
```

### 시나리오 2: 이미 설치됨, Grafana만 설정
```
03_Grafana_빠른_시작.md (5분만에 완료!)
```

### 시나리오 3: 설치 완료 후 검증
```
1. 05_모니터링_확인_가이드.md (전체 시스템 확인)
   ↓
2. 문제 발견 시 해당 가이드 참고
```

### 시나리오 4: 고급 설정 및 커스터마이징
```
1. 03_Grafana_빠른_시작.md (기본 대시보드)
   ↓
2. 04_Grafana_완전_가이드.md (커스텀 대시보드, 알람 등)
```

---

## 🏗️ 모니터링 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│  Kubernetes 클러스터                                     │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Spring Boot  │  │ Spring Boot  │  │ Spring Boot  │  │
│  │   Service    │  │   Service    │  │   Service    │  │
│  │              │  │              │  │              │  │
│  │ /actuator/   │  │ /actuator/   │  │ /actuator/   │  │
│  │ prometheus   │  │ prometheus   │  │ prometheus   │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                 │           │
│         └─────────────────┼─────────────────┘           │
│                           │                             │
│                           ↓                             │
│                  ┌─────────────────┐                    │
│                  │   Prometheus    │                    │
│                  │  (메트릭 수집)   │                    │
│                  └────────┬────────┘                    │
│                           │                             │
│                           ↓                             │
│                  ┌─────────────────┐                    │
│                  │    Grafana      │                    │
│                  │ (대시보드 시각화)│                    │
│                  └─────────────────┘                    │
│                           │                             │
└───────────────────────────┼─────────────────────────────┘
                            │
                            ↓
                    브라우저로 접속
              http://<워커노드IP>:30300
```

---

## 📊 수집되는 메트릭

### 인프라 메트릭 (Node Exporter)
- ✅ CPU 사용률
- ✅ 메모리 사용률
- ✅ 디스크 I/O
- ✅ 네트워크 트래픽

### Kubernetes 메트릭 (kube-state-metrics)
- ✅ Pod 상태 (Running, Pending, Failed)
- ✅ Deployment 상태
- ✅ Node 상태
- ✅ 리소스 요청/제한

### Spring Boot 메트릭 (Micrometer)
- ✅ JVM 힙 메모리
- ✅ GC (Garbage Collection)
- ✅ 스레드 수
- ✅ HTTP 요청 (처리율, 레이턴시, 에러율)
- ✅ HikariCP 커넥션 풀
- ✅ Kafka 프로듀서/컨슈머

---

## 🔧 설치 요구사항

### 리소스
- **CPU:** 최소 2 vCPU (권장 4 vCPU)
- **메모리:** 최소 4GB (권장 8GB)
- **디스크:** 여유 공간 10GB 이상

### 환경
- **Kubernetes:** v1.28+
- **Spring Boot:** 3.x
- **Java:** 21

---

## 🚨 문제 해결

### Prometheus 연결 안 됨
→ [Grafana_완전_가이드.md - 문제 해결 섹션](./Grafana_완전_가이드.md#9-문제-해결)

### 대시보드에 "No data" 표시
→ [Grafana_완전_가이드.md - 문제 해결 섹션](./Grafana_완전_가이드.md#9-문제-해결)

### Spring Boot 메트릭 수집 안 됨
→ [Spring_Boot_Prometheus_설정_가이드.md - 문제 해결](./Spring_Boot_Prometheus_설정_가이드.md#문제-해결)

---

## 📌 빠른 링크

### 접속 URL
- **Prometheus:** `http://<워커노드IP>:30090`
- **Grafana:** `http://<워커노드IP>:30300`
  - Username: `admin`
  - Password: `admin123`

### 추천 Grafana 대시보드 ID
| ID | 이름 | 용도 |
|----|------|------|
| **1860** | Node Exporter Full | 서버 모니터링 |
| **6417** | Kubernetes Cluster | K8s 클러스터 |
| **11378** | Spring Boot Statistics | 앱 모니터링 |
| 4701 | JVM (Micrometer) | JVM 상세 |
| 12900 | Spring Boot APM | HTTP 성능 |
| 11085 | HikariCP | DB 커넥션 풀 |

---

## 🎯 체크리스트

### 설치 완료 확인
- [ ] Prometheus Pod Running
- [ ] Grafana Pod Running
- [ ] Node Exporter DaemonSet 실행
- [ ] kube-state-metrics Pod Running
- [ ] Prometheus Targets 확인 (`http://<워커노드IP>:30090/targets`)

### Grafana 설정 완료 확인
- [ ] Prometheus 데이터소스 연결
- [ ] 필수 대시보드 3개 임포트
- [ ] 메트릭 데이터 표시 확인

### Spring Boot 메트릭 확인
- [ ] 각 서비스 `/actuator/prometheus` 엔드포인트 접근 가능
- [ ] Prometheus Targets에 7개 비즈니스 서비스 등록 (Member, Product, Order, Auction, Payment, Search, Chat)
- [ ] Grafana에서 서비스별 메트릭 조회 가능

---

## 📚 관련 문서

### 상위 문서
- [../README.md](../README.md) - 전체 배포 가이드

### 참고 자료
- [Prometheus 공식 문서](https://prometheus.io/docs/)
- [Grafana 공식 문서](https://grafana.com/docs/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Grafana 대시보드 갤러리](https://grafana.com/grafana/dashboards/)

---

**작성일:** 2026-07-09
**버전:** 1.0
**관리:** DevOps Team
