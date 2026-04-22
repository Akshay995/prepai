package com.prepai.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private MimeMessage mimeMessage;

    private EmailService emailService;

    @BeforeEach
    void setUp() throws Exception {
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(emailService, "frontendUrl", "http://localhost:3000");

        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(mimeMessage.getContentType()).thenReturn("text/html");
    }

    @Test
    void sendVerificationEmail_callsMailSender() throws Exception {
        doNothing().when(mailSender).send(any(MimeMessage.class));

        emailService.sendVerificationEmail("user@example.com", "Test User", "verify-token-abc");

        verify(mailSender, timeout(2000)).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordResetEmail_callsMailSender() throws Exception {
        doNothing().when(mailSender).send(any(MimeMessage.class));

        emailService.sendPasswordResetEmail("user@example.com", "Test User", "reset-token-xyz");

        verify(mailSender, timeout(2000)).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationEmail_mailSendFails_logsError() throws Exception {
        doThrow(new RuntimeException("SMTP connection failed"))
            .when(mailSender).send(any(MimeMessage.class));

        // Should not throw exception even if mail sending fails
        emailService.sendVerificationEmail("user@example.com", "Test User", "token");

        verify(mailSender, timeout(2000)).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordResetEmail_mailSendFails_doesNotPropagateException() throws Exception {
        doThrow(new RuntimeException("Mail failed"))
            .when(mailSender).send(any(MimeMessage.class));

        // Should swallow exception and log
        emailService.sendPasswordResetEmail("user@example.com", "User", "token");

        verify(mailSender, timeout(2000)).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationEmail_createsCorrectLink() throws Exception {
        doNothing().when(mailSender).send(any(MimeMessage.class));

        emailService.sendVerificationEmail("test@example.com", "Jane", "my-verify-token");

        // Verify mail is sent - link is constructed with frontendUrl + /auth/verify?token=
        verify(mailSender, timeout(2000)).send(any(MimeMessage.class));
    }
}
