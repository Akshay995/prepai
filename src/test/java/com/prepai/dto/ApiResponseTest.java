package com.prepai.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void ok_withDataOnly_returnsSuccessResponse() {
        ApiResponse<String> response = ApiResponse.ok("test-data");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Success");
        assertThat(response.getData()).isEqualTo("test-data");
    }

    @Test
    void ok_withMessageAndData_returnsSuccessResponse() {
        ApiResponse<Integer> response = ApiResponse.ok("Custom message", 42);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Custom message");
        assertThat(response.getData()).isEqualTo(42);
    }

    @Test
    void error_returnsFailureResponse() {
        ApiResponse<Void> response = ApiResponse.error("Something went wrong");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Something went wrong");
        assertThat(response.getData()).isNull();
    }

    @Test
    void ok_withNullData_returnsSuccessWithNullData() {
        ApiResponse<Object> response = ApiResponse.ok((Object) null);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
    }

    @Test
    void builder_createsResponseCorrectly() {
        ApiResponse<String> response = ApiResponse.<String>builder()
            .success(true)
            .message("Built")
            .data("value")
            .build();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Built");
        assertThat(response.getData()).isEqualTo("value");
    }

    @Test
    void noArgsConstructor_createsDefaultInstance() {
        ApiResponse<String> response = new ApiResponse<>();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getData()).isNull();
    }
}
