package com.velora.backend.service;

import com.velora.backend.entity.OtpPurpose;
import com.velora.backend.exception.EmailSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@Slf4j
public class BrevoEmailService implements EmailService {

    private final JavaMailSender javaMailSender;
    private final String fromEmail;

    public BrevoEmailService(JavaMailSender javaMailSender,
                             @Value("${app.brevo.from-email:${brevo.from.email:onboarding@brevo.com}}") String fromEmail) {
        this.javaMailSender = javaMailSender;
        this.fromEmail = fromEmail;
    }

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(toEmail);
            helper.setFrom(fromEmail, "Velora Security");

            String subject;
            String heading;
            String subText;

            if (purpose == OtpPurpose.LOGIN) {
                subject = "Your Login OTP — Velora";
                heading = "Login Verification";
                subText = "Use the OTP below to complete your login:";
            } else {
                subject = "Password Reset OTP — Velora";
                heading = "Password Reset Request";
                subText = "Use this OTP to reset your password:";
            }

            helper.setSubject(subject);

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

            helper.setText(htmlBody, true);

            javaMailSender.send(message);
            log.info("OTP email successfully dispatched via Brevo to: {}", toEmail);

        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to dispatch OTP email to {}: {}", toEmail, e.getMessage());
            throw new EmailSendException("Failed to send OTP email", e);
        }
    }
}
