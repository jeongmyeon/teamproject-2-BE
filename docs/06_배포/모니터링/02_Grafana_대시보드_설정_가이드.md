# Grafana 대시보드 설정 가이드

**작성일**: 2026-07-22
**환경**: Kubernetes + Prometheus + Grafana
**대상**: Spring Boot 애플리케이션 모니터링

---

## 📋 목차

1. [개요](#개요)
2. [기본 대시보드 Import](#기본-대시보드-import)
3. [변수 수정 방법](#변수-수정-방법)
4. [커스텀 대시보드 생성](#커스텀-대시보드-생성)
5. [추천 대시보드 목록](#추천-대시보드-목록)
6. [문제 해결](#문제-해결)

---

## 개요

Grafana는 Prometheus에서 수집한 메트릭을 시각화하는 도구입니다. 이 가이드에서는 현재 시스템에 최적화된 대시보드를 설정하는 방법을 설명합니다.

### 현재 수집 중인 메트릭

- **Node Exporter**: CPU, 메모리, 디스크, 네트워크 (노드 레벨)
- **kube-state-metrics**: Pod, Deployment, Service 상태
- **Spring Boot Actuator**: JVM, HTTP, HikariCP, Tomcat
- **cAdvisor**: 컨테이너 리소스 사용량

---

## 기본 대시보드 Import

### 1. Grafana 접속

```
http://<워커노드-IP>:30300
계정: admin
비밀번호: biddy-monitoring-2026
```

### 2. 대시보드 Import

**왼쪽 메뉴 → Dashboards → New → Import**

#### 추천 대시보드

| ID | 이름 | 용도 | 상태 |
|----|------|------|------|
| **1860** | Node Exporter Full | 서버 리소스 상세 | ✅ 바로 작동 |
| **6417** | Kubernetes Cluster | 클러스터 전체 상태 | ✅ 대부분 작동 |
| **4701** | JVM (Micrometer) | Spring Boot JVM | ✅ 바로 작동 |
| **8588** | K8s Deployment | Deployment 상세 | ✅ 바로 작동 |

### 3. Import 절차

1. **ID 입력** (예: `1860`)
2. **Load** 클릭
3. **Prometheus 데이터소스 선택**
4. **Import** 클릭

---

## 변수 수정 방법

일부 대시보드는 변수(Variables) 설정이 현재 메트릭과 맞지 않아 "N/A"가 표시됩니다.

### 문제: Spring Boot Statistics 대시보드

**증상**: Instance, Application 변수가 선택되지 않음

**원인**: 대시보드가 `jvm_classes_loaded` 메트릭을 찾지만, 실제로는 다른 메트릭 이름 사용

### 해결 방법

#### 1. 변수 편집 화면 열기

1. 대시보드 열기
2. **우측 상단 ⚙️ Settings**
3. **왼쪽 메뉴 → Variables**

#### 2. instance 변수 수정

```yaml
Name: instance
Label: Instance
Query type: Label values
Label: instance
Metric: jvm_memory_used_bytes
Label filters: 비워두기
Regex: 비워두기
Sort: Alphabetical (asc)
Refresh: On Dashboard Load
Multi-value: ✓
Include All option: ✓
```

**Preview of values 확인**: `10.42.1.126:8081`, `10.42.0.104:8000` 등이 표시되어야 함

#### 3. application 변수 수정

```yaml
Name: application
Label: Application
Query type: Label values
Label: app
Metric: jvm_memory_used_bytes
Label filters:
  - instance = $instance (선택사항)
Regex: 비워두기
Sort: Alphabetical (asc)
Refresh: On Dashboard Load
Multi-value: ✓
Include All option: ✓
```

**Preview of values 확인**: `member`, `product`, `order` 등이 표시되어야 함

#### 4. 저장

**우측 상단 💾 Save dashboard**

---

## 커스텀 대시보드 생성

### JSON Import 방식

현재 시스템에 최적화된 대시보드 JSON 파일:

**파일 위치**: `k8s/monitoring/dashboards/spring-boot-dashboard.json`

#### Import 방법

1. **Grafana → Dashboards → Import**
2. **Upload JSON file**
3. `spring-boot-dashboard.json` 선택
4. **Prometheus 데이터소스 선택**
5. **Import**

#### 포함된 패널

1. **JVM Heap Memory Usage** - Heap 메모리 사용률 (%)
2. **HTTP Requests Rate** - 초당 HTTP 요청 수
3. **HTTP Response Time (P95)** - 95 백분위 응답 시간
4. **HikariCP Active Connections** - DB 커넥션 풀 사용량
5. **GC Time** - Garbage Collection 시간
6. **Tomcat Active Sessions** - 활성 세션 수

### 직접 패널 만들기

#### 1. 새 대시보드 생성

**Dashboards → New → New Dashboard → Add visualization**

#### 2. 유용한 Prometheus 쿼리

##### JVM Heap 메모리 사용률 (%)
```promql
(jvm_memory_used_bytes{area="heap", job="kubernetes-pods"} / jvm_memory_max_bytes{area="heap", job="kubernetes-pods"}) * 100
```

**Panel 설정**:
- **Visualization**: Time series
- **Legend**: `{{app}} - {{instance}}`
- **Unit**: Percent (0-100)
- **Thresholds**: 80% (Warning), 90% (Critical)

##### HTTP 초당 요청 수
```promql
sum(rate(http_server_requests_seconds_count{job="kubernetes-pods"}[5m])) by (app)
```

**Panel 설정**:
- **Visualization**: Time series
- **Legend**: `{{app}}`
- **Unit**: Requests/sec

##### HTTP 응답 시간 (P95)
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="kubernetes-pods"}[5m])) by (app, le))
```

**Panel 설정**:
- **Visualization**: Time series
- **Legend**: `{{app}} P95`
- **Unit**: Seconds
- **Thresholds**: 1s (Warning), 3s (Critical)

##### HTTP 응답 시간 (P99)
```promql
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{job="kubernetes-pods"}[5m])) by (app, le))
```

##### JVM Non-Heap 메모리
```promql
jvm_memory_used_bytes{area="nonheap", job="kubernetes-pods"}
```

**Panel 설정**:
- **Unit**: Bytes (IEC)

##### HTTP 에러율 (5xx)
```promql
sum(rate(http_server_requests_seconds_count{status=~"5..", job="kubernetes-pods"}[5m])) by (app) / sum(rate(http_server_requests_seconds_count{job="kubernetes-pods"}[5m])) by (app) * 100
```

**Panel 설정**:
- **Unit**: Percent (0-100)
- **Thresholds**: 1% (Warning), 5% (Critical)

##### HikariCP 커넥션 풀 사용량
```promql
hikaricp_connections_active{job="kubernetes-pods"}
```

**Panel 설정**:
- **Legend**: `{{app}} - {{pool}}`

##### HikariCP 최대 커넥션 대비 사용률
```promql
(hikaricp_connections_active{job="kubernetes-pods"} / hikaricp_connections_max{job="kubernetes-pods"}) * 100
```

##### GC 시간
```promql
rate(jvm_gc_pause_seconds_sum{job="kubernetes-pods"}[5m])
```

**Panel 설정**:
- **Legend**: `{{app}} - {{action}} - {{cause}}`
- **Unit**: Seconds

##### GC 빈도 (초당 횟수)
```promql
rate(jvm_gc_pause_seconds_count{job="kubernetes-pods"}[5m])
```

##### Tomcat 활성 세션
```promql
tomcat_sessions_active_current_sessions{job="kubernetes-pods"}
```

##### 스레드 수
```promql
jvm_threads_live_threads{job="kubernetes-pods"}
```

##### CPU 사용률 (프로세스)
```promql
process_cpu_usage{job="kubernetes-pods"} * 100
```

**Panel 설정**:
- **Unit**: Percent (0-100)

##### 시스템 CPU 사용률
```promql
system_cpu_usage{job="kubernetes-pods"} * 100
```

##### 로그백 에러 로그 수
```promql
rate(logback_events_total{level="error", job="kubernetes-pods"}[5m])
```

---

## ConfigMap으로 대시보드 자동 배포

대시보드를 Kubernetes ConfigMap으로 관리하여 자동으로 로드할 수 있습니다.

### 1. ConfigMap 생성

**파일 위치**: `k8s/monitoring/grafana-dashboard-configmap.yaml`

### 2. 적용

```bash
kubectl apply -f k8s/monitoring/grafana-dashboard-configmap.yaml
```

### 3. Grafana 설정 필요

Grafana Deployment에 dashboard provider 설정이 필요합니다:

```yaml
# Grafana ConfigMap에 추가
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboard-provider
  namespace: biddy-monitoring
data:
  dashboards.yaml: |
    apiVersion: 1
    providers:
    - name: 'default'
      orgId: 1
      folder: ''
      type: file
      disableDeletion: false
      updateIntervalSeconds: 10
      allowUiUpdates: true
      options:
        path: /var/lib/grafana/dashboards
```

---

## 추천 대시보드 목록

### 인프라 모니터링

#### Node Exporter Full (1860)
- **상태**: ✅ 바로 작동
- **메트릭**: CPU, 메모리, 디스크, 네트워크, I/O
- **용도**: 서버 리소스 상세 모니터링

#### Kubernetes Cluster Monitoring (6417)
- **상태**: ⚠️ 일부 메트릭 N/A
- **메트릭**: Pod, Deployment, Node 상태
- **용도**: 클러스터 전체 상태 확인

#### Kubernetes Cluster (Prometheus) (3119)
- **상태**: ⚠️ 일부 메트릭 N/A
- **메트릭**: 클러스터 리소스 사용량
- **용도**: 클러스터 리소스 추세 분석

#### USE Method: Cluster (5228)
- **상태**: ✅ 작동
- **메트릭**: Utilization, Saturation, Errors
- **용도**: 리소스 병목 지점 파악

### 애플리케이션 모니터링

#### JVM (Micrometer) (4701)
- **상태**: ✅ 바로 작동
- **메트릭**: JVM Heap, GC, 스레드
- **용도**: Spring Boot JVM 상세 모니터링

#### Spring Boot 2.1 System Monitor (11378)
- **상태**: ✅ 작동
- **메트릭**: Spring Boot Actuator 전체
- **용도**: Spring Boot 서비스 종합 모니터링

#### Spring Boot Statistics (6756)
- **상태**: ⚠️ 변수 수정 필요
- **메트릭**: HTTP, JVM, HikariCP, Tomcat
- **용도**: Spring Boot 통계 대시보드

### Kubernetes 리소스

#### Kubernetes Deployment Statefulset Daemonset (8588)
- **상태**: ✅ 바로 작동
- **메트릭**: Deployment, Replica, 재시작 횟수
- **용도**: Deployment 상태 추적

#### Kubernetes API Server (12006)
- **상태**: ✅ 작동
- **메트릭**: API 요청, 응답 시간, 에러율
- **용도**: API Server 성능 분석

---

## 문제 해결

### 문제 1: "N/A" 표시

**증상**: 대시보드 패널에 "N/A" 또는 "No data" 표시

**원인**:
1. 메트릭 이름이 대시보드와 다름
2. Label 이름이 다름 (예: `application` vs `app`)
3. Job 이름 필터 누락

**해결**:
1. Prometheus UI에서 실제 메트릭 이름 확인
   ```
   http://<워커IP>:30090/api/v1/label/__name__/values
   ```

2. 패널 쿼리 수정
   - **Edit Panel → Query**
   - 쿼리에 `job="kubernetes-pods"` 추가

3. 변수 수정 (위 [변수 수정 방법](#변수-수정-방법) 참조)

### 문제 2: 변수 드롭다운이 비어있음

**증상**: Instance, Application 드롭다운에 선택할 값이 없음

**원인**: 변수 쿼리가 잘못됨

**해결**:
1. **Settings → Variables → 변수 클릭**
2. **Metric** 필드를 실제 존재하는 메트릭으로 변경
   - `jvm_classes_loaded` → `jvm_memory_used_bytes`
3. **Label** 필드 확인
   - `application` → `app`
4. **Preview of values**에서 값이 표시되는지 확인

### 문제 3: Graph가 너무 느림

**증상**: 대시보드 로딩이 매우 느림

**원인**:
1. 시간 범위가 너무 넓음
2. 쿼리가 비효율적
3. 너무 많은 시계열 데이터

**해결**:
1. 시간 범위 축소
   - Last 24 hours → Last 1 hour
2. 쿼리 최적화
   - `sum by (app)` 사용하여 그룹화
3. Refresh 간격 조정
   - 5s → 30s 또는 1m

### 문제 4: 대시보드 JSON Import 실패

**증상**: "Dashboard JSON is invalid" 오류

**원인**: JSON 형식 오류

**해결**:
1. JSON 파일을 온라인 validator로 검증
   - https://jsonlint.com/
2. 중괄호, 대괄호 쌍 확인
3. 쉼표 누락 확인

---

## 대시보드 설계 Best Practices

### 1. 계층적 구성

```
1. Overview 대시보드
   - 전체 시스템 상태 한눈에
   - 핵심 지표만 표시

2. 서비스별 상세 대시보드
   - Member, Product, Order 등
   - 개별 서비스 깊이 있는 분석

3. 인프라 대시보드
   - 노드, 네트워크, 스토리지
   - 리소스 추세 분석
```

### 2. 패널 구성 원칙

- **상단**: 핵심 지표 (RED - Rate, Errors, Duration)
- **중단**: 리소스 사용량 (CPU, 메모리)
- **하단**: 상세 메트릭 (GC, Thread, Connection Pool)

### 3. Alert 통합

주요 메트릭에 Alert 설정:
- CPU > 80%
- 메모리 > 90%
- HTTP 5xx 에러율 > 1%
- 응답 시간 P95 > 1s

### 4. 변수 활용

모든 대시보드에 기본 변수 추가:
- `$app`: 애플리케이션 선택
- `$instance`: 인스턴스 선택
- `$interval`: 시간 범위 자동 조정

---

## 참고 자료

- [Grafana 공식 문서](https://grafana.com/docs/)
- [Prometheus 쿼리 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana 대시보드 갤러리](https://grafana.com/grafana/dashboards/)
- [Spring Boot Actuator 메트릭](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics)

---

**작성자**: Claude (AI Assistant)
**최종 업데이트**: 2026-07-22
**환경**: Kubernetes + Prometheus + Grafana + Spring Boot
**실시간 모니터링**: ✅ 완벽 지원
