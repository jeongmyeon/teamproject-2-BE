package com.biddy.auction.watch.presentation;

import com.biddy.auction.watch.application.dto.ToggleWatchResult;
import com.biddy.auction.watch.application.usecase.WatchUseCase;
import com.biddy.auction.watch.presentation.dto.ToggleWatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "관심 경매", description = "관심 경매 등록/해제 토글 API")
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/watch")
@RequiredArgsConstructor
@Validated
public class WatchController {

    private final WatchUseCase watchUseCase;

    @Operation(summary = "관심 여부 확인")
    @GetMapping
    public ResponseEntity<Map<String, Boolean>> isWatching(
            @PathVariable String auctionId,
            @RequestHeader(value = "X-Member-Id", required = false) @Positive Long memberId
    ) {
        boolean watching = watchUseCase.isWatching(auctionId, memberId);
        return ResponseEntity.ok(Map.of("watching", watching));
    }

    @Operation(summary = "관심 경매 토글")
    @PostMapping
    public ResponseEntity<ToggleWatchResponse> toggleWatch(
            @Parameter(description = "경매 ID") @PathVariable String auctionId,
            @RequestHeader("X-Member-Id") @Positive Long memberId
    ) {
        ToggleWatchResult result = watchUseCase.toggleWatch(auctionId, memberId);
        return ResponseEntity.ok(ToggleWatchResponse.from(result));
    }
}
