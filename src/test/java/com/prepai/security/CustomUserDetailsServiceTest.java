package com.prepai.security;

import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private CustomUserDetailsService service;

    private User buildUser(String email, boolean verified, User.Role role) {
        return User.builder()
            .id(UUID.randomUUID())
            .email(email)
            .name("Test User")
            .password("encoded-password")
            .role(role)
            .emailVerified(verified)
            .build();
    }

    @Test
    void loadUserByUsername_found_returnsUserDetails() {
        User user = buildUser("test@example.com", true, User.Role.USER);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("test@example.com");

        assertThat(details.getUsername()).isEqualTo("test@example.com");
        assertThat(details.getPassword()).isEqualTo("encoded-password");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
            .anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
    }

    @Test
    void loadUserByUsername_notFound_throwsUsernameNotFoundException() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("missing@example.com");
    }

    @Test
    void loadUserById_found_returnsUserDetails() {
        UUID userId = UUID.randomUUID();
        User user = buildUser("test@example.com", true, User.Role.USER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserById(userId);

        assertThat(details.getUsername()).isEqualTo("test@example.com");
    }

    @Test
    void loadUserById_notFound_throwsUsernameNotFoundException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserById(userId))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserByUsername_adminRole_hasAdminAuthority() {
        User user = buildUser("admin@example.com", true, User.Role.ADMIN);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("admin@example.com");

        assertThat(details.getAuthorities())
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    @Test
    void loadUserByUsername_emailNotVerified_userIsDisabled() {
        User user = buildUser("unverified@example.com", false, User.Role.USER);
        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("unverified@example.com");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void loadUserByUsername_nullPassword_usesEmptyString() {
        User user = User.builder()
            .id(UUID.randomUUID())
            .email("oauth@example.com")
            .name("OAuth User")
            .password(null)
            .role(User.Role.USER)
            .emailVerified(true)
            .build();
        when(userRepository.findByEmail("oauth@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("oauth@example.com");

        assertThat(details.getPassword()).isEqualTo("");
    }

    @Test
    void loadUserByUsername_accountIsNeverLocked() {
        User user = buildUser("test@example.com", true, User.Role.USER);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("test@example.com");

        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }
}
