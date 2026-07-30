package com.practice.aiplatform.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import com.practice.aiplatform.event.NotificationEvent;
import com.practice.aiplatform.event.NotificationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record ProfileUpdateRequest(
        String firstName,
        String lastName,
        String gender,
        String bio,
        String headline,
        String avatarUrl,
        String githubUrl,
        String linkedinUrl,
        String websiteUrl) {
}

record ChangePasswordRequest(String oldPassword, String newPassword) {
}

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationEventPublisher notificationEventPublisher;
    private final StudentAccountService studentAccountService;
    private final MeterRegistry meterRegistry;
    private final Cache<String, StudentResponseDTO> localProfileCache;
    @Lazy
    @Autowired
    private StudentController self;

    public StudentController(
            StudentRepository studentRepository,
            PasswordEncoder passwordEncoder,
            NotificationEventPublisher notificationEventPublisher,
            StudentAccountService studentAccountService,
            MeterRegistry meterRegistry) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationEventPublisher = notificationEventPublisher;
        this.studentAccountService = studentAccountService;
        this.meterRegistry = meterRegistry;
        this.localProfileCache = Caffeine.newBuilder()
                .recordStats()
                .expireAfterWrite(Duration.ofSeconds(15))
                .maximumSize(2000)
                .build();
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        String email = principal.getName();
        StudentResponseDTO cached = localProfileCache.getIfPresent(email);
        if (cached != null) {
            recordL1("UserProfileLocalCache", "hit");
            return ResponseEntity.ok(cached);
        }
        recordL1("UserProfileLocalCache", "miss");

        StudentResponseDTO value = self.getProfileCached(email);
        localProfileCache.put(email, value);
        return ResponseEntity.ok(value);
    }

    @Cacheable(value = "UserProfileCache", key = "#email", sync = true)
    public StudentResponseDTO getProfileCached(String email) {
        Student student = studentRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Student not found"));
        return StudentResponseDTO.fromEntity(student);
    }

    @PutMapping("/profile")
    @Caching(evict = {
            @CacheEvict(value = "UserProfileCache", key = "#principal.name"),
            @CacheEvict(value = "LeaderboardCache", allEntries = true)
    })
    public ResponseEntity<?> updateProfile(@RequestBody ProfileUpdateRequest req, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        Student student = studentRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new RuntimeException("Student not found"));

        if (req.firstName() != null)
            student.setFirstName(req.firstName().trim());
        if (req.lastName() != null)
            student.setLastName(req.lastName().trim());
        if (req.gender() != null)
            student.setGender(req.gender().trim().toLowerCase());
        if (req.bio() != null)
            student.setBio(req.bio().trim());
        if (req.headline() != null)
            student.setHeadline(req.headline().trim());
        if (req.avatarUrl() != null)
            student.setAvatarUrl(req.avatarUrl().trim());
        if (req.githubUrl() != null)
            student.setGithubUrl(req.githubUrl().trim());
        if (req.linkedinUrl() != null)
            student.setLinkedinUrl(req.linkedinUrl().trim());
        if (req.websiteUrl() != null)
            student.setWebsiteUrl(req.websiteUrl().trim());

        studentRepository.save(student);
        localProfileCache.invalidate(principal.getName());

        notificationEventPublisher.publishNotificationEvent(
                NotificationEvent.builder()
                        .studentId(student.getId())
                        .type("PROFILE_UPDATED")
                        .message("Your profile was updated.")
                        .build());

        return ResponseEntity.ok(StudentResponseDTO.fromEntity(student));
    }

    @PutMapping("/password")
    @Caching(evict = {
            @CacheEvict(value = "UserProfileCache", key = "#principal.name"),
            @CacheEvict(value = "UserUsageRemainingCache", key = "#principal.name")
    })
    public ResponseEntity<String> changePassword(@RequestBody ChangePasswordRequest req, Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        Student student = studentRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new RuntimeException("Student not found"));

        if (req.oldPassword() == null || req.newPassword() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Old and new passwords are required.");
        }

        boolean matches = passwordEncoder.matches(req.oldPassword(), student.getPassword());
        if (!matches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Old password is incorrect.");
        }

        student.setPassword(passwordEncoder.encode(req.newPassword()));
        studentRepository.save(student);
        localProfileCache.invalidate(principal.getName());

        return ResponseEntity.ok("Password changed successfully.");
    }

    @DeleteMapping("/account")
    @Caching(evict = {
            @CacheEvict(value = "UserProfileCache", key = "#principal.name"),
            @CacheEvict(value = "LeaderboardCache", allEntries = true),
            @CacheEvict(value = "UserUsageRemainingCache", key = "#principal.name")
    })
    public ResponseEntity<?> deleteAccount(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        studentAccountService.deleteAccountByEmail(principal.getName());
        localProfileCache.invalidate(principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<List<StudentResponseDTO>> getLeaderboard() {
        return ResponseEntity.ok(self.getLeaderboardCached());
    }

    @GetMapping("/me/rank")
    public ResponseEntity<?> getMyRank(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        Student student = studentRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new RuntimeException("Student not found"));

        long rank = studentRepository.calculateRankByStudentId(student.getId());
        long totalStudents = studentRepository.count();

        Map<String, Object> result = new HashMap<>();
        result.put("rank", rank);
        result.put("totalStudents", totalStudents);
        result.put("totalXp", student.getTotalXp());
        result.put("streakDays", student.getStreakDays());

        return ResponseEntity.ok(result);
    }

    @Cacheable(value = "LeaderboardCache", key = "'top10'", sync = true)
    public List<StudentResponseDTO> getLeaderboardCached() {
        List<Student> topStudents = studentRepository.findTop10ByOrderByTotalXpDesc();

        List<StudentResponseDTO> dtos = new ArrayList<>();
        for (Student student : topStudents) {
            dtos.add(StudentResponseDTO.fromEntity(student));
        }

        return dtos;
    }

    private void recordL1(String cacheName, String result) {
        meterRegistry.counter(
                "cache_layer_access_total",
                "cache", cacheName,
                "layer", "l1",
                "result", result).increment();
    }
}
