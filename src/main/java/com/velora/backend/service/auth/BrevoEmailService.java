package com.velora.backend.service.auth;

import com.velora.backend.entity.auth.OtpPurpose;
import com.velora.backend.exception.EmailSendException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Sends via Brevo's HTTPS transactional email API (api.brevo.com), not SMTP.
 * Most PaaS hosts (Render included) block or heavily throttle outbound SMTP
 * ports (587/465/25) to curb spam abuse - a plain HTTPS call on 443 has no
 * such restriction. Needs a Brevo API key (Brevo dashboard -> SMTP & API ->
 * API Keys), which is a different credential from the SMTP login/key.
 */
@Service
@Slf4j
public class BrevoEmailService implements EmailService {

    private final RestClient restClient;
    private final String fromEmail;

    public BrevoEmailService(RestClient brevoRestClient,
                              @Value("${app.brevo.from-email:onboarding@brevo.com}") String fromEmail) {
        this.restClient = brevoRestClient;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        String subject;
        String heading;
        String subText;

        if (purpose == OtpPurpose.LOGIN) {
            subject = "Your Login OTP — Velora";
            heading = "Login Verification";
            subText = "Use the OTP below to complete your login:";
        } else if (purpose == OtpPurpose.EMAIL_VERIFY) {
            subject = "Verify Your Email — Velora";
            heading = "Email Verification";
            subText = "Use this OTP to verify your email and activate your account:";
        } else {
            subject = "Password Reset OTP — Velora";
            heading = "Password Reset Request";
            subText = "Use this OTP to reset your password:";
        }

        String htmlBody = """
                <!DOCTYPE html>
                <html>
                  <body style="font-family:Arial,sans-serif;max-width:480px;margin:auto;padding:20px;color:#334155;">
                    <h2 style="color:#2563eb;margin-bottom:16px;">%s</h2>
                    <p style="font-size:15px;line-height:1.5;">%s</p>
                    <div style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#1e293b;background:#f1f5f9;padding:16px;text-align:center;border-radius:8px;margin:24px 0;">
                      %s
                    </div>
                    <p style="color:#64748b;font-size:13px;line-height:1.4;">
                      Valid for <strong>5 minutes</strong>. Do not share this with anyone.
                    </p>
                    <p style="color:#94a3b8;font-size:12px;margin-top:16px;line-height:1.4;">
                      If you did not make this request, please ignore this email or contact support.
                    </p>
                  </body>
                </html>
                """.formatted(heading, subText, otp);

        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", "Velora Security", "email", fromEmail),
                "to", List.of(Map.of("email", toEmail)),
                "subject", subject,
                "htmlContent", htmlBody
        );

        try {
            restClient.post()
                    .uri("/smtp/email")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("OTP email successfully dispatched via Brevo to: {}", toEmail);
        } catch (RestClientException e) {
            log.error("Failed to dispatch OTP email to {}: {}", toEmail, e.getMessage());
            throw new EmailSendException("Failed to send OTP email", e);
        }
    }
}
