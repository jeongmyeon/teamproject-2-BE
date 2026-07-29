package com.biddy.auction.bid.presentation;

import com.biddy.auction.bid.application.dto.BidHistoryQuery;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.application.usecase.BidUseCase;
import com.biddy.auction.bid.presentation.dto.BidHistoryResponse;
import com.biddy.auction.bid.presentation.dto.PlaceBidRequest;
import com.biddy.auction.bid.presentation.dto.PlaceBidResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 입찰 REST API Controller.
 */
@Tag(name = "입찰", description = "입찰 실행 및 입찰 내역 조회 API")
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidUseCase bidUseCase;

    @Operation(summary = "입찰 실행", description = "낙관적 락으로 입찰을 커밋한다. 201 응답은 입찰 저장과 경매 현재가 갱신이 모두 완료된 경우에만 반환한다.")
    @PostMapping
    public ResponseEntity<PlaceBidResponse> placeBid(
            @Parameter(description = "경매 ID") @PathVariable String auctionId,
            @RequestHeader("X-Member-Id") Long bidderId,
            @RequestBody PlaceBidRequest request
    ) {
        PlaceBidCommand command = new PlaceBidCommand(auctionId, bidderId, request.amount());
        PlaceBidResult result = bidUseCase.placeBid(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaceBidResponse.from(result));
    }

    @Operation(summary = "입찰 내역 조회", description = "특정 경매의 입찰 내역을 최신순으로 페이징 조회한다.")
    @GetMapping
    public ResponseEntity<Page<BidHistoryResponse>> getBidHistory(
            @Parameter(description = "경매 ID") @PathVariable String auctionId,
            @Parameter(description = "페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size
    ) {
        BidHistoryQuery query = new BidHistoryQuery(auctionId, page, size);
        Page<BidHistoryResponse> response = bidUseCase.getBidHistory(query)
                .map(BidHistoryResponse::from);
        return ResponseEntity.ok(response);
    }
}
