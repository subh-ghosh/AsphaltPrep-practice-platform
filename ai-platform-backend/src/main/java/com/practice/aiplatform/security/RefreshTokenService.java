package com.practice.aiplatform.security;

import com.practice.aiplatform.user.Student;
import com.practice.aiplatform.user.StudentRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    @Value("${jwt.refreshExpirationMs}")
    private Long refreshTokenDurationMs;

    private final RefreshTokenRepository refreshTokenRepository;
    private final StudentRepository studentRepository;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            StudentRepository studentRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.studentRepository = studentRepository;
    }

    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
    }

    @Transactional
    public RefreshToken createRefreshToken(Long userId) {
        Student student = studentRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        refreshTokenRepository.deleteByStudent(student);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setStudent(student);
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setExpiryDate(Instant.now().plusMillis(refreshTokenDurationMs));

        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verifyExpiration(RefreshToken token) {
        boolean expired = token.getExpiryDate().isBefore(Instant.now());

        if (expired) {
            refreshTokenRepository.delete(token);
            throw new TokenRefreshException(
                    token.getToken(),
                    "Refresh token was expired. Please sign in again.");
        }

        return token;
    }

    @Transactional
    public int deleteByUserId(Long userId) {
        Student student = studentRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Student not found"));

        return refreshTokenRepository.deleteByStudent(student);
    }
}