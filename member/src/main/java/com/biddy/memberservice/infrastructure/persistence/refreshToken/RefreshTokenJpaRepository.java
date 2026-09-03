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
