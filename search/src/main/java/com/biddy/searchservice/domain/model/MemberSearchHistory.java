package com.biddy.searchservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
        name = "member_search_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_search_history_member_keyword",
                columnNames = {"member_id", "keyword"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSearchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 120)
    private String keyword;

    @Column(nullable = false)
    private Long count;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private MemberSearchHistory(Long memberId, String keyword) {
        this.memberId = memberId;
        this.keyword = keyword;
        this.count = 1L;
        this.updatedAt = LocalDateTime.now();
    }

    public static MemberSearchHistory create(Long memberId, String keyword) {
        return new MemberSearchHistory(memberId, keyword);
    }

    public void increase() {
        this.count += 1;
        this.updatedAt = LocalDateTime.now();
    }
}
