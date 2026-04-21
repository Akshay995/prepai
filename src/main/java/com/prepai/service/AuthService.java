package com.prepai.service;

import com.prepai.dto.AuthDtos.*;
import com.prepai.dto.UserResponse;
import com.prepai.exception.AppException;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.prepai.security.CustomUserDetailsService;
import com.prepai.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authManager;
    private final CustomUserDetailsService userDetailsService;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new AppException("Email already registered", 409);
        }

        String verificationToken = UUID.randomUUID().toString();
        User user = User.builder()
            .email(req.getEmail())
            .name(req.getName())
            .password(passwordEncoder.encode(req.getPassword()))
            .verificationToken(verificationToken)
            .emailVerified(false)
            .build();

        userRepository.save(user);
        emailService.sendVerificationEmail(user.getEmail(), user.getName(), verificationToken);

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        try {
            authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        } catch (BadCredentialsException e) {
            throw new AppException("Invalid email or password", 401);
        } catch (DisabledException e) {
            throw new AppException("Email not verified. Please check your inbox.", 403);
        }

        User user = userRepository.findByEmail(req.getEmail()).orElseThrow();
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest req) {
        var token = refreshTokenService.validateAndGet(req.getRefreshToken());
        if (token.isExpired()) {
            refreshTokenService.deleteToken(token);
            throw new AppException("Refresh token expired. Please log in again.", 401);
        }
        User user = token.getUser();
        refreshTokenService.deleteToken(token);
        return buildAuthResponse(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
            .orElseThrow(() -> new AppException("Invalid verification token", 400));
        user.setEmailVerified(true);
        user.setVerificationToken(null);
        userRepository.save(user);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        userRepository.findByEmail(req.getEmail()).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setResetToken(token);
            user.setResetTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            userRepository.save(user);
            emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), token);
        });
        // Always return 200 to avoid email enumeration
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        User user = userRepository.findByResetToken(req.getToken())
            .orElseThrow(() -> new AppException("Invalid or expired reset token", 400));
        if (user.getResetTokenExpiresAt().isBefore(Instant.now())) {
            throw new AppException("Reset token has expired", 400);
        }
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String accessToken = jwtUtil.generateToken(userDetails, user.getId());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId()).getToken();

        UserResponse userResp = UserResponse.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .avatarUrl(user.getAvatarUrl())
            .plan(user.getPlan())
            .credits(user.getCredits())
            .planExpiresAt(user.getPlanExpiresAt())
            .build();

        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .user(userResp)
            .build();
    }
}
