# Grafana 대시보드 설정 가이드

> K3s Worker 노드 모니터링을 위한 필수 대시보드 설정

**소요 시간:** 10분
**업데이트:** 2026-07-22

---

## 🎯 이 가이드의 목표

모니터링 시스템 배포 후 Grafana에 **4개 핵심 대시보드**를 Import하여 즉시 사용 가능한 상태로 만들기

---

## 📋 준비사항

### 1. Grafana 접속 확인
```bash
# Worker IP 확인
kubectl get nodes -o wide

# 브라우저에서 접속
http://<worker-ip>:30300
```

### 2. 로그인 정보
```
Username: admin
Password: admin1234
```

---

## 🎨 1단계: Prometheus 데이터 소스 확인

### 자동 설정 확인

Prometheus 데이터 소스는 자동으로 설정되어 있습니다.

1. 왼쪽 메뉴: **⚙️ Configuration** → **Data sources**
2. **Prometheus** 클릭
3. 하단 **Save & test** 버튼
4. ✅ "Data source is working" 메시지 확인

### 문제가 있는 경우

```
URL이 잘못되었다면:
http://prometheus.biddy-monitoring.svc.cluster.local:9090

또는

http://prometheus:9090
```

---

## 📊 2단계: 필수 대시보드 4개 Import

### Dashboard 1: Node Exporter Full (ID: 1860) ⭐

**용도:** Worker 노드 인프라 모니터링 (CPU, 메모리, 디스크, 네트워크)

#### Import 방법
1. 왼쪽 메뉴: **+ (Create)** → **Import**
2. **Import via grafana.com** 입력창에: `1860`
3. **Load** 버튼 클릭
4. 설정:
   ```
   Name: Node Exporter Full (Worker)
   Folder: General
   Prometheus: Prometheus (default)
   ```
5. **Import** 버튼 클릭

#### 확인할 메트릭
- ✅ CPU Usage (%)
- ✅ Memory Usage (%)
- ✅ Disk I/O
- ✅ Network Traffic
- ✅ Swap Usage (2GB)

---

### Dashboard 2: Kubernetes Cluster Monitoring (ID: 6417) ⭐

**용도:** K3s 클러스터 전체 상태 모니터링

#### Import 방법
1. **+ (Create)** → **Import**
2. Dashboard ID: `6417`
3. **Load**
4. 설정:
   ```
   Name: Kubernetes Cluster (K3s)
   Folder: General
   Prometheus: Prometheus (default)
   ```
5. **Import**

#### 확인할 메트릭
- ✅ Cluster CPU/Memory
- ✅ Pod 상태 (Running/Pending/Failed)
- ✅ Deployment 상태
- ✅ PVC 사용량

---

### Dashboard 3: Spring Boot Statistics (ID: 11378) ⭐

**용도:** Spring Boot 서비스 11개 애플리케이션 모니터링

#### Import 방법
1. **+ (Create)** → **Import**
2. Dashboard ID: `11378`
3. **Load**
4. 설정:
   ```
   Name: Spring Boot Services (Biddy)
   Folder: General
   Prometheus: Prometheus (default)
   ```
5. **Import**

#### 확인할 메트릭
- ✅ JVM Heap Memory
- ✅ HTTP Request Rate
- ✅ HTTP Response Time
- ✅ Error Rate (5xx)
- ✅ Thread Count

#### 서비스 선택 방법
대시보드 상단의 **application** 드롭다운에서 서비스 선택:
- member
- product
- order
- auction
- payment
- chatbot
- search
- recommendation

---

### Dashboard 4: JVM (Micrometer) (ID: 4701)

**용도:** JVM 상세 모니터링 (GC, Thread, Class Loader)

#### Import 방법
1. **+ (Create)** → **Import**
2. Dashboard ID: `4701`
3. **Load**
4. 설정:
   ```
   Name: JVM Details (Micrometer)
   Folder: General
   Prometheus: Prometheus (default)
   instance: (서비스 선택)
   ```
5. **Import**

#### 확인할 메트릭
- ✅ Heap Memory (Eden, Old Gen)
- ✅ Garbage Collection (GC)
- ✅ Thread States
- ✅ Class Loading

---

## ⚙️ 3단계: 대시보드 커스터마이징

### 시간 범위 설정

오른쪽 상단 시간 선택기:
```
Last 6 hours     # 기본 권장
Last 24 hours    # 일일 트렌드
Last 7 days      # 주간 트렌드
```

### 자동 새로고침 설정

오른쪽 상단 새로고침 버튼:
```
5s   # 실시간 모니터링
30s  # 일반 모니터링 (권장)
1m   # 리소스 절약
```

### 즐겨찾기 설정

1. 대시보드 상단 **⭐ (Star)** 아이콘 클릭
2. Home 페이지에서 즐겨찾기 목록 표시

---

## 📱 4단계: 모바일 접근 설정 (선택)

### 외부 접근 URL

Worker 노드의 Public IP 사용:
```
http://<worker-public-ip>:30300
```

### 보안 그룹 설정 (AWS EC2)

Master/Worker 보안 그룹에 인바운드 규칙 추가:
```
Type: Custom TCP
Port: 30300
Source: My IP (또는 사무실 IP 대역)
```

---

## 🎯 5단계: 대시보드 확인 체크리스트

### Node Exporter Full
- [ ] CPU 사용률 표시됨 (40-60%)
- [ ] 메모리 사용률 표시됨 (85-92%)
- [ ] Swap 사용 중 표시됨 (~300MB/2GB)
- [ ] 디스크 읽기/쓰기 그래프 표시됨
- [ ] 네트워크 In/Out 그래프 표시됨

### Kubernetes Cluster
- [ ] Cluster CPU/Memory 요약 표시
- [ ] Pod 상태: Running 11개 이상
- [ ] Deployment: 11개 Available
- [ ] PVC: prometheus-pvc, grafana-pvc Bound

### Spring Boot Statistics
- [ ] application 드롭다운에서 서비스 선택 가능
- [ ] HTTP Request Rate 그래프 표시
- [ ] JVM Heap Memory 그래프 표시
- [ ] Response Time (p50, p95, p99) 표시

### JVM Details
- [ ] Heap Memory 상세 (Eden, Old) 표시
- [ ] GC Count/Time 그래프 표시
- [ ] Thread 상태 그래프 표시

---

## 🔧 문제 해결

### "No data" 또는 "N/A" 표시

#### 1. Prometheus Targets 확인
```bash
# 브라우저에서
http://<worker-ip>:30090/targets

# 모든 타겟이 UP 상태여야 함
```

#### 2. 메트릭 데이터 확인
```bash
# Prometheus 쿼리 테스트
http://<worker-ip>:30090/graph

# 쿼리 입력:
up

# Execute 버튼 → 결과에 서비스 목록 표시되어야 함
```

#### 3. 서비스 메트릭 확인
```bash
# Master 노드에서
kubectl exec -it <pod-name> -n biddy-services -- \
  curl localhost:8080/actuator/prometheus | head -20

# 메트릭 데이터 출력되어야 함
```

### 대시보드 Import 실패

**에러:** "Dashboard with same uid already exists"

**해결:**
1. Dashboards → Search → (기존 대시보드 삭제)
2. 다시 Import

또는

Import 시 **Change uid** → 새로운 고유 ID 입력

### 패널이 비어있음 (Empty panel)

**원인:** 쿼리가 현재 환경과 맞지 않음

**해결:**
1. 패널 Edit (연필 아이콘)
2. Query 확인
3. label 이름 수정:
   ```promql
   # 예시: namespace 변경
   {namespace="biddy-services"}  # 맞는 namespace
   {namespace="default"}         # 틀린 경우
   ```

---

## 📊 추가 추천 대시보드 (선택)

필요에 따라 추가로 Import:

| ID | 이름 | 용도 |
|---|---|---|
| 12900 | Spring Boot APM | HTTP 성능 상세 |
| 11085 | HikariCP | DB 커넥션 풀 |
| 7362 | Prometheus Stats | Prometheus 자체 모니터링 |
| 6126 | Kubernetes Deployment | Deployment 상세 |

---

## 💡 팁과 Best Practices

### 1. 대시보드 즐겨찾기 순서
```
1. Node Exporter Full        # 인프라 먼저 확인
2. Spring Boot Statistics     # 애플리케이션 상태
3. Kubernetes Cluster         # 클러스터 전체 상태
4. JVM Details                # 문제 발생 시 상세 분석
```

### 2. 알람 패널 추가

각 대시보드에 Alert 상태 패널 추가:
1. Dashboard 상단 **Add panel**
2. **Stat** 선택
3. Query:
   ```promql
   sum(ALERTS{alertstate="firing"})
   ```
4. 제목: "🚨 Active Alerts"

### 3. 변수(Variable) 활용

대시보드 상단에 서비스 선택 드롭다운 추가:
1. Dashboard settings (⚙️) → **Variables**
2. **Add variable**
3. 설정:
   ```
   Name: application
   Type: Query
   Query: label_values(jvm_memory_used_bytes, application)
   ```

### 4. 스냅샷 저장

중요한 순간 캡처:
1. Dashboard 상단 **Share** (공유 아이콘)
2. **Snapshot** 탭
3. **Local Snapshot** 선택
4. **Publish to snapshot** 클릭

---

## 🔗 관련 링크

- [Grafana 대시보드 갤러리](https://grafana.com/grafana/dashboards/)
- [Prometheus 쿼리 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [PromQL 치트시트](https://promlabs.com/promql-cheat-sheet/)

---

## ✅ 완료 확인

대시보드 설정이 완료되었다면:
- [ ] 4개 필수 대시보드 Import 완료
- [ ] 모든 대시보드에서 데이터 표시 확인
- [ ] 시간 범위 및 자동 새로고침 설정
- [ ] 즐겨찾기 추가

**다음 단계:** [03_알림_설정_가이드.md](./03_알림_설정_가이드.md) - Slack/Discord 알림 연동

---

**문서 버전:** 1.0
**최종 업데이트:** 2026-07-22
**담당:** DevOps Team
