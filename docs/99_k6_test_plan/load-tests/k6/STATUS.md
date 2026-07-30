# 입찰 락 부하 테스트 준비 상태

최종 점검일: 2026-07-30 (Asia/Seoul)

## 저장소에서 확인한 사항

| 항목 | 현재 확인 상태 |
|---|---|
| 입찰 API | `POST /api/v1/auctions/{auctionId}/bids` |
| 요청 본문 | `{ "amount": number }` |
| 인증 | `Authorization: Bearer <accessToken>` 필수 |
| 상세 조회 | `GET /api/v1/auctions/{auctionId}` |
| 프런트 배포 프록시 대상 | `vercel.json` 기준 `https://43.202.187.240.nip.io` |
| 테스트 대상 상품 수 | 서로 다른 경매 ID 2개를 A/B 시나리오로 동시에 실행 |
| 테스트 회원 구성 | 사용자 1은 두 상품 판매자, 사용자 2·3만 입찰 토큰으로 사용 |
| 락 구현 | 사용자 전달 기준 현재 낙관적 락. 이 프런트 저장소만으로 백엔드 구현은 검증할 수 없음 |
| Kubernetes replica | 이 저장소에는 배포 manifest/클러스터 접근 정보가 없어 3개 여부를 아직 검증할 수 없음 |

`WEBSOCKET_SETUP.md`에는 과거 IP(`43.200.204.191`)도 남아 있습니다. 실제 테스트 주소는 당일 Ingress/LoadBalancer 주소를 기준으로 `BASE_URL`에 명시해야 합니다.

## 2026-07-30 외부 연결 확인

저장소의 현재 프록시 대상인 `43.202.187.240.nip.io`에 직접 요청한 결과입니다.

| 요청 | 결과 |
|---|---|
| `GET /actuator/health` | HTTP 200, TLS 검증 정상 |
| `GET /api/v1/auctions?page=0&size=1` | HTTP 200 |
| 목록 응답 필드 | `auctionId`, `startPrice`, `minIncrement`, `currentBid`, `bidCount`, `endsAt`, `status` 확인 |
| 표본 상세 조회 | HTTP 200, `currentBid`/`minIncrement`/`status=LIVE` 확인 |
| 연결 관찰 | 상세 조회 1회가 connect timeout, 바로 다음 재시도는 HTTP 200(약 4.8초) |

외부 엔드포인트는 현재 접근 가능하지만 간헐적인 연결 지연 가능성이 관찰됐습니다. 이는 입찰 인증·락 동작·3개 pod 분산까지 검증한 결과가 아니며, 당일 smoke와 Kubernetes 점검은 여전히 필요합니다.

## 구현 완료

- `bid-lock-stress.js`: 두 경매를 별도 시나리오로 동시에 압박
- `refresh` 전략: 매 반복마다 최신가 조회 후 최소 증가액으로 입찰해 실제 사용자 경합을 재현
- `sequence` 전략: 상세 조회를 줄이고 순번 기반 금액으로 입찰하는 보조 비교 모드
- 상태 코드별 지표: 성공, 409 충돌, 400/422 업무 거절, 429 제한, 그 외 오류
- A/B 상품별 시도/성공 카운터와 입찰 지연 p95/p99
- 테스트 전후 A/B의 `currentBid`와 `bidCount` 기록
- `X-K6-Test-Run` 헤더로 애플리케이션 로그와 실행 회차 연계
- `X-Pod-Name` 응답 헤더가 있다면 `pod_hits` 시계열 태그 수집
- 고부하 프로필 오실행 방지 확인값(`CONFIRM_STRESS=I_UNDERSTAND`)
- JSON 결과 저장과 두 락 결과의 Markdown 표 비교 도구
- 이 개발 환경의 k6 v2.1.0으로 스크립트 parse 및 로컬 mock API end-to-end 실행 완료
- 결과 JSON 생성과 비교 표 출력 검증 완료

## 당일 실행 전 미확인 항목

- [ ] 당일 실제 AWS API `BASE_URL`과 TLS 인증서 재확인
- [ ] Auction Deployment의 Ready replica가 정확히 3개인지
- [ ] 두 테스트용 경매가 `LIVE`이고 종료까지 충분한 시간이 남았는지
- [ ] 두 경매의 시작가와 최소 증가액이 동일한지
- [ ] 테스트 VU 수만큼 독립 입찰자 토큰을 준비했는지
- [ ] 낙관적 락 실행과 비관적 락 실행 사이에 경매 데이터가 동일하게 초기화되는지
- [ ] 애플리케이션/DB connection pool과 HPA 설정이 두 실행에서 동일한지
- [ ] 409가 실제 낙관적 락 충돌인지, 단순 업무 검증 실패인지 백엔드 오류 코드/로그로 구분 가능한지

위 항목 중 하나라도 달라지면 락 방식 외 변수가 결과에 섞이므로 비교 결과에 반드시 기록합니다.
