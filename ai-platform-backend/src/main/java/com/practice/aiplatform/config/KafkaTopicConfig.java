package com.practice.aiplatform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${kafka.topic.gamification}")
    private String gamificationTopicName;

    @Value("${kafka.topic.notification}")
    private String notificationTopicName;

    @Value("${kafka.topic.recoveryplan}")
    private String recoveryPlanTopicName;

    // Set replicas: 1 for StreamBase / single-broker environments, 3 if using multi-broker HA
    private static final short REPLICAS = System.getenv("SPRING_KAFKA_REPLICAS") != null
            ? Short.parseShort(System.getenv("SPRING_KAFKA_REPLICAS"))
            : (short) 1;

    @Bean
    public NewTopic gamificationTopic() {
        return TopicBuilder.name(gamificationTopicName)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic notificationTopic() {
        return TopicBuilder.name(notificationTopicName)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic recoveryPlanTopic() {
        return TopicBuilder.name(recoveryPlanTopicName)
                .partitions(1)
                .replicas(REPLICAS)
                .build();
    }
}
