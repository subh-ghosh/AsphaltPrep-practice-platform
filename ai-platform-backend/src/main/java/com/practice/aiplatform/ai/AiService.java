package com.practice.aiplatform.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachePut;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiService {
        public static final String PRACTICE_UNAVAILABLE_CODE = "AI_PRACTICE_UNAVAILABLE";
        public static final String STUDY_PLAN_UNAVAILABLE_CODE = "STUDY_PLAN_AI_UNAVAILABLE";
        private static final int MAX_QUESTION_ATTEMPTS = 3;

        private final WebClient webClient;
        private final String apiKey;
        private final String practiceModel;
        private final String studyPlanModel;
        private final MeterRegistry meterRegistry;
        private final Duration practiceRequestTimeout;
        private final Duration studyPlanRequestTimeout;
        @Lazy
        @Autowired
        private AiService self;

        public AiService(
                        @Qualifier("aiWebClient") WebClient webClient,
                        @Value("${groq.api.key}") String apiKey,
                        @Value("${ai.model.practice:llama-3.1-8b-instant}") String practiceModel,
                        @Value("${ai.model.study-plan:llama-3.3-70b-versatile}") String studyPlanModel,
                        @Value("${ai.request.timeout.practice-seconds:8}") long practiceTimeoutSeconds,
                        @Value("${ai.request.timeout.study-plan-seconds:30}") long studyPlanTimeoutSeconds,
                        MeterRegistry meterRegistry) {
                this.webClient = webClient;
                this.apiKey = apiKey;
                this.practiceModel = practiceModel;
                this.studyPlanModel = studyPlanModel;
                this.meterRegistry = meterRegistry;
                this.practiceRequestTimeout = Duration.ofSeconds(practiceTimeoutSeconds);
                this.studyPlanRequestTimeout = Duration.ofSeconds(studyPlanTimeoutSeconds);
        }

        public String generateRawContent(String prompt) {
                return generatePracticeContent(prompt);
        }

        public String generatePracticeContent(String prompt) {
                AiResponse response = self.executePracticeCompletion(prompt, practiceModel, "practice");
                return extractTextFromResponse(response);
        }

        public String generateStudyPlanContent(String prompt) {
                AiResponse response = self.executeStudyPlanCompletion(prompt, studyPlanModel, "study_plan");
                return extractTextFromResponse(response);
        }

        public String generateQuestion(String subject, String difficulty, String topic) {
                return generateQuestion(subject, difficulty, topic, null, null, List.of());
        }

        @Cacheable(value = "AiQuestionCache", key = "#subject + '|' + #difficulty + '|' + #topic", sync = true)
        public String generateQuestionFromCache(String subject, String difficulty, String topic) {
                return generateQuestion(subject, difficulty, topic, null, null, List.of());
        }

        @CachePut(value = "AiQuestionCache", key = "#subject + '|' + #difficulty + '|' + #topic")
        public String generateFreshQuestionAndRefreshCache(
                        String subject,
                        String difficulty,
                        String topic,
                        String previousQuestion,
                        String previousStatus,
                        List<String> recentQuestionTexts) {
                return generateQuestion(subject, difficulty, topic, previousQuestion, previousStatus, recentQuestionTexts);
        }

        public String generateQuestion(
                        String subject,
                        String difficulty,
                        String topic,
                        String previousQuestion,
                        String previousStatus) {
                return generateQuestion(subject, difficulty, topic, previousQuestion, previousStatus, List.of());
        }

        public String generateQuestion(
                        String subject,
                        String difficulty,
                        String topic,
                        String previousQuestion,
                        String previousStatus,
                        List<String> recentQuestionTexts) {

                String contextPrompt = "";
                if (previousQuestion != null && previousStatus != null) {
                        if ("CORRECT".equalsIgnoreCase(previousStatus)) {
                                contextPrompt = String.format(
                                                "The student just answered correctly on this topic: \"%s\". "
                                                                + "Generate a slightly more challenging question. ",
                                                previousQuestion);
                        } else {
                                contextPrompt = String.format(
                                                "The student just failed on this topic: \"%s\". "
                                                                + "Generate a simpler question to reinforce basics. ",
                                                previousQuestion);
                        }
                }

                List<String> blockedQuestions = new ArrayList<>();
                if (recentQuestionTexts != null) {
                        for (String q : recentQuestionTexts) {
                                if (q != null && !q.isBlank()) {
                                        blockedQuestions.add(q.trim());
                                }
                        }
                }

                if (previousQuestion != null && !previousQuestion.isBlank()) {
                        blockedQuestions.add(previousQuestion.trim());
                }

                String lastGenerated = "";
                for (int attempt = 1; attempt <= MAX_QUESTION_ATTEMPTS; attempt++) {
                        String prompt = buildQuestionPrompt(subject, difficulty, topic, contextPrompt, blockedQuestions, attempt);
                        AiResponse response = self.executePracticeCompletion(prompt, practiceModel, "question");
                        String candidate = extractTextFromResponse(response).trim();
                        lastGenerated = candidate;

                        if (!isNearDuplicate(candidate, blockedQuestions)) {
                                return candidate;
                        }

                        blockedQuestions.add(candidate);
                }

                return lastGenerated;
        }

        @Cacheable(value = "AiEvaluateCache", key = "#subject + '|' + #topic + '|' + #difficulty + '|' + #questionText + '|' + #answerText", sync = true)
        public String evaluateAnswer(String questionText, String answerText, String subject, String topic,
                        String difficulty) {
                String prompt = String.format(
                                "Subject: %s, Topic: %s, Level: %s.\n"
                                                + "Question: \"%s\"\n"
                                                + "Student Answer: \"%s\"\n\n"
                                                + "Reply in this format:\n"
                                                + "Line 1: CORRECT or INCORRECT or CLOSE\n"
                                                + "Line 2+: Feedback\n"
                                                + "If INCORRECT/CLOSE add [HINT] at end with a helpful hint.",
                                subject, topic, difficulty, questionText, answerText);

                AiResponse response = self.executePracticeCompletion(prompt, practiceModel, "evaluate");
                return extractTextFromResponse(response);
        }

        @Cacheable(value = "AiHintCache", key = "#subject + '|' + #topic + '|' + #difficulty + '|' + #questionText", sync = true)
        public String getHint(String questionText, String subject, String topic, String difficulty) {
                String prompt = String.format(
                                "Give one concise hint only.\n"
                                                + "Subject: %s, Topic: %s, Difficulty: %s\n"
                                                + "Question: \"%s\"",
                                subject, topic, difficulty, questionText);

                AiResponse response = self.executePracticeCompletion(prompt, practiceModel, "hint");
                return extractTextFromResponse(response);
        }

        @Cacheable(value = "AiAnswerCache", key = "#subject + '|' + #topic + '|' + #difficulty + '|' + #questionText", sync = true)
        public String getCorrectAnswer(String questionText, String subject, String topic, String difficulty) {
                String prompt = String.format(
                                "Provide the correct answer and short explanation.\n"
                                                + "Subject: %s, Topic: %s, Difficulty: %s\n"
                                                + "Question: \"%s\"",
                                subject, topic, difficulty, questionText);

                AiResponse response = self.executePracticeCompletion(prompt, practiceModel, "answer");
                return extractTextFromResponse(response);
        }

        public String generateRawContent(String prompt, String mimeType, byte[] data) {
                return generateStudyPlanContent(prompt, mimeType, data);
        }

        public String generateStudyPlanContent(String prompt, String mimeType, byte[] data) {
                String finalPrompt = prompt;

                if (mimeType != null && mimeType.startsWith("text/") && data != null) {
                        String textPayload = new String(data, StandardCharsets.UTF_8);
                        finalPrompt = prompt + "\n\nAttached text:\n" + textPayload;
                } else if ("application/pdf".equalsIgnoreCase(mimeType) && data != null && data.length > 0) {
                        String pdfText = extractPdfText(data);
                        if (pdfText.isBlank()) {
                                throw new RuntimeException("Could not extract readable text from the PDF.");
                        }
                        finalPrompt = prompt + "\n\nExtracted PDF text:\n" + pdfText;
                } else if (mimeType != null && data != null && data.length > 0) {
                        throw new RuntimeException("Only text and PDF files are supported.");
                }

                AiResponse response = self.executeStudyPlanCompletion(finalPrompt, studyPlanModel, "study_plan_file");
                return extractTextFromResponse(response);
        }

        private String extractPdfText(byte[] data) {
                try (PDDocument document = Loader.loadPDF(data)) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        return stripper.getText(document).trim();
                } catch (Exception e) {
                        throw new RuntimeException("Failed to read PDF content: " + e.getMessage(), e);
                }
        }

        @Retry(name = "aiPractice", fallbackMethod = "practiceCompletionFallback")
        @CircuitBreaker(name = "aiPractice", fallbackMethod = "practiceCompletionFallback")
        @Bulkhead(name = "aiPractice", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "practiceCompletionFallback")
        public AiResponse executePracticeCompletion(String prompt, String model, String purpose) {
                return callAiApi(prompt, model, purpose, practiceRequestTimeout);
        }

        @Retry(name = "aiStudyPlan", fallbackMethod = "studyPlanCompletionFallback")
        @CircuitBreaker(name = "aiStudyPlan", fallbackMethod = "studyPlanCompletionFallback")
        @Bulkhead(name = "aiStudyPlan", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "studyPlanCompletionFallback")
        public AiResponse executeStudyPlanCompletion(String prompt, String model, String purpose) {
                return callAiApi(prompt, model, purpose, studyPlanRequestTimeout);
        }

        private AiResponse callAiApi(String prompt, String model, String purpose, Duration timeout) {
                Map<String, Object> requestBody = Map.of(
                                "model", model,
                                "messages", List.of(Map.of("role", "user", "content", prompt)),
                                "temperature", 0.2);

                Timer.Sample sample = Timer.start(meterRegistry);
                String status = "success";
                try {
                        return webClient.post()
                                        .uri("/v1/chat/completions")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .onStatus(
                                                        s -> s.is4xxClientError() || s.is5xxServerError(),
                                                        clientResponse -> clientResponse.bodyToMono(String.class)
                                                                        .flatMap(errorBody -> reactor.core.publisher.Mono
                                                                                        .error(new RuntimeException(
                                                                                                        "Generation API Error: " + errorBody))))
                                        .bodyToMono(AiResponse.class)
                                        .block(timeout);
                } catch (RuntimeException ex) {
                        status = "error";
                        throw ex;
                } finally {
                        meterRegistry.counter(
                                        "ai.call.count",
                                        "model", model,
                                        "purpose", purpose,
                                        "status", status)
                                        .increment();
                        sample.stop(meterRegistry.timer(
                                        "ai.call.duration",
                                        "model", model,
                                        "purpose", purpose,
                                        "status", status));
                }
        }

        private AiResponse practiceCompletionFallback(String prompt, String model, String purpose,
                        Throwable throwable) {
                meterRegistry.counter(
                                "ai.call.count",
                                "model", model,
                                "purpose", purpose,
                                "status", "fallback")
                                .increment();
                throw new RuntimeException(PRACTICE_UNAVAILABLE_CODE
                                + ": Generation service temporarily unavailable. Please retry in a moment.");
        }

        private AiResponse studyPlanCompletionFallback(String prompt, String model, String purpose,
                        Throwable throwable) {
                meterRegistry.counter(
                                "ai.call.count",
                                "model", model,
                                "purpose", purpose,
                                "status", "fallback")
                                .increment();
                throw new RuntimeException(STUDY_PLAN_UNAVAILABLE_CODE
                                + ": Generation service temporarily unavailable. Please retry in a moment.");
        }

        private String extractTextFromResponse(AiResponse response) {
                if (response == null || response.choices() == null || response.choices().isEmpty()) {
                        throw new RuntimeException(PRACTICE_UNAVAILABLE_CODE + ": Empty response from generation service.");
                }
                String content = response.choices().get(0).message() != null ? response.choices().get(0).message().content() : null;
                if (content == null || content.isBlank()) {
                        throw new RuntimeException(PRACTICE_UNAVAILABLE_CODE + ": Generation service returned blank text.");
                }
                return content;
        }

        private String buildQuestionPrompt(
                        String subject,
                        String difficulty,
                        String topic,
                        String contextPrompt,
                        List<String> blockedQuestions,
                        int attempt) {
                StringBuilder prompt = new StringBuilder();
                if (contextPrompt != null && !contextPrompt.isBlank()) {
                        prompt.append(contextPrompt);
                }

                prompt.append(String.format(
                                "Generate one fresh practice question for %s level in %s on topic %s. ",
                                difficulty, subject, topic));

                if (attempt > 1) {
                        prompt.append("The last attempt was too similar. Make this one clearly different in wording and angle. ");
                }

                if (!blockedQuestions.isEmpty()) {
                        prompt.append("Do not repeat or closely paraphrase any of these prior questions:\n");
                        int limit = Math.min(blockedQuestions.size(), 12);
                        for (int i = 0; i < limit; i++) {
                                prompt.append("- ").append(blockedQuestions.get(i)).append("\n");
                        }
                }

                prompt.append("Return only question text.");
                return prompt.toString();
        }

        private boolean isNearDuplicate(String candidate, List<String> blockedQuestions) {
                String normalizedCandidate = normalizeQuestion(candidate);
                if (normalizedCandidate.isBlank()) {
                        return true;
                }

                for (String existing : blockedQuestions) {
                        String normalizedExisting = normalizeQuestion(existing);
                        if (normalizedExisting.isBlank()) {
                                continue;
                        }
                        if (normalizedCandidate.equals(normalizedExisting)) {
                                return true;
                        }
                }

                return false;
        }

        private String normalizeQuestion(String text) {
                if (text == null) {
                        return "";
                }
                return text
                                .toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9 ]", " ")
                                .replaceAll("\\s+", " ")
                                .trim();
        }
}
