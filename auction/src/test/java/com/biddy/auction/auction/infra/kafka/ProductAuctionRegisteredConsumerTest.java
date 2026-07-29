package com.biddy.auction.auction.infra.kafka;

import com.biddy.auction.auction.application.service.AuctionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductAuctionRegisteredConsumerTest {

    @Mock
    private AuctionService auctionService;

    @Test
    void consumesProductEventWithDecimalPrice() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProductAuctionRegisteredConsumer consumer =
                new ProductAuctionRegisteredConsumer(auctionService, objectMapper);
        String message = """
                {
                  "productId": 10,
                  "sellerId": 20,
                  "startPrice": 5000.00,
                  "minIncrement": 500,
                  "startsAt": "2026-07-30T10:00:00",
                  "endsAt": "2026-07-31T10:00:00"
                }
                """;
        ArgumentCaptor<ProductAuctionRegisteredPayload> captor =
                ArgumentCaptor.forClass(ProductAuctionRegisteredPayload.class);

        consumer.consume(message);

        verify(auctionService).createFromProduct(captor.capture());
        assertThat(captor.getValue().startPrice()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(captor.getValue().minIncrement()).isEqualTo(500);
    }

    @Test
    void rejectsMalformedEventInsteadOfAcknowledgingIt() {
        ProductAuctionRegisteredConsumer consumer =
                new ProductAuctionRegisteredConsumer(auctionService, new ObjectMapper());

        assertThatThrownBy(() -> consumer.consume("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("잘못된 경매 상품 등록 이벤트입니다.");
    }
}
