package com.biddy.payment.payment.presentation.controller;

import com.biddy.payment.global.response.ApiResponse;
import com.biddy.payment.payment.presentation.request.PaymentCancelRequest;
import com.biddy.payment.payment.presentation.request.PaymentCreateRequest;
import com.biddy.payment.payment.presentation.response.PaymentResponse;
import com.biddy.payment.payment.application.PaymentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ApiResponse<PaymentResponse> create(
            @AuthenticationPrincipal Long memberId,
            @RequestHeader("X-Member-Role") String memberRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentCreateRequest request
    ) {
        return ApiResponse.ok(
                paymentService.create(request.withUserId(memberId), memberRole, idempotencyKey),
                "결제가 생성되었습니다."
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentResponse> get(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(paymentService.get(id, memberId));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PaymentResponse> cancel(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody PaymentCancelRequest request
    ) {
        return ApiResponse.ok(paymentService.cancel(id, memberId, request), "결제가 취소되었습니다.");
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<PaymentResponse> refund(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody PaymentCancelRequest request
    ) {
        return ApiResponse.ok(paymentService.refund(id, memberId, request), "결제 환불이 완료되었습니다.");
    }
}
