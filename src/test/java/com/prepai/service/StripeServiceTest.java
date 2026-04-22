package com.prepai.service;

import com.prepai.config.StripeConfig;
import com.prepai.exception.AppException;
import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeServiceTest {

    @Mock private StripeConfig stripeConfig;
    @Mock private UserRepository userRepo;
    @InjectMocks private StripeService stripeService;

    private User buildUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email("user@example.com")
            .name("Test User")
            .plan(User.Plan.FREE)
            .credits(5)
            .emailVerified(true)
            .build();
    }

    @BeforeEach
    void setUp() {
        when(stripeConfig.getPriceProMonthly()).thenReturn("price_pro_monthly_test");
        when(stripeConfig.getPricePass48h()).thenReturn("price_pass_48h_test");
        when(stripeConfig.getWebhookSecret()).thenReturn("whsec_test");
    }

    @Test
    void createCheckoutSession_userNotFound_throwsAppException() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            stripeService.createCheckoutSession(userId, "price_pro_monthly_test",
                "http://success", "http://cancel"))
            .isInstanceOf(AppException.class)
            .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(404));
    }

    @Test
    void handleWebhook_invalidSignature_throwsAppException() {
        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenThrow(new RuntimeException("Invalid signature"));

            assertThatThrownBy(() ->
                stripeService.handleWebhook("payload", "invalid-sig"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(400));
        }
    }

    @Test
    void handleWebhook_checkoutComplete_upgradesUserToPro() {
        User user = buildUser();
        UUID userId = user.getId();

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        Session session = mock(Session.class);
        Map<String, String> metadata = Map.of(
            "userId", userId.toString(),
            "priceId", "price_pro_monthly_test"
        );

        when(event.getType()).thenReturn("checkout.session.completed");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(session));
        when(session.getMetadata()).thenReturn(metadata);
        when(session.getSubscription()).thenReturn("sub_123");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenReturn(user);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            assertThat(user.getPlan()).isEqualTo(User.Plan.PRO);
            assertThat(user.getStripeSubscriptionId()).isEqualTo("sub_123");
            verify(userRepo).save(user);
        }
    }

    @Test
    void handleWebhook_checkoutComplete_pass48h_upgradesUserToPass() {
        User user = buildUser();
        UUID userId = user.getId();

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        Session session = mock(Session.class);
        Map<String, String> metadata = Map.of(
            "userId", userId.toString(),
            "priceId", "price_pass_48h_test"
        );

        when(event.getType()).thenReturn("checkout.session.completed");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(session));
        when(session.getMetadata()).thenReturn(metadata);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenReturn(user);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            assertThat(user.getPlan()).isEqualTo(User.Plan.PASS_48H);
            assertThat(user.getPlanExpiresAt()).isNotNull();
        }
    }

    @Test
    void handleWebhook_subscriptionCanceled_downgradesUserToFree() {
        User user = buildUser();
        user.setPlan(User.Plan.PRO);
        user.setStripeSubscriptionId("sub_to_cancel");

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        Subscription subscription = mock(Subscription.class);

        when(event.getType()).thenReturn("customer.subscription.deleted");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(subscription));
        when(subscription.getId()).thenReturn("sub_to_cancel");
        when(userRepo.findAll()).thenReturn(List.of(user));
        when(userRepo.save(any(User.class))).thenReturn(user);

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            assertThat(user.getPlan()).isEqualTo(User.Plan.FREE);
            assertThat(user.getCredits()).isEqualTo(5);
            assertThat(user.getStripeSubscriptionId()).isNull();
        }
    }

    @Test
    void handleWebhook_subscriptionCanceled_noMatchingUser_doesNothing() {
        User user = buildUser();
        user.setStripeSubscriptionId("sub_different");

        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        Subscription subscription = mock(Subscription.class);

        when(event.getType()).thenReturn("customer.subscription.deleted");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(subscription));
        when(subscription.getId()).thenReturn("sub_not_found");
        when(userRepo.findAll()).thenReturn(List.of(user));

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            verify(userRepo, never()).save(any());
        }
    }

    @Test
    void handleWebhook_paymentFailed_logsWarning() {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("invoice.payment_failed");
        when(event.getId()).thenReturn("evt_test_123");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            // Verify it doesn't throw and no user modifications
            verify(userRepo, never()).save(any());
        }
    }

    @Test
    void handleWebhook_unknownEvent_isIgnored() {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn("some.unknown.event");

        try (MockedStatic<Webhook> webhookMock = mockStatic(Webhook.class)) {
            webhookMock.when(() -> Webhook.constructEvent(any(), any(), any()))
                .thenReturn(event);

            stripeService.handleWebhook("payload", "sig");

            verify(userRepo, never()).save(any());
        }
    }
}
