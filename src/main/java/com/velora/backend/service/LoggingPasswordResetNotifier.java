package com.velora.backend.service;

import com.velora.backend.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Stand-in delivery until an email provider is wired up: writes the code to the
 * application log so the flow is exercisable end to end in development.
 * <p>
 * Backs off automatically the moment a real {@link PasswordResetNotifier} bean exists
 * (see {@link BrevoPasswordResetNotifier}). This must not be what runs in production -
 * a reset code in a log file is a reset code available to anyone who can read logs.
 */
@Component
@ConditionalOnMissingBean(ignored = LoggingPasswordResetNotifier.class, value = PasswordResetNotifier.class)
@Slf4j
public class LoggingPasswordResetNotifier implements PasswordResetNotifier {

    @Override
    public void sendResetOtp(User user, String otp) {
        log.warn("No email delivery configured - password reset code for {} is: {}",
                user.getEmail(), otp);
    }
}
