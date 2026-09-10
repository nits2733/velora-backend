package com.velora.backend.service.auth;

import com.velora.backend.entity.auth.OtpPurpose;
import com.velora.backend.exception.EmailSendException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrevoEmailServiceTest {

    @Mock
    private JavaMailSender javaMailSender;

    private BrevoEmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new BrevoEmailService(javaMailSender, "test@velora.test");
    }

    @Test
    void sendsLoginOtpEmailSuccessfully() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendOtpEmail("user@velora.test", "123456", OtpPurpose.LOGIN);

        verify(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendsPasswordResetOtpEmailSuccessfully() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.sendOtpEmail("user@velora.test", "654321", OtpPurpose.PASSWORD_RESET);

        verify(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    void throwsEmailSendExceptionWhenMailSenderFails() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP relay unavailable")).when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> emailService.sendOtpEmail("user@velora.test", "123456", OtpPurpose.LOGIN))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("Failed to send OTP email");
    }
}
