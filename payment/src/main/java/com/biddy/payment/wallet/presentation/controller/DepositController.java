package com.biddy.payment.wallet.presentation.controller;

import com.biddy.payment.global.response.ApiResponse;
import com.biddy.payment.wallet.presentation.request.DepositAdjustRequest;
import com.biddy.payment.wallet.presentation.request.DepositChargeCancelRequest;
import com.biddy.payment.wallet.presentation.response.DepositBalanceResponse;
import com.biddy.payment.wallet.presentation.request.DepositChargeRequest;
import com.biddy.payment.wallet.presentation.response.DepositTransactionResponse;
import com.biddy.payment.wallet.presentation.request.DepositWithdrawRequest;
import com.biddy.payment.wallet.application.DepositService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping("/charge")
    public ApiResponse<DepositBalanceResponse> charge(
            @RequestHeader("X-Member-Id") Long memberId,
            @Valid @RequestBody DepositChargeRequest request
    ) {
        return ApiResponse.ok(depositService.charge(request.withUserId(memberId)), "예치금 충전이 완료되었습니다.");
    }

    @PostMapping("/charge/cancel")
    public ApiResponse<DepositBalanceResponse> cancelCharge(
            @RequestHeader("X-Member-Id") Long memberId,
            @Valid @RequestBody DepositChargeCancelRequest request
    ) {
        return ApiResponse.ok(depositService.cancelCharge(request.withUserId(memberId)), "예치금 충전 취소가 완료되었습니다.");
    }

    @PostMapping("/withdraw")
    public ApiResponse<DepositBalanceResponse> withdraw(
            @RequestHeader("X-Member-Id") Long memberId,
            @Valid @RequestBody DepositWithdrawRequest request
    ) {
        return ApiResponse.ok(depositService.withdraw(request.withUserId(memberId)), "예치금 출금 신청이 완료되었습니다.");
    }

    @PatchMapping("/adjust")
    public ApiResponse<DepositBalanceResponse> adjust(@Valid @RequestBody DepositAdjustRequest request) {
        return ApiResponse.ok(depositService.adjust(request), "예치금 강제 조정이 완료되었습니다.");
    }

    @GetMapping("/balance")
    public ApiResponse<DepositBalanceResponse> getBalance(
            @RequestHeader("X-Member-Id") Long memberId,
            @RequestHeader(value = "X-Member-Role", required = false) String memberRole,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        Long targetUserId = "ADMIN".equals(memberRole) && userId != null ? userId : memberId;
        return ApiResponse.ok(depositService.getBalance(targetUserId));
    }

    @GetMapping("/transactions")
    public ApiResponse<List<DepositTransactionResponse>> getTransactions(
            @RequestHeader("X-Member-Id") Long memberId,
            @RequestParam(value = "size", defaultValue = "30") int size
    ) {
        return ApiResponse.ok(depositService.getTransactions(memberId, size));
    }
}
