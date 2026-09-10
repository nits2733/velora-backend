package com.velora.backend.scheduler;

import com.velora.backend.repository.auth.OtpRepository;
import com.velora.backend.repository.auth.PasswordResetTokenRepository;
import com.velora.backend.repository.auth.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class OtpCleanupScheduler {

    private final OtpRepository otpRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        int deletedOtps = otpRepository.deleteByExpiresAtBefore(now);
        int deletedResetTokens = passwordResetTokenRepository.deleteByExpiresAtBeforeOrUsedAtIsNotNull(now);
        int deletedRefreshTokens = refreshTokenRepository.deleteByExpiresAtBeforeOrRevokedAtIsNotNull(now);

        log.info("Cleanup: removed {} expired OTPs, {} stale reset tokens, and {} expired refresh tokens",
                deletedOtps, deletedResetTokens, deletedRefreshTokens);
    }
}
