# Biddy Kafka 이벤트 흐름

## 토픽 목록

| 토픽명 | Producer | Consumer | 설명 |
|---|---|---|---|
| | | | |

---

## 주요 이벤트 시나리오

### 1. 경매 낙찰 → 주문 생성

```
Auction --[낙찰 이벤트]--> Kafka --> Order (주문 자동 생성)
                                 --> Member (예치금 차감)
```

### 2. 결제 완료 → 상태 변경

```
Payment --[결제 완료 이벤트]--> Kafka --> Order (주문 상태 변경)
                                    --> Product (상품 상태 변경)
```

### 3. 환불 처리

```
Payment --[환불 이벤트]--> Kafka --> Member (예치금 환급)
                               --> Order (주문 상태 변경)
```

---

## 이벤트 메시지 포맷

> 각 이벤트의 JSON 메시지 구조를 정의하세요.

```json
{
  "eventType": "",
  "timestamp": "",
  "payload": {}
}
```
