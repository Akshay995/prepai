package com.prepai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.config.SecurityConfig;
import com.prepai.config.TestSecurityConfig;
import com.prepai.dto.InterviewDtos.SessionListResponse;
import com.prepai.dto.PaymentDtos.*;
import com.prepai.exception.AppException;
import com.prepai.model.InterviewSession;
import com.prepai.model.User;
import com.prepai.repository.InterviewSessionRepository;
import com.prepai.repository.UserRepository;
import com.prepai.security.CustomOAuth2UserService;
import com.prepai.security.CustomUserDetailsService;
import com.prepai.security.JwtAuthenticationFilter;
import com.prepai.security.OAuth2AuthenticationFailureHandler;
import com.prepai.security.OAuth2AuthenticationSuccessHandler;
import com.prepai.service.StripeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
                   CustomUserDetailsService.class, CustomOAuth2UserService.class,
                   OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class,
                   AuthController.class, InterviewController.class}
    )
)
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private UserRepository userRepo;
    @MockBean private InterviewSessionRepository sessionRepo;
    @MockBean private StripeService stripeService;

    private static final String USER_EMAIL = "test@example.com";

    private User buildUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email(USER_EMAIL)
            .name("Test User")
            .plan(User.Plan.FREE)
            .credits(5)
            .emailVerified(true)
            .build();
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void getMe_authenticatedUser_returns200() throws Exception {
        User user = buildUser();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepo.countCompletedByUserId(user.getId())).thenReturn(3L);
        when(sessionRepo.avgScoreByUserId(user.getId())).thenReturn(82.5);

        mockMvc.perform(get("/v1/user/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value(USER_EMAIL))
            .andExpect(jsonPath("$.data.name").value("Test User"))
            .andExpect(jsonPath("$.data.plan").value("FREE"))
            .andExpect(jsonPath("$.data.credits").value(5));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void getDashboard_returns200WithStats() throws Exception {
        User user = buildUser();
        UUID uid = user.getId();

        SessionListResponse recent = SessionListResponse.builder()
            .id(UUID.randomUUID())
            .role("Engineer")
            .type(InterviewSession.InterviewType.BEHAVIORAL)
            .status(InterviewSession.Status.COMPLETED)
            .score(85)
            .grade("Excellent")
            .createdAt(Instant.now())
            .build();

        InterviewSession recentSession = InterviewSession.builder()
            .id(UUID.randomUUID())
            .user(user)
            .role("Engineer")
            .type(InterviewSession.InterviewType.BEHAVIORAL)
            .difficulty(InterviewSession.Difficulty.MID_LEVEL)
            .status(InterviewSession.Status.COMPLETED)
            .score(85)
            .grade("Excellent")
            .questionCount(5)
            .createdAt(Instant.now())
            .build();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepo.count()).thenReturn(10L);
        when(sessionRepo.countCompletedByUserId(uid)).thenReturn(7L);
        when(sessionRepo.avgScoreByUserId(uid)).thenReturn(78.3);
        when(sessionRepo.findTop5ByUserIdOrderByCreatedAtDesc(uid))
            .thenReturn(List.of(recentSession));

        mockMvc.perform(get("/v1/user/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalSessions").value(10))
            .andExpect(jsonPath("$.data.completedSessions").value(7))
            .andExpect(jsonPath("$.data.avgScore").value(78.3))
            .andExpect(jsonPath("$.data.recentSessions").isArray())
            .andExpect(jsonPath("$.data.recentSessions[0].role").value("Engineer"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createCheckout_validRequest_returns200WithCheckoutUrl() throws Exception {
        User user = buildUser();
        CreateCheckoutRequest req = new CreateCheckoutRequest();
        req.setPriceId("price_pro_monthly_test");

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(stripeService.createCheckoutSession(any(UUID.class), eq("price_pro_monthly_test"),
                anyString(), anyString()))
            .thenReturn("https://checkout.stripe.com/session123");

        mockMvc.perform(post("/v1/payment/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.checkoutUrl").value("https://checkout.stripe.com/session123"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createCheckout_withCustomUrls_usesProvidedUrls() throws Exception {
        User user = buildUser();
        CreateCheckoutRequest req = new CreateCheckoutRequest();
        req.setPriceId("price_pro_monthly_test");
        req.setSuccessUrl("https://myapp.com/success");
        req.setCancelUrl("https://myapp.com/cancel");

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(stripeService.createCheckoutSession(any(UUID.class), anyString(),
                eq("https://myapp.com/success"), eq("https://myapp.com/cancel")))
            .thenReturn("https://checkout.stripe.com/session456");

        mockMvc.perform(post("/v1/payment/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checkoutUrl").value("https://checkout.stripe.com/session456"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createCheckout_stripeError_returns500() throws Exception {
        User user = buildUser();
        CreateCheckoutRequest req = new CreateCheckoutRequest();
        req.setPriceId("price_pro_monthly_test");

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(stripeService.createCheckoutSession(any(), any(), any(), any()))
            .thenThrow(new AppException("Payment initialization failed", 500));

        mockMvc.perform(post("/v1/payment/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void webhook_validPayload_returns200() throws Exception {
        doNothing().when(stripeService).handleWebhook(anyString(), anyString());

        mockMvc.perform(post("/v1/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=123,v1=abc")
                .content("{\"type\":\"checkout.session.completed\"}"))
            .andExpect(status().isOk());

        verify(stripeService).handleWebhook(anyString(), eq("t=123,v1=abc"));
    }

    @Test
    void webhook_invalidSignature_returns400() throws Exception {
        doThrow(new AppException("Invalid webhook signature", 400))
            .when(stripeService).handleWebhook(anyString(), anyString());

        mockMvc.perform(post("/v1/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "invalid-sig")
                .content("{\"type\":\"checkout.session.completed\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void getMe_nullAvgScore_handledGracefully() throws Exception {
        User user = buildUser();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepo.countCompletedByUserId(user.getId())).thenReturn(0L);
        when(sessionRepo.avgScoreByUserId(user.getId())).thenReturn(null);

        mockMvc.perform(get("/v1/user/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalSessions").value(0));
    }
}
