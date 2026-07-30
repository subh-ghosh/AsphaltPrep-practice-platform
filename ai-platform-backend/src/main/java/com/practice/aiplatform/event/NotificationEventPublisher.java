package com.practice.aiplatform.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topic.notification}")
    private String topicName;

    public void publishNotificationEvent(NotificationEvent event) {
        log.info("📢 Broadcasting Notification Event to Kafka for student ID: {}", event.getStudentId());
        kafkaTemplate.send(topicName, String.valueOf(event.getStudentId()), event);
    }
}
