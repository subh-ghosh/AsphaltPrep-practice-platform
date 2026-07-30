package com.practice.aiplatform.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryPlanEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.recoveryplan}")
    private String topicName;

    public void publishRecoveryPlanEvent(RecoveryPlanEvent event) {
        log.info("📢 Broadcasting Recovery Plan Event to Kafka (Plan ID: {}) for user: {}",
                event.getPlanId() != null ? event.getPlanId() : "NEW", event.getUserEmail());
        kafkaTemplate.send(topicName, event.getUserEmail(), event);
    }
}
