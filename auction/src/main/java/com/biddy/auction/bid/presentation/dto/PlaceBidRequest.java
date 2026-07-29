package com.biddy.auction.bid.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 입찰 API 요청 DTO.
 *
 * @param amount 입찰 금액
 */
public record PlaceBidRequest(
        @NotNull(message = "입찰 금액은 필수입니다")
        @Positive(message = "입찰 금액은 0보다 커야 합니다")
        Long amount
) {
}
