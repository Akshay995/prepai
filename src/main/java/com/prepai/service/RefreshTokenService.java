package com.prepai.service;

import com.prepai.exception.AppException;
import com.prepai.model.RefreshToken;
import com.prepai.repository.RefreshTokenRepository;
import com.prepai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepo;
    private final UserRepository userRepo;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Transactional
    public RefreshToken createRefreshToken(UUID userId) {
        var user = userRepo.findById(userId).orElseThrow();
        refreshTokenRepo.deleteByUserId(userId); // rotate tokens

        RefreshToken token = RefreshToken.builder()
            .user(user)
            .token(UUID.randomUUID().toString())
            .expiresAt(Instant.now().plusMillis(refreshExpirationMs))
            .build();
        return refreshTokenRepo.save(token);
    }

    public RefreshToken validateAndGet(String token) {
        return refreshTokenRepo.findByToken(token)
            .orElseThrow(() -> new AppException("Invalid refresh token", 401));
    }

    @Transactional
    public void deleteToken(RefreshToken token) {
        refreshTokenRepo.delete(token);
    }

    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        refreshTokenRepo.deleteByUserId(userId);
    }

    @Scheduled(cron = "0 0 3 * * *") // 3 AM daily
    @Transactional
    public void cleanExpiredTokens() {
        refreshTokenRepo.deleteAllExpired(Instant.now());
    }
}
