package com.practice.aiplatform.user;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageService {

    private final StudentRepository studentRepository;

    // Define your free limit here
    private static final int FREE_ACTION_LIMIT = 20;

    public UsageService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    /**
 * Checks if a user can perform a metered action (like question generation or evaluation).
     * If the user is on a free plan and within their limit, this method will
     * also INCREMENT their usage count.
     *
     * @param userEmail The email of the user attempting the action.
     * @return true if the action is allowed, false if they have hit their paywall.
     */
    @Transactional
    @CacheEvict(value = "UserUsageRemainingCache", key = "#userEmail")
    public boolean canPerformAction(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found with email: " + userEmail));

        // 1. Check if user is an active paid subscriber
        if ("PREMIUM".equalsIgnoreCase(student.getSubscriptionStatus())) {
            if (student.getSubscriptionEndsAt() == null || !student.getSubscriptionEndsAt().isBefore(java.time.LocalDate.now())) {
                return true;
            }
        }

        // 2. User is on a "FREE" plan, check their usage
        if (student.getFreeActionsUsed() < FREE_ACTION_LIMIT) {
            // 3. User is within the limit. Increment their count and allow the action.
            student.setFreeActionsUsed(student.getFreeActionsUsed() + 1);
            studentRepository.save(student);
            return true;
        }

        // 4. User has reached their free limit
        return false;
    }

    /**
     * A simple check that does NOT increment the usage count.
     * Useful for the frontend to know if it should show an "upgrade" button.
     *
     * @param userEmail The email of the user.
     * @return true if the user can still perform actions, false otherwise.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "UserUsageRemainingCache", key = "#userEmail", sync = true)
    public boolean hasActionsRemaining(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found with email: " + userEmail));

        if ("PREMIUM".equalsIgnoreCase(student.getSubscriptionStatus())) {
            return true;
        }

        return student.getFreeActionsUsed() < FREE_ACTION_LIMIT;
    }
}
