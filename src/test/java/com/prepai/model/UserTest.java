package com.prepai.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void hasPlanAccess_freePlan_withCredits_returnsTrue() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.FREE)
            .credits(3)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isTrue();
    }

    @Test
    void hasPlanAccess_freePlan_withZeroCredits_returnsFalse() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.FREE)
            .credits(0)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isFalse();
    }

    @Test
    void hasPlanAccess_freePlan_withNegativeCredits_returnsFalse() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.FREE)
            .credits(-1)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isFalse();
    }

    @Test
    void hasPlanAccess_proPlan_alwaysTrue() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.PRO)
            .credits(0)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isTrue();
    }

    @Test
    void hasPlanAccess_pass48h_notExpired_returnsTrue() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.PASS_48H)
            .planExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
            .credits(0)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isTrue();
    }

    @Test
    void hasPlanAccess_pass48h_expired_returnsFalse() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.PASS_48H)
            .planExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .credits(0)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isFalse();
    }

    @Test
    void hasPlanAccess_pass48h_nullExpiresAt_returnsFalse() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .plan(User.Plan.PASS_48H)
            .planExpiresAt(null)
            .credits(0)
            .emailVerified(true)
            .build();

        assertThat(user.hasPlanAccess()).isFalse();
    }

    @Test
    void userBuilder_defaultValues() {
        User user = User.builder()
            .email("test@example.com")
            .name("Test")
            .build();

        assertThat(user.getProvider()).isEqualTo("local");
        assertThat(user.getRole()).isEqualTo(User.Role.USER);
        assertThat(user.getPlan()).isEqualTo(User.Plan.FREE);
        assertThat(user.getCredits()).isEqualTo(5);
        assertThat(user.getEmailVerified()).isFalse();
    }

    @Test
    void userRoleEnum_values() {
        assertThat(User.Role.values()).containsExactlyInAnyOrder(User.Role.USER, User.Role.ADMIN);
    }

    @Test
    void userPlanEnum_values() {
        assertThat(User.Plan.values()).containsExactlyInAnyOrder(
            User.Plan.FREE, User.Plan.PRO, User.Plan.PASS_48H);
    }
}
