package com.prepai.model;

import io.hypersistence.utils.hibernate.type.array.StringArrayType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_feedback")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SessionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "star_score", precision = 3, scale = 1)
    private BigDecimal starScore;

    @Column(name = "speaking_pace")
    private Integer speakingPace;

    @Column(name = "filler_rate", precision = 4, scale = 1)
    private BigDecimal fillerRate;

    @Column(name = "vocal_confidence")
    private Integer vocalConfidence;

    private Integer specificity;

    @Column(columnDefinition = "TEXT[]")
    private String[] strengths;

    @Column(columnDefinition = "TEXT[]")
    private String[] improvements;

    @Column(name = "next_steps", columnDefinition = "TEXT[]")
    private String[] nextSteps;

    @Column(name = "per_answer_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String perAnswerJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
