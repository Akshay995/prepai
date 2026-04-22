package com.prepai.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppExceptionTest {

    @Test
    void constructor_setsMessageAndStatus() {
        AppException ex = new AppException("Email already registered", 409);

        assertThat(ex.getMessage()).isEqualTo("Email already registered");
        assertThat(ex.getStatus()).isEqualTo(409);
    }

    @Test
    void isRuntimeException() {
        AppException ex = new AppException("Error", 400);

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void differentStatusCodes_arePreserved() {
        assertThat(new AppException("Not found", 404).getStatus()).isEqualTo(404);
        assertThat(new AppException("Unauthorized", 401).getStatus()).isEqualTo(401);
        assertThat(new AppException("Forbidden", 403).getStatus()).isEqualTo(403);
        assertThat(new AppException("Server error", 500).getStatus()).isEqualTo(500);
        assertThat(new AppException("Payment required", 402).getStatus()).isEqualTo(402);
    }
}
