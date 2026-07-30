package com.practice.aiplatform.gamification;

import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class XpService {

    private static final int XP_HISTORY_DAYS = 30;

    private final StudentRepository studentRepository;
    private final DailyXpHistoryRepository dailyXpHistoryRepository;

    public XpService(StudentRepository studentRepository, DailyXpHistoryRepository dailyXpHistoryRepository) {
        this.studentRepository = studentRepository;
        this.dailyXpHistoryRepository = dailyXpHistoryRepository;
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "LeaderboardCache", allEntries = true),
            @CacheEvict(value = "UserProfileCache", key = "#student.email"),
            @CacheEvict(value = "UserXpHistoryCache", key = "#student.id")
    })
    public void awardXp(Student student, int amount) {
        if (amount <= 0)
            return;

        // 1. Update Student Total XP
        student.setTotalXp(student.getTotalXp() + amount);
        studentRepository.save(student);

        // 2. Update Daily XP History (Thread-safe atomic increment with fallback)
        LocalDate today = LocalDate.now();
        int updated = dailyXpHistoryRepository.incrementXp(student.getId(), today, amount);
        if (updated == 0) {
            try {
                dailyXpHistoryRepository.save(new DailyXpHistory(student, today, amount));
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                dailyXpHistoryRepository.incrementXp(student.getId(), today, amount);
            }
        }
    }

    @Cacheable(value = "UserXpHistoryCache", key = "#studentId", sync = true)
    public List<DailyXpHistory> getXpHistory(Long studentId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(XP_HISTORY_DAYS);
        return dailyXpHistoryRepository.findByStudentIdAndDateBetweenOrderByDateAsc(studentId, startDate, endDate);
    }
}
