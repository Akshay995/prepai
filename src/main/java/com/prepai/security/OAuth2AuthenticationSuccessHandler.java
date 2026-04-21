package com.prepai.security;

import com.prepai.model.User;
import com.prepai.repository.UserRepository;
import com.prepai.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final CustomUserDetailsService userDetailsService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        User user = userRepository.findByEmail(email).orElseThrow();

        var userDetails = userDetailsService.loadUserByUsername(email);
        String accessToken = jwtUtil.generateToken(userDetails, user.getId());
        String refreshToken = refreshTokenService.createRefreshToken(user.getId()).getToken();

        getRedirectStrategy().sendRedirect(request, response,
            frontendUrl + "/auth/callback?token=" + accessToken + "&refresh=" + refreshToken);
    }
}

