package com.velora.backend.service;

import com.velora.backend.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real email delivery via Brevo's SMTP relay ({@code spring.mail.*} in application.yml,
 * pointed at {@code smtp-relay.brevo.com}). Only activates when a Brevo SMTP key is
 * actually configured; otherwise {@link LoggingPasswordResetNotifier} is what runs, so a
 * developer without Brevo credentials still gets a working flow.
 */
@Component
@ConditionalOnExpression("!'${BREVO_SMTP_KEY:}'.isBlank()")
@Slf4j
public class BrevoPasswordResetNotifier implements PasswordResetNotifier {

    private final JavaMailSender mailSender;
    private final String fromEmail;

    public BrevoPasswordResetNotifier(JavaMailSender mailSender,
                                       @Value("${BREVO_FROM_EMAIL:}") String fromEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendResetOtp(User user, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(user.getEmail());
        message.setSubject("Your Velora password reset code");
        message.setText("""
                Hi %s,

                Your Velora password reset code is: %s

                This code expires in 30 minutes and can only be used once. If you didn't
                request this, you can safely ignore this email.

                - Velora
                """.formatted(user.getFullName(), otp));

        try {
            mailSender.send(message);
        } catch (Exception ex) {
            // A delivery failure must not surface the OTP or the underlying SMTP error to
            // the caller (requestReset() already never reveals whether the email exists),
            // but it also must not silently look identical to a successful send in logs.
            log.error("Failed to send password reset email to {}", user.getEmail(), ex);
        }
    }
}
