package com.practice.aiplatform.user;

import com.practice.aiplatform.event.NotificationEvent;
import com.practice.aiplatform.event.NotificationEventPublisher;
import com.practice.aiplatform.security.JwtUtil;
import com.practice.aiplatform.security.RefreshToken;
import com.practice.aiplatform.security.RefreshTokenService;
import com.practice.aiplatform.security.TokenRefreshException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/students")
@CrossOrigin
public class StudentAuthController {

    private static final Logger log = LoggerFactory.getLogger(StudentAuthController.class);

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final NotificationEventPublisher notificationEventPublisher;
    private final GoogleAuthService googleAuthService;
    private final GitHubAuthService gitHubAuthService;
    private final RefreshTokenService refreshTokenService;

    public StudentAuthController(
            StudentRepository studentRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            NotificationEventPublisher notificationEventPublisher,
            GoogleAuthService googleAuthService,
            GitHubAuthService gitHubAuthService,
            RefreshTokenService refreshTokenService) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.notificationEventPublisher = notificationEventPublisher;
        this.googleAuthService = googleAuthService;
        this.gitHubAuthService = gitHubAuthService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (req == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid request body"));
        }

        String email = normalizeEmail(req.email());
        String firstName = normalizeName(req.firstName(), "Student");
        String lastName = normalizeName(req.lastName(), "");
        String password = req.password();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Email and password are required"));
        }

        try {
            Optional<Student> existingStudent = studentRepository.findByEmail(email);
            if (existingStudent.isPresent()) {
                Student student = existingStudent.get();

                // Google-first accounts are created without a password and must complete registration here.
                if (hasNoPassword(student)) {
                    student.setFirstName(firstName);
                    student.setLastName(lastName);
                    student.setPassword(passwordEncoder.encode(password));
                    studentRepository.save(student);
                    return ResponseEntity.ok(Map.of("message", "Google registration completed successfully"));
                }

                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Email already exists"));
            }

            Student student = new Student();
            student.setEmail(email);
            student.setPassword(passwordEncoder.encode(password));
            student.setFirstName(firstName);
            student.setLastName(lastName);
            student.setFreeActionsUsed(0);
            student.setSubscriptionStatus("FREE");
            student.setTotalXp(0);
            student.setStreakDays(1);

            Student savedStudent = studentRepository.save(student);

            try {
                notificationEventPublisher.publishNotificationEvent(
                        NotificationEvent.builder()
                                .studentId(savedStudent.getId())
                                .type("REGISTER")
                                .message("Welcome! Registration successful.")
                                .build());
            } catch (Exception ignored) {
            }

            return ResponseEntity.ok(Map.of("message", "User registered successfully"));
        } catch (Exception ex) {
            log.error("Register failed for email {}: {}", email, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Registration failed. Please try again."));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Invalid request body"));
        }

        String email = normalizeEmail(req.email());
        if (email == null || email.isBlank() || req.password() == null || req.password().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Email and password are required"));
        }

        Optional<Student> studentOpt;
        try {
            studentOpt = studentRepository.findByEmail(email);
        } catch (Exception ex) {
            log.error("Login pre-check failed for email {}: {}", email, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Login failed. Please try again."));
        }

        if (studentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid credentials"));
        }

        try {
            Student student = studentOpt.get();
            if (!passwordEncoder.matches(req.password(), student.getPassword())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid credentials"));
            }

            updateStreak(student);
            studentRepository.save(student);

            String token = jwtUtil.generateToken(student);

            refreshTokenService.deleteByUserId(student.getId());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(student.getId());

            try {
                notificationEventPublisher.publishNotificationEvent(
                        NotificationEvent.builder()
                                .studentId(student.getId())
                                .type("LOGIN")
                                .message("New login detected.")
                                .build());
            } catch (Exception ignored) {
            }

            StudentDto dto = new StudentDto(
                    student.getId(),
                    student.getEmail(),
                    student.getFirstName(),
                    student.getLastName(),
                    student.getGender(),
                    token,
                    student.getSubscriptionStatus(),
                    student.getFreeActionsUsed(),
                    student.getTotalXp(),
                    student.getStreakDays(),
                    refreshToken.getToken());

            Map<String, Object> response = new HashMap<>();
            response.put("token", token);
            response.put("refreshToken", refreshToken.getToken());
            response.put("student", dto);
            response.put("message", "Login successful");

            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.error("Login failed for email {}: {}", email, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Login failed. Please try again."));
        }
    }

    @PostMapping("/oauth/google")
    public ResponseEntity<?> handleGoogleLogin(@RequestBody Map<String, String> request) {
        try {
            String idToken = request.get("token");
            if (idToken == null || idToken.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Google token is required"));
            }

            var payload = googleAuthService.verifyToken(idToken);

            if (payload == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Google token");
            }

            String email = payload.getEmail();
            Optional<Student> existing = studentRepository.findByEmail(email);

            Student student;
            if (existing.isPresent()) {
                student = existing.get();
                if (hasNoPassword(student)) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("status", "NEEDS_REGISTRATION");
                    response.put("registrationData", Map.of(
                            "email", student.getEmail(),
                            "firstName", normalizeName(student.getFirstName(), (String) payload.get("given_name")),
                            "lastName", normalizeName(student.getLastName(), (String) payload.get("family_name"))));
                    response.put("message", "Please complete registration by setting a password.");
                    return ResponseEntity.ok(response);
                }
            } else {
                student = new Student();
                student.setEmail(email);
                student.setFirstName(normalizeName((String) payload.get("given_name"), "Student"));
                student.setLastName(normalizeName((String) payload.get("family_name"), ""));
                student.setPassword("");
                student.setSubscriptionStatus("FREE");
                student.setFreeActionsUsed(0);
                student.setTotalXp(0);
                student.setStreakDays(1);
                studentRepository.save(student);

                try {
                    notificationEventPublisher.publishNotificationEvent(
                            NotificationEvent.builder()
                                    .studentId(student.getId())
                                    .type("REGISTER")
                                    .message("Welcome! Your account has been created via Google.")
                                    .build());
                } catch (Exception ignored) {
                }

                Map<String, Object> response = new HashMap<>();
                response.put("status", "NEEDS_REGISTRATION");
                response.put("registrationData", Map.of(
                        "email", student.getEmail(),
                        "firstName", student.getFirstName(),
                        "lastName", student.getLastName()));
                response.put("message", "Please complete registration by setting a password.");
                return ResponseEntity.ok(response);
            }

            updateStreak(student);
            studentRepository.save(student);

            String jwt = jwtUtil.generateToken(student);

            refreshTokenService.deleteByUserId(student.getId());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(student.getId());

            try {
                notificationEventPublisher.publishNotificationEvent(
                        NotificationEvent.builder()
                                .studentId(student.getId())
                                .type("LOGIN")
                                .message("New login detected via Google.")
                                .build());
            } catch (Exception ignored) {
            }

            StudentDto dto = new StudentDto(
                    student.getId(),
                    student.getEmail(),
                    student.getFirstName(),
                    student.getLastName(),
                    student.getGender(),
                    jwt,
                    student.getSubscriptionStatus(),
                    student.getFreeActionsUsed(),
                    student.getTotalXp(),
                    student.getStreakDays(),
                    refreshToken.getToken());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "LOGIN_SUCCESS");
            response.put("token", jwt);
            response.put("refreshToken", refreshToken.getToken());
            response.put("student", dto);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Google Auth failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Google Auth Failed");
        }
    }

    @PostMapping("/oauth/github")
    public ResponseEntity<?> handleGitHubLogin(@RequestBody Map<String, String> request) {
        try {
            String code = request.get("code");
            if (code == null || code.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "GitHub authorization code is required"));
            }

            GitHubAuthService.GitHubUser ghUser = gitHubAuthService.processGitHubCode(code);
            String email = ghUser.email();
            Optional<Student> existing = studentRepository.findByEmail(email);

            Student student;
            if (existing.isPresent()) {
                student = existing.get();
                if (student.getAvatarUrl() == null || student.getAvatarUrl().isBlank()) {
                    student.setAvatarUrl(ghUser.avatarUrl());
                }
                if (student.getGithubUrl() == null || student.getGithubUrl().isBlank()) {
                    student.setGithubUrl(ghUser.htmlUrl());
                }

                if (hasNoPassword(student)) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("status", "NEEDS_REGISTRATION");
                    response.put("registrationData", Map.of(
                            "email", student.getEmail(),
                            "firstName", student.getFirstName(),
                            "lastName", student.getLastName(),
                            "avatarUrl", ghUser.avatarUrl() != null ? ghUser.avatarUrl() : "",
                            "githubUrl", ghUser.htmlUrl() != null ? ghUser.htmlUrl() : ""));
                    response.put("message", "Please complete registration by setting a password.");
                    return ResponseEntity.ok(response);
                }
            } else {
                String name = ghUser.name() != null && !ghUser.name().isBlank() ? ghUser.name() : ghUser.login();
                String[] nameParts = name.trim().split("\\s+", 2);
                String firstName = nameParts[0];
                String lastName = nameParts.length > 1 ? nameParts[1] : "";

                Map<String, Object> response = new HashMap<>();
                response.put("status", "NEEDS_REGISTRATION");
                response.put("registrationData", Map.of(
                        "email", email,
                        "firstName", firstName,
                        "lastName", lastName,
                        "avatarUrl", ghUser.avatarUrl() != null ? ghUser.avatarUrl() : "",
                        "githubUrl", ghUser.htmlUrl() != null ? ghUser.htmlUrl() : ""));
                response.put("message", "Please complete registration by setting a password.");
                return ResponseEntity.ok(response);
            }

            updateStreak(student);
            studentRepository.save(student);

            String jwt = jwtUtil.generateToken(student);

            refreshTokenService.deleteByUserId(student.getId());
            RefreshToken refreshToken = refreshTokenService.createRefreshToken(student.getId());

            try {
                notificationEventPublisher.publishNotificationEvent(
                        NotificationEvent.builder()
                                .studentId(student.getId())
                                .type("LOGIN")
                                .message("New login detected via GitHub.")
                                .build());
            } catch (Exception ignored) {
            }

            StudentDto dto = new StudentDto(
                    student.getId(),
                    student.getEmail(),
                    student.getFirstName(),
                    student.getLastName(),
                    student.getGender(),
                    jwt,
                    student.getSubscriptionStatus(),
                    student.getFreeActionsUsed(),
                    student.getTotalXp(),
                    student.getStreakDays(),
                    refreshToken.getToken());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "LOGIN_SUCCESS");
            response.put("token", jwt);
            response.put("refreshToken", refreshToken.getToken());
            response.put("student", dto);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("GitHub Auth failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "GitHub Authentication Failed: " + e.getMessage()));
        }
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        String requestRefreshToken = request.refreshToken();

        return refreshTokenService.findByToken(requestRefreshToken)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getStudent)
                .map(student -> {
                    String token = jwtUtil.generateToken(student);
                    return ResponseEntity.ok(new TokenRefreshResponse(token, requestRefreshToken));
                })
                .orElseThrow(() -> new TokenRefreshException(
                        requestRefreshToken,
                        "Refresh token is not in database!"));
    }

    private void updateStreak(Student student) {
        LocalDate today = LocalDate.now();

        if (student.getLastLoginDate() != null) {
            long daysBetween = ChronoUnit.DAYS.between(student.getLastLoginDate(), today);

            if (daysBetween == 1) {
                student.setStreakDays(student.getStreakDays() + 1);
            } else if (daysBetween > 1) {
                student.setStreakDays(1);
            }
        } else {
            student.setStreakDays(1);
        }

        student.setLastLoginDate(today);
    }

    public record RefreshTokenRequest(String refreshToken) {
    }

    public record TokenRefreshResponse(String accessToken, String refreshToken, String tokenType) {
        public TokenRefreshResponse(String accessToken, String refreshToken) {
            this(accessToken, refreshToken, "Bearer");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private String normalizeName(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private boolean hasNoPassword(Student student) {
        return student.getPassword() == null || student.getPassword().isBlank();
    }
}
