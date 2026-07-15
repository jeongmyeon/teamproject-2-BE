package com.biddy.searchservice.infrastructure.persistence;

import com.biddy.searchservice.domain.model.MemberSearchHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberSearchHistoryJpaRepository extends JpaRepository<MemberSearchHistory, Long> {

    Optional<MemberSearchHistory> findByMemberIdAndKeyword(Long memberId, String keyword);

    List<MemberSearchHistory> findByMemberIdOrderByCountDescUpdatedAtDesc(Long memberId, Pageable pageable);
}
