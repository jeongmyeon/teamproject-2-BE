package com.biddy.auction.auction.presentation;

import com.biddy.auction.auction.application.dto.AuctionFeedQuery;
import com.biddy.auction.auction.application.usecase.AuctionUseCase;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.presentation.dto.AuctionDetailResponse;
import com.biddy.auction.auction.presentation.dto.AuctionFeedResponse;
import com.biddy.auction.auction.presentation.dto.AuctionResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 경매 REST API Controller.
 */
@Tag(name = "경매", description = "경매 피드 조회, 상세 조회, 낙찰 결과 조회 API")
@RestController
@RequestMapping("/api/v1/auctions")
@RequiredArgsConstructor
@Validated
public class AuctionController {

    private final AuctionUseCase auctionUseCase;

    @Operation(summary = "경매 피드 조회", description = "상태, 정렬 기준으로 경매 목록을 페이징 조회한다. 카테고리 필터는 Product Service 책임 (Gateway 조합).")
    @GetMapping
    public ResponseEntity<Page<AuctionFeedResponse>> getAuctionFeed(
            @Parameter(description = "경매 상태 (LIVE, ENDED)") @RequestParam(required = false) AuctionStatus status,
            @Parameter(description = "정렬 기준 (ending: 마감임박, price: 높은가격, priceAsc: 낮은가격, latest: 최신)")
            @RequestParam(required = false) @Pattern(regexp = "ending|price|priceAsc|latest") String sort,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        AuctionFeedQuery query = new AuctionFeedQuery(status, sort, page, size);
        Page<AuctionFeedResponse> response = auctionUseCase.getAuctionFeed(query)
                .map(AuctionFeedResponse::from);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 상세 조회", description = "경매 ID로 상세 정보를 조회한다. memberId를 전달하면 관심 등록 여부도 포함한다.")
    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionDetailResponse> getAuctionDetail(
            @Parameter(description = "경매 ID (예: A-FNF97)") @PathVariable String auctionId,
            @RequestHeader(value = "X-Member-Id", required = false) @Positive Long memberId
    ) {
        AuctionDetailResponse response = AuctionDetailResponse.from(
                auctionUseCase.getAuctionDetail(auctionId, memberId));
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 즉시 종료", description = "판매자가 자신의 경매를 즉시 종료한다. 입찰이 있으면 낙찰, 없으면 유찰 처리.")
    @PostMapping("/{auctionId}/close")
    public ResponseEntity<Void> closeAuction(
            @Parameter(description = "경매 ID") @PathVariable String auctionId,
            @RequestHeader("X-Member-Id") @Positive Long memberId
    ) {
        auctionUseCase.closeAuctionBySeller(auctionId, memberId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "낙찰/유찰 결과 조회", description = "종료된 경매의 낙찰 또는 유찰 결과를 조회한다. LIVE 상태면 409 Conflict.")
    @GetMapping("/{auctionId}/result")
    public ResponseEntity<AuctionResultResponse> getAuctionResult(
            @Parameter(description = "경매 ID (예: A-FNF97)") @PathVariable String auctionId
    ) {
        AuctionResultResponse response = AuctionResultResponse.from(
                auctionUseCase.getAuctionResult(auctionId));
        return ResponseEntity.ok(response);
    }
}
