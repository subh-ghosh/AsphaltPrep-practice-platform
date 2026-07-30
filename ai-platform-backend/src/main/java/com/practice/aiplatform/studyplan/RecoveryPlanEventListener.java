package com.practice.aiplatform.studyplan;

import com.practice.aiplatform.event.RecoveryPlanEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryPlanEventListener {

    private final StudyPlanService studyPlanService;

    @KafkaListener(topics = "${kafka.topic.recoveryplan}", groupId = "${kafka.group.recoveryplan}", concurrency = "1")
    public void consumeRecoveryPlanEvent(RecoveryPlanEvent event) {
        log.info("🎧 Received Kafka Event: Starting heavy background plan generations for {} (Plan ID: {})",
                event.getUserEmail(), event.getPlanId() != null ? event.getPlanId() : "NEW");
        try {
            if (event.getPlanId() != null) {
                // Main Study Plan Generation (Async)
                studyPlanService.completeAsyncStudyPlan(
                        event.getPlanId(),
                        event.getUserEmail(),
                        event.getTopic(),
                        event.getDifficulty(),
                        event.getDays());
            } else {
                // Automatic Recovery Plan (Synchronous fallback within listener)
                studyPlanService.generateStudyPlan(
                        event.getUserEmail(),
                        event.getTopic() + " Recovery",
                        event.getDifficulty(),
                        event.getDays());
            }
            log.info("✅ Asynchronously finished study plan generation for {} via Kafka.", event.getUserEmail());
        } catch (Exception e) {
            log.error("❌ Failed to process Kafka recovery plan event for {}: {}", event.getUserEmail(), e.getMessage());
        }
    }
}
