package com.practice.aiplatform.notifications;

import com.practice.aiplatform.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = "${kafka.topic.notification}", groupId = "${kafka.group.notification}")
    public void consumeNotificationEvent(NotificationEvent event) {
        log.info("🎧 Received Kafka Notification Event: Generating '{}' alert for student ID {}", event.getType(),
                event.getStudentId());
        try {
            notificationService.createNotification(event.getStudentId(), event.getType(), event.getMessage());
            log.info("✅ Asynchronously saved notification via Kafka.");
        } catch (Exception e) {
            log.error("❌ Failed to process Kafka notification event: {}", e.getMessage());
        }
    }
}
