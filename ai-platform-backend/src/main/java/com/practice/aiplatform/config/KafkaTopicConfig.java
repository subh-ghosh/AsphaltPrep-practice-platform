package com.practice.aiplatform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // Set replicas: 1 for StreamBase / single-broker environments, 3 if using multi-broker HA
    private static final short REPLICAS = System.getenv("SPRING_KAFKA_REPLICAS") != null
            ? Short.parseShort(System.getenv("SPRING_KAFKA_REPLICAS"))
            : (short) 1;

    @Bean
    public NewTopic gamificationTopic() {
        return TopicBuilder.name("gamification.events")
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic notificationTopic() {
        return TopicBuilder.name("notification.events")
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic recoveryPlanTopic() {
        return TopicBuilder.name("recoveryplan.events")
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }
}
