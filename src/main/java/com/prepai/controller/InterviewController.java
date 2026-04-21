package com.prepai.controller;

import com.prepai.dto.ApiResponse;
import com.prepai.dto.InterviewDtos.*;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.prepai.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/v1/interview")
@RequiredArgsConstructor
@Tag(name = "Interview")
@SecurityRequirement(name = "bearerAuth")
public class InterviewController {

    private final InterviewService interviewService;
    private final UserRepository userRepo;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PostMapping("/sessions")
    @Operation(summary = "Create a new interview session")
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody CreateSessionRequest req) {

        UUID userId = resolveUserId(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(interviewService.createSession(userId, req)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List user's interview sessions")
    public ResponseEntity<ApiResponse<Page<SessionListResponse>>> listSessions(
        @AuthenticationPrincipal UserDetails principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {

        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(interviewService.getUserSessions(userId, page, size)));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get session details with feedback")
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable UUID id) {

        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(ApiResponse.ok(interviewService.getSession(id, userId)));
    }

    /**
     * SSE endpoint — start session and stream first AI question
     */
    @GetMapping(value = "/sessions/{id}/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Start interview session — SSE stream of first question")
    public SseEmitter startSession(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable UUID id) {

        UUID userId = resolveUserId(principal);
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout

        sseExecutor.execute(() -> {
            interviewService.startSession(id, userId)
                .subscribe(
                    chunk -> sendChunk(emitter, chunk),
                    err -> emitter.completeWithError(err),
                    () -> sendComplete(emitter)
                );
        });
        return emitter;
    }

    /**
     * SSE endpoint — send answer and stream next AI question
     */
    @PostMapping(value = "/sessions/{id}/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send answer and receive next question — SSE stream")
    public SseEmitter sendMessage(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable UUID id,
        @Valid @RequestBody SendMessageRequest req) {

        UUID userId = resolveUserId(principal);
        SseEmitter emitter = new SseEmitter(120_000L);

        sseExecutor.execute(() -> {
            interviewService.sendMessage(id, userId, req.getContent())
                .subscribe(
                    chunk -> {
                        if ("[SESSION_COMPLETE]".equals(chunk)) {
                            sendEvent(emitter, "session_complete", "{}");
                        } else {
                            sendChunk(emitter, chunk);
                        }
                    },
                    err -> emitter.completeWithError(err),
                    () -> sendComplete(emitter)
                );
        });
        return emitter;
    }

    /**
     * End session and return full feedback (non-streaming, may take ~10s)
     */
    @PostMapping("/sessions/{id}/end")
    @Operation(summary = "End session and generate AI feedback")
    public ResponseEntity<ApiResponse<SessionResponse>> endSession(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable UUID id) {

        UUID userId = resolveUserId(principal);
        SessionResponse result = interviewService.endSession(id, userId).block();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── SSE helpers ──────────────────────────────────────────────────────

    private void sendChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().name("chunk").data(chunk));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendComplete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("done").data(""));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private UUID resolveUserId(UserDetails principal) {
        return userRepo.findByEmail(principal.getUsername())
            .map(User::getId)
            .orElseThrow(() -> new com.prepai.exception.AppException("User not found", 404));
    }
}
