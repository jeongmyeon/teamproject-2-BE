# Biddy 추천 서비스

머신러닝 기반 실시간 상품/경매 추천 엔진입니다.

## 기능

### 1. 개인화 추천
- **협업 필터링 (Collaborative Filtering)**
  - 사용자 기반: 비슷한 입찰 패턴을 가진 사용자 분석
  - 아이템 기반: 유사한 상품/경매 추천

### 2. 콘텐츠 기반 추천
- 카테고리, 가격대, 브랜드 등 상품 속성 분석
- 사용자의 과거 관심 카테고리 기반 추천

### 3. 실시간 추천
- Redis 캐싱으로 빠른 응답 (< 100ms)
- Kafka 이벤트 기반 실시간 모델 업데이트

### 4. 컨텍스트 인식
- 시간대별 추천 (새벽/점심/저녁)
- 종료 임박 경매 우선순위

## 추천 알고리즘

### Phase 1: 기본 추천 (현재)
```python
# 간단한 규칙 기반
- 최근 조회한 카테고리의 다른 상품
- 입찰 중인 경매와 유사한 경매
- 인기 경매 (입찰 수 기준)
```

### Phase 2: ML 기반 추천 (향후)
```python
# 협업 필터링 (Matrix Factorization)
from surprise import SVD

# 사용자-아이템 행렬
user_item_matrix = [
  [user_id, auction_id, rating],  # rating = 입찰 여부, Watch 여부
  ...
]

# SVD로 잠재 요인 추출
model = SVD(n_factors=50, n_epochs=20)
model.fit(user_item_matrix)

# 예측
predicted_rating = model.predict(user_id, auction_id)
```

### Phase 3: 딥러닝 추천 (고도화)
```python
# Neural Collaborative Filtering
import torch
import torch.nn as nn

class RecommenderNet(nn.Module):
    def __init__(self, n_users, n_items, embedding_dim=50):
        super().__init__()
        self.user_embedding = nn.Embedding(n_users, embedding_dim)
        self.item_embedding = nn.Embedding(n_items, embedding_dim)
        self.fc = nn.Sequential(
            nn.Linear(embedding_dim * 2, 128),
            nn.ReLU(),
            nn.Dropout(0.2),
            nn.Linear(128, 1),
            nn.Sigmoid()
        )

    def forward(self, user_ids, item_ids):
        user_vec = self.user_embedding(user_ids)
        item_vec = self.item_embedding(item_ids)
        x = torch.cat([user_vec, item_vec], dim=1)
        return self.fc(x)
```

## API 엔드포인트

### 1. 개인화 추천

```bash
# 사용자 맞춤 경매 추천
GET /api/v1/recommendations/auctions
Authorization: Bearer {JWT_TOKEN}

# 응답
{
  "recommendations": [
    {
      "auctionId": "A-EAR01",
      "score": 0.95,
      "reason": "유사한 경매에 입찰한 사용자들이 관심을 보였습니다"
    },
    ...
  ]
}
```

### 2. 유사 상품 추천

```bash
# 특정 경매와 유사한 경매 추천
GET /api/v1/recommendations/similar/{auctionId}

# 응답
{
  "similar": [
    {
      "auctionId": "A-EAR02",
      "similarity": 0.87
    },
    ...
  ]
}
```

### 3. 인기 경매

```bash
# 트렌딩 경매 (시간대별)
GET /api/v1/recommendations/trending?timeWindow=24h

# 응답
{
  "trending": [
    {
      "auctionId": "A-TOY01",
      "trendScore": 0.92,
      "bidCount": 47,
      "watchCount": 128
    },
    ...
  ]
}
```

## 데이터 수집

### Kafka 이벤트 구독

```yaml
# 추천 모델 학습 데이터 수집
kafka:
  topics:
    - auction.bid.placed      # 입찰 이벤트
    - auction.watched         # Watch 이벤트
    - product.viewed          # 상품 조회
    - auction.ended           # 경매 종료
    - order.created           # 주문 생성
```

### 특징(Feature) 추출

```sql
-- 사용자 프로필
SELECT
  user_id,
  COUNT(DISTINCT category) as interested_categories,
  AVG(bid_amount) as avg_bid,
  COUNT(*) as total_bids,
  MAX(bid_at) as last_bid_time
FROM bids
GROUP BY user_id;

-- 경매 특징
SELECT
  auction_id,
  category,
  start_price,
  current_bid,
  bid_count,
  watcher_count,
  EXTRACT(HOUR FROM ends_at) as end_hour
FROM auctions;
```

## 모델 학습 파이프라인

### CronJob: 매일 새벽 3시 재학습

```bash
# 1. 최근 7일 데이터 추출
# 2. 특징 엔지니어링
# 3. 모델 학습
# 4. 검증 (Precision@K, Recall@K)
# 5. 모델 저장 (/models/latest)
# 6. 추천 서비스에 Reload 신호
```

### 수동 학습 트리거

```bash
# 모델 재학습 Job 실행
kubectl create job --from=cronjob/recommendation-training manual-training-$(date +%s) -n biddy-services

# 학습 로그 확인
kubectl logs job/manual-training-xxxxx -n biddy-services
```

## 성능 최적화

### 1. Redis 캐싱

```python
# 추천 결과 캐싱 (10분)
@cacheable(key="recommendations:user:{user_id}", ttl=600)
def get_recommendations(user_id):
    return model.predict(user_id, top_k=20)
```

### 2. 배치 예측

```python
# 사용자별 추천 미리 계산 (매일 새벽 4시)
for user in active_users:
    recommendations = model.predict(user.id, top_k=50)
    redis.set(f"rec:user:{user.id}", recommendations, ex=86400)
```

### 3. 모델 경량화

```python
# 모델 양자화 (Float32 → Int8)
import tensorflow as tf

converter = tf.lite.TFLiteConverter.from_saved_model(model_path)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

# 모델 크기: 50MB → 12MB (75% 감소)
# 추론 속도: 20ms → 5ms (4배 향상)
```

## 메트릭 모니터링

### Prometheus 메트릭

```promql
# 추천 응답 시간
histogram_quantile(0.95, rate(recommendation_request_duration_seconds_bucket[5m]))

# 추천 적중률 (사용자가 추천 상품 클릭)
rate(recommendation_click_count[5m]) / rate(recommendation_shown_count[5m])

# 모델 학습 소요 시간
recommendation_training_duration_seconds
```

### Grafana 대시보드

- **추천 성능**: 응답 시간, 처리량, 에러율
- **추천 품질**: CTR (Click-Through Rate), CVR (Conversion Rate)
- **모델 성능**: Precision@10, Recall@10, NDCG

## A/B 테스트

### 실험 설정

```yaml
# ConfigMap: 실험 설정
apiVersion: v1
kind: ConfigMap
metadata:
  name: recommendation-experiments
  namespace: biddy-services
data:
  experiments.yml: |
    - name: collaborative_vs_content
      control: collaborative_filtering  # 50% 사용자
      variant: content_based             # 50% 사용자
      metric: click_through_rate
      start_date: "2026-07-01"
      end_date: "2026-07-14"
```

### 결과 분석

```sql
-- 실험 그룹별 CTR 비교
SELECT
  experiment_group,
  COUNT(DISTINCT user_id) as users,
  SUM(clicked) / COUNT(*) as ctr
FROM recommendation_logs
WHERE experiment_name = 'collaborative_vs_content'
GROUP BY experiment_group;
```

## 배포

### 초기 배포

```bash
# 추천 서비스 배포
kubectl apply -f recommendation.yaml

# 모델 학습 CronJob 배포
kubectl apply -f recommendation.yaml

# 상태 확인
kubectl get pods -n biddy-services -l app=recommendation
kubectl get cronjobs -n biddy-services
```

### 업데이트

```bash
# 새 모델 이미지 배포
kubectl set image deployment/recommendation \
  recommendation=YOUR_ACCOUNT_ID.dkr.ecr.ap-northeast-2.amazonaws.com/biddy/recommendation:v1.1.0 \
  -n biddy-services

# 롤링 업데이트 상태 확인
kubectl rollout status deployment/recommendation -n biddy-services
```

## 비용 최적화

### CPU/Memory 최적화

```yaml
# 추론 전용 경량화 모델
resources:
  requests:
    memory: "512Mi"  # 1Gi → 512Mi
    cpu: "250m"      # 500m → 250m
  limits:
    memory: "1Gi"
    cpu: "500m"
```

### Spot Instance 활용

```bash
# 학습 Job은 Spot Instance에서 실행 (비용 90% 절감)
eksctl create nodegroup \
  --cluster biddy-eks-cluster \
  --name biddy-ml-spot \
  --instance-types c5.xlarge,c5a.xlarge \
  --spot \
  --nodes 0 \
  --nodes-min 0 \
  --nodes-max 3 \
  --node-labels workload=ml-training
```

학습 Job에 nodeSelector 추가:

```yaml
spec:
  template:
    spec:
      nodeSelector:
        workload: ml-training
```

## 향후 개선 사항

1. **실시간 Feature Store**: 사용자 행동 즉시 반영
2. **Multi-Armed Bandit**: Exploration vs Exploitation 균형
3. **Contextual Bandits**: 시간/위치/디바이스 고려
4. **Graph Neural Networks**: 소셜 그래프 활용
5. **Reinforcement Learning**: 장기 사용자 만족도 최적화

## 참고 자료

- [Collaborative Filtering Tutorial](https://realpython.com/build-recommendation-engine-collaborative-filtering/)
- [Netflix Recommendation System](https://netflixtechblog.com/netflix-recommendations-beyond-the-5-stars-part-1-55838468f429)
- [Amazon Personalize](https://aws.amazon.com/personalize/)
- [TensorFlow Recommenders](https://www.tensorflow.org/recommenders)
