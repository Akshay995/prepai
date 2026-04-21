package com.prepai.controller;

import com.prepai.dto.ApiResponse;
import com.prepai.dto.DashboardDtos.*;
import com.prepai.dto.PaymentDtos.*;
import com.prepai.dto.UserResponse;
import com.prepai.model.User;
import com.prepai.repository.InterviewSessionRepository;
import com.prepai.repository.UserRepository;
import com.prepai.service.StripeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

// ── User Controller ────────────────────────────────────────────────────────
@RestController
@RequestMapping("/v1/user")
@RequiredArgsConstructor
@Tag(name = "User")
@SecurityRequirement(name = "bearerAuth")
class UserController {

    private final UserRepository userRepo;
    private final InterviewSessionRepository sessionRepo;

    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(
        @AuthenticationPrincipal UserDetails principal) {

        User user = userRepo.findByEmail(principal.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.ok(toUserResponse(user)));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get dashboard stats")
    public ResponseEntity<ApiResponse<StatsResponse>> getDashboard(
        @AuthenticationPrincipal UserDetails principal) {

        User user = userRepo.findByEmail(principal.getUsername()).orElseThrow();
        UUID uid = user.getId();

        long total = sessionRepo.count();
        long completed = sessionRepo.countCompletedByUserId(uid);
        Double avg = sessionRepo.avgScoreByUserId(uid);
        var recent = sessionRepo.findTop5ByUserIdOrderByCreatedAtDesc(uid).stream()
            .map(s -> com.prepai.dto.InterviewDtos.SessionListResponse.builder()
                .id(s.getId()).role(s.getRole()).company(s.getCompany())
                .type(s.getType()).status(s.getStatus()).score(s.getScore())
                .grade(s.getGrade()).questionCount(s.getQuestionCount())
                .createdAt(s.getCreatedAt()).build())
            .toList();

        return ResponseEntity.ok(ApiResponse.ok(StatsResponse.builder()
            .totalSessions(total).completedSessions(completed).avgScore(avg)
            .recentSessions(recent).build()));
    }

    private UserResponse toUserResponse(User u) {
        return UserResponse.builder()
            .id(u.getId()).name(u.getName()).email(u.getEmail())
            .avatarUrl(u.getAvatarUrl()).plan(u.getPlan())
            .credits(u.getCredits()).planExpiresAt(u.getPlanExpiresAt())
            .totalSessions(sessionRepo.countCompletedByUserId(u.getId()))
            .avgScore(sessionRepo.avgScoreByUserId(u.getId()))
            .build();
    }
}

// ── Payment Controller ─────────────────────────────────────────────────────
@RestController
@RequestMapping("/v1/payment")
@RequiredArgsConstructor
@Tag(name = "Payments")
@SecurityRequirement(name = "bearerAuth")
class PaymentController {

    private final StripeService stripeService;
    private final UserRepository userRepo;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @PostMapping("/checkout")
    @Operation(summary = "Create Stripe checkout session")
    public ResponseEntity<ApiResponse<CheckoutResponse>> createCheckout(
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody CreateCheckoutRequest req) {

        User user = userRepo.findByEmail(principal.getUsername()).orElseThrow();
        String successUrl = req.getSuccessUrl() != null ? req.getSuccessUrl() : frontendUrl + "/dashboard?upgraded=true";
        String cancelUrl = req.getCancelUrl() != null ? req.getCancelUrl() : frontendUrl + "/pricing";

        String url = stripeService.createCheckoutSession(user.getId(), req.getPriceId(), successUrl, cancelUrl);
        return ResponseEntity.ok(ApiResponse.ok(CheckoutResponse.builder().checkoutUrl(url).build()));
    }

    @PostMapping("/webhook")
    @Operation(summary = "Stripe webhook handler (no auth required)")
    public ResponseEntity<Void> webhook(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String sig) {

        stripeService.handleWebhook(payload, sig);
        return ResponseEntity.ok().build();
    }
}
