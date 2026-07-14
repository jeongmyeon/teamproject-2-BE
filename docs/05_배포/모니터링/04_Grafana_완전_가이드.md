# Grafana 모니터링 대시보드 설정 가이드

## 📋 목차
1. [Grafana 접속 및 로그인](#1-grafana-접속-및-로그인)
2. [Prometheus 데이터소스 연결](#2-prometheus-데이터소스-연결)
3. [사전 제작된 대시보드 임포트](#3-사전-제작된-대시보드-임포트)
4. [커스텀 대시보드 생성](#4-커스텀-대시보드-생성)
5. [주요 메트릭 쿼리 예시](#5-주요-메트릭-쿼리-예시)

---

## 1. Grafana 접속 및 로그인

### 1.1 접속 URL
```
http://<워커노드-퍼블릭-IP>:30300
```

**워커 노드 IP 확인:**
```bash
# Master 노드에서 실행
kubectl get nodes -o wide
```

### 1.2 초기 로그인
- **Username:** `admin`
- **Password:** `admin123`

> ⚠️ 첫 로그인 후 비밀번호 변경 권장

---

## 2. Prometheus 데이터소스 연결

### 2.1 데이터소스 추가
1. 좌측 메뉴 → **⚙️ Configuration** → **Data sources**
2. **Add data source** 버튼 클릭
3. **Prometheus** 선택

### 2.2 Prometheus 설정

먼저 Prometheus 서비스의 ClusterIP를 확인합니다 (Master 노드에서 실행):
```bash
kubectl get svc -n monitoring prometheus -o jsonpath='{.spec.clusterIP}'
```

다음 정보를 입력:

| 항목 | 값 |
|------|-----|
| Name | `Prometheus` |
| URL | `http://<위에서-확인한-ClusterIP>:9090` (예: `http://10.99.6.2:9090`) |
| Access | `Server (default)` |

> 💡 **참고:** 또는 DNS 이름 사용 가능: `http://prometheus.monitoring.svc.cluster.local:9090`

### 2.3 저장 및 테스트
1. 하단의 **Save & test** 버튼 클릭
2. ✅ "Data source is working" 메시지 확인

---

## 3. 사전 제작된 대시보드 임포트

### 3.1 추천 대시보드 목록

#### 🖥️ 인프라 모니터링
| 대시보드 | ID | 용도 |
|---------|-----|------|
| Node Exporter Full | 1860 | 서버 CPU, 메모리, 디스크, 네트워크 |
| Kubernetes Cluster Monitoring | 6417 | K8s 클러스터 전체 상태 |
| Kubernetes Pod Monitoring | 6781 | Pod별 리소스 사용량 |

#### ☕ Spring Boot 애플리케이션
| 대시보드 | ID | 용도 |
|---------|-----|------|
| Spring Boot 2.1 Statistics | 11378 | Spring Boot 전체 메트릭 |
| JVM (Micrometer) | 4701 | JVM 힙, GC, 스레드 |
| Spring Boot APM Dashboard | 12900 | HTTP 요청, 응답 시간 |

#### 🗄️ 데이터베이스
| 대시보드 | ID | 용도 |
|---------|-----|------|
| HikariCP | 11085 | 커넥션 풀 모니터링 |

### 3.2 대시보드 임포트 방법

#### Step 1: Import 메뉴 열기
1. 좌측 메뉴 → **➕** (Create) → **Import**

#### Step 2: Dashboard ID 입력
1. **Import via grafana.com** 필드에 대시보드 ID 입력
   - 예: `1860` (Node Exporter Full)
2. **Load** 버튼 클릭

#### Step 3: 설정 및 임포트
1. **Name:** 대시보드 이름 (자동 입력됨)
2. **Folder:** `General` (또는 원하는 폴더)
3. **Prometheus:** 앞서 생성한 `Prometheus` 데이터소스 선택
4. **Import** 버튼 클릭

#### Step 4: 반복
위 3.1 표의 모든 대시보드에 대해 반복

---

## 4. 커스텀 대시보드 생성

### 4.1 새 대시보드 만들기
1. 좌측 메뉴 → **➕** (Create) → **Dashboard**
2. **Add new panel** 클릭

### 4.2 서비스별 대시보드 예시

#### 예시 1: Member Service JVM 메모리
1. **Query** 탭에서 다음 입력:
   ```promql
   jvm_memory_used_bytes{application="member-service", area="heap"}
   ```
2. **Panel options**:
   - Title: `Member Service - Heap Memory Usage`
   - Description: `힙 메모리 사용량`
3. **Visualization**: `Time series` (기본값)
4. **Apply** 클릭

#### 예시 2: HTTP 요청 처리율 (전체 서비스)
1. **Query** 입력:
   ```promql
   rate(http_server_requests_seconds_count[5m])
   ```
2. **Legend**: `{{application}} - {{uri}}`
3. **Panel options**:
   - Title: `HTTP Request Rate (per second)`
4. **Apply** 클릭

#### 예시 3: 서비스별 응답 시간
1. **Query** 입력:
   ```promql
   histogram_quantile(0.95,
     rate(http_server_requests_seconds_bucket[5m])
   )
   ```
2. **Legend**: `{{application}} - p95`
3. **Panel options**:
   - Title: `Response Time (95th percentile)`
   - Unit: `seconds (s)`
4. **Apply** 클릭

### 4.3 대시보드 저장
1. 우측 상단 💾 아이콘 클릭
2. **Dashboard name** 입력: `Spring Boot Services Overview`
3. **Folder**: 원하는 폴더 선택
4. **Save** 클릭

---

## 5. 주요 메트릭 쿼리 예시

### 5.1 JVM 메트릭

#### 힙 메모리 사용률
```promql
(jvm_memory_used_bytes{application="member-service", area="heap"} /
 jvm_memory_max_bytes{application="member-service", area="heap"}) * 100
```

#### GC 시간 (초당)
```promql
rate(jvm_gc_pause_seconds_sum[5m])
```

#### 활성 스레드 수
```promql
jvm_threads_live_threads{application="member-service"}
```

### 5.2 HTTP 메트릭

#### 요청 처리율 (서비스별)
```promql
sum(rate(http_server_requests_seconds_count[5m])) by (application)
```

#### 에러율 (5xx 응답)
```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application)
```

#### 평균 응답 시간
```promql
rate(http_server_requests_seconds_sum[5m]) /
rate(http_server_requests_seconds_count[5m])
```

### 5.3 데이터베이스 메트릭

#### HikariCP 활성 커넥션
```promql
hikaricp_connections_active{application="member-service"}
```

#### 커넥션 풀 사용률
```promql
(hikaricp_connections_active / hikaricp_connections_max) * 100
```

#### 커넥션 대기 시간
```promql
rate(hikaricp_connections_acquire_seconds_sum[5m]) /
rate(hikaricp_connections_acquire_seconds_count[5m])
```

### 5.4 Kafka 메트릭 (해당 서비스)

#### Producer 전송률
```promql
rate(kafka_producer_record_send_total[5m])
```

#### Consumer Lag
```promql
kafka_consumer_lag
```

### 5.5 인프라 메트릭

#### CPU 사용률 (노드별)
```promql
100 - (avg by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

#### 메모리 사용률 (노드별)
```promql
(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) /
node_memory_MemTotal_bytes * 100
```

#### 디스크 사용률
```promql
(node_filesystem_size_bytes - node_filesystem_avail_bytes) /
node_filesystem_size_bytes * 100
```

---

## 6. 대시보드 레이아웃 예시

### 6.1 서비스 전체 모니터링 대시보드 구성

#### Row 1: 서비스 상태 개요
- 총 요청 수 (Stat)
- 평균 응답 시간 (Stat)
- 에러율 (Stat)
- 서비스 UP/DOWN 상태 (Stat)

#### Row 2: JVM 메트릭
- 힙 메모리 사용량 (Time series)
- GC 시간 (Time series)
- 스레드 수 (Time series)

#### Row 3: HTTP 메트릭
- 요청 처리율 (Time series)
- 응답 시간 분포 (Heatmap)
- 상태 코드별 분포 (Bar chart)

#### Row 4: 데이터베이스
- 커넥션 풀 사용률 (Gauge)
- 활성 커넥션 수 (Time series)
- 쿼리 실행 시간 (Time series)

### 6.2 개별 서비스 상세 대시보드

각 서비스(member, product, order, auction, payment)별로 동일한 구조의 대시보드 생성하되, 쿼리에서 `application` 필터 적용:

```promql
# member-service만 필터링
{application="member-service"}
```

---

## 7. 알람 설정 (선택사항)

### 7.1 Notification Channel 설정
1. **Alerting** → **Notification channels** → **New channel**
2. 유형 선택:
   - Email
   - Slack
   - Webhook 등

### 7.2 Alert Rule 예시

#### 메모리 사용률 80% 초과
1. 패널 편집 → **Alert** 탭
2. **Create Alert** 클릭
3. 조건 설정:
   ```
   WHEN avg() OF query(A, 5m) IS ABOVE 80
   ```
4. **Notifications** → 앞서 생성한 채널 선택

#### 응답 시간 1초 초과
```
WHEN avg() OF query(A, 5m) IS ABOVE 1
```

---

## 8. 대시보드 활용 팁

### 8.1 Variables 활용
서비스를 동적으로 선택할 수 있도록 Variable 추가:

1. **Dashboard settings** (⚙️) → **Variables** → **Add variable**
2. **Name**: `service`
3. **Type**: `Query`
4. **Data source**: `Prometheus`
5. **Query**:
   ```promql
   label_values(jvm_memory_used_bytes, application)
   ```
6. **Save**

쿼리에서 사용:
```promql
jvm_memory_used_bytes{application="$service"}
```

### 8.2 Time Range 설정
- 우측 상단에서 시간 범위 선택 가능
- 추천: `Last 1 hour` ~ `Last 6 hours` (실시간 모니터링)
- `Last 7 days` (트렌드 분석)

### 8.3 Auto Refresh
- 우측 상단 새로고침 아이콘 → 자동 새로고침 간격 설정
- 추천: `30s` ~ `1m`

---

## 9. 문제 해결

### 9.1 "No data" 표시
**원인:**
- Prometheus가 메트릭을 수집하지 못함
- 잘못된 쿼리

**해결:**
```bash
# 1. Prometheus Targets 확인
http://<워커노드IP>:30090/targets

# 2. 서비스 Pod 상태 확인
kubectl get pods -A

# 3. 서비스 엔드포인트 직접 확인
kubectl exec -it <pod-name> -n default -- curl localhost:8081/actuator/prometheus
```

### 9.2 데이터소스 연결 실패
**원인:**
- Prometheus 서비스 URL 오류

**해결:**
```bash
# Prometheus 서비스 확인
kubectl get svc -n monitoring prometheus

# ClusterIP 확인
kubectl get svc -n monitoring prometheus -o jsonpath='{.spec.clusterIP}'

# Grafana URL 필드에 입력
# http://<ClusterIP>:9090
```

**올바른 URL 예시:**
- `http://10.99.6.2:9090` (ClusterIP 직접 사용 - 추천)
- `http://prometheus.monitoring.svc.cluster.local:9090` (DNS 이름)
- `http://prometheus:9090` (Grafana가 monitoring 네임스페이스에 있는 경우)

### 9.3 일부 메트릭만 보임
**원인:**
- Spring Boot 서비스 재시작 안 됨
- Prometheus annotations 누락

**해결:**
```bash
# Pod 재시작
kubectl rollout restart deployment member -n default
kubectl rollout restart deployment product -n default
# ... (모든 서비스)

# Deployment annotations 확인
kubectl get deployment member -n default -o yaml | grep prometheus
```

---

## 10. 추천 대시보드 구성

### 10.1 운영팀용
- **인프라 대시보드**: Node Exporter Full (1860)
- **K8s 대시보드**: Kubernetes Cluster (6417)
- **서비스 상태**: Spring Boot APM (12900)

### 10.2 개발팀용
- **JVM 모니터링**: JVM Micrometer (4701)
- **HTTP 성능**: Spring Boot Statistics (11378)
- **DB 커넥션**: HikariCP (11085)

### 10.3 관리자용
- **전체 개요**: 커스텀 대시보드 (모든 서비스 요약)
- **알람 대시보드**: Alert List

---

## 11. 다음 단계

대시보드 설정 완료 후:

1. ✅ 실시간 메트릭 수집 확인
2. ✅ 각 서비스별 대시보드 생성
3. ✅ 알람 규칙 설정
4. ✅ 정기적인 모니터링 체크 (일일/주간)
5. ✅ 성능 병목 지점 파악 및 개선

---

## 📚 참고 자료

- [Grafana 공식 문서](https://grafana.com/docs/)
- [Prometheus 쿼리 가이드](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana 대시보드 갤러리](https://grafana.com/grafana/dashboards/)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics)

---

**작성일:** 2026-07-08
**버전:** 1.0
