# Grafana 대시보드 5분 빠른 설정 가이드

> 💡 **목표:** 5분 안에 Grafana에서 모든 서비스의 메트릭을 확인할 수 있는 대시보드 구축

---

## 🚀 Step 1: Grafana 접속 (30초)

### 접속
```
http://<워커노드-퍼블릭-IP>:30300
```

**워커 노드 IP 확인 (Master 노드에서):**
```bash
kubectl get nodes -o wide
```

### 로그인
- Username: `admin`
- Password: `admin123`

> 첫 로그인 시 비밀번호 변경 화면이 나오면 **Skip** 클릭 (나중에 변경 가능)

---

## 🔌 Step 2: Prometheus 연결 (1분)

### 2-1. 데이터소스 추가

**방법 1 (메뉴):**
1. 좌측 메뉴 **🔌 Connections** (또는 **⚙️ Configuration**) → **Data sources**
2. **Add data source** 클릭
3. **Prometheus** 선택

**방법 2 (URL 직접 - 빠름!):**
```
http://<워커노드IP>:30300/connections/datasources/new
```

### 2-2. Prometheus ClusterIP 확인

Master 노드에서 실행:
```bash
kubectl get svc -n monitoring prometheus -o jsonpath='{.spec.clusterIP}'
```

**출력 예시:** `10.99.6.2`

### 2-3. URL 입력
- **URL 필드에 입력:**
  ```
  http://10.99.6.2:9090
  ```
  > 💡 위 단계에서 확인한 ClusterIP로 변경하세요!

- 다른 설정은 기본값 유지

### 2-4. 저장
- 하단 **Save & test** 클릭
- ✅ "Data source is working" 확인

> ⚠️ **연결 실패 시:**
> - DNS 이름 사용: `http://prometheus.monitoring.svc.cluster.local:9090`
> - 또는 Master 노드에서 `kubectl get svc -n monitoring prometheus`로 ClusterIP 재확인

---

## 📊 Step 3: 필수 대시보드 임포트 (3분)

> 💡 **각 대시보드마다 5단계를 반복합니다**

### 대시보드 임포트 화면 열기

**방법 1:** 좌측 메뉴 **Dashboards** → 우측 상단 **New** → **Import**

**방법 2 (빠름):** URL 직접 입력
```
http://<워커노드IP>:30300/dashboard/import
```

---

### 3-1. 서버 모니터링 대시보드 (ID: 1860)

Import 화면에서:

```
┌─────────────────────────────────────┐
│ Grafana.com dashboard URL or ID     │
│ ┌─────────────────────────────────┐ │
│ │ 1860                            │ │ ← 여기에 입력
│ └─────────────────────────────────┘ │
│                    [ Load ]          │
└─────────────────────────────────────┘
```

**단계:**
1. **"Grafana.com dashboard URL or ID"** 필드에 `1860` 입력
2. 파란색 **Load** 버튼 클릭
3. 다음 화면에서 **Prometheus** 드롭다운 → `Prometheus` 선택
4. **Import** 버튼 클릭

✅ **완료!** 대시보드가 자동으로 열리면서 서버 메트릭 표시

---

### 3-2. Kubernetes 모니터링 대시보드 (ID: 6417)

1. 다시 **Dashboards** → **New** → **Import**
2. **ID 입력:** `6417`
3. **Load** 클릭
4. **Prometheus** 드롭다운에서 `Prometheus` 선택
5. **Import** 클릭

✅ **완료!** Pod 상태, 리소스 사용량 확인 가능

---

### 3-3. Spring Boot 모니터링 대시보드 (ID: 11378)

1. 다시 **Dashboards** → **New** → **Import**
2. **ID 입력:** `11378`
3. **Load** 클릭
4. **Prometheus** 드롭다운에서 `Prometheus` 선택
5. **Import** 클릭

✅ **완료!** JVM, HTTP, DB 메트릭 확인 가능

---

## 🎯 Step 4: 확인 (30초)

### 4-1. 대시보드 목록 확인
좌측 메뉴 📁 **Dashboards** → **Browse**

다음 3개 대시보드가 보여야 함:
- ✅ Node Exporter Full
- ✅ Kubernetes Cluster Monitoring (via Prometheus)
- ✅ Spring Boot 2.1 Statistics

### 4-2. 메트릭 확인
각 대시보드를 열어서 데이터가 표시되는지 확인

---

## ✅ 설정 완료!

이제 다음을 모니터링할 수 있습니다:

### 📈 인프라 메트릭
- 서버 CPU, 메모리, 디스크, 네트워크
- Kubernetes Pod 상태 및 리소스

### ☕ 애플리케이션 메트릭
- JVM 힙 메모리, GC, 스레드
- HTTP 요청 처리율, 응답 시간, 에러율
- HikariCP 커넥션 풀 상태

---

## 🔍 문제 발생 시

### "No data" 표시되는 경우

#### 1. Prometheus 상태 확인
브라우저에서 접속:
```
http://<워커노드IP>:30090/targets
```
→ 모든 타겟이 **UP** 상태여야 함

#### 2. Pod 재시작 (필요시)
```bash
# Master 노드에서 실행
kubectl rollout restart deployment member -n default
kubectl rollout restart deployment product -n default
kubectl rollout restart deployment order -n default
kubectl rollout restart deployment auction -n default
kubectl rollout restart deployment payment -n default
kubectl rollout restart deployment apigateway -n default
kubectl rollout restart deployment config -n default
kubectl rollout restart deployment discovery -n default
```

#### 3. 5분 대기
Prometheus가 메트릭을 수집하는데 1-2분 소요

---

## 📊 추가 대시보드 (선택사항)

더 자세한 모니터링이 필요하면 추가 임포트:

| 대시보드 | ID | 용도 |
|---------|-----|------|
| JVM (Micrometer) | 4701 | JVM 상세 모니터링 |
| Spring Boot APM Dashboard | 12900 | HTTP 성능 분석 |
| HikariCP | 11085 | DB 커넥션 풀 |
| Kubernetes Pod Monitoring | 6781 | Pod별 리소스 |

**임포트 방법:** Step 3과 동일하게 ID만 바꿔서 진행

---

## 🎨 대시보드 커스터마이징

### 서비스별 필터링
대시보드에서 상단의 **Variables** 드롭다운을 사용하여 특정 서비스만 필터링 가능:
- member-service
- product-service
- order-service
- auction-service
- payment-service
- apigateway
- config
- discovery

### 시간 범위 조정
우측 상단에서 시간 범위 선택:
- **Last 1 hour** - 실시간 모니터링
- **Last 6 hours** - 단기 트렌드
- **Last 7 days** - 장기 트렌드

### 자동 새로고침
우측 상단 새로고침 아이콘 → `30s` 또는 `1m` 선택

---

## 📚 더 자세한 내용

상세한 설정 및 커스텀 대시보드 생성 방법은 다음 문서 참고:
- [Grafana_완전_가이드.md](./Grafana_완전_가이드.md) - 전체 가이드
- [Grafana 공식 문서](https://grafana.com/docs/)

---

**설정 시간:** 약 5분
**난이도:** ⭐ 쉬움
**작성일:** 2026-07-09
