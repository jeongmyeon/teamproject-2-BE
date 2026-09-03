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
