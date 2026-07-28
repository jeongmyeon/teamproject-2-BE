package com.biddy.order.order.infra.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "kafka", name = "enabled", havingValue = "true")
public class OrderDlqEventListener {

    @KafkaListener(topics = {
            "auction.ended.DLT",
            "payment.completed.DLT",
            "payment.failed.DLT",
            "order.stock.deduct.failed.DLT"
    }, groupId = "order-dlq-service")
    public void handleDlqMessages(String payload) {
        log.error("==================================================");
        log.error("[DLQ ALERT] 🚨 복구 불가능한 카프카 메시지가 DLQ로 유입되었습니다! 🚨");
        log.error("Payload: {}", payload);
        log.error("Action: 메시지 형식이 잘못되었거나 DB 처리에 심각한 오류가 있습니다. 원인 파악이 필요합니다.");
        log.error("==================================================");
        
        // TODO: 향후 필요 시 에러 전용 DB 테이블에 저장하거나 슬랙(Slack)으로 알림을 보내는 로직을 여기에 추가합니다.
    }
}
