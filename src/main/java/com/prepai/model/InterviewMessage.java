package com.prepai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "interview_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InterviewMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(nullable = false)
    private String role; // "assistant" | "user"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "question_number")
    private Integer questionNumber;

    @Column(name = "word_count")
    private Integer wordCount;

    @Column(name = "filler_count")
    @Builder.Default
    private Integer fillerCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
