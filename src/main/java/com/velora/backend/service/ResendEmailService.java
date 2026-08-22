package com.velora.backend.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import com.velora.backend.config.ResendProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResendEmailService implements EmailService {

    private final ResendProperties resendProperties;

    @Override
    public void sendEmail(String to, String subject, String htmlBody, String textBody) {
        if (!resendProperties.isConfigured()) {
            log.warn("Resend API key is not configured. Email to {} with subject '{}' was skipped.", to, subject);
            return;
        }

        try {
            Resend resend = new Resend(resendProperties.getApiKey());

            CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                    .from(resendProperties.getFromEmail())
                    .to(to)
                    .subject(subject);

            if (htmlBody != null && !htmlBody.isBlank()) {
                builder.html(htmlBody);
            }
            if (textBody != null && !textBody.isBlank()) {
                builder.text(textBody);
            }

            CreateEmailResponse response = resend.emails().send(builder.build());
            log.info("Email successfully sent via Resend to {} with ID {}", to, response != null ? response.getId() : "unknown");
        } catch (ResendException e) {
            log.error("Failed to send email via Resend to {}: {}", to, e.getMessage());
            throw new RuntimeException("Email delivery failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error sending email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Email delivery failed", e);
        }
    }
}
