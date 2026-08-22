package com.velora.backend.service;

import com.velora.backend.config.ResendProperties;
import com.velora.backend.entity.Role;
import com.velora.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResendPasswordResetNotifierTest {

    @Mock
    private EmailService emailService;

    private ResendProperties resendProperties;
    private ResendPasswordResetNotifier notifier;

    private User user;

    @BeforeEach
    void setUp() {
        resendProperties = new ResendProperties();
        resendProperties.setApiKey("re_test_12345");
        resendProperties.setFromEmail("onboarding@resend.dev");
        resendProperties.setResetUrlTemplate("https://app.velora.design/reset-password?token=%s");

        notifier = new ResendPasswordResetNotifier(emailService, resendProperties);

        user = User.builder()
                .id(1L)
                .email("alex@velora.test")
                .fullName("Alex Morgan")
                .role(Role.CUSTOMER)
                .build();
    }

    @Test
    void sendResetTokenFormatsEmailWithRawTokenAndDispatchesViaEmailService() {
        String rawToken = "secure-random-token-abc123";

        notifier.sendResetToken(user, rawToken);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);

        verify(emailService).sendEmail(
                eq("alex@velora.test"),
                eq("Reset your Velora password"),
                htmlCaptor.capture(),
                textCaptor.capture()
        );

        String htmlBody = htmlCaptor.getValue();
        assertThat(htmlBody).contains("Alex Morgan");
        assertThat(htmlBody).contains("https://app.velora.design/reset-password?token=secure-random-token-abc123");

        String textBody = textCaptor.getValue();
        assertThat(textBody).contains("Alex Morgan");
        assertThat(textBody).contains("https://app.velora.design/reset-password?token=secure-random-token-abc123");
    }
}
