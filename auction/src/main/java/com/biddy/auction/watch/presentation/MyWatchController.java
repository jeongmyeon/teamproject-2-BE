package com.biddy.auction.watch.presentation;

import com.biddy.auction.watch.application.usecase.WatchUseCase;
import com.biddy.auction.watch.presentation.dto.MyWatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내 관심 경매", description = "내가 관심 등록한 경매 목록 조회 API")
@RestController
@RequestMapping("/api/v1/members/me/watches")
@RequiredArgsConstructor
@Validated
public class MyWatchController {

    private final WatchUseCase watchUseCase;

    @Operation(summary = "내 관심 경매 목록 조회")
    @GetMapping
    public ResponseEntity<Page<MyWatchResponse>> getMyWatches(
            @RequestHeader("X-Member-Id") @Positive Long memberId,
            @Parameter(description = "페이지 번호") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<MyWatchResponse> response = watchUseCase.getMyWatches(memberId, page, size)
                .map(MyWatchResponse::from);
        return ResponseEntity.ok(response);
    }
}
