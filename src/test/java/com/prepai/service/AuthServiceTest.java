package com.prepai.service;

import com.prepai.dto.AuthDtos.*;
import com.prepai.exception.AppException;
import com.prepai.model.RefreshToken;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.prepai.security.CustomUserDetailsService;
import com.prepai.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthenticationManager authManager;
    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private EmailService emailService;
    @InjectMocks private AuthService authService;

    private User buildUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .name("Test User")
            .password("encoded-pass")
            .emailVerified(true)
            .credits(5)
            .plan(User.Plan.FREE)
            .build();
    }

    private UserDetails buildUserDetails() {
        return org.springframework.security.core.userdetails.User.builder()
            .username("test@example.com")
            .password("encoded-pass")
            .authorities(List.of())
            .build();
    }

    private RefreshToken buildRefreshToken(User user) {
        return RefreshToken.builder()
            .user(user)
            .token("refresh-token-value")
            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();
    }

    @Test
    void register_success_returnsAuthResponse() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@example.com");
        req.setName("New User");
        req.setPassword("password123");

        User savedUser = User.builder()
            .id(UUID.randomUUID())
            .email("new@example.com")
            .name("New User")
            .password("encoded")
            .emailVerified(false)
            .credits(5)
            .plan(User.Plan.FREE)
            .build();

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(userDetailsService.loadUserByUsername("new@example.com")).thenReturn(buildUserDetails());
        when(jwtUtil.generateToken(any(UserDetails.class), any(UUID.class))).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(any(UUID.class))).thenReturn(buildRefreshToken(savedUser));
        doNothing().when(emailService).sendVerificationEmail(any(), any(), any());

        AuthResponse response = authService.register(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token-value");
        assertThat(response.getUser().getEmail()).isEqualTo("new@example.com");
        verify(emailService).sendVerificationEmail(eq("new@example.com"), eq("New User"), anyString());
    }

    @Test
    void register_emailAlreadyExists_throwsAppException() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("existing@example.com");
        req.setName("User");
        req.setPassword("password123");

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("already registered")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(409));
    }

    @Test
    void login_success_updatesLastLoginAndReturnsToken() {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("password");

        User user = buildUser();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(buildUserDetails());
        when(jwtUtil.generateToken(any(UserDetails.class), any(UUID.class))).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(any(UUID.class))).thenReturn(buildRefreshToken(user));

        AuthResponse response = authService.login(req);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getLastLoginAt()).isNotNull();
    }

    @Test
    void login_badCredentials_throwsAppException() {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("wrong-password");

        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("bad creds"));

        assertThatThrownBy(() -> authService.login(req))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Invalid email or password")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(401));
    }

    @Test
    void login_disabledUser_throwsAppException() {
        LoginRequest req = new LoginRequest();
        req.setEmail("unverified@example.com");
        req.setPassword("password");

        when(authManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(req))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Email not verified")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(403));
    }

    @Test
    void refreshToken_valid_returnsNewTokens() {
        User user = buildUser();
        RefreshToken refreshToken = buildRefreshToken(user);

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("old-refresh-token");

        when(refreshTokenService.validateAndGet("old-refresh-token")).thenReturn(refreshToken);
        when(userDetailsService.loadUserByUsername(user.getEmail())).thenReturn(buildUserDetails());
        when(jwtUtil.generateToken(any(UserDetails.class), any(UUID.class))).thenReturn("new-access-token");
        when(refreshTokenService.createRefreshToken(user.getId())).thenReturn(
            buildRefreshToken(user));

        AuthResponse response = authService.refreshToken(req);

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        verify(refreshTokenService).deleteToken(refreshToken);
    }

    @Test
    void refreshToken_expired_throwsAppException() {
        User user = buildUser();
        RefreshToken expiredToken = RefreshToken.builder()
            .user(user)
            .token("expired-token")
            .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .build();

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("expired-token");

        when(refreshTokenService.validateAndGet("expired-token")).thenReturn(expiredToken);

        assertThatThrownBy(() -> authService.refreshToken(req))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("expired")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(401));

        verify(refreshTokenService).deleteToken(expiredToken);
    }

    @Test
    void verifyEmail_validToken_setsEmailVerifiedAndClearsToken() {
        User user = buildUser();
        user.setEmailVerified(false);
        user.setVerificationToken("valid-verify-token");

        when(userRepository.findByVerificationToken("valid-verify-token"))
            .thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        authService.verifyEmail("valid-verify-token");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmailVerified()).isTrue();
        assertThat(saved.getVerificationToken()).isNull();
    }

    @Test
    void verifyEmail_invalidToken_throwsAppException() {
        when(userRepository.findByVerificationToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyEmail("bad-token"))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(400));
    }

    @Test
    void forgotPassword_userExists_setsResetTokenAndSendsEmail() {
        User user = buildUser();
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("test@example.com");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);
        doNothing().when(emailService).sendPasswordResetEmail(any(), any(), any());

        authService.forgotPassword(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getResetToken()).isNotNull();
        assertThat(saved.getResetTokenExpiresAt()).isNotNull();
        verify(emailService).sendPasswordResetEmail(eq("test@example.com"), eq("Test User"), anyString());
    }

    @Test
    void forgotPassword_userNotFound_doesNothing() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("notfound@example.com");

        when(userRepository.findByEmail("notfound@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(req);

        verifyNoInteractions(emailService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_validToken_updatesPassword() {
        User user = buildUser();
        user.setResetToken("reset-token");
        user.setResetTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));

        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("reset-token");
        req.setNewPassword("newSecurePassword");

        when(userRepository.findByResetToken("reset-token")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newSecurePassword")).thenReturn("new-encoded");
        when(userRepository.save(any(User.class))).thenReturn(user);

        authService.resetPassword(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getPassword()).isEqualTo("new-encoded");
        assertThat(saved.getResetToken()).isNull();
        assertThat(saved.getResetTokenExpiresAt()).isNull();
    }

    @Test
    void resetPassword_invalidToken_throwsAppException() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("bad-reset-token");
        req.setNewPassword("newpass123");

        when(userRepository.findByResetToken("bad-reset-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(req))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(400));
    }

    @Test
    void resetPassword_expiredToken_throwsAppException() {
        User user = buildUser();
        user.setResetToken("expired-reset-token");
        user.setResetTokenExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("expired-reset-token");
        req.setNewPassword("newpass123");

        when(userRepository.findByResetToken("expired-reset-token")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resetPassword(req))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("expired")
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(400));
    }
}
