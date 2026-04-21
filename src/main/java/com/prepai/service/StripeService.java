package com.prepai.service;

import com.prepai.config.StripeConfig;
import com.prepai.exception.AppException;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeService {

    private final StripeConfig stripeConfig;
    private final UserRepository userRepo;

    public String createCheckoutSession(UUID userId, String priceId, String successUrl, String cancelUrl) {
        User user = userRepo.findById(userId).orElseThrow(() -> new AppException("User not found", 404));

        try {
            String customerId = ensureCustomer(user);

            boolean isSubscription = priceId.equals(stripeConfig.getPriceProMonthly());

            SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setCustomer(customerId)
                .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .setMode(isSubscription
                    ? SessionCreateParams.Mode.SUBSCRIPTION
                    : SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build())
                .putMetadata("userId", userId.toString())
                .putMetadata("priceId", priceId);

            Session session = Session.create(params.build());
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe checkout error: {}", e.getMessage());
            throw new AppException("Payment initialization failed", 500);
        }
    }

    @Transactional
    public void handleWebhook(String payload, String sigHeader) {
        com.stripe.model.Event event;
        try {
            event = com.stripe.net.Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
        } catch (Exception e) {
            throw new AppException("Invalid webhook signature", 400);
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutComplete(event);
            case "customer.subscription.deleted" -> handleSubscriptionCanceled(event);
            case "invoice.payment_failed" -> handlePaymentFailed(event);
            default -> log.debug("Unhandled Stripe event: {}", event.getType());
        }
    }

    private void handleCheckoutComplete(com.stripe.model.Event event) {
        Session session = (Session) event.getDataObjectDeserializer().getObject().orElseThrow();
        String userId = session.getMetadata().get("userId");
        String priceId = session.getMetadata().get("priceId");

        User user = userRepo.findById(UUID.fromString(userId)).orElseThrow();

        if (priceId.equals(stripeConfig.getPriceProMonthly())) {
            user.setPlan(User.Plan.PRO);
            user.setStripeSubscriptionId(session.getSubscription());
        } else if (priceId.equals(stripeConfig.getPricePass48h())) {
            user.setPlan(User.Plan.PASS_48H);
            user.setPlanExpiresAt(Instant.now().plus(48, ChronoUnit.HOURS));
        }
        userRepo.save(user);
        log.info("Plan upgraded for user {} to {}", userId, user.getPlan());
    }

    private void handleSubscriptionCanceled(com.stripe.model.Event event) {
        Subscription sub = (Subscription) event.getDataObjectDeserializer().getObject().orElseThrow();
        userRepo.findAll().stream()
            .filter(u -> sub.getId().equals(u.getStripeSubscriptionId()))
            .findFirst()
            .ifPresent(user -> {
                user.setPlan(User.Plan.FREE);
                user.setCredits(5);
                user.setStripeSubscriptionId(null);
                userRepo.save(user);
            });
    }

    private void handlePaymentFailed(com.stripe.model.Event event) {
        log.warn("Payment failed event received: {}", event.getId());
    }

    private String ensureCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null) return user.getStripeCustomerId();

        CustomerCreateParams params = CustomerCreateParams.builder()
            .setEmail(user.getEmail())
            .setName(user.getName())
            .putMetadata("userId", user.getId().toString())
            .build();

        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepo.save(user);
        return customer.getId();
    }
}
