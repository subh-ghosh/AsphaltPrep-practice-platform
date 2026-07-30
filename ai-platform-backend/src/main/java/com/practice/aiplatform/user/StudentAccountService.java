package com.practice.aiplatform.user;

import com.practice.aiplatform.course.CourseRepository;
import com.practice.aiplatform.gamification.DailyChallengeRepository;
import com.practice.aiplatform.gamification.DailyXpHistoryRepository;
import com.practice.aiplatform.gamification.UserBadgeRepository;
import com.practice.aiplatform.notifications.NotificationRepository;
import com.practice.aiplatform.practice.AnswerRepository;
import com.practice.aiplatform.practice.QuestionRepository;
import com.practice.aiplatform.security.RefreshTokenService;
import com.practice.aiplatform.studyplan.QuizQuestionRepository;
import com.practice.aiplatform.studyplan.StudyPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StudentAccountService {

    private final StudentRepository studentRepository;
    private final NotificationRepository notificationRepository;
    private final DailyChallengeRepository dailyChallengeRepository;
    private final DailyXpHistoryRepository dailyXpHistoryRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final AnswerRepository answerRepository;
    private final QuestionRepository questionRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final CourseRepository courseRepository;
    private final StudyPlanRepository studyPlanRepository;
    private final RefreshTokenService refreshTokenService;
    private final CacheManager cacheManager;

    @Transactional
    public void deleteAccountByEmail(String email) {
        Student student = studentRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        Long studentId = student.getId();

        // 1. Delete notifications & refresh tokens
        notificationRepository.deleteByStudentId(studentId);
        refreshTokenService.deleteByUserId(studentId);

        // 2. Delete gamification records
        dailyChallengeRepository.deleteByStudentId(studentId);
        dailyXpHistoryRepository.deleteByStudentId(studentId);
        userBadgeRepository.deleteByStudentId(studentId);

        // 3. Delete answers linked to student or questions
        answerRepository.deleteByStudentId(studentId);
        answerRepository.deleteByQuestionStudentId(studentId);

        // 4. Delete practice questions
        questionRepository.deleteByStudentId(studentId);

        // 5. Delete quiz questions linked to study plan items
        quizQuestionRepository.deleteByStudentId(studentId);

        // 6. Delete generated courses & modules
        var courses = courseRepository.findByStudentId(studentId);
        if (!courses.isEmpty()) {
            courseRepository.deleteAll(courses);
        }

        // 7. Delete study plans & items
        var plans = studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(studentId, org.springframework.data.domain.Pageable.unpaged());
        if (!plans.isEmpty()) {
            studyPlanRepository.deleteAll(plans);
        }

        // 8. Delete student record
        studentRepository.delete(student);

        // 9. Evict all cached entries across all Spring Cache stores for this user
        evictUserCaches(email);
    }

    private void evictUserCaches(String email) {
        String[] userCaches = {
                "UserStudentIdCache",
                "UserProfileCache",
                "UserUsageRemainingCache",
                "UserStudyPlansCache",
                "UserStudyPlanSummariesCache",
                "UserStudyPlanStatsCache",
                "UserSuggestedPracticeCache",
                "UserActiveContextCache",
                "UserRecommendationsCache",
                "UserStatisticsRecommendationsCache"
        };

        for (String cacheName : userCaches) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(email);
                cache.evict(email.toLowerCase());
            }
        }

        String[] wildcardCaches = {
                "StudyPlanByIdCache",
                "StudyPlanQuizQuestionsCache",
                "LeaderboardCache"
        };

        for (String cacheName : wildcardCaches) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
