package com.prepai.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    @Test
    void isExpired_futureExpiry_returnsFalse() {
        RefreshToken token = RefreshToken.builder()
            .token("test-token")
            .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .build();

        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isExpired_pastExpiry_returnsTrue() {
        RefreshToken token = RefreshToken.builder()
            .token("test-token")
            .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .build();

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_justExpired_returnsTrue() {
        RefreshToken token = RefreshToken.builder()
            .token("test-token")
            .expiresAt(Instant.now().minus(1, ChronoUnit.SECONDS))
            .build();

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void refreshTokenBuilder_setsFields() {
        User user = User.builder().email("u@test.com").name("U").build();
        Instant expiry = Instant.now().plus(7, ChronoUnit.DAYS);

        RefreshToken token = RefreshToken.builder()
            .user(user)
            .token("abc-123")
            .expiresAt(expiry)
            .build();

        assertThat(token.getToken()).isEqualTo("abc-123");
        assertThat(token.getUser()).isEqualTo(user);
        assertThat(token.getExpiresAt()).isEqualTo(expiry);
    }
}
