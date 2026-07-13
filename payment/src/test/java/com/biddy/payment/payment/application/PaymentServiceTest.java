package com.biddy.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.biddy.payment.payment.domain.PaymentMethod;
import com.biddy.payment.payment.domain.PaymentStatus;
import com.biddy.payment.payment.domain.event.OrderCancelledEvent;
import com.biddy.payment.payment.domain.event.PaymentCompletedEvent;
import com.biddy.payment.payment.domain.event.PaymentRefundedEvent;
import com.biddy.payment.payment.infrastructure.client.order.OrderClient;
import com.biddy.payment.payment.infrastructure.client.order.OrderPaymentInfo;
import com.biddy.payment.payment.infrastructure.client.order.OrderPaymentStatus;
import com.biddy.payment.payment.infrastructure.kafka.producer.PaymentEventProducer;
import com.biddy.payment.payment.presentation.request.PaymentCreateRequest;
import com.biddy.payment.payment.presentation.response.PaymentResponse;
import com.biddy.payment.wallet.application.DepositService;
import com.biddy.payment.wallet.infrastructure.client.toss.TossPaymentClient;
import com.biddy.payment.wallet.infrastructure.client.toss.TossPaymentConfirmResponse;
import com.biddy.payment.wallet.presentation.request.DepositAdjustRequest;
import com.biddy.payment.wallet.presentation.response.DepositBalanceResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DepositService depositService;

    @MockBean
    private OrderClient orderClient;

    @MockBean
    private PaymentEventProducer paymentEventProducer;

    @MockBean
    private TossPaymentClient tossPaymentClient;

    @MockBean
    private PaymentIdempotencyService paymentIdempotencyService;

    @BeforeEach
    void setUp() {
        when(paymentIdempotencyService.begin(anyLong(), anyString())).thenReturn(Optional.empty());
        when(paymentIdempotencyService.acquireOrderLock(anyLong())).thenReturn("order-lock-token");
    }

    @Test
    void walletPayment_decreasesDepositAndPublishesCompletedEvent() {
        Long orderId = 100L;
        Long buyerId = 1L;
        Long sellerId = 2L;
        Long amount = 50_000L;

        depositService.adjust(new DepositAdjustRequest(buyerId, 100_000L, "테스트 예치금 지급"));
        when(orderClient.getPaymentInfo(orderId)).thenReturn(new OrderPaymentInfo(
                orderId,
                buyerId,
                sellerId,
                amount,
                OrderPaymentStatus.PAYMENT_PENDING,
                LocalDateTime.now().plusMinutes(10)
        ));

        PaymentResponse response = paymentService.create(new PaymentCreateRequest(
                orderId,
                buyerId,
                amount,
                PaymentMethod.WALLET,
                null,
                null,
                null
        ), "USER", "wallet-payment-key-100");

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        DepositBalanceResponse balance = depositService.getBalance(buyerId);
        assertThat(balance.balance()).isEqualTo(50_000L);

        verify(orderClient).requestPaymentProcessing(eq(orderId), eq(buyerId), eq("USER"));

        ArgumentCaptor<PaymentCompletedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(paymentEventProducer).publish(eventCaptor.capture());
        PaymentCompletedEvent event = eventCaptor.getValue();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.paymentId()).isEqualTo(response.id());
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.buyerId()).isEqualTo(buyerId);
        assertThat(event.sellerId()).isEqualTo(sellerId);
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.WALLET);
        assertThat(event.paidAt()).isNotNull();
    }

    @Test
    void normalPayment_confirmsTossPaymentAndPublishesCompletedEvent() {
        Long orderId = 200L;
        Long buyerId = 3L;
        Long sellerId = 4L;
        Long amount = 70_000L;
        String paymentKey = "payment-key-200";
        String tossOrderId = "toss-order-200";

        when(orderClient.getPaymentInfo(orderId)).thenReturn(new OrderPaymentInfo(
                orderId,
                buyerId,
                sellerId,
                amount,
                OrderPaymentStatus.PAYMENT_PENDING,
                LocalDateTime.now().plusMinutes(10)
        ));
        when(tossPaymentClient.confirm(paymentKey, tossOrderId, amount))
                .thenReturn(new TossPaymentConfirmResponse(paymentKey, tossOrderId, "DONE", amount));

        PaymentResponse response = paymentService.create(new PaymentCreateRequest(
                orderId,
                buyerId,
                amount,
                PaymentMethod.NORMAL,
                null,
                paymentKey,
                tossOrderId
        ), "USER", "normal-payment-key-200");

        assertThat(response.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(response.pgTransactionId()).isEqualTo(paymentKey);

        verify(orderClient).requestPaymentProcessing(eq(orderId), eq(buyerId), eq("USER"));
        verify(tossPaymentClient).confirm(eq(paymentKey), eq(tossOrderId), eq(amount));

        ArgumentCaptor<PaymentCompletedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(paymentEventProducer).publish(eventCaptor.capture());
        PaymentCompletedEvent event = eventCaptor.getValue();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.paymentId()).isEqualTo(response.id());
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.buyerId()).isEqualTo(buyerId);
        assertThat(event.sellerId()).isEqualTo(sellerId);
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.paymentMethod()).isEqualTo(PaymentMethod.NORMAL);
        assertThat(event.paidAt()).isNotNull();
    }

    @Test
    void repeatedIdempotencyKey_returnsExistingPaymentWithoutProcessingAgain() {
        Long orderId = 2_500L;
        Long buyerId = 3_000L;
        Long sellerId = 4_000L;
        Long amount = 30_000L;
        String idempotencyKey = "same-payment-key-250";

        depositService.adjust(new DepositAdjustRequest(buyerId, 100_000L, "테스트 예치금 지급"));
        when(orderClient.getPaymentInfo(orderId)).thenReturn(new OrderPaymentInfo(
                orderId,
                buyerId,
                sellerId,
                amount,
                OrderPaymentStatus.PAYMENT_PENDING,
                LocalDateTime.now().plusMinutes(10)
        ));

        PaymentCreateRequest request = new PaymentCreateRequest(
                orderId,
                buyerId,
                amount,
                PaymentMethod.WALLET,
                null,
                null,
                null
        );
        PaymentResponse firstResponse = paymentService.create(request, "USER", idempotencyKey);

        Mockito.clearInvocations(orderClient, paymentEventProducer, paymentIdempotencyService);
        when(paymentIdempotencyService.begin(buyerId, idempotencyKey)).thenReturn(Optional.of(firstResponse.id()));

        PaymentResponse repeatedResponse = paymentService.create(request, "USER", idempotencyKey);

        assertThat(repeatedResponse.id()).isEqualTo(firstResponse.id());
        assertThat(repeatedResponse.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(depositService.getBalance(buyerId).balance()).isEqualTo(70_000L);

        verify(paymentIdempotencyService, never()).acquireOrderLock(anyLong());
        verify(orderClient, never()).getPaymentInfo(anyLong());
        verify(paymentEventProducer, never()).publish(Mockito.any(PaymentCompletedEvent.class));
    }

    @Test
    void orderCancelledEvent_refundsWalletPaymentAndPublishesRefundedEvent() {
        Long orderId = 300L;
        Long buyerId = 5L;
        Long sellerId = 6L;
        Long amount = 40_000L;

        depositService.adjust(new DepositAdjustRequest(buyerId, 100_000L, "테스트 예치금 지급"));
        when(orderClient.getPaymentInfo(orderId)).thenReturn(new OrderPaymentInfo(
                orderId,
                buyerId,
                sellerId,
                amount,
                OrderPaymentStatus.PAYMENT_PENDING,
                LocalDateTime.now().plusMinutes(10)
        ));

        PaymentResponse payment = paymentService.create(new PaymentCreateRequest(
                orderId,
                buyerId,
                amount,
                PaymentMethod.WALLET,
                null,
                null,
                null
        ), "USER", "wallet-payment-key-300");
        Mockito.clearInvocations(paymentEventProducer);

        paymentService.cancelByOrderCancellation(new OrderCancelledEvent(
                UUID.randomUUID(),
                orderId,
                buyerId,
                "주문 취소",
                LocalDateTime.now()
        ));

        PaymentResponse refunded = paymentService.get(payment.id(), buyerId);
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(depositService.getBalance(buyerId).balance()).isEqualTo(100_000L);

        ArgumentCaptor<PaymentRefundedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
        verify(paymentEventProducer).publish(eventCaptor.capture());
        PaymentRefundedEvent event = eventCaptor.getValue();

        assertThat(event.paymentId()).isEqualTo(payment.id());
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.buyerId()).isEqualTo(buyerId);
        assertThat(event.amount()).isEqualTo(amount);
        assertThat(event.reason()).isEqualTo("주문 취소");
    }

    @Test
    void orderCancelledEvent_cancelsTossPaymentForNormalPayment() {
        Long orderId = 400L;
        Long buyerId = 7L;
        Long sellerId = 8L;
        Long amount = 80_000L;
        String paymentKey = "payment-key-400";
        String tossOrderId = "toss-order-400";

        when(orderClient.getPaymentInfo(orderId)).thenReturn(new OrderPaymentInfo(
                orderId,
                buyerId,
                sellerId,
                amount,
                OrderPaymentStatus.PAYMENT_PENDING,
                LocalDateTime.now().plusMinutes(10)
        ));
        when(tossPaymentClient.confirm(paymentKey, tossOrderId, amount))
                .thenReturn(new TossPaymentConfirmResponse(paymentKey, tossOrderId, "DONE", amount));

        PaymentResponse payment = paymentService.create(new PaymentCreateRequest(
                orderId,
                buyerId,
                amount,
                PaymentMethod.NORMAL,
                null,
                paymentKey,
                tossOrderId
        ), "USER", "normal-payment-key-400");
        Mockito.clearInvocations(paymentEventProducer);

        paymentService.cancelByOrderCancellation(new OrderCancelledEvent(
                UUID.randomUUID(),
                orderId,
                buyerId,
                "주문 취소",
                LocalDateTime.now()
        ));

        PaymentResponse refunded = paymentService.get(payment.id(), buyerId);
        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);

        verify(tossPaymentClient).cancel(paymentKey, "주문 취소", amount);
    }
}
