package com.velora.backend.service.auth;

import com.velora.backend.entity.auth.OtpPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Local-development stand-in for {@link BrevoEmailService}: writes the OTP to the
 * application log instead of emailing it, so sign-up/login can be exercised without a
 * working mail provider (e.g. when Brevo blocks the developer machine's IP).
 *
 * Active ONLY under the {@code local} Spring profile. Logging a live OTP defeats email
 * verification, so this must never run in a deployed environment; deployments use the
 * default profile and get {@link BrevoEmailService}.
 */
@Service
@Profile("local")
@Slf4j
public class LoggingEmailService implements EmailService {

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        log.warn("[LOCAL PROFILE - NO EMAIL SENT] {} OTP for {} is {}", purpose, toEmail, otp);
    }
}
