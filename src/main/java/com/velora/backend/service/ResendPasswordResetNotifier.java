package com.velora.backend.service;

import com.velora.backend.config.ResendProperties;
import com.velora.backend.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class ResendPasswordResetNotifier implements PasswordResetNotifier {

    private final EmailService emailService;
    private final ResendProperties resendProperties;

    @Override
    public void sendResetToken(User user, String rawToken) {
        String encodedToken = URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String resetLink;

        if (resendProperties.getResetUrlTemplate() != null && resendProperties.getResetUrlTemplate().contains("%s")) {
            resetLink = String.format(resendProperties.getResetUrlTemplate(), encodedToken);
        } else {
            resetLink = resendProperties.getFrontendUrl() + "/reset-password?token=" + encodedToken;
        }

        String subject = "Reset your Velora password";

        String htmlBody = String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; margin: 0; padding: 40px 20px; }
                    .card { max-width: 540px; margin: 0 auto; background: #ffffff; border-radius: 12px; padding: 36px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }
                    .logo { font-size: 24px; font-weight: 700; color: #0f172a; margin-bottom: 24px; }
                    .title { font-size: 20px; font-weight: 600; margin-bottom: 16px; color: #0f172a; }
                    .text { font-size: 15px; line-height: 1.6; color: #475569; margin-bottom: 24px; }
                    .btn { display: inline-block; background-color: #2563eb; color: #ffffff !important; font-weight: 600; text-decoration: none; padding: 12px 28px; border-radius: 8px; font-size: 15px; margin-bottom: 24px; }
                    .footer { font-size: 13px; color: #94a3b8; line-height: 1.5; border-top: 1px solid #e2e8f0; padding-top: 20px; margin-top: 20px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="logo">🏠 Velora</div>
                    <div class="title">Password Reset Request</div>
                    <p class="text">Hello %s,</p>
                    <p class="text">We received a request to reset the password for your Velora account. Click the button below to choose a new password. This link is valid for 15 minutes.</p>
                    <a href="%s" class="btn" target="_blank">Reset Password</a>
                    <p class="text" style="font-size: 13px; color: #64748b;">Or copy and paste this link into your browser:<br><a href="%s" style="color: #2563eb; word-break: break-all;">%s</a></p>
                    <div class="footer">
                      If you did not request a password reset, no action is required. Your account remains secure.
                    </div>
                  </div>
                </body>
                </html>
                """,
                escapeHtml(user.getFullName()),
                resetLink,
                resetLink,
                resetLink
        );

        String textBody = String.format("""
                Hello %s,

                We received a request to reset your Velora account password.
                Please visit the following link to set a new password (valid for 15 minutes):

                %s

                If you did not request this, you can safely ignore this email.
                """,
                user.getFullName(),
                resetLink
        );

        log.info("Sending password reset email to user {}", user.getEmail());
        emailService.sendEmail(user.getEmail(), subject, htmlBody, textBody);
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
