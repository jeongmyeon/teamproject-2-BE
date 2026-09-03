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
