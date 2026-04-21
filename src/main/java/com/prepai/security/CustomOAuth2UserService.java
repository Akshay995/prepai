package com.prepai.security;

import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.*;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        OAuth2User oauth2User = delegate.loadUser(request);

        String registrationId = request.getClientRegistration().getRegistrationId();
        Map<String, Object> attrs = oauth2User.getAttributes();

        String email = (String) attrs.get("email");
        String name = (String) attrs.get("name");
        String picture = (String) attrs.get("picture");
        String providerId = (String) attrs.get("sub");

        User user = userRepository.findByEmail(email).orElseGet(() ->
            userRepository.findByProviderAndProviderId(registrationId, providerId)
                .orElse(null));

        if (user == null) {
            user = User.builder()
                .email(email)
                .name(name)
                .avatarUrl(picture)
                .provider(registrationId)
                .providerId(providerId)
                .emailVerified(true)
                .build();
        } else {
            user.setName(name);
            user.setAvatarUrl(picture);
        }
        userRepository.save(user);

        return oauth2User;
    }
}
