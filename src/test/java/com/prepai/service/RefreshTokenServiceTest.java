package com.prepai.service;

import com.prepai.exception.AppException;
import com.prepai.model.RefreshToken;
import com.prepai.model.User;
import com.prepai.repository.RefreshTokenRepository;
import com.prepai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepo;
    @Mock private UserRepository userRepo;
    @InjectMocks private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "refreshExpirationMs", 604_800_000L);
    }

    private User buildUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .name("Test")
            .emailVerified(true)
            .build();
    }

    @Test
    void createRefreshToken_success_deletesOldAndCreatesNew() {
        UUID userId = UUID.randomUUID();
        User user = buildUser();

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        doNothing().when(refreshTokenRepo).deleteByUserId(userId);
        when(refreshTokenRepo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        RefreshToken result = service.createRefreshToken(userId);

        verify(refreshTokenRepo).deleteByUserId(userId);
        assertThat(result.getToken()).isNotBlank();
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void createRefreshToken_expiryIsCorrect() {
        UUID userId = UUID.randomUUID();
        User user = buildUser();

        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenRepo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken result = service.createRefreshToken(userId);

        Instant expectedExpiry = Instant.now().plusMillis(604_800_000L);
        assertThat(result.getExpiresAt())
            .isBetween(expectedExpiry.minus(5, ChronoUnit.SECONDS),
                       expectedExpiry.plus(5, ChronoUnit.SECONDS));
    }

    @Test
    void validateAndGet_existingToken_returnsToken() {
        RefreshToken token = RefreshToken.builder()
            .token("valid-token")
            .user(buildUser())
            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

        when(refreshTokenRepo.findByToken("valid-token")).thenReturn(Optional.of(token));

        RefreshToken result = service.validateAndGet("valid-token");

        assertThat(result).isEqualTo(token);
    }

    @Test
    void validateAndGet_notFound_throwsAppException() {
        when(refreshTokenRepo.findByToken("invalid-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndGet("invalid-token"))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Invalid refresh token")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void deleteToken_callsRepository() {
        RefreshToken token = RefreshToken.builder()
            .token("token-to-delete")
            .user(buildUser())
            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

        service.deleteToken(token);

        verify(refreshTokenRepo).delete(token);
    }

    @Test
    void revokeAllUserTokens_deletesAllForUser() {
        UUID userId = UUID.randomUUID();
        doNothing().when(refreshTokenRepo).deleteByUserId(userId);

        service.revokeAllUserTokens(userId);

        verify(refreshTokenRepo).deleteByUserId(userId);
    }

    @Test
    void cleanExpiredTokens_deletesExpiredTokens() {
        doNothing().when(refreshTokenRepo).deleteAllExpired(any(Instant.class));

        service.cleanExpiredTokens();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepo).deleteAllExpired(captor.capture());
        assertThat(captor.getValue()).isBefore(Instant.now().plusSeconds(1));
    }
}
