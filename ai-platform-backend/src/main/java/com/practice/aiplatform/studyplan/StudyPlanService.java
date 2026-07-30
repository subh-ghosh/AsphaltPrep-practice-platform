package com.practice.aiplatform.studyplan;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.aiplatform.ai.AiService;
import com.practice.aiplatform.event.GamificationEventPublisher;
import com.practice.aiplatform.event.PracticeCompletedEvent;
import com.practice.aiplatform.event.RecoveryPlanEvent;
import com.practice.aiplatform.event.RecoveryPlanEventPublisher;
import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class StudyPlanService {

    private static final int VIDEO_XP = 10;
    private static final int PRACTICE_XP = 50;
    private static final int QUESTIONS_PER_PRACTICE = 5;

    private final AiService aiService;
    private final YouTubeService youTubeService;
    private final StudyPlanRepository studyPlanRepository;
    private final StudyPlanItemRepository studyPlanItemRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final StudentRepository studentRepository;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final RecoveryPlanEventPublisher recoveryPlanEventPublisher;
    private final GamificationEventPublisher eventPublisher;
    @Lazy
    @Autowired
    private StudyPlanService self;

    public StudyPlanService(
            AiService aiService,
            YouTubeService youTubeService,
            StudyPlanRepository studyPlanRepository,
            StudyPlanItemRepository studyPlanItemRepository,
            QuizQuestionRepository quizQuestionRepository,
            StudentRepository studentRepository,
            ObjectMapper objectMapper,
            CacheManager cacheManager,
            MeterRegistry meterRegistry,
            RecoveryPlanEventPublisher recoveryPlanEventPublisher,
            GamificationEventPublisher eventPublisher) {
        this.aiService = aiService;
        this.youTubeService = youTubeService;
        this.studyPlanRepository = studyPlanRepository;
        this.studyPlanItemRepository = studyPlanItemRepository;
        this.quizQuestionRepository = quizQuestionRepository;
        this.studentRepository = studentRepository;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
        this.recoveryPlanEventPublisher = recoveryPlanEventPublisher;
        this.eventPublisher = eventPublisher;
    }

    public StudyPlan generateStudyPlan(String userEmail, String topic, String difficulty, int durationDays) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        try {
            enforceDailyLimitForFreeUsers(student);

            int maxVideos = Math.min(durationDays * 3, 25);
            List<Map<String, String>> videos = youTubeService.searchVideos(topic + " " + difficulty,
                    maxVideos);

            if (videos.isEmpty()) {
                throw new RuntimeException("No videos found for the topic: " + topic);
            }

            String prompt = createPrompt(topic, difficulty, durationDays, videos);
            String aiResponse = aiService.generateStudyPlanContent(prompt);

            StudyPlan plan = parseAndSavePlan(aiResponse, student, topic, difficulty, durationDays, videos);
            generateQuizQuestionsForPlan(plan, topic, difficulty);
            studyPlanRepository.save(plan); // 👈 Save after attaching questions

            return plan;
        } catch (RuntimeException ex) {
            status = "error";
            throw ex;
        } finally {
            meterRegistry.counter("study_plan.generate.count", "status", status).increment();
            sample.stop(meterRegistry.timer("study_plan.generate.duration", "status", status));
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail")
    })
    public StudyPlan initiateAsyncStudyPlan(String userEmail, String topic, String difficulty, int durationDays) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        enforceDailyLimitForFreeUsers(student);

        StudyPlan shell = new StudyPlan();
        shell.setTopic(topic);
        shell.setDifficulty(difficulty);
        shell.setDurationDays(durationDays);
        shell.setStudent(student);
        shell.setGenerating(true);
        shell.setTitle("Generating: " + topic + "...");
        shell.setDescription(
                "We are currently curating your personalized study plan. This usually takes 30-60 seconds.");
        shell.setCreatedAt(LocalDateTime.now());

        StudyPlan saved = studyPlanRepository.save(shell);

        // Notify Kafka to handle the heavy lifting
        recoveryPlanEventPublisher.publishRecoveryPlanEvent(RecoveryPlanEvent.builder()
                .userEmail(userEmail)
                .topic(topic)
                .difficulty(difficulty)
                .days(durationDays)
                .planId(saved.getId())
                .build());

        return saved;
    }

    // --- REFACTOR: Moved generation calls outside of @Transactional to prevent DB
    // connection starvation ---
    public void completeAsyncStudyPlan(Long planId, String userEmail, String topic, String difficulty,
            int durationDays) {
        try {
            int maxVideos = Math.min(durationDays * 3, 25);
            List<Map<String, String>> videos = youTubeService.searchVideos(topic + " " + difficulty,
                    maxVideos);

            if (videos.isEmpty()) {
                throw new RuntimeException("No videos found for the topic: " + topic);
            }

            String prompt = createPrompt(topic, difficulty, durationDays, videos);
            String aiResponse = aiService.generateStudyPlanContent(prompt);

            // Persist the results in a short-lived transaction
            self.saveAndFinalizePlan(planId, aiResponse, topic, difficulty, durationDays, videos);

        } catch (Exception e) {
            log.error("Failed async study plan for {}: {}", userEmail, e.getMessage());
            self.markPlanAsFailed(planId, e.getMessage());
        } finally {
            // Evict caches after completion (success or failure)
            evictAllUserCaches(userEmail);
            evictOwnedStudyPlanByIdCache(userEmail, planId);
        }
    }

    @Transactional
    public void saveAndFinalizePlan(Long planId, String aiResponse, String topic, String difficulty,
            int durationDays, List<Map<String, String>> videos) {
        StudyPlan plan = studyPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Study Plan shell not found"));

        updateExistingPlan(plan, aiResponse, topic, difficulty, durationDays, videos);
        generateQuizQuestionsForPlan(plan, topic, difficulty);

        plan.setGenerating(false);
        studyPlanRepository.save(plan);
    }

    @Transactional
    public void markPlanAsFailed(Long planId, String errorMessage) {
        studyPlanRepository.findById(planId).ifPresent(plan -> {
            plan.setGenerating(false);
            plan.setTitle("Generation Failed");
            plan.setDescription("We encountered an error while generating this plan: " + errorMessage);
            studyPlanRepository.save(plan);
        });
    }

    private void updateExistingPlan(StudyPlan plan, String aiResponse, String topic, String difficulty,
            int durationDays, List<Map<String, String>> videos) {
        try {
            String cleanJson = aiResponse.replace("```json", "").replace("```", "").trim();
            JsonNode root = objectMapper.readTree(cleanJson);

            Map<String, Map<String, String>> videoMap = new HashMap<>();
            for (Map<String, String> video : videos) {
                videoMap.put(video.get("videoId"), video);
            }

            plan.setTitle(root.path("title").asText("Study Plan: " + topic));
            plan.setDescription(root.path("description").asText(""));
            plan.getItems().clear(); // 👈 Prevent duplicates if this is a retry

            int globalOrder = 1;
            JsonNode days = root.path("days");

            if (days.isArray()) {
                for (JsonNode day : days) {
                    int dayNumber = day.path("dayNumber").asInt(1);
                    JsonNode dayItems = day.path("items");

                    if (!dayItems.isArray())
                        continue;

                    for (JsonNode itemNode : dayItems) {
                        StudyPlanItem item = new StudyPlanItem();
                        String type = itemNode.path("type").asText("VIDEO");

                        item.setItemType(type);
                        item.setDayNumber(dayNumber);
                        item.setOrderIndex(globalOrder++);
                        item.setDescription(itemNode.path("description").asText(""));

                        if ("VIDEO".equals(type)) {
                            String videoId = itemNode.path("videoId").asText("");
                            Map<String, String> videoData = videoMap.get(videoId);
                            if (videoData == null)
                                continue;

                            item.setVideoId(videoId);
                            item.setTitle(videoData.get("title"));
                            item.setVideoUrl("https://www.youtube.com/watch?v=" + videoId);
                            item.setThumbnailUrl(videoData.get("thumbnailUrl"));
                            item.setChannelName(videoData.get("channelTitle"));
                            item.setVideoDuration(videoData.get("duration"));
                            item.setPracticeTopic(itemNode.path("practiceTopic").asText(topic));
                            item.setPracticeSubject(topic);
                            item.setPracticeDifficulty(difficulty);
                            item.setXpReward(VIDEO_XP);
                        } else if ("PRACTICE".equals(type)) {
                            item.setTitle("Checkpoint: " + itemNode.path("practiceTopic").asText(topic));
                            item.setPracticeSubject(itemNode.path("practiceSubject").asText(topic));
                            item.setPracticeTopic(itemNode.path("practiceTopic").asText(topic));
                            item.setPracticeDifficulty(itemNode.path("practiceDifficulty").asText(difficulty));
                            item.setXpReward(PRACTICE_XP);
                        }
                        plan.addItem(item);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update study plan: " + e.getMessage(), e);
        }
    }

    private void evictAllUserCaches(String userEmail) {
        Cache plansCache = cacheManager.getCache("UserStudyPlansCache");
        Cache summariesCache = cacheManager.getCache("UserStudyPlanSummariesCache");
        Cache statsCache = cacheManager.getCache("UserStudyPlanStatsCache");
        Cache activeContextCache = cacheManager.getCache("UserActiveContextCache");

        if (plansCache != null)
            plansCache.evict(userEmail);
        if (summariesCache != null)
            summariesCache.evict(userEmail);
        if (statsCache != null)
            statsCache.evict(userEmail);
        if (activeContextCache != null)
            activeContextCache.evict(userEmail);
    }

    private void enforceDailyLimitForFreeUsers(Student student) {
        if (!"FREE".equals(student.getSubscriptionStatus())) {
            return;
        }

        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        long count = studyPlanRepository.countByStudentIdAndCreatedAtAfter(student.getId(), startOfDay);

        if (count >= 1) {
            throw new RuntimeException(
                    "Free tier users can only generate one study plan per day. Please upgrade for unlimited plans or try again tomorrow.");
        }
    }

    private void generateQuizQuestionsForPlan(StudyPlan plan, String topic, String difficulty) {
        List<StudyPlanItem> items = plan.getItems();

        for (StudyPlanItem item : items) {
            if (!"PRACTICE".equals(item.getItemType())) {
                continue;
            }

            try {
                String subject = item.getPracticeSubject() != null ? item.getPracticeSubject() : topic;
                String practiceTopic = item.getPracticeTopic() != null ? item.getPracticeTopic() : topic;
                String level = item.getPracticeDifficulty() != null ? item.getPracticeDifficulty() : difficulty;

                String quizPrompt = createQuizPrompt(subject, practiceTopic, level);
                String quizResponse = aiService.generatePracticeContent(quizPrompt);

                List<QuizQuestion> questions = parseQuizQuestions(quizResponse, item);
                if (!questions.isEmpty()) {
                    item.getQuizQuestions().addAll(questions); // 👈 Let JPA cascade save
                }
            } catch (Exception e) {
                System.err.println("Failed to generate quiz for item " + item.getId() + ": " + e.getMessage());
            }
        }
    }

    private String createQuizPrompt(String subject, String practiceTopic, String difficulty) {
        return String.format(
                """
                        Generate exactly %d multiple choice questions for a %s level student on the subject of %s, specifically on the topic: %s.

                        Return ONLY valid JSON with this exact structure:
                        {
                          "questions": [
                            {
                              "question": "The question text",
                              "optionA": "First option",
                              "optionB": "Second option",
                              "optionC": "Third option",
                              "optionD": "Fourth option",
                              "correctOption": "A"
                            }
                          ]
                        }

                        Rules:
                        - Generate exactly %d questions
                        - Each question must have exactly 4 options (A, B, C, D)
                        - correctOption must be one of: "A", "B", "C", "D"
                        - Questions should test understanding
                        - Do not include Markdown formatting
                        """,
                QUESTIONS_PER_PRACTICE, difficulty, subject, practiceTopic, QUESTIONS_PER_PRACTICE);
    }

    private List<QuizQuestion> parseQuizQuestions(String jsonResponse, StudyPlanItem item) {
        List<QuizQuestion> questions = new ArrayList<>();

        try {
            String cleanJson = jsonResponse.replace("```json", "").replace("```", "").trim();
            JsonNode root = objectMapper.readTree(cleanJson);
            JsonNode questionsNode = root.path("questions");

            if (!questionsNode.isArray()) {
                return questions;
            }

            for (JsonNode qNode : questionsNode) {
                QuizQuestion q = new QuizQuestion();
                q.setQuestionText(qNode.path("question").asText(""));
                q.setOptionA(qNode.path("optionA").asText(""));
                q.setOptionB(qNode.path("optionB").asText(""));
                q.setOptionC(qNode.path("optionC").asText(""));
                q.setOptionD(qNode.path("optionD").asText(""));
                q.setCorrectOption(qNode.path("correctOption").asText("A").toUpperCase());
                q.setStudyPlanItem(item);
                questions.add(q);
            }
        } catch (Exception e) {
            System.err.println("Quiz parsing error: " + e.getMessage());
        }

        return questions;
    }

    public record QuizResult(
            int totalQuestions,
            int correctCount,
            int xpEarned,
            boolean passed,
            List<QuestionResult> results) {
    }

    public record QuestionResult(Long questionId, String correctOption, boolean isCorrect) {
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "StudyPlanByIdCache", key = "#userEmail + '-' + #planId"),
            @CacheEvict(value = "StudyPlanQuizQuestionsCache", key = "#userEmail + '-' + #planId + '-' + #itemId"),
            @CacheEvict(value = "LeaderboardCache", allEntries = true)
    })
    public QuizResult submitQuizAnswers(Long planId, Long itemId, Map<Long, String> answers, String userEmail) {
        StudyPlan plan = getOwnedStudyPlan(planId, userEmail);

        StudyPlanItem item = null;
        for (StudyPlanItem it : plan.getItems()) {
            if (it.getId().equals(itemId)) {
                item = it;
                break;
            }
        }

        if (item == null) {
            throw new RuntimeException("Item not found in this plan");
        }

        if (!"PRACTICE".equals(item.getItemType())) {
            throw new RuntimeException("This item is not a practice checkpoint");
        }

        List<QuizQuestion> questions = quizQuestionRepository.findByStudyPlanItemId(itemId);
        List<QuestionResult> results = new ArrayList<>();

        int correctCount = 0;
        for (QuizQuestion q : questions) {
            String submitted = answers.getOrDefault(q.getId(), "").toUpperCase();
            boolean isCorrect = q.getCorrectOption().equalsIgnoreCase(submitted);
            if (isCorrect) {
                correctCount++;
            }
            results.add(new QuestionResult(q.getId(), q.getCorrectOption(), isCorrect));
        }

        int xpEarned = 0;
        double score = questions.isEmpty() ? 0.0 : (double) correctCount / questions.size();
        boolean passed = score >= 0.8;

        if (passed && !item.isCompleted()) {
            item.setCompleted(true);
            xpEarned = PRACTICE_XP;

            Student student = plan.getStudent();
            student.setTotalXp(student.getTotalXp() + xpEarned);
            studentRepository.save(student);

            recalculateProgress(plan);
            studyPlanRepository.save(plan);

            PracticeCompletedEvent event = PracticeCompletedEvent.builder()
                    .userEmail(userEmail)
                    .practiceSessionId(itemId)
                    .scoreEarned(xpEarned)
                    .totalQuestions(questions.size())
                    .subject(item.getPracticeSubject() != null ? item.getPracticeSubject() : plan.getTopic())
                    .completedAt(LocalDateTime.now())
                    .build();
            eventPublisher.publishPracticeCompletedEvent(event);
        }

        return new QuizResult(questions.size(), correctCount, xpEarned, passed, results);
    }

    @Cacheable(value = "StudyPlanQuizQuestionsCache", key = "#userEmail + '-' + #planId + '-' + #itemId", sync = true)
    public List<QuizQuestion> getQuizQuestions(Long planId, Long itemId, String userEmail) {
        StudyPlan plan = getOwnedStudyPlan(planId, userEmail);

        boolean belongsToPlan = false;
        for (StudyPlanItem item : plan.getItems()) {
            if (item.getId().equals(itemId)) {
                belongsToPlan = true;
                break;
            }
        }

        if (!belongsToPlan) {
            throw new RuntimeException("Item not found in this plan");
        }

        return quizQuestionRepository.findByStudyPlanItemId(itemId);
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "StudyPlanByIdCache", key = "#userEmail + '-' + #planId"),
            @CacheEvict(value = "LeaderboardCache", allEntries = true)
    })
    public StudyPlanItem markItemComplete(Long planId, Long itemId, String userEmail) {
        StudyPlan plan = getOwnedStudyPlan(planId, userEmail);

        StudyPlanItem item = null;
        for (StudyPlanItem it : plan.getItems()) {
            if (it.getId().equals(itemId)) {
                item = it;
                break;
            }
        }

        if (item == null) {
            throw new RuntimeException("Item not found in this plan");
        }

        if ("PRACTICE".equals(item.getItemType())) {
            List<QuizQuestion> questions = quizQuestionRepository.findByStudyPlanItemId(itemId);
            if (!questions.isEmpty()) {
                throw new RuntimeException("Practice items with quizzes must be completed through the quiz");
            }
        }

        if (!item.isCompleted()) {
            item.setCompleted(true);

            int xp = item.getXpReward() > 0 ? item.getXpReward() : VIDEO_XP;
            Student student = plan.getStudent();
            student.setTotalXp(student.getTotalXp() + xp);
            studentRepository.save(student);

            recalculateProgress(plan);
            studyPlanRepository.save(plan);
        }

        return item;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    public record StudyPlanStats(int activePlans, int completedPlans, int totalXp, int totalItemsCompleted) {
    }

    @Cacheable(value = "UserStudyPlanStatsCache", key = "#userEmail", sync = true)
    public StudyPlanStats getStats(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        List<StudyPlan> plans = studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(),
                PageRequest.of(0, 50));

        int active = 0;
        int completed = 0;
        int itemsCompleted = 0;

        for (StudyPlan plan : plans) {
            if (plan.isCompleted()) {
                completed++;
            } else {
                active++;
            }

            for (StudyPlanItem item : plan.getItems()) {
                if (item.isCompleted()) {
                    itemsCompleted++;
                }
            }
        }

        return new StudyPlanStats(active, completed, student.getTotalXp(), itemsCompleted);
    }

    private void recalculateProgress(StudyPlan plan) {
        int totalItems = plan.getItems().size();
        int completedItems = 0;

        for (StudyPlanItem item : plan.getItems()) {
            if (item.isCompleted()) {
                completedItems++;
            }
        }

        int progress = 0;
        if (totalItems > 0) {
            progress = (completedItems * 100) / totalItems;
        }

        plan.setProgress(progress);
        plan.setCompleted(progress == 100);
    }

    public List<StudyPlan> getStudyPlans(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        return studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(), PageRequest.of(0, 50));
    }

    @Cacheable(value = "UserStudyPlanSummariesCache", key = "#userEmail", sync = true)
    public List<StudyPlanSummaryDto> getStudyPlanSummaries(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        return studyPlanRepository.findSummariesByStudentId(student.getId(), PageRequest.of(0, 50));
    }

    @Cacheable(value = "StudyPlanByIdCache", key = "#userEmail + '-' + #id", sync = true)
    @Transactional(readOnly = true)
    public StudyPlanDetailDto getStudyPlan(Long id, String userEmail) {
        StudyPlan plan = getOwnedStudyPlanWithItems(id, userEmail);
        return toDetailDto(plan);
    }

    private String createPrompt(String topic, String difficulty, int durationDays, List<Map<String, String>> videos) {
        StringBuilder videoList = new StringBuilder();

        for (int i = 0; i < videos.size(); i++) {
            Map<String, String> v = videos.get(i);
            videoList.append(String.format(
                    "  %d. videoId=\"%s\", title=\"%s\", channel=\"%s\", duration=\"%s\"\n",
                    i + 1, v.get("videoId"), v.get("title"), v.get("channelTitle"), v.get("duration")));
        }

        return String.format(
                """
                        You are an expert curriculum designer. Create a structured study plan for a "%s" level student learning "%s" over %d days.
                        Strongly prefer longer, in-depth lectures and whiteboard sessions. If the same channel/creator has multiple relevant videos, strongly prefer clustering their videos together to provide a consistent learning experience.

                        Here are videos available for this topic:
                        %s

                        Return ONLY valid JSON with this structure:
                        {
                          "title": "Study Plan Title",
                          "description": "Brief description",
                          "days": [
                            {
                              "dayNumber": 1,
                              "items": [
                                {
                                  "type": "VIDEO",
                                  "videoId": "video_id",
                                  "description": "what to focus on",
                                  "practiceTopic": "topic"
                                },
                                {
                                  "type": "PRACTICE",
                                  "practiceSubject": "subject",
                                  "practiceTopic": "topic",
                                  "practiceDifficulty": "%s",
                                  "description": "practice description"
                                }
                              ]
                            }
                          ]
                        }
                        """,
                difficulty, topic, durationDays, videoList.toString(), difficulty);
    }

    private StudyPlan parseAndSavePlan(
            String jsonResponse,
            Student student,
            String topic,
            String difficulty,
            int durationDays,
            List<Map<String, String>> videos) {

        try {
            String cleanJson = jsonResponse.replace("```json", "").replace("```", "").trim();
            JsonNode root = objectMapper.readTree(cleanJson);

            Map<String, Map<String, String>> videoMap = new HashMap<>();
            for (Map<String, String> video : videos) {
                videoMap.put(video.get("videoId"), video);
            }

            StudyPlan plan = new StudyPlan();
            plan.setTitle(root.path("title").asText("Study Plan: " + topic));
            plan.setDescription(root.path("description").asText(""));
            plan.setTopic(topic);
            plan.setDifficulty(difficulty);
            plan.setDurationDays(durationDays);
            plan.setStudent(student);
            plan.setCreatedAt(LocalDateTime.now());

            int globalOrder = 1;
            JsonNode days = root.path("days");

            if (days.isArray()) {
                for (JsonNode day : days) {
                    int dayNumber = day.path("dayNumber").asInt(1);
                    JsonNode dayItems = day.path("items");

                    if (!dayItems.isArray()) {
                        continue;
                    }

                    for (JsonNode itemNode : dayItems) {
                        StudyPlanItem item = new StudyPlanItem();
                        String type = itemNode.path("type").asText("VIDEO");

                        item.setItemType(type);
                        item.setDayNumber(dayNumber);
                        item.setOrderIndex(globalOrder++);
                        item.setDescription(itemNode.path("description").asText(""));

                        if ("VIDEO".equals(type)) {
                            String videoId = itemNode.path("videoId").asText("");
                            Map<String, String> videoData = videoMap.get(videoId);

                            if (videoData == null) {
                                continue;
                            }

                            item.setVideoId(videoId);
                            item.setTitle(videoData.get("title"));
                            item.setVideoUrl("https://www.youtube.com/watch?v=" + videoId);
                            item.setThumbnailUrl(videoData.get("thumbnailUrl"));
                            item.setChannelName(videoData.get("channelTitle"));
                            item.setVideoDuration(videoData.get("duration"));

                            String cleanTopic = itemNode.path("practiceTopic").asText(topic);
                            item.setPracticeTopic(cleanTopic);
                            item.setPracticeSubject(topic);
                            item.setPracticeDifficulty(difficulty);
                            item.setXpReward(VIDEO_XP);
                        } else if ("PRACTICE".equals(type)) {
                            item.setTitle("Checkpoint: " + itemNode.path("practiceTopic").asText(topic));
                            item.setPracticeSubject(itemNode.path("practiceSubject").asText(topic));
                            item.setPracticeTopic(itemNode.path("practiceTopic").asText(topic));
                            item.setPracticeDifficulty(itemNode.path("practiceDifficulty").asText(difficulty));
                            item.setXpReward(PRACTICE_XP);
                        }

                        plan.addItem(item);
                    }
                }
            }

            return studyPlanRepository.save(plan);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse study plan response: " + e.getMessage(), e);
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "StudyPlanByIdCache", key = "#userEmail + '-' + #id")
    })
    public void deleteStudyPlan(Long id, String userEmail) {
        StudyPlan plan = getOwnedStudyPlan(id, userEmail);
        evictOwnedQuizQuestionCaches(userEmail, plan);
        studyPlanRepository.delete(plan);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    public record SuggestedPracticeDto(String topic, String subject, String difficulty, Long planId, Long itemId) {
    }

    @Cacheable(value = "UserSuggestedPracticeCache", key = "#userEmail", sync = true)
    public SuggestedPracticeDto getSuggestedPracticeItem(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        List<StudyPlanItem> nextItems = studyPlanItemRepository.findNextPracticeItems(student.getId());
        if (nextItems.isEmpty()) {
            return null;
        }

        StudyPlanItem nextItem = nextItems.get(0);

        return new SuggestedPracticeDto(
                nextItem.getPracticeTopic(),
                nextItem.getPracticeSubject(),
                nextItem.getPracticeDifficulty(),
                nextItem.getStudyPlan().getId(),
                nextItem.getId());
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "LeaderboardCache", allEntries = true)
    })
    public int markExternalPracticeAsComplete(String userEmail, String topic, String difficulty) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        List<StudyPlanItem> matches = studyPlanItemRepository.findMatchingIncompleteItems(student.getId(), topic);
        Set<Long> touchedPlanIds = new HashSet<>();

        int completed = 0;
        for (StudyPlanItem item : matches) {
            item.setCompleted(true);
            completed++;

            int xp = item.getXpReward() > 0 ? item.getXpReward() : PRACTICE_XP;
            student.setTotalXp(student.getTotalXp() + xp);

            recalculateProgress(item.getStudyPlan());
            studyPlanRepository.save(item.getStudyPlan());
            if (item.getStudyPlan() != null && item.getStudyPlan().getId() != null) {
                touchedPlanIds.add(item.getStudyPlan().getId());
            }
        }

        studentRepository.save(student);
        for (Long planId : touchedPlanIds) {
            evictOwnedStudyPlanByIdCache(userEmail, planId);
        }
        return completed;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    public record ActiveContextDto(
            Long planId,
            String planTitle,
            int progress,
            int currentDay,
            int totalDays,
            List<ActiveContextItemDto> todayItems,
            SuggestedPracticeDto nextPractice) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    public record ActiveContextItemDto(
            Long itemId,
            String title,
            String type,
            boolean isCompleted,
            String practiceTopic,
            String practiceSubject,
            String practiceDifficulty) {
    }

    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail")
    })
    public StudyPlan generateStudyPlanFromSyllabus(String userEmail, MultipartFile file, int durationDays) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        try {
            enforceDailyLimitForFreeUsers(student);

            String mimeType = file.getContentType();
            byte[] fileData;
            try {
                fileData = file.getBytes();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read uploaded file.", e);
            }

            String preAnalysisPrompt = """
                    Analyze this syllabus/document.
                    Return ONLY valid JSON:
                    {
                      "title": "Clear Course Title",
                      "playlistQuery": "Search query for a comprehensive playlist"
                    }
                    """;

            String preAnalysisResponse = aiService.generateStudyPlanContent(preAnalysisPrompt, mimeType, fileData);
            JsonNode preNode = parseJson(preAnalysisResponse);

            String courseTitle = preNode.path("title").asText("Custom Course");
            String playlistQuery = preNode.path("playlistQuery").asText(courseTitle);

            List<Map<String, String>> playlistVideos = new ArrayList<>();
            List<Map<String, String>> foundPlaylists = youTubeService.searchPlaylists(playlistQuery, 2);
            if (!foundPlaylists.isEmpty()) {
                playlistVideos = youTubeService.getPlaylistItems(foundPlaylists.get(0).get("playlistId"), 50);
            }

            StringBuilder videoList = new StringBuilder();
            for (int i = 0; i < playlistVideos.size(); i++) {
                Map<String, String> v = playlistVideos.get(i);
                videoList.append(i + 1).append(". ").append(v.get("title")).append(" (videoId: ")
                        .append(v.get("videoId")).append(")\n");
            }

            String analysisPrompt = String.format(
                    """
                            Analyze this syllabus and create a %d-day complete study plan.
                            Strongly prefer longer, in-depth lectures and whiteboard sessions. If the same channel/creator has multiple relevant videos, strongly prefer clustering their videos together to provide a consistent learning experience.

                            Playlist videos:
                            %s

                            Return ONLY valid JSON:
                            {
                              "title": "%s",
                              "description": "Description",
                              "difficulty": "Intermediate",
                              "days": [
                                {
                                  "dayNumber": 1,
                                  "lessons": [
                                    {
                                      "title": "Topic",
                                      "videoId": "id_or_null",
                                      "searchQuery": "query_if_needed",
                                      "description": "lesson description"
                                    }
                                  ]
                                }
                              ]
                            }
                            """,
                    durationDays, videoList.toString(), courseTitle);

            String analysisResponse = aiService.generateStudyPlanContent(analysisPrompt, mimeType, fileData);
            JsonNode root = parseJson(analysisResponse);

            String title = root.path("title").asText(courseTitle);
            String description = root.path("description").asText("Generated from uploaded syllabus");
            String difficulty = root.path("difficulty").asText("Intermediate");
            JsonNode daysNode = root.path("days");

            if (!daysNode.isArray() || daysNode.isEmpty()) {
                throw new RuntimeException("Could not extract a valid day-by-day schedule from the syllabus.");
            }

            Map<String, Map<String, String>> playlistVideoMap = new HashMap<>();
            for (Map<String, String> v : playlistVideos) {
                playlistVideoMap.put(v.get("videoId"), v);
            }

            Set<String> usedVideoIds = new HashSet<>();
            List<StudyPlanItem> allItems = new ArrayList<>();

            int itemOrder = 1;
            int videoItemsAdded = 0;

            for (JsonNode day : daysNode) {
                int dayNumber = day.path("dayNumber").asInt();
                JsonNode lessons = day.path("lessons");

                if (!lessons.isArray()) {
                    continue;
                }

                for (JsonNode lesson : lessons) {
                    String lessonTitle = normalizeAiField(lesson.path("title").asText(""));
                    String videoId = normalizeAiField(lesson.path("videoId").asText(""));
                    String searchQuery = normalizeAiField(lesson.path("searchQuery").asText(""));
                    String lessonDesc = lesson.path("description").asText("");

                    Map<String, String> videoData = null;

                    if (!videoId.isEmpty() && playlistVideoMap.containsKey(videoId)
                            && !usedVideoIds.contains(videoId)) {
                        videoData = playlistVideoMap.get(videoId);
                    }

                    if (videoData == null) {
                        List<String> fallbackQueries = new ArrayList<>();
                        if (!searchQuery.isEmpty())
                            fallbackQueries.add(searchQuery);
                        if (!lessonTitle.isEmpty())
                            fallbackQueries.add(lessonTitle + " " + title + " tutorial");
                        if (!lessonTitle.isEmpty())
                            fallbackQueries.add(lessonTitle + " tutorial for beginners");
                        fallbackQueries.add(title + " " + difficulty + " tutorial");

                        for (String query : fallbackQueries) {
                            List<Map<String, String>> results = youTubeService.searchVideos(query, 15);
                            for (Map<String, String> r : results) {
                                String candidateId = r.get("videoId");
                                if (candidateId != null && !usedVideoIds.contains(candidateId)) {
                                    videoData = r;
                                    break;
                                }
                            }
                            if (videoData != null) {
                                break;
                            }
                        }
                    }

                    if (videoData != null) {
                        usedVideoIds.add(videoData.get("videoId"));

                        StudyPlanItem item = new StudyPlanItem();
                        item.setItemType("VIDEO");
                        item.setDayNumber(dayNumber);
                        item.setOrderIndex(itemOrder++);
                        item.setDescription(lessonDesc);
                        item.setVideoId(videoData.get("videoId"));
                        item.setTitle(lessonTitle);
                        item.setVideoUrl("https://www.youtube.com/watch?v=" + videoData.get("videoId"));
                        item.setThumbnailUrl(videoData.get("thumbnailUrl"));
                        item.setChannelName(videoData.get("channelTitle"));
                        item.setVideoDuration(videoData.get("duration"));
                        item.setXpReward(VIDEO_XP);
                        item.setPracticeTopic(lessonTitle);
                        item.setPracticeSubject(title);
                        item.setPracticeDifficulty(difficulty);

                        allItems.add(item);
                        videoItemsAdded++;
                    }
                }

                StudyPlanItem practice = new StudyPlanItem();
                practice.setItemType("PRACTICE");
                practice.setDayNumber(dayNumber);
                practice.setOrderIndex(itemOrder++);
                practice.setTitle("Day " + dayNumber + " Checkpoint");
                practice.setPracticeSubject(title);

                String lastLessonTitle = "General Review";
                if (lessons.size() > 0) {
                    lastLessonTitle = lessons.get(lessons.size() - 1).path("title").asText("Day Review");
                }

                practice.setPracticeTopic(lastLessonTitle);
                practice.setPracticeDifficulty(difficulty);
                practice.setXpReward(PRACTICE_XP);
                practice.setDescription("Test your knowledge on today's topics.");

                allItems.add(practice);
            }

            if (videoItemsAdded == 0) {
                throw new RuntimeException(
                        "Could not map syllabus topics to YouTube videos. Try a different syllabus file or retry.");
            }

            StudyPlan plan = new StudyPlan();
            plan.setTitle(title);
            plan.setDescription(description);
            plan.setTopic(title);
            plan.setDifficulty(difficulty);
            plan.setDurationDays(durationDays);
            plan.setStudent(student);
            plan.setCreatedAt(LocalDateTime.now());
            plan.setItems(allItems);

            for (StudyPlanItem item : allItems) {
                item.setStudyPlan(plan);
            }

            StudyPlan savedPlan = studyPlanRepository.save(plan);
            generateQuizQuestionsForPlan(savedPlan, title, difficulty);

            return savedPlan;
        } catch (RuntimeException ex) {
            status = "error";
            throw ex;
        } finally {
            meterRegistry.counter("study_plan.generate_from_syllabus.count", "status", status).increment();
            sample.stop(meterRegistry.timer("study_plan.generate_from_syllabus.duration", "status", status));
        }
    }

    private JsonNode parseJson(String jsonResponse) {
        try {
            String cleanJson = jsonResponse.replace("```json", "").replace("```", "").trim();
            return objectMapper.readTree(cleanJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse generated response: " + e.getMessage(), e);
        }
    }

    private String normalizeAiField(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        return "null".equalsIgnoreCase(cleaned) ? "" : cleaned;
    }

    @Cacheable(value = "UserActiveContextCache", key = "#userEmail", sync = true)
    public ActiveContextDto getActiveContext(String userEmail) {
        Student student = studentRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        List<StudyPlan> plans = studyPlanRepository.findByStudentIdOrderByCreatedAtDesc(student.getId(),
                PageRequest.of(0, 20));

        StudyPlan activePlan = null;
        for (StudyPlan plan : plans) {
            if (!plan.isCompleted()) {
                activePlan = plan;
                break;
            }
        }

        if (activePlan == null) {
            return null;
        }

        int totalDays = activePlan.getDurationDays();
        int currentDay = totalDays;

        for (StudyPlanItem item : activePlan.getItems()) {
            if (!item.isCompleted() && item.getDayNumber() < currentDay) {
                currentDay = item.getDayNumber();
            }
        }

        List<ActiveContextItemDto> todayItems = new ArrayList<>();
        for (StudyPlanItem item : activePlan.getItems()) {
            if (item.getDayNumber() == currentDay) {
                todayItems.add(new ActiveContextItemDto(
                        item.getId(),
                        item.getTitle(),
                        item.getItemType(),
                        item.isCompleted(),
                        item.getPracticeTopic(),
                        item.getPracticeSubject(),
                        item.getPracticeDifficulty()));
            }
        }

        SuggestedPracticeDto nextPractice = self.getSuggestedPracticeItem(userEmail);

        return new ActiveContextDto(
                activePlan.getId(),
                activePlan.getTitle(),
                activePlan.getProgress(),
                currentDay,
                totalDays,
                todayItems,
                nextPractice);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "UserStudyPlansCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanSummariesCache", key = "#userEmail"),
            @CacheEvict(value = "UserStudyPlanStatsCache", key = "#userEmail"),
            @CacheEvict(value = "UserActiveContextCache", key = "#userEmail"),
            @CacheEvict(value = "UserSuggestedPracticeCache", key = "#userEmail"),
            @CacheEvict(value = "UserRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "UserStatisticsRecommendationsCache", key = "#userEmail"),
            @CacheEvict(value = "StudyPlanByIdCache", key = "#userEmail + '-' + #id")
    })
    public void deleteStudyPlan(Long id, String userEmail) {
        StudyPlan plan = getOwnedStudyPlanWithItems(id, userEmail);
        evictOwnedQuizQuestionCaches(userEmail, plan);
        studyPlanRepository.delete(plan);
    }

    private StudyPlan getOwnedStudyPlan(Long planId, String userEmail) {
        StudyPlan plan = studyPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Study plan not found"));

        if (plan.getStudent() == null || plan.getStudent().getEmail() == null) {
            throw new RuntimeException("Study plan owner could not be verified");
        }

        if (!plan.getStudent().getEmail().equalsIgnoreCase(userEmail)) {
            throw new RuntimeException("You do not have permission to access this study plan");
        }

        return plan;
    }

    private StudyPlan getOwnedStudyPlanWithItems(Long planId, String userEmail) {
        StudyPlan plan = studyPlanRepository.findWithItemsById(planId);
        if (plan == null) {
            throw new RuntimeException("Study plan not found");
        }

        if (plan.getStudent() == null || plan.getStudent().getEmail() == null) {
            throw new RuntimeException("Study plan owner could not be verified");
        }

        if (!plan.getStudent().getEmail().equalsIgnoreCase(userEmail)) {
            throw new RuntimeException("You do not have permission to access this study plan");
        }

        return plan;
    }

    private StudyPlanDetailDto toDetailDto(StudyPlan plan) {
        Map<Long, List<QuizQuestion>> questionsByItemId = new HashMap<>();
        if (plan.getItems() != null && !plan.getItems().isEmpty()) {
            List<Long> itemIds = new ArrayList<>();
            for (StudyPlanItem item : plan.getItems()) {
                if (item != null && item.getId() != null) {
                    itemIds.add(item.getId());
                }
            }
            if (!itemIds.isEmpty()) {
                List<QuizQuestion> questions = quizQuestionRepository.findByStudyPlanItemIdIn(itemIds);
                for (QuizQuestion question : questions) {
                    Long itemId = question.getStudyPlanItem() != null ? question.getStudyPlanItem().getId() : null;
                    if (itemId == null) {
                        continue;
                    }
                    questionsByItemId.computeIfAbsent(itemId, k -> new ArrayList<>()).add(question);
                }
            }
        }

        List<StudyPlanItemDto> items = new ArrayList<>();
        if (plan.getItems() != null) {
            for (StudyPlanItem item : plan.getItems()) {
                List<StudyPlanQuizQuestionDto> questions = new ArrayList<>();
                List<QuizQuestion> itemQuestions = questionsByItemId.get(item.getId());
                if (itemQuestions != null) {
                    for (QuizQuestion q : itemQuestions) {
                        questions.add(new StudyPlanQuizQuestionDto(
                                q.getId(),
                                q.getQuestionText(),
                                q.getOptionA(),
                                q.getOptionB(),
                                q.getOptionC(),
                                q.getOptionD()));
                    }
                }

                items.add(new StudyPlanItemDto(
                        item.getId(),
                        item.getItemType(),
                        item.getTitle(),
                        item.getDescription(),
                        item.getVideoId(),
                        item.getVideoUrl(),
                        item.getThumbnailUrl(),
                        item.getChannelName(),
                        item.getVideoDuration(),
                        item.getPracticeSubject(),
                        item.getPracticeTopic(),
                        item.getPracticeDifficulty(),
                        item.getDayNumber(),
                        item.getOrderIndex(),
                        item.getXpReward(),
                        questions,
                        item.isCompleted()));
            }
        }

        Student student = plan.getStudent();
        StudyPlanDetailStudentDto studentDto = student == null ? null
                : new StudyPlanDetailStudentDto(
                        student.getId(),
                        student.getFirstName(),
                        student.getLastName(),
                        student.getGender(),
                        student.getBio(),
                        student.getHeadline(),
                        student.getAvatarUrl(),
                        student.getGithubUrl(),
                        student.getLinkedinUrl(),
                        student.getWebsiteUrl(),
                        student.getSubscriptionStatus(),
                        student.getTotalXp(),
                        student.getStreakDays(),
                        student.getLastLoginDate() == null ? null : student.getLastLoginDate().toString());

        return new StudyPlanDetailDto(
                plan.getId(),
                plan.getTitle(),
                plan.getTopic(),
                plan.getDifficulty(),
                plan.getDurationDays(),
                plan.getDescription(),
                plan.getProgress(),
                studentDto,
                items,
                plan.getCreatedAt(),
                plan.isCompleted(),
                plan.isGenerating());
    }

    private void evictOwnedStudyPlanByIdCache(String userEmail, Long planId) {
        Cache cache = cacheManager.getCache("StudyPlanByIdCache");
        if (cache != null && planId != null) {
            cache.evict(userEmail + "-" + planId);
        }
    }

    private void evictOwnedQuizQuestionCaches(String userEmail, StudyPlan plan) {
        Cache cache = cacheManager.getCache("StudyPlanQuizQuestionsCache");
        if (cache == null || plan == null || plan.getId() == null || plan.getItems() == null) {
            return;
        }

        for (StudyPlanItem item : plan.getItems()) {
            if (item != null && item.getId() != null) {
                cache.evict(userEmail + "-" + plan.getId() + "-" + item.getId());
            }
        }
    }
}
