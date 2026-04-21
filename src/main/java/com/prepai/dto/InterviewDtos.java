package com.prepai.dto;

import com.prepai.model.InterviewSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class InterviewDtos {

    @Data public static class CreateSessionRequest {
        @NotBlank public String role;
        public String company;
        @NotNull public InterviewSession.InterviewType type;
        @NotNull public InterviewSession.Difficulty difficulty;
    }

    @Data public static class SendMessageRequest {
        @NotBlank @Size(max=2000) public String content;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SessionResponse {
        public UUID id;
        public String role;
        public String company;
        public InterviewSession.InterviewType type;
        public InterviewSession.Difficulty difficulty;
        public InterviewSession.Status status;
        public Integer score;
        public String grade;
        public Integer questionCount;
        public Integer durationSeconds;
        public Instant startedAt;
        public Instant endedAt;
        public Instant createdAt;
        public FeedbackResponse feedback;
        public List<MessageResponse> messages;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MessageResponse {
        public UUID id;
        public String role;
        public String content;
        public Integer questionNumber;
        public Integer wordCount;
        public Instant createdAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FeedbackResponse {
        public Integer score;
        public String grade;
        public String summary;
        public BigDecimal starScore;
        public Integer speakingPace;
        public BigDecimal fillerRate;
        public Integer vocalConfidence;
        public Integer specificity;
        public List<String> strengths;
        public List<String> improvements;
        public List<String> nextSteps;
        public String perAnswerJson;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SessionListResponse {
        public UUID id;
        public String role;
        public String company;
        public InterviewSession.InterviewType type;
        public InterviewSession.Status status;
        public Integer score;
        public String grade;
        public Integer questionCount;
        public Instant createdAt;
    }
}
