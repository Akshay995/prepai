package com.prepai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.config.SecurityConfig;
import com.prepai.config.TestSecurityConfig;
import com.prepai.dto.InterviewDtos.*;
import com.prepai.exception.AppException;
import com.prepai.model.InterviewSession;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.prepai.security.CustomOAuth2UserService;
import com.prepai.security.CustomUserDetailsService;
import com.prepai.security.JwtAuthenticationFilter;
import com.prepai.security.OAuth2AuthenticationFailureHandler;
import com.prepai.security.OAuth2AuthenticationSuccessHandler;
import com.prepai.service.InterviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    value = InterviewController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {SecurityConfig.class, JwtAuthenticationFilter.class,
                   CustomUserDetailsService.class, CustomOAuth2UserService.class,
                   OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class}
    )
)
@Import(TestSecurityConfig.class)
class InterviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private InterviewService interviewService;
    @MockBean private UserRepository userRepo;

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

    private SessionResponse buildSessionResponse() {
        return SessionResponse.builder()
            .id(UUID.randomUUID())
            .role("Software Engineer")
            .company("Acme Corp")
            .type(InterviewSession.InterviewType.BEHAVIORAL)
            .difficulty(InterviewSession.Difficulty.MID_LEVEL)
            .status(InterviewSession.Status.SETUP)
            .questionCount(0)
            .createdAt(Instant.now())
            .build();
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createSession_validRequest_returns201() throws Exception {
        User user = buildUser();
        SessionResponse sessionResponse = buildSessionResponse();
        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Software Engineer");
        req.setType(InterviewSession.InterviewType.BEHAVIORAL);
        req.setDifficulty(InterviewSession.Difficulty.MID_LEVEL);

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.createSession(any(UUID.class), any(CreateSessionRequest.class)))
            .thenReturn(sessionResponse);

        mockMvc.perform(post("/v1/interview/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.role").value("Software Engineer"))
            .andExpect(jsonPath("$.data.status").value("SETUP"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createSession_noCredits_returns402() throws Exception {
        User user = buildUser();
        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Engineer");
        req.setType(InterviewSession.InterviewType.TECHNICAL);
        req.setDifficulty(InterviewSession.Difficulty.SENIOR);

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.createSession(any(UUID.class), any(CreateSessionRequest.class)))
            .thenThrow(new AppException("No credits remaining. Please upgrade your plan.", 402));

        mockMvc.perform(post("/v1/interview/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void listSessions_returns200WithPaginatedResults() throws Exception {
        User user = buildUser();
        SessionListResponse item = SessionListResponse.builder()
            .id(UUID.randomUUID())
            .role("Engineer")
            .status(InterviewSession.Status.COMPLETED)
            .createdAt(Instant.now())
            .build();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.getUserSessions(any(UUID.class), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/v1/interview/sessions")
                .param("page", "0")
                .param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content[0].role").value("Engineer"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void getSession_existingSession_returns200() throws Exception {
        User user = buildUser();
        UUID sessionId = UUID.randomUUID();
        SessionResponse sessionResponse = buildSessionResponse();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.getSession(eq(sessionId), any(UUID.class)))
            .thenReturn(sessionResponse);

        mockMvc.perform(get("/v1/interview/sessions/{id}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.role").value("Software Engineer"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void getSession_notFound_returns404() throws Exception {
        User user = buildUser();
        UUID sessionId = UUID.randomUUID();

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.getSession(eq(sessionId), any(UUID.class)))
            .thenThrow(new AppException("Session not found", 404));

        mockMvc.perform(get("/v1/interview/sessions/{id}", sessionId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void endSession_success_returns200() throws Exception {
        User user = buildUser();
        UUID sessionId = UUID.randomUUID();
        SessionResponse sessionResponse = buildSessionResponse();
        sessionResponse.setStatus(InterviewSession.Status.COMPLETED);

        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(interviewService.endSession(eq(sessionId), any(UUID.class)))
            .thenReturn(Mono.just(sessionResponse));

        mockMvc.perform(post("/v1/interview/sessions/{id}/end", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createSession_missingRole_returns400() throws Exception {
        CreateSessionRequest req = new CreateSessionRequest();
        req.setType(InterviewSession.InterviewType.BEHAVIORAL);
        req.setDifficulty(InterviewSession.Difficulty.MID_LEVEL);
        // role is not set (null / blank)

        User user = buildUser();
        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/v1/interview/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void createSession_userNotFound_returns404() throws Exception {
        when(userRepo.findByEmail(USER_EMAIL)).thenReturn(Optional.empty());

        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Engineer");
        req.setType(InterviewSession.InterviewType.BEHAVIORAL);
        req.setDifficulty(InterviewSession.Difficulty.MID_LEVEL);

        mockMvc.perform(post("/v1/interview/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound());
    }
}
