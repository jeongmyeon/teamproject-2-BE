# Biddy 모니터링 스택

Prometheus와 Grafana를 사용한 Kubernetes 클러스터 모니터링 설정입니다.

## 구성 요소

- **Prometheus**: 메트릭 수집 및 저장
- **Grafana**: 시각화 대시보드

## 배포

### 1. 네임스페이스 생성

```bash
kubectl apply -f namespace.yaml
```

### 2. Prometheus 배포

```bash
kubectl apply -f prometheus.yaml

# 상태 확인
kubectl get pods -n biddy-monitoring
kubectl logs -f deployment/prometheus -n biddy-monitoring
```

### 3. Grafana 배포

```bash
kubectl apply -f grafana.yaml

# Grafana 접속 정보 확인
kubectl get svc grafana -n biddy-monitoring

# LoadBalancer External IP 확인
kubectl get svc grafana -n biddy-monitoring -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

### 4. Grafana 접속

- URL: `http://<EXTERNAL-IP>:3000`
- 기본 계정: `admin / admin1234` (변경 권장)

## 대시보드 설정

### 1. Prometheus 데이터소스 확인

Grafana 로그인 후:
1. Configuration → Data Sources
2. Prometheus가 자동으로 추가되어 있는지 확인
3. URL: `http://prometheus:9090`

### 2. 추천 대시보드 Import

1. Dashboards → Import
2. 다음 대시보드 ID 입력:

#### Kubernetes 클러스터 모니터링
- **Dashboard ID: 315** - Kubernetes cluster monitoring (via Prometheus)
- **Dashboard ID: 6417** - Kubernetes Cluster (Prometheus)
- **Dashboard ID: 13770** - Kubernetes / Views / Global

#### Spring Boot 모니터링
- **Dashboard ID: 4701** - JVM (Micrometer)
- **Dashboard ID: 11378** - Spring Boot 2.1 Statistics

#### 커스텀 대시보드 추천
- **Dashboard ID: 12006** - Kubernetes Deployment Statefulset Daemonset metrics

### 3. 커스텀 대시보드 생성 예시

#### Biddy 서비스별 요청/응답 시간

```promql
# HTTP 요청 수 (서비스별)
sum(rate(http_server_requests_seconds_count{namespace="biddy-services"}[5m])) by (application)

# 평균 응답 시간 (서비스별)
rate(http_server_requests_seconds_sum{namespace="biddy-services"}[5m])
/
rate(http_server_requests_seconds_count{namespace="biddy-services"}[5m])

# JVM 메모리 사용량
sum(jvm_memory_used_bytes{namespace="biddy-services"}) by (application, area)

# CPU 사용률
rate(process_cpu_seconds_total{namespace="biddy-services"}[5m]) * 100
```

## 메트릭 수집 확인

### Spring Boot Actuator 메트릭 노출

각 서비스의 `application.yml`에 다음 설정 추가:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### Prometheus Annotation 추가

각 서비스 Deployment의 Pod 템플릿에 Annotation 추가:

```yaml
spec:
  template:
    metadata:
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
```

이미 `k8s-aws/services/*.yaml` 파일에 적용되어 있지 않다면 추가 필요.

## Prometheus 쿼리 예시

### 1. API Gateway 트래픽

```promql
# 초당 요청 수
rate(http_server_requests_seconds_count{application="apigateway"}[5m])

# 응답 코드별 요청 수
sum(rate(http_server_requests_seconds_count{application="apigateway"}[5m])) by (status)
```

### 2. Auction 서비스 성능

```promql
# 입찰 처리 시간 (P95)
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{application="auction", uri="/api/v1/auctions/{auctionId}/bids"}[5m]))

# 현재 활성 스레드
jvm_threads_live_threads{application="auction"}
```

### 3. Redis 캐시 히트율

```promql
# Redis Watch 캐시 조회 수 (애플리케이션 로그 기반)
# Spring Boot Redis 메트릭이 활성화된 경우
```

### 4. Kafka 메시지 처리

```promql
# Kafka Consumer Lag
kafka_consumer_fetch_manager_records_lag_max

# 메시지 처리 속도
rate(kafka_consumer_fetch_manager_records_consumed_total[5m])
```

## 알림 설정 (Alert Manager)

### 1. Alert Rules 추가

`prometheus.yaml`의 ConfigMap에 추가:

```yaml
data:
  alert_rules.yml: |
    groups:
    - name: biddy_alerts
      interval: 30s
      rules:
      # Pod CPU 사용률 80% 초과
      - alert: HighPodCPU
        expr: rate(container_cpu_usage_seconds_total{namespace="biddy-services"}[5m]) > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High CPU usage on {{ $labels.pod }}"
          description: "Pod {{ $labels.pod }} CPU usage is above 80%"

      # Pod 메모리 사용률 90% 초과
      - alert: HighPodMemory
        expr: container_memory_working_set_bytes{namespace="biddy-services"} / container_spec_memory_limit_bytes{namespace="biddy-services"} > 0.9
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High memory usage on {{ $labels.pod }}"
          description: "Pod {{ $labels.pod }} memory usage is above 90%"

      # API 응답 시간 1초 초과
      - alert: SlowAPIResponse
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{namespace="biddy-services"}[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow API response in {{ $labels.application }}"
          description: "95th percentile response time is above 1 second"
```

### 2. Slack 알림 연동 (선택)

AlertManager 설정:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-config
  namespace: biddy-monitoring
data:
  alertmanager.yml: |
    global:
      slack_api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'

    route:
      group_by: ['alertname', 'cluster', 'service']
      group_wait: 10s
      group_interval: 10s
      repeat_interval: 12h
      receiver: 'slack-notifications'

    receivers:
    - name: 'slack-notifications'
      slack_configs:
      - channel: '#biddy-alerts'
        title: '{{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
```

## 주요 모니터링 지표

### 1. 서비스 가용성
- Pod Readiness/Liveness 상태
- 서비스별 응답 성공률 (2xx/3xx 비율)

### 2. 성능
- API 응답 시간 (P50, P95, P99)
- 처리량 (RPS - Requests Per Second)
- Kafka Consumer Lag

### 3. 리소스
- CPU 사용률
- 메모리 사용률
- Disk I/O
- Network I/O

### 4. 비즈니스 메트릭
- 경매 생성 수 (시간당)
- 입찰 수 (분당)
- 활성 사용자 수
- 주문 처리 성공률

## 삭제

```bash
kubectl delete -f grafana.yaml
kubectl delete -f prometheus.yaml
kubectl delete -f namespace.yaml
```

## 참고 자료

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Spring Boot Actuator + Prometheus](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics.export.prometheus)
- [kube-prometheus-stack (Helm)](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
