package com.prepai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.config.SecurityConfig;
import com.prepai.config.TestSecurityConfig;
import com.prepai.dto.AuthDtos.*;
import com.prepai.dto.UserResponse;
import com.prepai.exception.AppException;
import com.prepai.model.User;
import com.prepai.security.CustomOAuth2UserService;
import com.prepai.security.CustomUserDetailsService;
import com.prepai.security.JwtAuthenticationFilter;
import com.prepai.security.OAuth2AuthenticationFailureHandler;
import com.prepai.security.OAuth2AuthenticationSuccessHandler;
import com.prepai.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    value = AuthController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
                   CustomUserDetailsService.class, CustomOAuth2UserService.class,
                   OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class}
    )
)
@Import(TestSecurityConfig.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AuthService authService;

    private AuthResponse buildAuthResponse() {
        UserResponse userResp = UserResponse.builder()
            .id(UUID.randomUUID())
            .name("Test User")
            .email("test@example.com")
            .plan(User.Plan.FREE)
            .credits(5)
            .build();

        return AuthResponse.builder()
            .accessToken("access-token-123")
            .refreshToken("refresh-token-123")
            .user(userResp)
            .build();
    }

    @Test
    void register_validRequest_returns201() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setName("Test User");
        req.setEmail("test@example.com");
        req.setPassword("password123");

        when(authService.register(any(RegisterRequest.class))).thenReturn(buildAuthResponse());

        mockMvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token-123"))
            .andExpect(jsonPath("$.data.user.email").value("test@example.com"));
    }

    @Test
    void register_emailAlreadyExists_returns409() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setName("Test User");
        req.setEmail("taken@example.com");
        req.setPassword("password123");

        when(authService.register(any(RegisterRequest.class)))
            .thenThrow(new AppException("Email already registered", 409));

        mockMvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setName("Test User");
        req.setEmail("not-an-email");
        req.setPassword("password123");

        mockMvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setName("Test");
        req.setEmail("test@example.com");
        req.setPassword("short");

        mockMvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void login_validCredentials_returns200() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("password123");

        when(authService.login(any(LoginRequest.class))).thenReturn(buildAuthResponse());

        mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token-123"));
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("wrong");

        when(authService.login(any(LoginRequest.class)))
            .thenThrow(new AppException("Invalid email or password", 401));

        mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void login_unverifiedEmail_returns403() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("unverified@example.com");
        req.setPassword("password");

        when(authService.login(any(LoginRequest.class)))
            .thenThrow(new AppException("Email not verified. Please check your inbox.", 403));

        mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Email not verified. Please check your inbox."));
    }

    @Test
    void refresh_validToken_returns200() throws Exception {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("valid-refresh-token");

        when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(buildAuthResponse());

        mockMvc.perform(post("/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("access-token-123"));
    }

    @Test
    void refresh_expiredToken_returns401() throws Exception {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("expired-refresh-token");

        when(authService.refreshToken(any(RefreshTokenRequest.class)))
            .thenThrow(new AppException("Refresh token expired. Please log in again.", 401));

        mockMvc.perform(post("/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void verify_validToken_returns200() throws Exception {
        doNothing().when(authService).verifyEmail("valid-token");

        mockMvc.perform(get("/v1/auth/verify").param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Email verified successfully."));
    }

    @Test
    void verify_invalidToken_returns400() throws Exception {
        doThrow(new AppException("Invalid verification token", 400))
            .when(authService).verifyEmail("bad-token");

        mockMvc.perform(get("/v1/auth/verify").param("token", "bad-token"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPassword_validEmail_returns200() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("test@example.com");

        doNothing().when(authService).forgotPassword(any(ForgotPasswordRequest.class));

        mockMvc.perform(post("/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("If that email exists, a reset link has been sent."));
    }

    @Test
    void resetPassword_validRequest_returns200() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("valid-reset-token");
        req.setNewPassword("newpassword123");

        doNothing().when(authService).resetPassword(any(ResetPasswordRequest.class));

        mockMvc.perform(post("/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Password reset successfully."));
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setToken("bad-token");
        req.setNewPassword("newpassword123");

        doThrow(new AppException("Invalid or expired reset token", 400))
            .when(authService).resetPassword(any(ResetPasswordRequest.class));

        mockMvc.perform(post("/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }
}
