package com.prepai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prepai.model.InterviewSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnthropicService {

    @Qualifier("anthropicWebClient")
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.max-tokens}")
    private int maxTokens;

    /**
     * Non-streaming call — for feedback generation (returns full text)
     */
    public Mono<String> complete(String systemPrompt, List<Map<String, String>> messages) {
        Map<String, Object> body = Map.of(
            "model", model,
            "max_tokens", maxTokens,
            "system", systemPrompt,
            "messages", messages
        );

        return webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(json -> json.path("content").get(0).path("text").asText())
            .doOnError(e -> log.error("Anthropic API error: {}", e.getMessage()));
    }

    /**
     * SSE streaming call — for live interview responses
     */
    public Flux<String> streamComplete(String systemPrompt, List<Map<String, String>> messages) {
        Map<String, Object> body = Map.of(
            "model", model,
            "max_tokens", maxTokens,
            "stream", true,
            "system", systemPrompt,
            "messages", messages
        );

        return webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> line.startsWith("data: "))
            .map(line -> line.substring(6).trim())
            .filter(data -> !data.equals("[DONE]") && !data.isEmpty())
            .flatMap(data -> {
                try {
                    JsonNode node = objectMapper.readTree(data);
                    String type = node.path("type").asText();
                    if ("content_block_delta".equals(type)) {
                        String text = node.path("delta").path("text").asText("");
                        return text.isEmpty() ? Flux.empty() : Flux.just(text);
                    }
                } catch (Exception e) {
                    log.debug("SSE parse skip: {}", e.getMessage());
                }
                return Flux.empty();
            })
            .doOnError(e -> log.error("Anthropic stream error: {}", e.getMessage()));
    }

    /**
     * Build the interviewer system prompt based on session config
     */
    public String buildInterviewerPrompt(InterviewSession session, int currentQuestion, int totalQuestions) {
        return String.format("""
            You are a professional %s interviewer at %s. You are conducting a %s-level %s interview for the role of %s.
            
            RULES:
            - Ask ONE question at a time, maximum 80 words per response
            - You are on question %d of %d
            - Briefly acknowledge the candidate's answer (1 sentence max), then ask your next question
            - Do NOT provide scores, ratings, or detailed feedback during the interview
            - Be professional, conversational, and realistic
            - Adapt follow-up questions based on what the candidate actually said
            %s
            
            Interview type context:
            - BEHAVIORAL: Use STAR-probing questions (Situation, Task, Action, Result)
            - TECHNICAL: System design, architecture decisions, debugging scenarios
            - LEADERSHIP: Team challenges, stakeholder management, decision-making
            - CASE_STUDY: Business problems, market sizing, strategic thinking
            - CULTURE_FIT: Values, work style, motivations
            """,
            session.getType().name().toLowerCase(),
            session.getCompany() != null ? session.getCompany() : "a leading tech company",
            session.getDifficulty().name().replace("_", " ").toLowerCase(),
            session.getType().name().toLowerCase(),
            session.getRole(),
            currentQuestion, totalQuestions,
            currentQuestion == totalQuestions
                ? "\n- This is the LAST question. Make it a strong, challenging closing question."
                : ""
        );
    }

    /**
     * Build the feedback analysis prompt
     */
    public String buildFeedbackPrompt(InterviewSession session, List<String> answers) {
        StringBuilder answersText = new StringBuilder();
        for (int i = 0; i < answers.size(); i++) {
            answersText.append("Q").append(i + 1).append(": ").append(answers.get(i)).append("\n\n");
        }

        return String.format("""
            You are an expert interview coach analyzing a completed %s interview.
            Role: %s%s
            Difficulty: %s
            
            Candidate answers:
            %s
            
            Analyze comprehensively and respond with ONLY valid JSON (no markdown, no backticks):
            {
              "score": <0-100 integer>,
              "grade": "<Excellent|Good|Developing|Needs Work>",
              "headline": "<one encouraging sentence about overall performance, max 15 words>",
              "summary": "<2-3 sentence paragraph of honest, actionable overall feedback>",
              "metrics": {
                "starScore": "<x.x/5>",
                "speakingPace": <estimated wpm 100-200>,
                "fillerRate": <estimated fillers per minute 0-10>,
                "vocalConfidence": <0-100>,
                "specificity": <0-100>
              },
              "strengths": ["<specific strength 1>", "<specific strength 2>", "<specific strength 3>"],
              "improvements": ["<specific improvement 1>", "<specific improvement 2>", "<specific improvement 3>"],
              "nextSteps": ["<actionable step 1>", "<actionable step 2>", "<actionable step 3>"],
              "perAnswer": [
                {
                  "questionNumber": 1,
                  "score": <0-100>,
                  "starCoverage": "<High|Medium|Low>",
                  "fillerCount": <integer>,
                  "keyStrength": "<one thing they did well>",
                  "keyImprovement": "<one specific thing to fix>"
                }
              ]
            }
            """,
            session.getType().name().toLowerCase(),
            session.getRole(),
            session.getCompany() != null ? " at " + session.getCompany() : "",
            session.getDifficulty().name().replace("_", " "),
            answersText
        );
    }
}
