package com.biddy.payment.settlement.presentation.controller;

import com.biddy.payment.global.response.ApiResponse;
import com.biddy.payment.settlement.presentation.request.MonthlySettlementRequest;
import com.biddy.payment.settlement.presentation.response.SettlementResponse;
import com.biddy.payment.settlement.application.SettlementService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/settlements")
public class SettlementController {

    private final SettlementService settlementService;
    private final int defaultBatchSize;

    public SettlementController(
            SettlementService settlementService,
            @Value("${payment.settlement.batch-size:500}") int defaultBatchSize
    ) {
        this.settlementService = settlementService;
        this.defaultBatchSize = defaultBatchSize;
    }

    @PostMapping("/monthly")
    public ApiResponse<List<SettlementResponse>> runMonthlySettlement(
            @Valid @RequestBody MonthlySettlementRequest request
    ) {
        return ApiResponse.ok(settlementService.runMonthlySettlement(request), "월별 정산 배치가 완료되었습니다.");
    }

    @PostMapping("/pending-batch")
    public ApiResponse<Integer> runPendingSettlementBatch(
            @RequestParam(value = "batchSize", required = false) Integer batchSize
    ) {
        int processedCount = settlementService.completePendingSettlements(
                batchSize == null ? defaultBatchSize : batchSize
        );
        return ApiResponse.ok(processedCount, "대기 정산 배치가 완료되었습니다.");
    }

    @GetMapping
    public ApiResponse<List<SettlementResponse>> getSettlements(@RequestHeader("X-Member-Id") Long memberId) {
        return ApiResponse.ok(settlementService.getSettlements(memberId));
    }

    @GetMapping("/{id}")
    public ApiResponse<SettlementResponse> getSettlement(
            @RequestHeader("X-Member-Id") Long memberId,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(settlementService.getSettlement(id, memberId));
    }
}
