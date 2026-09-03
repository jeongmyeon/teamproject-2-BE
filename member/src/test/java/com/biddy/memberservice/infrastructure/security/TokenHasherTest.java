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
