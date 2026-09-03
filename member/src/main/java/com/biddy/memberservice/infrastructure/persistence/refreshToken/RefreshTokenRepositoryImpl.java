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
