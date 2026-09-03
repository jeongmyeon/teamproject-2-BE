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
