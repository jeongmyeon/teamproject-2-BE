# Spring Boot Prometheus 메트릭 설정 가이드

**작성일**: 2026-07-08
**목적**: Spring Boot 애플리케이션에서 Prometheus 메트릭 노출

---

## 📋 현재 상태 확인

### ✅ 이미 설정된 것
- `spring-boot-starter-actuator` 의존성 (모든 서비스에 이미 있음)

### ❌ 추가로 필요한 것
1. **Micrometer Prometheus 의존성** 추가
2. **application.yml 설정** 변경

---

## 🔧 변경 사항

### 1단계: build.gradle 수정 (모든 서비스)

다음 서비스들의 `build.gradle`에 의존성 추가:
- member
- product
- order
- auction
- payment
- apigateway
- config
- discovery

#### 추가할 의존성

```gradle
dependencies {
    // 기존 dependencies...
    implementation 'org.springframework.boot:spring-boot-starter-actuator'  // 이미 있음

    // ⬇️ 이것만 추가
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

#### 예시: member/build.gradle

**변경 전:**
```gradle
dependencies {
    // spring boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    // ...
}
```

**변경 후:**
```gradle
dependencies {
    // spring boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'  // ⬅️ 추가
    // ...
}
```

---

### 2단계: application.yml 수정 (모든 서비스)

각 서비스의 `application.yml` 또는 Config Server의 설정 파일 수정

#### apigateway/src/main/resources/application.yml

**변경 전:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, gateway
```

**변경 후:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, gateway, metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

#### config/src/main/resources/application.yaml

**변경 전:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, busrefresh
```

**변경 후:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, busrefresh, metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

#### 나머지 서비스 (member, product, order, auction, payment, discovery)

각 서비스의 `application.yml` 또는 `bootstrap.yml`에 추가:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 🚀 변경 적용 방법

### 로컬 개발 환경

```bash
# 1. 각 서비스의 build.gradle 수정
# 2. Gradle 빌드
./gradlew clean build

# 3. 애플리케이션 재시작
./gradlew bootRun

# 4. 확인
curl http://localhost:8080/actuator/prometheus
# 또는
curl http://localhost:8081/actuator/prometheus
```

### Docker 이미지 재빌드

```bash
# 1. 코드 변경 커밋
git add .
git commit -m "feat: Add Prometheus metrics support"
git push

# 2. Docker 이미지 재빌드 (GitHub Actions 자동 실행)
# 또는 수동:
docker build -t your-registry/member:latest ./member
docker push your-registry/member:latest

# 3. Kubernetes에서 재배포
kubectl rollout restart deployment member -n biddy
kubectl rollout restart deployment product -n biddy
kubectl rollout restart deployment order -n biddy
kubectl rollout restart deployment auction -n biddy
kubectl rollout restart deployment payment -n biddy
kubectl rollout restart deployment apigateway -n biddy
kubectl rollout restart deployment config -n biddy
kubectl rollout restart deployment discovery -n biddy
```

---

## ✅ 설정 확인

### 1. Actuator 엔드포인트 확인

```bash
# 각 서비스 Pod에서 확인
kubectl exec -n biddy <pod-name> -- curl http://localhost:8080/actuator

# 출력에 "prometheus" 있는지 확인
{
  "_links": {
    "self": {...},
    "health": {...},
    "prometheus": {
      "href": "http://localhost:8080/actuator/prometheus",
      "templated": false
    }
  }
}
```

### 2. Prometheus 메트릭 확인

```bash
# Pod에서 직접 확인
kubectl exec -n biddy <pod-name> -- curl http://localhost:8080/actuator/prometheus | head -20

# 출력 예시:
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
# jvm_memory_used_bytes{area="heap",id="G1 Survivor Space",} 1048576.0
# ...
```

### 3. Kubernetes Deployment에 어노테이션 추가

```bash
# Master 노드에서 실행
for service in discovery config apigateway member product order auction payment; do
  kubectl patch deployment $service -n biddy --type='json' -p='[
    {
      "op": "add",
      "path": "/spec/template/metadata/annotations",
      "value": {
        "prometheus.io/scrape": "true",
        "prometheus.io/port": "8080",
        "prometheus.io/path": "/actuator/prometheus"
      }
    }
  ]'
done
```

### 4. Prometheus에서 확인

Prometheus 접속 (`http://<워커IP>:30090`) → Status → Targets

**확인 사항:**
- `kubernetes-pods` job에서 각 서비스 Pod 확인
- State: UP (초록색)
- Labels에 `app="member"` 등 확인

---

## 📊 확인 가능한 메트릭

### JVM 메트릭
```promql
# Heap 메모리 사용률
jvm_memory_used_bytes{area="heap"}

# GC 시간
jvm_gc_pause_seconds_sum
```

### HTTP 메트릭
```promql
# 초당 요청 수
rate(http_server_requests_seconds_count[5m])

# 평균 응답 시간
rate(http_server_requests_seconds_sum[5m]) / rate(http_server_requests_seconds_count[5m])
```

### 시스템 메트릭
```promql
# CPU 사용률
process_cpu_usage

# 스레드 수
jvm_threads_live
```

---

## 🔄 변경 순서 요약

### 개발 환경
```
1. build.gradle 수정 (micrometer-registry-prometheus 추가)
2. application.yml 수정 (prometheus 엔드포인트 노출)
3. Gradle 빌드
4. 로컬 실행
5. curl로 /actuator/prometheus 확인
```

### 운영 환경 (Kubernetes)
```
1. 로컬에서 코드 변경 및 테스트
2. Git commit & push
3. Docker 이미지 재빌드 (CI/CD)
4. Kubernetes Deployment 업데이트
   kubectl rollout restart deployment <service> -n biddy
5. Pod 재시작 확인
   kubectl get pods -n biddy
6. Deployment에 어노테이션 추가
   kubectl patch deployment ...
7. Prometheus에서 Target 확인
```

---

## ⚠️ 주의사항

### 1. Spring Boot 버전 호환성

현재 프로젝트: Spring Boot 3.4.1
- ✅ `io.micrometer:micrometer-registry-prometheus` 자동 버전 관리
- Spring Boot BOM이 호환 버전 자동 선택

### 2. Port 설정

각 서비스의 포트 확인:
- member: 8081
- product: 8082
- order: 8083
- auction: 8084
- payment: 8085
- apigateway: 8000
- config: 8888
- discovery: 8761

Kubernetes 어노테이션의 `prometheus.io/port`를 각 서비스 포트로 설정

### 3. Config Server 사용 시

Config Server에서 설정을 가져오는 경우:
1. Config Server의 Git 저장소에서 설정 변경
2. 각 서비스에서 `/actuator/refresh` 호출 (또는 재시작)

---

## 📋 체크리스트

### 모든 서비스별 작업
```
□ member/build.gradle에 micrometer-registry-prometheus 추가
□ member application 설정에 prometheus 엔드포인트 노출
□ product/build.gradle 수정
□ product application 설정 수정
□ order/build.gradle 수정
□ order application 설정 수정
□ auction/build.gradle 수정
□ auction application 설정 수정
□ payment/build.gradle 수정
□ payment application 설정 수정
□ apigateway/build.gradle 수정
□ apigateway/application.yml 수정
□ config/build.gradle 수정
□ config/application.yaml 수정
□ discovery/build.gradle 수정
□ discovery application 설정 수정
```

### 배포 및 확인
```
□ Git commit & push
□ Docker 이미지 재빌드
□ Kubernetes Deployment 업데이트
□ Pod 재시작 확인
□ Deployment 어노테이션 추가
□ Prometheus Targets 확인
□ Grafana에서 메트릭 확인
```

---

**작성자**: Claude (AI Assistant)
**최종 업데이트**: 2026-07-08
**적용 범위**: 모든 Spring Boot 서비스
