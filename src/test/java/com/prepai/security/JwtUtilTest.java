package com.prepai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    private static final String TEST_SECRET =
        Base64.getEncoder().encodeToString("prepai-unit-test-jwt-secret-key!".getBytes());
    private static final long EXPIRATION_MS = 900_000L;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "jwtExpirationMs", EXPIRATION_MS);
    }

    private UserDetails buildUserDetails(String email) {
        return User.builder()
            .username(email)
            .password("password")
            .authorities(List.of())
            .build();
    }

    @Test
    void generateToken_withUserIdClaim_producesValidToken() {
        UUID userId = UUID.randomUUID();
        UserDetails userDetails = buildUserDetails("test@example.com");

        String token = jwtUtil.generateToken(userDetails, userId);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    void extractUsername_returnsCorrectEmail() {
        UserDetails userDetails = buildUserDetails("user@test.com");
        String token = jwtUtil.generateToken(userDetails, UUID.randomUUID());

        String username = jwtUtil.extractUsername(token);

        assertThat(username).isEqualTo("user@test.com");
    }

    @Test
    void extractUserId_returnsCorrectUUID() {
        UUID userId = UUID.randomUUID();
        UserDetails userDetails = buildUserDetails("test@example.com");
        String token = jwtUtil.generateToken(userDetails, userId);

        UUID extractedId = jwtUtil.extractUserId(token);

        assertThat(extractedId).isEqualTo(userId);
    }

    @Test
    void extractUserId_tokenWithoutUserIdClaim_returnsNull() {
        UserDetails userDetails = buildUserDetails("test@example.com");
        String token = jwtUtil.generateToken(Map.of(), userDetails);

        UUID extractedId = jwtUtil.extractUserId(token);

        assertThat(extractedId).isNull();
    }

    @Test
    void isTokenValid_validTokenAndUser_returnsTrue() {
        UserDetails userDetails = buildUserDetails("test@example.com");
        String token = jwtUtil.generateToken(userDetails, UUID.randomUUID());

        boolean valid = jwtUtil.isTokenValid(token, userDetails);

        assertThat(valid).isTrue();
    }

    @Test
    void isTokenValid_wrongUser_returnsFalse() {
        UserDetails userDetails = buildUserDetails("test@example.com");
        UserDetails otherUser = buildUserDetails("other@example.com");
        String token = jwtUtil.generateToken(userDetails, UUID.randomUUID());

        boolean valid = jwtUtil.isTokenValid(token, otherUser);

        assertThat(valid).isFalse();
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtUtil, "jwtExpirationMs", -1000L);
        UserDetails userDetails = buildUserDetails("test@example.com");
        String token = jwtUtil.generateToken(userDetails, UUID.randomUUID());

        boolean valid = jwtUtil.isTokenValid(token, userDetails);

        assertThat(valid).isFalse();
    }

    @Test
    void extractClaim_customResolver_returnsValue() {
        UserDetails userDetails = buildUserDetails("claim@example.com");
        String token = jwtUtil.generateToken(userDetails, UUID.randomUUID());

        String subject = jwtUtil.extractClaim(token, claims -> claims.getSubject());

        assertThat(subject).isEqualTo("claim@example.com");
    }

    @Test
    void generateToken_withExtraClaims_includesClaimsInToken() {
        UserDetails userDetails = buildUserDetails("test@example.com");
        Map<String, Object> extraClaims = Map.of("role", "ADMIN", "userId", "123");

        String token = jwtUtil.generateToken(extraClaims, userDetails);
        String role = jwtUtil.extractClaim(token, claims -> claims.get("role", String.class));

        assertThat(role).isEqualTo("ADMIN");
    }

    @Test
    void invalidToken_throwsException() {
        assertThatThrownBy(() -> jwtUtil.extractUsername("invalid.token.here"))
            .isInstanceOf(Exception.class);
    }
}
