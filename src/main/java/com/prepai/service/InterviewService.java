package com.prepai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.dto.InterviewDtos.*;
import com.prepai.exception.AppException;
import com.prepai.model.*;
import com.prepai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {

    private static final int MAX_QUESTIONS = 5;

    private final InterviewSessionRepository sessionRepo;
    private final InterviewMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final AnthropicService anthropicService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SessionResponse createSession(UUID userId, CreateSessionRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new AppException("User not found", 404));

        if (!user.hasPlanAccess()) {
            throw new AppException("No credits remaining. Please upgrade your plan.", 402);
        }

        InterviewSession session = InterviewSession.builder()
            .user(user)
            .role(req.getRole())
            .company(req.getCompany())
            .type(req.getType())
            .difficulty(req.getDifficulty())
            .status(InterviewSession.Status.SETUP)
            .build();

        return toSessionResponse(sessionRepo.save(session));
    }

    /**
     * Start session and stream the first question via SSE
     */
    @Transactional
    public Flux<String> startSession(UUID sessionId, UUID userId) {
        InterviewSession session = getSessionOrThrow(sessionId, userId);
        session.setStatus(InterviewSession.Status.IN_PROGRESS);
        session.setStartedAt(Instant.now());
        sessionRepo.save(session);

        String systemPrompt = anthropicService.buildInterviewerPrompt(session, 1, MAX_QUESTIONS);
        List<Map<String, String>> messages = List.of(
            Map.of("role", "user", "content", "Please start the interview with your first question.")
        );

        return streamAndPersist(session, "assistant", 1, systemPrompt, messages);
    }

    /**
     * Accept candidate answer, stream next AI question via SSE
     */
    @Transactional
    public Flux<String> sendMessage(UUID sessionId, UUID userId, String content) {
        InterviewSession session = getSessionOrThrow(sessionId, userId);

        if (session.getStatus() != InterviewSession.Status.IN_PROGRESS) {
            throw new AppException("Session is not active", 400);
        }

        // Count word and filler words
        int wordCount = content.trim().split("\\s+").length;
        int fillerCount = countFillers(content);

        // Persist user message
        InterviewMessage userMsg = InterviewMessage.builder()
            .session(session)
            .role("user")
            .content(content)
            .wordCount(wordCount)
            .fillerCount(fillerCount)
            .build();
        messageRepo.save(userMsg);

        int nextQuestion = session.getQuestionCount() + 1;
        session.setQuestionCount(nextQuestion);
        sessionRepo.save(session);

        if (nextQuestion > MAX_QUESTIONS) {
            return Flux.just("[SESSION_COMPLETE]");
        }

        // Build conversation history for Claude
        List<Map<String, String>> history = buildConversationHistory(session);
        String systemPrompt = anthropicService.buildInterviewerPrompt(session, nextQuestion, MAX_QUESTIONS);

        return streamAndPersist(session, "assistant", nextQuestion, systemPrompt, history);
    }

    /**
     * End session and generate full AI feedback
     */
    @Transactional
    public Mono<SessionResponse> endSession(UUID sessionId, UUID userId) {
        InterviewSession session = getSessionOrThrow(sessionId, userId);
        session.setStatus(InterviewSession.Status.COMPLETED);
        session.setEndedAt(Instant.now());

        if (session.getStartedAt() != null) {
            long secs = Instant.now().getEpochSecond() - session.getStartedAt().getEpochSecond();
            session.setDurationSeconds((int) secs);
        }
        sessionRepo.save(session);

        // Deduct credit for free users
        User user = session.getUser();
        if (user.getPlan() == User.Plan.FREE && user.getCredits() > 0) {
            user.setCredits(user.getCredits() - 1);
            userRepo.save(user);
        }

        // Collect all user answers
        List<String> answers = messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId())
            .stream()
            .filter(m -> "user".equals(m.getRole()))
            .map(InterviewMessage::getContent)
            .collect(Collectors.toList());

        String feedbackPrompt = anthropicService.buildFeedbackPrompt(session, answers);

        return anthropicService.complete(feedbackPrompt,
                List.of(Map.of("role", "user", "content", "Analyze the interview and return JSON only.")))
            .map(json -> persistFeedback(session, json))
            .map(s -> toSessionResponse(s));
    }

    @Transactional(readOnly = true)
    public Page<SessionListResponse> getUserSessions(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return sessionRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(this::toSessionListResponse);
    }

    @Transactional(readOnly = true)
    public SessionResponse getSession(UUID sessionId, UUID userId) {
        return toSessionResponse(getSessionOrThrow(sessionId, userId));
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Flux<String> streamAndPersist(InterviewSession session, String role,
                                           int questionNumber, String systemPrompt,
                                           List<Map<String, String>> messages) {
        StringBuilder buffer = new StringBuilder();

        return anthropicService.streamComplete(systemPrompt, messages)
            .doOnNext(buffer::append)
            .doOnComplete(() -> {
                String full = buffer.toString();
                InterviewMessage msg = InterviewMessage.builder()
                    .session(session)
                    .role(role)
                    .content(full)
                    .questionNumber(questionNumber)
                    .wordCount(full.trim().split("\\s+").length)
                    .build();
                messageRepo.save(msg);
            });
    }

    private InterviewSession persistFeedback(InterviewSession session, String json) {
        try {
            String clean = json.replaceAll("```json|```", "").trim();
            var node = objectMapper.readTree(clean);

            var metrics = node.path("metrics");
            String starStr = metrics.path("starScore").asText("3.5/5").split("/")[0];

            SessionFeedback feedback = SessionFeedback.builder()
                .session(session)
                .summary(node.path("summary").asText())
                .starScore(new BigDecimal(starStr))
                .speakingPace(metrics.path("speakingPace").asInt(140))
                .fillerRate(new BigDecimal(metrics.path("fillerRate").asText("2.0")))
                .vocalConfidence(metrics.path("vocalConfidence").asInt(75))
                .specificity(metrics.path("specificity").asInt(70))
                .strengths(toStringArray(node.path("strengths")))
                .improvements(toStringArray(node.path("improvements")))
                .nextSteps(toStringArray(node.path("nextSteps")))
                .perAnswerJson(node.path("perAnswer").toString())
                .build();

            session.setFeedback(feedback);
            session.setScore(node.path("score").asInt(70));
            session.setGrade(node.path("grade").asText("Good"));
            return sessionRepo.save(session);
        } catch (Exception e) {
            log.error("Failed to parse feedback JSON: {}", e.getMessage());
            session.setScore(70);
            session.setGrade("Good");
            return sessionRepo.save(session);
        }
    }

    private List<Map<String, String>> buildConversationHistory(InterviewSession session) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(session.getId())
            .stream()
            .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
            .collect(Collectors.toList());
    }

    private int countFillers(String text) {
        String lower = text.toLowerCase();
        String[] fillers = {"um", "uh", "like", "you know", "basically", "literally", "actually"};
        int count = 0;
        for (String f : fillers) {
            int idx = 0;
            while ((idx = lower.indexOf(f, idx)) != -1) { count++; idx += f.length(); }
        }
        return count;
    }

    private String[] toStringArray(com.fasterxml.jackson.databind.JsonNode arr) {
        if (!arr.isArray()) return new String[0];
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).asText();
        return result;
    }

    private InterviewSession getSessionOrThrow(UUID sessionId, UUID userId) {
        return sessionRepo.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new AppException("Session not found", 404));
    }

    private SessionResponse toSessionResponse(InterviewSession s) {
        var resp = SessionResponse.builder()
            .id(s.getId()).role(s.getRole()).company(s.getCompany())
            .type(s.getType()).difficulty(s.getDifficulty()).status(s.getStatus())
            .score(s.getScore()).grade(s.getGrade())
            .questionCount(s.getQuestionCount()).durationSeconds(s.getDurationSeconds())
            .startedAt(s.getStartedAt()).endedAt(s.getEndedAt()).createdAt(s.getCreatedAt())
            .build();

        if (s.getFeedback() != null) {
            var fb = s.getFeedback();
            resp.setFeedback(FeedbackResponse.builder()
                .score(s.getScore()).grade(s.getGrade()).summary(fb.getSummary())
                .starScore(fb.getStarScore()).speakingPace(fb.getSpeakingPace())
                .fillerRate(fb.getFillerRate()).vocalConfidence(fb.getVocalConfidence())
                .specificity(fb.getSpecificity())
                .strengths(fb.getStrengths() != null ? Arrays.asList(fb.getStrengths()) : List.of())
                .improvements(fb.getImprovements() != null ? Arrays.asList(fb.getImprovements()) : List.of())
                .nextSteps(fb.getNextSteps() != null ? Arrays.asList(fb.getNextSteps()) : List.of())
                .perAnswerJson(fb.getPerAnswerJson())
                .build());
        }
        return resp;
    }

    private SessionListResponse toSessionListResponse(InterviewSession s) {
        return SessionListResponse.builder()
            .id(s.getId()).role(s.getRole()).company(s.getCompany())
            .type(s.getType()).status(s.getStatus()).score(s.getScore())
            .grade(s.getGrade()).questionCount(s.getQuestionCount())
            .createdAt(s.getCreatedAt()).build();
    }
}
