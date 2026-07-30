package com.biddy.auction.auction.infra.persistence;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AuctionRepository Port 구현체 (Adapter).
 *
 * <p>domain 레이어의 {@code AuctionRepository} 인터페이스를 구현하여
 * JPA를 통한 실제 데이터 접근을 담당한다.
 * domain → infra 의존성을 역전(DIP)시키는 핵심 어댑터.</p>
 */
@Repository
@RequiredArgsConstructor
public class AuctionRepositoryAdapter implements AuctionRepository {

    private final AuctionJpaRepository auctionJpaRepository;

    @Override
    public Page<Auction> findByFilters(AuctionStatus status, Pageable pageable) {
        return auctionJpaRepository.findByFilters(status, pageable);
    }

    @Override
    public Optional<Auction> findById(String auctionId) {
        return auctionJpaRepository.findById(auctionId);
    }

    @Override
    public Optional<Auction> findByIdForUpdate(String auctionId) {
        return auctionJpaRepository.findByIdForUpdate(auctionId);
    }

    @Override
    public Auction save(Auction auction) {
        return auctionJpaRepository.save(auction);
    }

    @Override
    public void flush() {
        auctionJpaRepository.flush();
    }

    @Override
    public boolean existsById(String auctionId) {
        return auctionJpaRepository.existsById(auctionId);
    }

    @Override
    public List<Auction> findExpiredLiveAuctions(LocalDateTime now) {
        return auctionJpaRepository.findAllByStatusAndEndsAtBefore(AuctionStatus.LIVE, now);
    }
}
