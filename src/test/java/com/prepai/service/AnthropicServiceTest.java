package com.prepai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.model.InterviewSession;
import com.prepai.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class AnthropicServiceTest {

    @Mock private WebClient webClient;
    @Mock private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private WebClient.RequestBodySpec requestBodySpec;
    @Mock private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private AnthropicService anthropicService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        anthropicService = new AnthropicService(webClient, objectMapper);
        ReflectionTestUtils.setField(anthropicService, "model", "claude-3-5-sonnet-20241022");
        ReflectionTestUtils.setField(anthropicService, "maxTokens", 1024);

        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.accept(any(MediaType.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    private InterviewSession buildSession() {
        User user = User.builder().id(UUID.randomUUID()).email("u@test.com").name("U").build();
        return InterviewSession.builder()
            .id(UUID.randomUUID())
            .user(user)
            .role("Software Engineer")
            .company("Tech Corp")
            .type(InterviewSession.InterviewType.BEHAVIORAL)
            .difficulty(InterviewSession.Difficulty.MID_LEVEL)
            .status(InterviewSession.Status.IN_PROGRESS)
            .build();
    }

    @Test
    void complete_success_returnsExtractedText() throws Exception {
        String responseJson = """
            {"content": [{"text": "Here is the feedback..."}]}
            """;
        var jsonNode = objectMapper.readTree(responseJson);

        when(responseSpec.bodyToMono(any(Class.class))).thenReturn(Mono.just(jsonNode));

        Mono<String> result = anthropicService.complete("system", List.of(Map.of("role", "user", "content", "msg")));

        StepVerifier.create(result)
            .expectNext("Here is the feedback...")
            .verifyComplete();
    }

    @Test
    void complete_apiError_propagatesError() {
        when(responseSpec.bodyToMono(any(Class.class)))
            .thenReturn(Mono.error(new RuntimeException("API error")));

        Mono<String> result = anthropicService.complete("system", List.of());

        StepVerifier.create(result)
            .expectError(RuntimeException.class)
            .verify();
    }

    @Test
    void streamComplete_parsesContentBlockDelta() {
        String sseData1 = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}";
        String sseData2 = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\" World\"}}";
        String sseData3 = "data: {\"type\":\"message_stop\"}";

        when(responseSpec.bodyToFlux(String.class))
            .thenReturn(Flux.just(sseData1, sseData2, sseData3));

        Flux<String> result = anthropicService.streamComplete("system", List.of());

        StepVerifier.create(result)
            .expectNext("Hello")
            .expectNext(" World")
            .verifyComplete();
    }

    @Test
    void streamComplete_filtersDoneSignal() {
        String sseData = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"text\"}}";
        String doneLine = "data: [DONE]";

        when(responseSpec.bodyToFlux(String.class))
            .thenReturn(Flux.just(sseData, doneLine));

        Flux<String> result = anthropicService.streamComplete("system", List.of());

        StepVerifier.create(result)
            .expectNext("text")
            .verifyComplete();
    }

    @Test
    void streamComplete_skipsNonDataLines() {
        String eventLine = "event: content_block_delta";
        String dataLine = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"ok\"}}";

        when(responseSpec.bodyToFlux(String.class))
            .thenReturn(Flux.just(eventLine, dataLine));

        Flux<String> result = anthropicService.streamComplete("system", List.of());

        StepVerifier.create(result)
            .expectNext("ok")
            .verifyComplete();
    }

    @Test
    void streamComplete_skipsEmptyTextDelta() {
        String sseWithEmpty = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"\"}}";
        String sseWithText = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"real\"}}";

        when(responseSpec.bodyToFlux(String.class))
            .thenReturn(Flux.just(sseWithEmpty, sseWithText));

        Flux<String> result = anthropicService.streamComplete("system", List.of());

        StepVerifier.create(result)
            .expectNext("real")
            .verifyComplete();
    }

    @Test
    void streamComplete_invalidJson_skipsEntry() {
        String invalidData = "data: not-valid-json";
        String validData = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"valid\"}}";

        when(responseSpec.bodyToFlux(String.class))
            .thenReturn(Flux.just(invalidData, validData));

        Flux<String> result = anthropicService.streamComplete("system", List.of());

        StepVerifier.create(result)
            .expectNext("valid")
            .verifyComplete();
    }

    @Test
    void buildInterviewerPrompt_notLastQuestion_doesNotIncludeClosingNote() {
        InterviewSession session = buildSession();

        String prompt = anthropicService.buildInterviewerPrompt(session, 2, 5);

        assertThat(prompt).contains("Software Engineer");
        assertThat(prompt).contains("Tech Corp");
        assertThat(prompt).contains("2 of 5");
        assertThat(prompt).doesNotContain("LAST question");
    }

    @Test
    void buildInterviewerPrompt_lastQuestion_includesClosingNote() {
        InterviewSession session = buildSession();

        String prompt = anthropicService.buildInterviewerPrompt(session, 5, 5);

        assertThat(prompt).contains("LAST question");
        assertThat(prompt).contains("5 of 5");
    }

    @Test
    void buildInterviewerPrompt_nullCompany_usesDefaultCompany() {
        InterviewSession session = buildSession();
        session.setCompany(null);

        String prompt = anthropicService.buildInterviewerPrompt(session, 1, 5);

        assertThat(prompt).contains("leading tech company");
    }

    @Test
    void buildInterviewerPrompt_withCompany_usesCompanyName() {
        InterviewSession session = buildSession();
        session.setCompany("Google");

        String prompt = anthropicService.buildInterviewerPrompt(session, 1, 5);

        assertThat(prompt).contains("Google");
    }

    @Test
    void buildInterviewerPrompt_containsInterviewTypeContext() {
        InterviewSession session = buildSession();
        session.setType(InterviewSession.InterviewType.TECHNICAL);

        String prompt = anthropicService.buildInterviewerPrompt(session, 1, 5);

        assertThat(prompt).contains("TECHNICAL");
    }

    @Test
    void buildFeedbackPrompt_withAnswers_includesAllAnswers() {
        InterviewSession session = buildSession();
        List<String> answers = List.of("Answer 1", "Answer 2", "Answer 3");

        String prompt = anthropicService.buildFeedbackPrompt(session, answers);

        assertThat(prompt).contains("Q1: Answer 1");
        assertThat(prompt).contains("Q2: Answer 2");
        assertThat(prompt).contains("Q3: Answer 3");
        assertThat(prompt).contains("Software Engineer");
        assertThat(prompt).contains("Tech Corp");
    }

    @Test
    void buildFeedbackPrompt_noCompany_omitsCompanyInOutput() {
        InterviewSession session = buildSession();
        session.setCompany(null);

        String prompt = anthropicService.buildFeedbackPrompt(session, List.of("Answer 1"));

        assertThat(prompt).doesNotContain("at Tech Corp");
        assertThat(prompt).contains("Software Engineer");
    }

    @Test
    void buildFeedbackPrompt_emptyAnswers_stillProducesPrompt() {
        InterviewSession session = buildSession();

        String prompt = anthropicService.buildFeedbackPrompt(session, List.of());

        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("score");
    }

    @Test
    void buildFeedbackPrompt_includesJsonStructure() {
        InterviewSession session = buildSession();

        String prompt = anthropicService.buildFeedbackPrompt(session, List.of("Answer"));

        assertThat(prompt).contains("\"score\"");
        assertThat(prompt).contains("\"grade\"");
        assertThat(prompt).contains("\"strengths\"");
        assertThat(prompt).contains("\"improvements\"");
    }
}
