package com.practice.aiplatform.ai;

import com.practice.aiplatform.moderation.PromptModerationService;
import com.practice.aiplatform.practice.Question;
import com.practice.aiplatform.practice.QuestionRepository;
import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin
public class AiController {
    private static final Set<String> ANSWERED_STATUSES = Set.of("CORRECT", "INCORRECT", "CLOSE", "REVEALED");

    private final AiService aiService;
    private final QuestionRepository questionRepository;
    private final StudentRepository studentRepository;
    private final PromptModerationService promptModerationService;

    public AiController(
            AiService aiService,
            QuestionRepository questionRepository,
            StudentRepository studentRepository,
            PromptModerationService promptModerationService) {
        this.aiService = aiService;
        this.questionRepository = questionRepository;
        this.studentRepository = studentRepository;
        this.promptModerationService = promptModerationService;
    }

    public record GenerateQuestionRequest(
            String subject,
            String topic,
            String difficulty,
            Long previousQuestionId,
            String previousStatus) {
    }

    public record HintRequest(Long questionId) {
    }

    public record AnswerRequest(Long questionId) {
    }

    @PostMapping("/generate-question")
    public ResponseEntity<?> generateQuestion(@RequestBody GenerateQuestionRequest request, Principal principal) {
        try {
            if (promptModerationService.isBlocked(
                    request.subject(),
                    request.topic(),
                    request.difficulty())) {
                return ResponseEntity.badRequest().body(Map.of("message", promptModerationService.warningMessage()));
            }

            String email = principal.getName();

            Student student = studentRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Student not found"));

            String previousQuestionText = null;
            if (request.previousQuestionId() != null) {
                previousQuestionText = questionRepository.findById(request.previousQuestionId())
                        .map(Question::getQuestionText)
                        .orElse(null);
            }

            List<String> recentQuestionTexts = questionRepository
                    .findTop12ByStudent_IdAndTopicIgnoreCaseOrderByGeneratedAtDesc(student.getId(), request.topic())
                    .stream()
                    .map(Question::getQuestionText)
                    .toList();

            // Always force a fresh question while excluding recent questions to prevent repetition
            String questionText = aiService.generateFreshQuestionAndRefreshCache(
                    request.subject(),
                    request.difficulty(),
                    request.topic(),
                    previousQuestionText,
                    request.previousStatus(),
                    recentQuestionTexts);

            Question question = new Question();
            question.setStudent(student);
            question.setSubject(request.subject());
            question.setTopic(request.topic());
            question.setDifficulty(request.difficulty());
            question.setQuestionText(questionText);

            Question saved = questionRepository.save(question);
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            return aiFailureResponse(e);
        }
    }

    @PostMapping("/get-hint")
    public ResponseEntity<?> getHint(@RequestBody HintRequest request, Principal principal) {
        try {
            String email = principal.getName();

            studentRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Student not found"));

            Question question = questionRepository.findById(request.questionId())
                    .orElseThrow(() -> new RuntimeException("Question not found"));

            String hint = aiService.getHint(
                    question.getQuestionText(),
                    question.getSubject(),
                    question.getTopic(),
                    question.getDifficulty());

            return ResponseEntity.ok(hint);

        } catch (Exception e) {
            return aiFailureResponse(e);
        }
    }

    @PostMapping("/get-answer")
    public ResponseEntity<?> getAnswer(@RequestBody AnswerRequest request, Principal principal) {
        try {
            String email = principal.getName();

            studentRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Student not found"));

            Question question = questionRepository.findById(request.questionId())
                    .orElseThrow(() -> new RuntimeException("Question not found"));

            String answer = aiService.getCorrectAnswer(
                    question.getQuestionText(),
                    question.getSubject(),
                    question.getTopic(),
                    question.getDifficulty());

            return ResponseEntity.ok(answer);

        } catch (Exception e) {
            return aiFailureResponse(e);
        }
    }

    private ResponseEntity<String> aiFailureResponse(Exception e) {
        String message = e.getMessage() == null ? "Unknown error" : e.getMessage();
        if (message.contains(AiService.PRACTICE_UNAVAILABLE_CODE) || message.contains("temporarily unavailable")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Error: " + message);
        }
        return ResponseEntity.internalServerError().body("Error: " + message);
    }
}
