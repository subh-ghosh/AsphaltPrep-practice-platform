package com.practice.aiplatform.gamification;

import com.practice.aiplatform.event.PracticeCompletedEvent;
import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationEventListener {

    private final XpService xpService;
    private final StudentRepository studentRepository;

    /**
     * This method listens to the Confluent Cloud Kafka 'gamification.events' topic.
     * Whenever the PracticeController broadcasts a user finishing a quiz,
     * this method automatically wakes up, pulls the message, and processes XP in
     * the background!
     */
    @KafkaListener(topics = "${kafka.topic.gamification}", groupId = "${kafka.group.gamification}")
    public void consumePracticeCompletedEvent(PracticeCompletedEvent event) {
        log.info("🎧 Received Kafka Event: User {} finished a quiz for {} points!", event.getUserEmail(),
                event.getScoreEarned());

        if (event.getScoreEarned() <= 0) {
            log.info("No points earned for this event, ignoring gamification processing.");
            return;
        }

        try {
            // Find the student in the database
            Student student = studentRepository.findByEmail(event.getUserEmail())
                    .orElseThrow(() -> new RuntimeException("Student not found during Kafka event processing"));

            // Calculate & award XP asynchronously using the already-built Gamification
            // logic
            xpService.awardXp(student, event.getScoreEarned());

            log.info("✅ Asynchronously awarded {} XP to {} via Kafka.", event.getScoreEarned(), student.getEmail());

        } catch (Exception e) {
            log.error("❌ Failed to process Kafka event for user {}: {}", event.getUserEmail(), e.getMessage());
            // Since this is asynchronous, a failure here won't crash the user's UI request!
            // They already got their score.
        }
    }
}
