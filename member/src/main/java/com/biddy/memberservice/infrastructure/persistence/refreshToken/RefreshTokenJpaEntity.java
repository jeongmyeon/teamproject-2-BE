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
