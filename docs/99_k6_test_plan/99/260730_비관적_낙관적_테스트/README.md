# 260730 비관적 락·낙관적 락 테스트 실행 안내

이 폴더는 사용자 1이 등록한 경매 상품 2개에 사용자 2·3이 동시에 입찰하는 테스트 회차 전용입니다. 계정 정보와 토큰은 Git에서 제외되며 테스트가 끝나거나 실패하면 자동 삭제됩니다. 결과 JSON과 `TEST_RECORD.md`만 남깁니다.

비관적 variant의 구현 범위, AWS 예측값, 정합성 식과 낙관적 복구 절차는 [비관적 락 테스트 명세](./PESSIMISTIC_TEST_SPEC_260730.md)를 먼저 확인합니다.

## 1. 실행 전 파일 준비

이 폴더에서 다음을 실행합니다.

```bash
cp credentials.example.json credentials.local.json
cp test.env.example test.local.env
chmod 600 credentials.local.json test.local.env
```

`credentials.local.json`에는 입찰자 사용자 2·3의 이메일과 비밀번호만 입력합니다. 사용자 1은 판매자이므로 넣지 않습니다.

```json
{
  "bidders": [
    { "name": "user2", "email": "입찰자2 이메일", "password": "입찰자2 비밀번호" },
    { "name": "user3", "email": "입찰자3 이메일", "password": "입찰자3 비밀번호" }
  ]
}
```

계정 정보는 채팅, 커밋, `TEST_RECORD.md`, 터미널 명령 인자에 입력하지 않습니다. 로컬 편집기로 `credentials.local.json`에만 입력합니다.

`test.local.env`에는 실제 `AUCTION_A_ID`, `AUCTION_B_ID`를 입력합니다. 이 파일에는 계정 정보나 토큰을 넣지 않습니다.

## 2. 낙관적 락 실행

현재 배포가 낙관적 락이고 Auction pod가 Ready 3/3인지 확인한 다음 실행합니다.

```bash
./run-test.sh optimistic
```

실행 과정은 다음과 같습니다.

1. 사용자 2·3 로그인
2. access token 2개를 `tokens.local.json`에 임시 저장
3. 상품별 2 VU, 전체 4 VU로 두 상품 동시 입찰
4. 결과를 `results/260730-optimistic-run-01-summary.json`에 저장
5. 서버 로그아웃 시도
6. `credentials.local.json`, `tokens.local.json` 자동 삭제

## 3. 비관적 락 실행

비관적 락 배포와 rollout 완료를 확인합니다. 낙관적 테스트로 가격이 변경된 상품을 재사용하지 말고 사용자 1이 동일 조건의 새 상품 2개를 등록합니다.

자동 삭제된 `credentials.local.json`을 예제에서 다시 만들고 사용자 2·3의 정보를 입력합니다. `test.local.env`의 경매 ID도 새 ID로 바꿉니다.

```bash
cp credentials.example.json credentials.local.json
chmod 600 credentials.local.json
./run-test.sh pessimistic
```

결과는 `results/260730-pessimistic-run-01-summary.json`에 저장되고 계정·토큰 파일은 다시 자동 삭제됩니다.

## 4. 결과 비교

저장소 루트에서 실행합니다.

```bash
node load-tests/k6/compare-results.mjs \
  '99/260730_비관적_낙관적_테스트/results/260730-optimistic-run-01-summary.json' \
  '99/260730_비관적_낙관적_테스트/results/260730-pessimistic-run-01-summary.json'
```

출력값과 Kubernetes/DB 관측값을 `TEST_RECORD.md`에 기록합니다. 가장 먼저 `(최종 bidCount - 최초 bidCount) = k6 2xx 성공 수`인지 확인합니다.

## 5. 수동 정보 삭제

실행을 시작하기 전에 중단했거나 자동 정리가 의심되면 다음을 실행합니다.

```bash
./cleanup-secrets.sh
```

이 명령은 토큰이 있으면 서버 로그아웃을 시도하고 다음 로컬 파일만 삭제합니다.

- `credentials.local.json`
- `tokens.local.json`

`test.local.env`와 결과 파일은 삭제하지 않습니다. 일반 파일 삭제는 SSD, 백업, 파일 동기화 서비스의 이전 복사본까지 완전 소거한다는 의미는 아닙니다. 따라서 계정 정보를 이 폴더 밖에 복사하지 않는 것이 중요합니다.

전체 k6 지표와 Kubernetes 확인 방법은 [공통 가이드](../../load-tests/k6/README.md)를 참고합니다.
