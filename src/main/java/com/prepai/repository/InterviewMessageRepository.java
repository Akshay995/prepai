package com.prepai.repository;

import com.prepai.model.InterviewMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface InterviewMessageRepository extends JpaRepository<InterviewMessage, UUID> {
    List<InterviewMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
    long countBySessionId(UUID sessionId);
}
