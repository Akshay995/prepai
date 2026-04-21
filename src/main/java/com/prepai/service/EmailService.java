package com.prepai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import jakarta.mail.internet.MimeMessage;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    public void sendVerificationEmail(String to, String name, String token) {
        String link = frontendUrl + "/auth/verify?token=" + token;
        sendHtml(to, "Verify your PrepAI email", buildVerificationHtml(name, link));
    }

    @Async
    public void sendPasswordResetEmail(String to, String name, String token) {
        String link = frontendUrl + "/auth/reset-password?token=" + token;
        sendHtml(to, "Reset your PrepAI password", buildResetHtml(name, link));
    }

    private void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom("noreply@prepai.app", "PrepAI");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Email send failed to {}: {}", to, e.getMessage());
        }
    }

    private String buildVerificationHtml(String name, String link) {
        return "<div style='font-family:sans-serif;max-width:520px;margin:0 auto'>"
            + "<h2 style='color:#1a1a2e'>Welcome to PrepAI, " + name + "!</h2>"
            + "<p>Click the button below to verify your email address.</p>"
            + "<a href='" + link + "' style='display:inline-block;background:#1a1a2e;color:#e8ff47;"
            + "padding:12px 28px;border-radius:40px;text-decoration:none;font-weight:700'>Verify Email</a>"
            + "<p style='color:#999;font-size:13px;margin-top:20px'>Link expires in 24 hours.</p></div>";
    }

    private String buildResetHtml(String name, String link) {
        return "<div style='font-family:sans-serif;max-width:520px;margin:0 auto'>"
            + "<h2 style='color:#1a1a2e'>Reset your password</h2>"
            + "<p>Hi " + name + ", click below to set a new password.</p>"
            + "<a href='" + link + "' style='display:inline-block;background:#1a1a2e;color:#e8ff47;"
            + "padding:12px 28px;border-radius:40px;text-decoration:none;font-weight:700'>Reset Password</a>"
            + "<p style='color:#999;font-size:13px;margin-top:20px'>Link expires in 1 hour. If you didn't request this, ignore this email.</p></div>";
    }
}
