package com.prepai.security;

import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private CustomOAuth2UserService service;

    private OAuth2UserRequest buildRequest(String registrationId) {
        ClientRegistration clientReg = ClientRegistration.withRegistrationId(registrationId)
            .clientId("test-client-id")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .authorizationUri("http://localhost/auth")
            .tokenUri("http://localhost/token")
            .userInfoUri("http://localhost/userinfo")
            .userNameAttributeName("sub")
            .build();

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "test-token",
            Instant.now(), Instant.now().plusSeconds(3600));

        return new OAuth2UserRequest(clientReg, accessToken);
    }

    private OAuth2User buildOAuth2User(Map<String, Object> attributes) {
        return new DefaultOAuth2User(Set.of(), attributes, "sub");
    }

    @Test
    void loadUser_newUser_createsAndSaves() {
        OAuth2UserRequest request = buildRequest("google");
        Map<String, Object> attrs = Map.of(
            "email", "new@example.com",
            "name", "New User",
            "picture", "http://pic.url",
            "sub", "google-sub-123"
        );
        OAuth2User oauth2User = buildOAuth2User(attrs);

        try (MockedConstruction<DefaultOAuth2UserService> mocked =
                 mockConstruction(DefaultOAuth2UserService.class,
                     (mock, ctx) -> when(mock.loadUser(any())).thenReturn(oauth2User))) {

            when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByProviderAndProviderId("google", "google-sub-123"))
                .thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            OAuth2User result = service.loadUser(request);

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();

            assertThat(savedUser.getEmail()).isEqualTo("new@example.com");
            assertThat(savedUser.getName()).isEqualTo("New User");
            assertThat(savedUser.getAvatarUrl()).isEqualTo("http://pic.url");
            assertThat(savedUser.getProvider()).isEqualTo("google");
            assertThat(savedUser.getProviderId()).isEqualTo("google-sub-123");
            assertThat(savedUser.getEmailVerified()).isTrue();
            assertThat(result).isEqualTo(oauth2User);
        }
    }

    @Test
    void loadUser_existingUserByEmail_updatesFields() {
        OAuth2UserRequest request = buildRequest("google");
        Map<String, Object> attrs = Map.of(
            "email", "existing@example.com",
            "name", "Updated Name",
            "picture", "http://new-pic.url",
            "sub", "google-sub-456"
        );
        OAuth2User oauth2User = buildOAuth2User(attrs);

        User existingUser = User.builder()
            .id(UUID.randomUUID())
            .email("existing@example.com")
            .name("Old Name")
            .avatarUrl("http://old-pic.url")
            .emailVerified(true)
            .build();

        try (MockedConstruction<DefaultOAuth2UserService> mocked =
                 mockConstruction(DefaultOAuth2UserService.class,
                     (mock, ctx) -> when(mock.loadUser(any())).thenReturn(oauth2User))) {

            when(userRepository.findByEmail("existing@example.com"))
                .thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.loadUser(request);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            assertThat(saved.getName()).isEqualTo("Updated Name");
            assertThat(saved.getAvatarUrl()).isEqualTo("http://new-pic.url");
        }
    }

    @Test
    void loadUser_existingUserByProvider_updatesFields() {
        OAuth2UserRequest request = buildRequest("google");
        Map<String, Object> attrs = Map.of(
            "email", "changed@example.com",
            "name", "Provider User",
            "picture", "http://pic.url",
            "sub", "google-sub-789"
        );
        OAuth2User oauth2User = buildOAuth2User(attrs);

        User existingUser = User.builder()
            .id(UUID.randomUUID())
            .email("old@example.com")
            .name("Old Name")
            .provider("google")
            .providerId("google-sub-789")
            .emailVerified(true)
            .build();

        try (MockedConstruction<DefaultOAuth2UserService> mocked =
                 mockConstruction(DefaultOAuth2UserService.class,
                     (mock, ctx) -> when(mock.loadUser(any())).thenReturn(oauth2User))) {

            when(userRepository.findByEmail("changed@example.com")).thenReturn(Optional.empty());
            when(userRepository.findByProviderAndProviderId("google", "google-sub-789"))
                .thenReturn(Optional.of(existingUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.loadUser(request);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Provider User");
        }
    }
}
