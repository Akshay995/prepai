package com.prepai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.dto.InterviewDtos.*;
import com.prepai.exception.AppException;
import com.prepai.model.*;
import com.prepai.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterviewServiceTest {

    @Mock private InterviewSessionRepository sessionRepo;
    @Mock private InterviewMessageRepository messageRepo;
    @Mock private UserRepository userRepo;
    @Mock private AnthropicService anthropicService;

    private InterviewService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new InterviewService(sessionRepo, messageRepo, userRepo, anthropicService, objectMapper);
    }

    private User buildUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .name("Test User")
            .plan(User.Plan.FREE)
            .credits(5)
            .emailVerified(true)
            .build();
    }

    private InterviewSession buildSession(User user, InterviewSession.Status status) {
        return InterviewSession.builder()
            .id(UUID.randomUUID())
            .user(user)
            .role("Software Engineer")
            .company("Acme Corp")
            .type(InterviewSession.InterviewType.BEHAVIORAL)
            .difficulty(InterviewSession.Difficulty.MID_LEVEL)
            .status(status)
            .questionCount(0)
            .build();
    }

    @Test
    void createSession_success_returnsSessionResponse() {
        User user = buildUser();
        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Software Engineer");
        req.setCompany("Acme Corp");
        req.setType(InterviewSession.InterviewType.BEHAVIORAL);
        req.setDifficulty(InterviewSession.Difficulty.MID_LEVEL);

        InterviewSession savedSession = buildSession(user, InterviewSession.Status.SETUP);

        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(savedSession);

        SessionResponse response = service.createSession(user.getId(), req);

        assertThat(response.getId()).isEqualTo(savedSession.getId());
        assertThat(response.getRole()).isEqualTo("Software Engineer");
        assertThat(response.getStatus()).isEqualTo(InterviewSession.Status.SETUP);
    }

    @Test
    void createSession_userNotFound_throwsAppException() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Developer");
        req.setType(InterviewSession.InterviewType.TECHNICAL);
        req.setDifficulty(InterviewSession.Difficulty.SENIOR);

        assertThatThrownBy(() -> service.createSession(userId, req))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(404));
    }

    @Test
    void createSession_noCredits_throwsAppException() {
        User user = buildUser();
        user.setCredits(0);

        when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

        CreateSessionRequest req = new CreateSessionRequest();
        req.setRole("Developer");
        req.setType(InterviewSession.InterviewType.TECHNICAL);
        req.setDifficulty(InterviewSession.Difficulty.MID_LEVEL);

        assertThatThrownBy(() -> service.createSession(user.getId(), req))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(402));
    }

    @Test
    void startSession_success_streamsQuestion() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.SETUP);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId())).thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(anthropicService.buildInterviewerPrompt(any(), anyInt(), anyInt()))
            .thenReturn("System prompt");
        when(anthropicService.streamComplete(anyString(), anyList()))
            .thenReturn(Flux.just("Hello", " there", "!"));
        when(messageRepo.save(any(InterviewMessage.class))).thenReturn(
            InterviewMessage.builder().role("assistant").content("Hello there!").build());

        Flux<String> result = service.startSession(session.getId(), user.getId());

        StepVerifier.create(result)
            .expectNext("Hello")
            .expectNext(" there")
            .expectNext("!")
            .verifyComplete();

        assertThat(session.getStatus()).isEqualTo(InterviewSession.Status.IN_PROGRESS);
        assertThat(session.getStartedAt()).isNotNull();
    }

    @Test
    void startSession_sessionNotFound_throwsAppException() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(sessionRepo.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startSession(sessionId, userId))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(404));
    }

    @Test
    void sendMessage_sessionNotActive_throwsAppException() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.COMPLETED);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));

        assertThatThrownBy(() ->
            service.sendMessage(session.getId(), user.getId(), "My answer"))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(400));
    }

    @Test
    void sendMessage_maxQuestionsReached_returnsSessionComplete() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);
        session.setQuestionCount(5);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(messageRepo.save(any(InterviewMessage.class))).thenReturn(
            InterviewMessage.builder().role("user").content("My answer").build());
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);

        Flux<String> result = service.sendMessage(session.getId(), user.getId(), "My answer");

        StepVerifier.create(result)
            .expectNext("[SESSION_COMPLETE]")
            .verifyComplete();
    }

    @Test
    void sendMessage_withinLimit_streamsNextQuestion() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);
        session.setQuestionCount(2);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(messageRepo.save(any(InterviewMessage.class))).thenReturn(
            InterviewMessage.builder().role("user").content("answer").build());
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of());
        when(anthropicService.buildInterviewerPrompt(any(), anyInt(), anyInt()))
            .thenReturn("prompt");
        when(anthropicService.streamComplete(anyString(), anyList()))
            .thenReturn(Flux.just("Next question?"));

        Flux<String> result = service.sendMessage(session.getId(), user.getId(), "answer");

        StepVerifier.create(result)
            .expectNext("Next question?")
            .verifyComplete();
    }

    @Test
    void sendMessage_countsFillerWords() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);
        session.setQuestionCount(5); // next is 6 → SESSION_COMPLETE

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);

        ArgumentCaptor<InterviewMessage> captor = ArgumentCaptor.forClass(InterviewMessage.class);
        when(messageRepo.save(captor.capture())).thenReturn(
            InterviewMessage.builder().role("user").content("um").build());

        service.sendMessage(session.getId(), user.getId(), "um like you know basically").blockFirst();

        InterviewMessage saved = captor.getValue();
        assertThat(saved.getFillerCount()).isGreaterThan(0);
    }

    @Test
    void endSession_success_setsStatusAndDuration() {
        User user = buildUser();
        user.setPlan(User.Plan.PRO);
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);
        session.setStartedAt(Instant.now().minus(10, ChronoUnit.MINUTES));

        String feedbackJson = """
            {"score": 85, "grade": "Excellent", "headline": "Good",
             "summary": "Excellent performance",
             "metrics": {"starScore": "4.0/5", "speakingPace": 150, "fillerRate": "2.5",
                         "vocalConfidence": 80, "specificity": 75},
             "strengths": ["Clear"], "improvements": ["Detail"], "nextSteps": ["Practice"],
             "perAnswer": []}
            """;

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of(
                InterviewMessage.builder().role("user").content("My answer").build()
            ));
        when(anthropicService.buildFeedbackPrompt(any(), anyList())).thenReturn("prompt");
        when(anthropicService.complete(anyString(), anyList()))
            .thenReturn(Mono.just(feedbackJson));

        Mono<SessionResponse> result = service.endSession(session.getId(), user.getId());

        StepVerifier.create(result)
            .assertNext(response -> {
                assertThat(session.getStatus()).isEqualTo(InterviewSession.Status.COMPLETED);
                assertThat(session.getEndedAt()).isNotNull();
                assertThat(session.getDurationSeconds()).isGreaterThan(0);
            })
            .verifyComplete();
    }

    @Test
    void endSession_freePlanUser_deductsCredit() {
        User user = buildUser();
        user.setPlan(User.Plan.FREE);
        user.setCredits(3);
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);

        String feedbackJson = """
            {"score": 70, "grade": "Good", "headline": "Ok", "summary": "Good job",
             "metrics": {"starScore": "3.5/5", "speakingPace": 140, "fillerRate": "2.0",
                         "vocalConfidence": 70, "specificity": 65},
             "strengths": [], "improvements": [], "nextSteps": [], "perAnswer": []}
            """;

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of());
        when(anthropicService.buildFeedbackPrompt(any(), anyList())).thenReturn("prompt");
        when(anthropicService.complete(anyString(), anyList()))
            .thenReturn(Mono.just(feedbackJson));
        when(userRepo.save(any(User.class))).thenReturn(user);

        service.endSession(session.getId(), user.getId()).block();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getCredits()).isEqualTo(2);
    }

    @Test
    void endSession_proPlanUser_doesNotDeductCredit() {
        User user = buildUser();
        user.setPlan(User.Plan.PRO);
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);

        String feedbackJson = """
            {"score": 90, "grade": "Excellent", "headline": "Great",
             "summary": "Excellent", "metrics": {"starScore": "4.5/5", "speakingPace": 155,
             "fillerRate": "1.0", "vocalConfidence": 90, "specificity": 85},
             "strengths": [], "improvements": [], "nextSteps": [], "perAnswer": []}
            """;

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of());
        when(anthropicService.buildFeedbackPrompt(any(), anyList())).thenReturn("prompt");
        when(anthropicService.complete(anyString(), anyList()))
            .thenReturn(Mono.just(feedbackJson));

        service.endSession(session.getId(), user.getId()).block();

        verify(userRepo, never()).save(any());
    }

    @Test
    void getUserSessions_returnsPaginatedResults() {
        User user = buildUser();
        InterviewSession s1 = buildSession(user, InterviewSession.Status.COMPLETED);
        Page<InterviewSession> page = new PageImpl<>(List.of(s1));

        when(sessionRepo.findByUserIdOrderByCreatedAtDesc(eq(user.getId()), any(Pageable.class)))
            .thenReturn(page);

        Page<SessionListResponse> result = service.getUserSessions(user.getId(), 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getRole()).isEqualTo("Software Engineer");
    }

    @Test
    void getSession_success_returnsResponse() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.COMPLETED);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));

        SessionResponse response = service.getSession(session.getId(), user.getId());

        assertThat(response.getId()).isEqualTo(session.getId());
        assertThat(response.getRole()).isEqualTo("Software Engineer");
    }

    @Test
    void getSession_notFound_throwsAppException() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(sessionRepo.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession(sessionId, userId))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(404));
    }

    @Test
    void getSession_withFeedback_includesFeedbackInResponse() {
        User user = buildUser();
        InterviewSession session = buildSession(user, InterviewSession.Status.COMPLETED);
        session.setScore(85);
        session.setGrade("Excellent");

        SessionFeedback feedback = SessionFeedback.builder()
            .summary("Great performance")
            .starScore(new BigDecimal("4.2"))
            .speakingPace(145)
            .fillerRate(new BigDecimal("1.5"))
            .vocalConfidence(82)
            .specificity(78)
            .strengths(new String[]{"Clear", "Concise"})
            .improvements(new String[]{"More detail"})
            .nextSteps(new String[]{"Practice"})
            .perAnswerJson("[]")
            .build();
        session.setFeedback(feedback);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));

        SessionResponse response = service.getSession(session.getId(), user.getId());

        assertThat(response.getFeedback()).isNotNull();
        assertThat(response.getFeedback().getSummary()).isEqualTo("Great performance");
        assertThat(response.getFeedback().getStrengths()).containsExactly("Clear", "Concise");
        assertThat(response.getFeedback().getScore()).isEqualTo(85);
    }

    @Test
    void endSession_invalidFeedbackJson_usesDefaults() {
        User user = buildUser();
        user.setPlan(User.Plan.PRO);
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of());
        when(anthropicService.buildFeedbackPrompt(any(), anyList())).thenReturn("prompt");
        when(anthropicService.complete(anyString(), anyList()))
            .thenReturn(Mono.just("not-valid-json!!!"));

        service.endSession(session.getId(), user.getId()).block();

        assertThat(session.getScore()).isEqualTo(70);
        assertThat(session.getGrade()).isEqualTo("Good");
    }

    @Test
    void endSession_feedbackJsonWithMarkdown_stripsMarkdown() {
        User user = buildUser();
        user.setPlan(User.Plan.PRO);
        InterviewSession session = buildSession(user, InterviewSession.Status.IN_PROGRESS);

        String jsonWithMarkdown = """
            ```json
            {"score": 80, "grade": "Good", "headline": "Good",
             "summary": "Well done",
             "metrics": {"starScore": "3.8/5", "speakingPace": 148, "fillerRate": "2.2",
                         "vocalConfidence": 77, "specificity": 72},
             "strengths": ["A"], "improvements": ["B"], "nextSteps": ["C"], "perAnswer": []}
            ```
            """;

        when(sessionRepo.findByIdAndUserId(session.getId(), user.getId()))
            .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(InterviewSession.class))).thenReturn(session);
        when(messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of());
        when(anthropicService.buildFeedbackPrompt(any(), anyList())).thenReturn("prompt");
        when(anthropicService.complete(anyString(), anyList()))
            .thenReturn(Mono.just(jsonWithMarkdown));

        service.endSession(session.getId(), user.getId()).block();

        assertThat(session.getScore()).isEqualTo(80);
        assertThat(session.getGrade()).isEqualTo("Good");
    }
}
