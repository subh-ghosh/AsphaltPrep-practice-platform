package com.practice.aiplatform.user;

import com.practice.aiplatform.course.CourseRepository;
import com.practice.aiplatform.gamification.DailyChallengeRepository;
import com.practice.aiplatform.gamification.DailyXpHistoryRepository;
import com.practice.aiplatform.gamification.UserBadgeRepository;
import com.practice.aiplatform.notifications.NotificationRepository;
import com.practice.aiplatform.practice.AnswerRepository;
import com.practice.aiplatform.practice.QuestionRepository;
import com.practice.aiplatform.security.RefreshTokenService;
import com.practice.aiplatform.studyplan.StudyPlanRepository;
import lombok.RequiredArgsConstructor;
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
    private final CourseRepository courseRepository;
    private final StudyPlanRepository studyPlanRepository;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public void deleteAccountByEmail(String email) {
        Student student = studentRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        Long studentId = student.getId();

        notificationRepository.deleteByStudentId(studentId);
        refreshTokenService.deleteByUserId(studentId);
        dailyChallengeRepository.deleteByStudentId(studentId);
        dailyXpHistoryRepository.deleteByStudentId(studentId);
        answerRepository.deleteByStudentId(studentId);
        questionRepository.deleteByStudentId(studentId);
        userBadgeRepository.deleteByStudentId(studentId);

        var courses = courseRepository.findByStudentId(studentId);
        if (!courses.isEmpty()) {
            courseRepository.deleteAll(courses);
        }

        var plans = studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(studentId, org.springframework.data.domain.Pageable.unpaged());
        if (!plans.isEmpty()) {
            studyPlanRepository.deleteAll(plans);
        }

        studentRepository.delete(student);
    }
}
