package com.prepai.repository;

import com.prepai.model.InterviewSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {

    Page<InterviewSession> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<InterviewSession> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT COUNT(s) FROM InterviewSession s WHERE s.user.id = :userId AND s.status = 'COMPLETED'")
    long countCompletedByUserId(UUID userId);

    @Query("SELECT AVG(s.score) FROM InterviewSession s WHERE s.user.id = :userId AND s.score IS NOT NULL")
    Double avgScoreByUserId(UUID userId);

    Optional<InterviewSession> findByIdAndUserId(UUID id, UUID userId);
}
