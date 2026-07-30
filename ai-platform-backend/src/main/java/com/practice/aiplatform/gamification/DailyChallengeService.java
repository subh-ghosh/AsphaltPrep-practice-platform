package com.practice.aiplatform.gamification;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import com.practice.aiplatform.studyplan.StudyPlan;
import com.practice.aiplatform.studyplan.StudyPlanRepository;
import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@Service
public class DailyChallengeService {

    private final DailyChallengeRepository dailyChallengeRepository;
    private final StudentRepository studentRepository;
    private final StudyPlanRepository studyPlanRepository;
    private final XpService xpService;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final Cache<Long, List<DailyChallenge>> localChallengesCache;
    @Lazy
    @Autowired
    private DailyChallengeService self;

    public DailyChallengeService(
            DailyChallengeRepository dailyChallengeRepository,
            StudentRepository studentRepository,
            StudyPlanRepository studyPlanRepository,
            XpService xpService,
            CacheManager cacheManager,
            MeterRegistry meterRegistry) {
        this.dailyChallengeRepository = dailyChallengeRepository;
        this.studentRepository = studentRepository;
        this.studyPlanRepository = studyPlanRepository;
        this.xpService = xpService;
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
        this.localChallengesCache = Caffeine.newBuilder()
                .recordStats()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(2000)
                .build();
    }

    public List<DailyChallenge> getTodayChallenges(Long studentId) {
        List<DailyChallenge> cached = localChallengesCache.getIfPresent(studentId);
        if (cached != null) {
            recordL1("UserDailyChallengesLocalCache", "hit");
            return cached;
        }
        recordL1("UserDailyChallengesLocalCache", "miss");

        List<DailyChallenge> value = self.getTodayChallengesCached(studentId);
        localChallengesCache.put(studentId, value);
        return value;
    }

    @Cacheable(value = "UserDailyChallengesCache", key = "#studentId", sync = true)
    public List<DailyChallenge> getTodayChallengesCached(Long studentId) {
        LocalDate today = LocalDate.now();
        List<DailyChallenge> challenges = dailyChallengeRepository.findByStudentIdAndDate(studentId, today);

        if (!challenges.isEmpty()) {
            return challenges;
        }

        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        return generateDailyChallenges(student);
    }

    @Transactional
    public List<DailyChallenge> generateDailyChallenges(Student student) {
        createChallenge(student, "Quiz Master", "Complete 1 quiz with >80% score", 50, 1);
        createChallenge(student, "Fast Learner", "Complete 3 practice questions", 30, 3);
        createPlanBasedChallenge(student);

        return dailyChallengeRepository.findByStudentIdAndDate(student.getId(), LocalDate.now());
    }

    private void createPlanBasedChallenge(Student student) {
        List<StudyPlan> plans = studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(),
                PageRequest.of(0, 1));

        if (!plans.isEmpty() && !plans.get(0).isCompleted()) {
            StudyPlan activePlan = plans.get(0);
            createChallenge(
                    student,
                    "Focus: " + activePlan.getTopic(),
                    "Complete 2 items in '" + activePlan.getTitle() + "'",
                    60,
                    2);
        } else {
            createChallenge(student, "Dedication", "Open and review your study plan", 20, 1);
        }
    }

    private void createChallenge(Student student, String title, String description, int xpReward, int targetAmount) {
        DailyChallenge challenge = new DailyChallenge(student, title, description, xpReward, targetAmount);
        dailyChallengeRepository.save(challenge);
    }

    @Transactional
    public void incrementProgress(Long studentId, String challengeTitle, int amount) {
        List<DailyChallenge> challenges = dailyChallengeRepository.findByStudentIdAndDate(studentId, LocalDate.now());

        for (DailyChallenge challenge : challenges) {
            if (!challenge.isClaimed() && challenge.getTitle().equalsIgnoreCase(challengeTitle)) {
                int updated = Math.min(challenge.getCurrentAmount() + amount, challenge.getTargetAmount());
                challenge.setCurrentAmount(updated);
                dailyChallengeRepository.save(challenge);
            }
        }

        evictDailyChallengeCache(studentId);
    }

    @Transactional
    public void claimReward(Long challengeId, Long studentId) {
        DailyChallenge challenge = dailyChallengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found"));

        if (!challenge.getStudent().getId().equals(studentId)) {
            throw new RuntimeException("Unauthorized: This challenge belongs to another student");
        }

        if (challenge.isClaimed()) {
            throw new RuntimeException("Reward already claimed");
        }

        if (challenge.getCurrentAmount() < challenge.getTargetAmount()) {
            throw new RuntimeException("Challenge not completed yet");
        }

        challenge.setClaimed(true);
        dailyChallengeRepository.save(challenge);

        Student student = challenge.getStudent();
        xpService.awardXp(student, challenge.getXpReward());
        evictDailyChallengeCache(student.getId());
    }

    private void evictDailyChallengeCache(Long studentId) {
        localChallengesCache.invalidate(studentId);
        org.springframework.cache.Cache cache = cacheManager.getCache("UserDailyChallengesCache");
        if (cache != null) {
            cache.evict(studentId);
        }
    }

    private void recordL1(String cacheName, String result) {
        meterRegistry.counter(
                "cache_layer_access_total",
                "cache", cacheName,
                "layer", "l1",
                "result", result).increment();
    }
}
