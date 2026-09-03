# Refresh Token Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `member` 서비스의 refresh token 재발급 로직에 family-id 기반 rotation을 도입해, 탈취된 토큰이 재사용될 때 이를 감지하고 해당 계정의 모든 세션을 강제 무효화한다. 동시에 DB에 저장하는 토큰 값을 원문에서 SHA-256 해시로 바꾼다.

**Architecture:** `refresh_token` 테이블에 `family_id`(로그인 1회당 발급되는 UUID)와 `revoked`(rotation으로 교체됐는지) 컬럼을 추가한다. 재발급마다 기존 토큰은 삭제 대신 `revoked=true`로 표시하고 같은 family_id로 새 토큰을 발급한다. 이미 `revoked=true`인 토큰이 다시 들어오면 재사용으로 간주해 같은 family의 모든 토큰을 무효화하고 전용 예외를 던진다.

**Tech Stack:** Spring Boot, Spring Data JPA (PostgreSQL, `ddl-auto: update`), JUnit 5 + Mockito + AssertJ (spring-boot-starter-test 기본 포함), 참조 스펙: [`docs/superpowers/specs/2026-09-03-refresh-token-rotation-design.md`](../specs/2026-09-03-refresh-token-rotation-design.md)

## Global Constraints

- 회원당 활성 세션은 1개로 유지한다 (멀티 디바이스 지원 안 함) — 로그인 시 기존 세션 전체 삭제하는 기존 동작 유지.
- DB에는 refresh token 원문을 저장하지 않는다 — SHA-256 해시(hex, 64자)만 저장한다.
- 재사용이 감지되면 해당 계정의 **family 전체**를 `revoked=true`로 전환한다 (완전 삭제 아님 — 만료 시 기존 정리 스케줄러가 자연스럽게 지운다).
- 재사용 감지 응답은 신규 예외 `RefreshTokenReuseException` → **401**. 기존 "유효하지 않은 토큰"/"만료된 토큰" 응답은 지금처럼 **400**으로 유지한다(기존 동작 변경 없음).
- `reissue()`의 `@Transactional`에는 반드시 `noRollbackFor = RefreshTokenReuseException.class`를 지정한다 — 그렇지 않으면 재사용 감지 시 실행한 family 전체 무효화가 예외로 인해 롤백되어 버린다 (설계 문서 "구현 시 주의사항" 참고).
- Access token 자체의 즉시 무효화는 이번 범위에 포함하지 않는다 (기존 README "향후 개선점"에 이미 명시된 한계).

---

### Task 1: TokenHasher — refresh token 해시 유틸리티

**Files:**
- Create: `member/src/main/java/com/biddy/memberservice/infrastructure/security/TokenHasher.java`
- Test: `member/src/test/java/com/biddy/memberservice/infrastructure/security/TokenHasherTest.java`

**Interfaces:**
- Produces: `TokenHasher.hash(String rawToken) -> String` (64자리 소문자 16진수 SHA-256 해시).이후 모든 Task에서 이 메서드로 토큰을 해시한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.memberservice.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    private final TokenHasher tokenHasher = new TokenHasher();

    @Test
    void 같은_입력은_항상_같은_해시를_반환한다() {
        String hash1 = tokenHasher.hash("sample-refresh-token");
        String hash2 = tokenHasher.hash("sample-refresh-token");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void 다른_입력은_다른_해시를_반환한다() {
        String hash1 = tokenHasher.hash("token-a");
        String hash2 = tokenHasher.hash("token-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void 해시_결과는_64자리_16진수_문자열이다() {
        String hash = tokenHasher.hash("sample-refresh-token");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.infrastructure.security.TokenHasherTest"`
Expected: FAIL — `TokenHasher` 클래스가 없어 컴파일 실패

- [ ] **Step 3: 최소 구현 작성**

```java
package com.biddy.memberservice.infrastructure.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class TokenHasher {

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.infrastructure.security.TokenHasherTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add member/src/main/java/com/biddy/memberservice/infrastructure/security/TokenHasher.java member/src/test/java/com/biddy/memberservice/infrastructure/security/TokenHasherTest.java
git commit -m "feat(member): refresh token 해시용 TokenHasher 추가"
```

---

### Task 2: RefreshToken 도메인 모델 — family/revoked 개념 도입

**Files:**
- Modify: `member/src/main/java/com/biddy/memberservice/domain/model/RefreshToken.java`
- Test: `member/src/test/java/com/biddy/memberservice/domain/model/RefreshTokenTest.java`

**Interfaces:**
- Consumes: `Member` (기존 도메인, 변경 없음)
- Produces: `RefreshToken.issue(Member member, String tokenHash, String familyId, LocalDateTime expiredAt) -> RefreshToken`, `refreshToken.revoke() -> void`, `refreshToken.isRevoked() -> boolean`, `refreshToken.getFamilyId() -> String`, `refreshToken.getTokenHash() -> String`. Task 3~5에서 이 시그니처를 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.memberservice.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    private final Member member = Member.builder().id(1L).build();

    @Test
    void issue로_생성한_토큰은_revoked가_false다() {
        RefreshToken token = RefreshToken.issue(member, "hash", "family-1", LocalDateTime.now().plusDays(7));

        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getFamilyId()).isEqualTo("family-1");
        assertThat(token.getTokenHash()).isEqualTo("hash");
    }

    @Test
    void revoke를_호출하면_revoked가_true로_바뀐다() {
        RefreshToken token = RefreshToken.issue(member, "hash", "family-1", LocalDateTime.now().plusDays(7));

        token.revoke();

        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void 만료시각이_지나면_isExpired가_true다() {
        RefreshToken token = RefreshToken.issue(member, "hash", "family-1", LocalDateTime.now().minusSeconds(1));

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void 만료시각_전이면_isExpired가_false다() {
        RefreshToken token = RefreshToken.issue(member, "hash", "family-1", LocalDateTime.now().plusDays(7));

        assertThat(token.isExpired()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.domain.model.RefreshTokenTest"`
Expected: FAIL — `issue`, `isRevoked`, `getFamilyId`, `getTokenHash` 가 없어 컴파일 실패

- [ ] **Step 3: RefreshToken 도메인 모델 수정**

`member/src/main/java/com/biddy/memberservice/domain/model/RefreshToken.java` 전체를 다음으로 교체한다.

```java
package com.biddy.memberservice.domain.model;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RefreshToken {

    private Long id;
    private Member member;
    private String tokenHash;
    private String familyId;
    private boolean revoked;
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;

    public static RefreshToken issue(Member member, String tokenHash, String familyId, LocalDateTime expiredAt) {
        return RefreshToken.builder()
                .member(member)
                .tokenHash(tokenHash)
                .familyId(familyId)
                .revoked(false)
                .expiredAt(expiredAt)
                .build();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiredAt);
    }

    public boolean isRevoked() {
        return this.revoked;
    }

    public void revoke() {
        this.revoked = true;
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.domain.model.RefreshTokenTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add member/src/main/java/com/biddy/memberservice/domain/model/RefreshToken.java member/src/test/java/com/biddy/memberservice/domain/model/RefreshTokenTest.java
git commit -m "feat(member): RefreshToken 도메인에 familyId/revoked 개념 도입"
```

---

### Task 3: 영속성 계층 — JPA 엔티티 · 리포지토리 변경

이 Task는 매핑/쿼리 정의만 다루며 독립적인 단위 테스트가 없다 (프로젝트에 `@DataJpaTest` 등 JPA 계층 테스트 인프라가 아직 없고, 이 리포지토리는 Task 4에서 mock으로 대체되어 검증된다 — 기존 프로젝트의 테스트 커버리지 수준과 동일한 선에서 범위를 맞춘다).

**Files:**
- Modify: `member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenJpaEntity.java`
- Modify: `member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenJpaRepository.java`
- Modify: `member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenRepositoryImpl.java`
- Modify: `member/src/main/java/com/biddy/memberservice/domain/repository/RefreshTokenRepository.java`

**Interfaces:**
- Consumes: `RefreshToken.issue/revoke/isRevoked/getFamilyId/getTokenHash` (Task 2)
- Produces: `RefreshTokenRepository.findByTokenHash(String) -> Optional<RefreshToken>`, `RefreshTokenRepository.revokeAllByFamilyId(String familyId) -> void`, `RefreshTokenRepository.save/deleteByMemberId/deleteByExpiredAtBefore` (기존 시그니처 유지). Task 4(AuthService)가 이 인터페이스를 그대로 사용한다.

- [ ] **Step 1: `RefreshTokenRepository` 도메인 인터페이스 수정**

`member/src/main/java/com/biddy/memberservice/domain/repository/RefreshTokenRepository.java` 전체를 다음으로 교체한다.

```java
package com.biddy.memberservice.domain.repository;

import com.biddy.memberservice.domain.model.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken refreshToken);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByMemberId(Long memberId);
    void revokeAllByFamilyId(String familyId);
    void deleteByExpiredAtBefore(LocalDateTime now);
}
```

(기존의 `findByMemberId`, `delete(RefreshToken)`는 `AuthService` 외 사용처가 없음을 확인했으므로 제거한다 — `AdminService`는 `deleteByMemberId`만 사용하며 영향 없음.)

- [ ] **Step 2: `RefreshTokenJpaEntity` 수정**

`member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenJpaEntity.java` 전체를 다음으로 교체한다.

```java
package com.biddy.memberservice.infrastructure.persistence.refreshToken;

import com.biddy.memberservice.domain.model.RefreshToken;
import com.biddy.memberservice.infrastructure.persistence.member.MemberJpaEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private MemberJpaEntity member;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(nullable = false)
    private boolean revoked;

    @Column(nullable = false)
    private LocalDateTime expiredAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public RefreshToken toDomain() {
        return RefreshToken.builder()
                .id(this.id)
                .member(this.member.toDomain())
                .tokenHash(this.tokenHash)
                .familyId(this.familyId)
                .revoked(this.revoked)
                .expiredAt(this.expiredAt)
                .createdAt(this.createdAt)
                .build();
    }

    public static RefreshTokenJpaEntity from(RefreshToken rt) {
        RefreshTokenJpaEntity e = new RefreshTokenJpaEntity();
        e.id = rt.getId();
        e.member = MemberJpaEntity.from(rt.getMember());
        e.tokenHash = rt.getTokenHash();
        e.familyId = rt.getFamilyId();
        e.revoked = rt.isRevoked();
        e.expiredAt = rt.getExpiredAt();
        e.createdAt = rt.getCreatedAt();
        return e;
    }
}
```

- [ ] **Step 3: `RefreshTokenJpaRepository` 수정**

`member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenJpaRepository.java` 전체를 다음으로 교체한다.

```java
package com.biddy.memberservice.infrastructure.persistence.refreshToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {
    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);
    void deleteByMemberId(Long memberId);
    void deleteByExpiredAtBefore(LocalDateTime expiredAtBefore);

    @Modifying
    @Query("update RefreshTokenJpaEntity r set r.revoked = true where r.familyId = :familyId")
    void revokeAllByFamilyId(@Param("familyId") String familyId);
}
```

- [ ] **Step 4: `RefreshTokenRepositoryImpl` 수정**

`member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/RefreshTokenRepositoryImpl.java` 전체를 다음으로 교체한다.

```java
package com.biddy.memberservice.infrastructure.persistence.refreshToken;

import com.biddy.memberservice.domain.model.RefreshToken;
import com.biddy.memberservice.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        return jpaRepository.save(RefreshTokenJpaEntity.from(refreshToken)).toDomain();
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash)
                .map(RefreshTokenJpaEntity::toDomain);
    }

    @Override
    public void deleteByMemberId(Long memberId) {
        jpaRepository.deleteByMemberId(memberId);
    }

    @Override
    @Transactional
    public void revokeAllByFamilyId(String familyId) {
        jpaRepository.revokeAllByFamilyId(familyId);
    }

    @Override
    public void deleteByExpiredAtBefore(LocalDateTime now) {
        jpaRepository.deleteByExpiredAtBefore(now);
    }
}
```

- [ ] **Step 5: member 모듈 컴파일 확인** (이 시점에서 `AuthService`, `AdminService`가 옛 인터페이스를 참조해 컴파일이 깨지는 것이 정상 — Task 4에서 고친다)

Run: `./gradlew :member:compileJava`
Expected: FAIL — `AuthService.java`에서 `findByToken`, `RefreshToken.create` 등을 찾을 수 없다는 컴파일 에러 (예상된 상태, Task 4에서 해결)

- [ ] **Step 6: 커밋**

```bash
git add member/src/main/java/com/biddy/memberservice/infrastructure/persistence/refreshToken/ member/src/main/java/com/biddy/memberservice/domain/repository/RefreshTokenRepository.java
git commit -m "feat(member): refresh_token 영속성 계층에 family_id/revoked/token_hash 반영"
```

---

### Task 4: AuthService — rotation·재사용 감지 로직

**Files:**
- Create: `member/src/main/java/com/biddy/memberservice/domain/exception/RefreshTokenReuseException.java`
- Modify: `member/src/main/java/com/biddy/memberservice/application/service/AuthService.java`
- Test: `member/src/test/java/com/biddy/memberservice/application/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `TokenHasher.hash` (Task 1), `RefreshToken.issue/revoke/isRevoked/isExpired/getFamilyId` (Task 2), `RefreshTokenRepository.findByTokenHash/save/deleteByMemberId/revokeAllByFamilyId` (Task 3)
- Produces: `AuthService.login/reissue/logout` 시그니처는 기존과 동일하게 유지 (컨트롤러 변경 불필요). `RefreshTokenReuseException`은 Task 5(GlobalExceptionHandler)에서 사용.

- [ ] **Step 1: `RefreshTokenReuseException` 작성**

```java
package com.biddy.memberservice.domain.exception;

public class RefreshTokenReuseException extends RuntimeException {

    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 실패하는 `AuthServiceTest` 작성**

```java
package com.biddy.memberservice.application.service;

import com.biddy.memberservice.application.dto.request.LoginRequest;
import com.biddy.memberservice.application.dto.response.TokenResponse;
import com.biddy.memberservice.application.event.MemberEventPublisher;
import com.biddy.memberservice.domain.enums.MemberRole;
import com.biddy.memberservice.domain.enums.MemberStatus;
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import com.biddy.memberservice.domain.model.Member;
import com.biddy.memberservice.domain.model.RefreshToken;
import com.biddy.memberservice.domain.repository.EmailVerificationRepository;
import com.biddy.memberservice.domain.repository.MemberRepository;
import com.biddy.memberservice.domain.repository.RefreshTokenRepository;
import com.biddy.memberservice.infrastructure.security.JwtBlacklistService;
import com.biddy.memberservice.infrastructure.security.JwtTokenProvider;
import com.biddy.memberservice.infrastructure.security.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtBlacklistService jwtBlacklistService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JavaMailSender mailSender;
    @Mock private MemberEventPublisher eventPublisher;
    @Mock private TokenHasher tokenHasher;

    @InjectMocks
    private AuthService authService;

    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .id(1L)
                .email("user@biddy.com")
                .password("encoded-password")
                .role(MemberRole.USER)
                .status(MemberStatus.ACTIVE)
                .build();
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        return request;
    }

    @Test
    void login_성공하면_새_familyId로_토큰을_발급하고_이전_세션을_삭제한다() {
        given(memberRepository.findByEmail("user@biddy.com")).willReturn(Optional.of(member));
        given(passwordEncoder.matches("raw-password", "encoded-password")).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(1L, MemberRole.USER)).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("refresh-token");
        given(tokenHasher.hash("refresh-token")).willReturn("hashed-refresh-token");
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TokenResponse response = authService.login(loginRequest("user@biddy.com", "raw-password"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        verify(refreshTokenRepository).deleteByMemberId(1L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo("hashed-refresh-token");
        assertThat(captor.getValue().isRevoked()).isFalse();
        assertThat(captor.getValue().getFamilyId()).isNotBlank();
    }

    @Test
    void reissue_정상_토큰이면_기존_토큰을_revoke하고_같은_familyId로_새_토큰을_발급한다() {
        RefreshToken existing = RefreshToken.issue(member, "old-hash", "family-1", LocalDateTime.now().plusDays(1));
        given(tokenHasher.hash("old-refresh-token")).willReturn("old-hash");
        given(refreshTokenRepository.findByTokenHash("old-hash")).willReturn(Optional.of(existing));
        given(jwtTokenProvider.generateAccessToken(1L, MemberRole.USER)).willReturn("new-access-token");
        given(jwtTokenProvider.generateRefreshToken(1L)).willReturn("new-refresh-token");
        given(tokenHasher.hash("new-refresh-token")).willReturn("new-hash");
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TokenResponse response = authService.reissue("old-refresh-token");

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(existing.isRevoked()).isTrue();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());
        RefreshToken savedNew = captor.getAllValues().get(1);
        assertThat(savedNew.getTokenHash()).isEqualTo("new-hash");
        assertThat(savedNew.getFamilyId()).isEqualTo("family-1");
        assertThat(savedNew.isRevoked()).isFalse();
    }

    @Test
    void reissue_이미_사용된_revoked_토큰이_다시_들어오면_family_전체를_무효화하고_예외를_던진다() {
        RefreshToken alreadyUsed = RefreshToken.issue(member, "stolen-hash", "family-2", LocalDateTime.now().plusDays(1));
        alreadyUsed.revoke();
        given(tokenHasher.hash("stolen-refresh-token")).willReturn("stolen-hash");
        given(refreshTokenRepository.findByTokenHash("stolen-hash")).willReturn(Optional.of(alreadyUsed));

        assertThatThrownBy(() -> authService.reissue("stolen-refresh-token"))
                .isInstanceOf(RefreshTokenReuseException.class);

        verify(refreshTokenRepository).revokeAllByFamilyId("family-2");
        verify(jwtTokenProvider, never()).generateAccessToken(any(), any());
    }

    @Test
    void reissue_존재하지_않는_토큰이면_IllegalArgumentException을_던진다() {
        given(tokenHasher.hash("unknown-token")).willReturn("unknown-hash");
        given(refreshTokenRepository.findByTokenHash("unknown-hash")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue("unknown-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("유효하지 않은 토큰입니다.");
    }

    @Test
    void reissue_만료된_토큰이면_IllegalArgumentException을_던진다() {
        RefreshToken expired = RefreshToken.issue(member, "expired-hash", "family-3", LocalDateTime.now().minusSeconds(1));
        given(tokenHasher.hash("expired-token")).willReturn("expired-hash");
        given(refreshTokenRepository.findByTokenHash("expired-hash")).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.reissue("expired-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("만료된 토큰입니다.");
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.application.service.AuthServiceTest"`
Expected: FAIL — `AuthService`에 `TokenHasher` 필드가 없고, `login`/`reissue`가 아직 옛 `RefreshTokenRepository` API(`findByToken`, `RefreshToken.create`)를 호출하고 있어 컴파일 실패

- [ ] **Step 4: `AuthService` 수정**

`member/src/main/java/com/biddy/memberservice/application/service/AuthService.java`에서 import·필드·`login`·`reissue`를 아래와 같이 바꾼다 (`signup`, `logout`, `sendVerificationEmail`, `verifyEmail`은 변경 없음).

import 절 상단에 추가:
```java
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import com.biddy.memberservice.infrastructure.security.TokenHasher;

import java.util.UUID;
```

필드 추가 (기존 `private final MemberEventPublisher eventPublisher;` 바로 아래):
```java
    private final TokenHasher tokenHasher;
```

`login` 메서드 전체 교체:
```java
    @Transactional
    public TokenResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new IllegalArgumentException("탈퇴 처리 중인 계정입니다.");
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new IllegalArgumentException("정지된 계정입니다.");
        }
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(member.getId(), member.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(member.getId());

        refreshTokenRepository.deleteByMemberId(member.getId());
        String familyId = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.issue(
                member,
                tokenHasher.hash(refreshToken),
                familyId,
                LocalDateTime.now().plusDays(7)
        ));

        return TokenResponse.of(accessToken, refreshToken);
    }
```

`reissue` 메서드 전체 교체:
```java
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenResponse reissue(String refreshToken) {
        String tokenHash = tokenHasher.hash(refreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

        if (token.isRevoked()) {
            refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId());
            log.warn("refresh token 재사용 감지: memberId={}, familyId={}",
                    token.getMember().getId(), token.getFamilyId());
            throw new RefreshTokenReuseException(
                    "비정상적인 토큰 재사용이 감지되어 모든 세션이 종료되었습니다. 다시 로그인해 주세요.");
        }

        if (token.isExpired()) {
            throw new IllegalArgumentException("만료된 토큰입니다.");
        }

        Member member = token.getMember();
        token.revoke();
        refreshTokenRepository.save(token);

        String newAccessToken = jwtTokenProvider.generateAccessToken(member.getId(), member.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(member.getId());

        refreshTokenRepository.save(RefreshToken.issue(
                member,
                tokenHasher.hash(newRefreshToken),
                token.getFamilyId(),
                LocalDateTime.now().plusDays(7)
        ));

        return TokenResponse.of(newAccessToken, newRefreshToken);
    }
```

**주의:** `@Transactional(noRollbackFor = RefreshTokenReuseException.class)`를 빠뜨리면 재사용 감지 시 실행한 `revokeAllByFamilyId` 호출이 예외로 인해 롤백되어, 정작 계정 세션이 무효화되지 않는다. (Mockito 단위 테스트는 실제 트랜잭션 프록시를 거치지 않으므로 이 부분은 테스트로 검증되지 않는다 — 어노테이션을 놓치지 않도록 특히 주의해서 확인할 것.)

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.application.service.AuthServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: member 모듈 전체 컴파일·테스트 확인** (`AdminService` 등 다른 소비자가 깨지지 않았는지 포함)

Run: `./gradlew :member:test`
Expected: PASS (전체 테스트 그린)

- [ ] **Step 7: 커밋**

```bash
git add member/src/main/java/com/biddy/memberservice/domain/exception/RefreshTokenReuseException.java member/src/main/java/com/biddy/memberservice/application/service/AuthService.java member/src/test/java/com/biddy/memberservice/application/service/AuthServiceTest.java
git commit -m "feat(member): refresh token rotation·재사용 감지 로직 구현"
```

---

### Task 5: GlobalExceptionHandler — 재사용 감지 예외를 401로 매핑

**Files:**
- Modify: `member/src/main/java/com/biddy/memberservice/presentation/controller/GlobalExceptionHandler.java`
- Test: `member/src/test/java/com/biddy/memberservice/presentation/controller/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `RefreshTokenReuseException` (Task 4)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.biddy.memberservice.presentation.controller;

import com.biddy.memberservice.domain.exception.ErrorResponse;
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void RefreshTokenReuseException은_401로_매핑된다() {
        RefreshTokenReuseException exception =
                new RefreshTokenReuseException("비정상적인 토큰 재사용이 감지되어 모든 세션이 종료되었습니다. 다시 로그인해 주세요.");

        ResponseEntity<ErrorResponse> response = handler.handleRefreshTokenReuseException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getMessage()).isEqualTo(exception.getMessage());
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 에러) 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.presentation.controller.GlobalExceptionHandlerTest"`
Expected: FAIL — `handleRefreshTokenReuseException` 메서드가 없어 컴파일 실패

- [ ] **Step 3: `GlobalExceptionHandler`에 핸들러 추가**

`import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;`을 import 절에 추가하고, `handleIllegalStateException` 메서드 바로 아래에 다음을 추가한다.

```java
    // Refresh Token 재사용 감지 — 재로그인 필요
    @ExceptionHandler(RefreshTokenReuseException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenReuseException(
            RefreshTokenReuseException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(401, e.getMessage()));
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew :member:test --tests "com.biddy.memberservice.presentation.controller.GlobalExceptionHandlerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add member/src/main/java/com/biddy/memberservice/presentation/controller/GlobalExceptionHandler.java member/src/test/java/com/biddy/memberservice/presentation/controller/GlobalExceptionHandlerTest.java
git commit -m "feat(member): RefreshTokenReuseException을 401로 매핑"
```

---

### Task 6: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: member 모듈 전체 테스트 실행**

Run: `./gradlew :member:test`
Expected: PASS — Task 1~5에서 추가한 테스트(TokenHasherTest 3개, RefreshTokenTest 4개, AuthServiceTest 5개, GlobalExceptionHandlerTest 1개) + 기존 `MemberServiceApplicationTests` 모두 그린

- [ ] **Step 2: member 모듈 빌드 확인**

Run: `./gradlew :member:build -x test` (이미 테스트는 위에서 통과 확인했으므로 빌드만 재확인)
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: README "진행 중인 개선 작업" 섹션 상태 갱신**

`README.md`의 "## 9. 진행 중인 개선 작업" 항목 중 Refresh Token Rotation 줄을 "설계 완료, 구현 진행 중"에서 "구현 완료 (커밋: Task 1~5 참고)"로 갱신한다. 정확한 문구는 구현 완료 시점에 다시 확인해서 작성한다.

- [ ] **Step 4: 최종 커밋 & 개인 저장소 push**

```bash
git add README.md
git commit -m "docs: Refresh Token Rotation 구현 완료 상태로 README 갱신"
git push origin develop
```
