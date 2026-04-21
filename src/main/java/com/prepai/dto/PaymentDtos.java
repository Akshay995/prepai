package com.prepai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class PaymentDtos {

    @Data public static class CreateCheckoutRequest {
        @NotBlank public String priceId;
        public String successUrl;
        public String cancelUrl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CheckoutResponse {
        public String checkoutUrl;
        public String sessionId;
    }
}
