package com.prepai.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDtos {

    @Data public static class RegisterRequest {
        @NotBlank @Size(min=2, max=100) public String name;
        @NotBlank @Email public String email;
        @NotBlank @Size(min=8, max=100) public String password;
    }

    @Data public static class LoginRequest {
        @NotBlank @Email public String email;
        @NotBlank public String password;
    }

    @Data public static class RefreshTokenRequest {
        @NotBlank public String refreshToken;
    }

    @Data public static class ForgotPasswordRequest {
        @NotBlank @Email public String email;
    }

    @Data public static class ResetPasswordRequest {
        @NotBlank public String token;
        @NotBlank @Size(min=8) public String newPassword;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuthResponse {
        public String accessToken;
        public String refreshToken;
        public String tokenType = "Bearer";
        public UserResponse user;
    }
}
