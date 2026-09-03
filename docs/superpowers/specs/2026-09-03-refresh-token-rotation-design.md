# Refresh Token Rotation 설계

- 작성일: 2026-09-03
- 대상 모듈: `member` (memberservice)
- 관련 파일: `AuthService`, `RefreshToken`(domain), `RefreshTokenRepository`, `RefreshTokenJpaEntity`, `AuthController`

## 배경 / 현재 문제

현재 `AuthService.reissue()`는 클라이언트가 보낸 refresh token을 DB에서 원문 그대로 조회하고, 재발급이 끝나면 해당 row를 즉시 **hard delete** 한다.

```java
refreshTokenRepository.delete(token);
refreshTokenRepository.save(RefreshToken.create(member, newRefreshToken, ...));
```

이 구조에는 두 가지 문제가 있다.

1. **토큰 재사용(reuse)을 감지할 방법이 없다.** 공격자가 refresh token을 탈취해 먼저 재발급을 받으면, 원래 토큰 row는 삭제된다. 이후 정상 사용자가 같은(이미 사용된) 토큰으로 재발급을 시도해도 "유효하지 않은 토큰"이라는 일반 에러만 발생할 뿐, 서버는 이것이 **탈취·재사용 상황**이라는 것을 인지하지 못하고 아무 조치도 취하지 않는다. 공격자는 이미 발급받은 세션으로 계속 활동할 수 있다.
2. **refresh token이 DB에 원문(raw JWT)으로 저장된다.** DB가 유출되면 저장된 토큰을 그대로 재사용할 수 있다.

## 목표

- refresh token이 **재사용되면 이를 감지**하고, 해당 계정의 모든 활성 세션을 무효화한다(강제 재로그인).
- refresh token을 DB에 **해시(SHA-256)로 저장**해 원문 유출 위험을 없앤다.
- 기존 동작(로그인 시 이전 세션 종료, access token 1시간/refresh token 7일, 로그아웃 시 전체 삭제)은 그대로 유지한다.

## 비목표 (Out of scope)

- 멀티 디바이스(동시 다중 로그인) 지원 — 현재처럼 회원당 활성 세션은 1개로 유지한다.
- 재사용 감지 시 이메일 알림 등 추가 사용자 통지 — 서버 로그 기록과 세션 무효화까지만 처리한다.
- Access token 자체의 즉시 무효화(재사용 감지 시점에 이미 발급된 access token은 자연 만료까지 유효) — JWT가 stateless라 별도 저장소 없이는 추적이 불가능하고, 기존 프로젝트도 이 한계를 인지하고 있었다(README "향후 개선점" 참고).

## 데이터 모델 변경

`refresh_token` 테이블 / `RefreshToken` 도메인에 컬럼 추가. `ddl-auto: update`이므로 스키마는 앱 재기동 시 자동 반영된다.

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `token` → `token_hash` | String | 기존 원문 저장 컬럼을 해시(SHA-256, hex) 저장으로 의미 변경. 컬럼명도 `tokenHash`로 변경 |
| `family_id` | String(UUID) | 로그인 1회당 새로 발급. 이후 rotation은 같은 family_id를 유지 |
| `revoked` | boolean, default false | rotation으로 교체되었거나(정상 사용 후) 재사용 감지로 강제 무효화된 토큰은 true |

`RefreshToken` 도메인에 다음 동작 추가:
- `RefreshToken.issue(member, tokenHash, familyId, expiredAt)` — 신규 발급(로그인/rotation 공통)
- `revoke()` — `revoked = true`로 전환
- `isRevoked()` — 조회용

## 플로우

### 로그인 (`login`)
1. 기존과 동일하게 `refreshTokenRepository.deleteByMemberId(member.getId())`로 이전 세션 완전 삭제
2. 새 `familyId = UUID.randomUUID()` 발급
3. refresh token 생성 → 해시 계산 → `RefreshToken.issue(...)` 저장 (`revoked=false`)
4. 클라이언트에는 원문 토큰 반환(기존과 동일), DB에는 해시만 남음

### 재발급 (`reissue`) — 핵심 변경
1. 클라이언트가 보낸 refresh token을 해시해서 `findByTokenHash(hash)`로 조회
2. **조회 결과 없음** → 기존과 동일하게 `IllegalArgumentException("유효하지 않은 토큰입니다.")` (400, 기존 `GlobalExceptionHandler` 매핑 유지)
3. **`revoked == true`** (이미 로테이션되어 사용 완료 처리된 토큰이 다시 들어옴) → **재사용 감지**:
   - 같은 `familyId`를 가진 모든 토큰을 `revoked=true`로 일괄 전환 (해당 회원의 활성 세션 완전 무효화)
   - `log.warn("refresh token 재사용 감지: memberId={}, familyId={}", ...)`
   - 전용 예외 `RefreshTokenReuseException` ("비정상적인 토큰 재사용이 감지되어 모든 세션이 종료되었습니다. 다시 로그인해 주세요.") throw — 아래 예외 처리 절 참고
4. **만료됨** (`isExpired()`) → 기존과 동일 `IllegalArgumentException("만료된 토큰입니다.")` (400)
5. **정상** → 조회된 토큰 `revoke()` 호출 후 저장 → 같은 `familyId`로 새 토큰 생성·해시 저장 (`revoked=false`) → `TokenResponse` 반환

### 로그아웃 (`logout`)
변경 없음 — `deleteByMemberId`로 해당 회원의 모든 토큰 row를 hard delete.

### 정리 스케줄러 (`RefreshTokenCleanupScheduler`)
변경 없음 — `deleteByExpiredAtBefore`가 revoked 여부와 무관하게 만료된 row를 정리하므로, revoked 토큰도 만료 시점에 자연스럽게 삭제된다.

## 저장소(Repository) 인터페이스 변경

```java
public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken refreshToken);
    Optional<RefreshToken> findByTokenHash(String tokenHash); // findByToken → 이름 변경
    void deleteByMemberId(Long memberId);
    void revokeAllByFamilyId(String familyId);                // 신규: 재사용 감지 시 family 전체 무효화
    void deleteByExpiredAtBefore(LocalDateTime now);
}
```

- `findByMemberId`, `delete(RefreshToken)`는 `AuthService` 외 다른 사용처가 없음을 확인했다. 인터페이스에서 제거한다.
- `revokeAllByFamilyId`는 JPA `@Modifying` 벌크 업데이트로 구현.

## 예외 처리

`member` 모듈에는 이미 `presentation/controller/GlobalExceptionHandler`가 있고, 확인 결과 매핑은 다음과 같다.

| 예외 | 현재 상태 코드 |
| --- | --- |
| `MethodArgumentNotValidException` (`@Valid` 실패) | 400 |
| `IllegalArgumentException` (비즈니스 로직 오류 — 무효/만료 토큰 등 현재 모든 인증 오류가 여기 포함) | 400 |
| `IllegalStateException` (권한 없음) | 409 |
| 그 외 `Exception` | 500 |

즉 현재는 "유효하지 않은 토큰"과 "만료된 토큰" 모두 400으로 응답하고 있고, 이 스펙에서는 그 기존 동작을 바꾸지 않는다.

재사용 감지만 의미상 분명히 구분하기 위해 신규 예외를 추가한다.

- `RefreshTokenReuseException extends RuntimeException` (도메인/애플리케이션 계층에 위치)
- `GlobalExceptionHandler`에 `@ExceptionHandler(RefreshTokenReuseException.class)`를 추가해 **401**로 매핑한다(다른 401 케이스가 아직 없으므로 이 예외가 이 프로젝트의 첫 401 응답이 된다 — 클라이언트에게 "재로그인 필요"를 명확히 구분해서 전달하기 위한 의도적 선택).

## 테스트 계획 (TDD)

`member/src/test/java/.../application/service/AuthServiceTest.java` 신규 작성 (Mockito 기반 단위 테스트, `RefreshTokenRepository`/`JwtTokenProvider`/`MemberRepository` mock):

1. `login()` — 새 familyId로 토큰이 발급되고, 이전 세션이 삭제되는지
2. `reissue()` 정상 케이스 — 기존 토큰이 revoke 처리되고, 같은 familyId로 새 토큰이 저장되는지
3. `reissue()` 재사용 감지 케이스 — revoked=true인 토큰이 다시 들어왔을 때 `RefreshTokenReuseException`이 발생하고 `revokeAllByFamilyId`가 호출되는지
4. `reissue()` 존재하지 않는 토큰 — `IllegalArgumentException("유효하지 않은 토큰입니다.")`
5. `reissue()` 만료된 토큰 — `IllegalArgumentException("만료된 토큰입니다.")`
6. `RefreshToken` 도메인 — `revoke()` 호출 후 `isRevoked()`가 true인지 (순수 도메인 유닛 테스트)

## 마이그레이션 참고

- 기존에 DB에 남아있는 원문 refresh token row는 새 해시 조회 로직과 맞지 않는다. `ddl-auto: update`로 컬럼만 추가되고 기존 데이터는 그대로 남으므로, 서비스 재기동 시점 이후 로그인한 사용자부터 새 구조가 적용된다. 개인 프로젝트 규모(운영 데이터 없음)이므로 별도 데이터 마이그레이션 스크립트는 작성하지 않는다.
