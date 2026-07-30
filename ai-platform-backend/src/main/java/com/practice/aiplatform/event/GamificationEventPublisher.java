package com.practice.aiplatform.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.gamification}")
    private String topicName;

    public void publishPracticeCompletedEvent(PracticeCompletedEvent event) {
        log.info("📢 Broadcasting Practice Completed Event to Kafka for user: {}", event.getUserEmail());
        kafkaTemplate.send(topicName, event.getUserEmail(), event);
    }
}
