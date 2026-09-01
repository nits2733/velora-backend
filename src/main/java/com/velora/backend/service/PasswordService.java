package com.velora.backend.service;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.PasswordResetOtp;
import com.velora.backend.entity.User;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.repository.PasswordResetOtpRepository;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.security.SecureTokenGenerator;
import lombok.RequiredArgsConstructor;
import com.velora.backend.exception.AuthenticationFailedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Password recovery and change. Split out of {@link AuthService} because it answers a
 * different question - "prove you may set this password" rather than "prove who you are".
 * <p>
 * Recovery is OTP-based: a 6-digit code is emailed, and the caller must present the
 * email + code + new password together, since the code alone isn't globally unique.
 * Both paths end the same way: every existing session is revoked. A password change is
 * exactly the moment you want any attacker's stolen session to stop working.
 */
@Service
@RequiredArgsConstructor
public class PasswordService {

    /** 6 digits is only a million possibilities - cap guesses per code before it's dead. */
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository passwordResetOtpRepository;
    private final RefreshTokenService refreshTokenService;
    private final SecureTokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetNotifier passwordResetNotifier;
    private final AuthProperties authProperties;

    /**
     * Starts recovery. Returns normally whether or not the email exists - the response
     * must not reveal which addresses have accounts, the same reason a failed login is
     * vague about which half was wrong. An unknown address simply does nothing.
     */
    @Transactional
    public void requestReset(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            // A second request invalidates the first, so only one code is ever live.
            passwordResetOtpRepository.invalidateOutstandingForUser(user.getId(), Instant.now());

            String otp = tokenGenerator.generateNumericCode();
            passwordResetOtpRepository.save(PasswordResetOtp.builder()
                    .user(user)
                    .codeHash(tokenGenerator.hash(otp))
                    .expiresAt(Instant.now().plus(authProperties.getPasswordResetTtl()))
                    .build());

            passwordResetNotifier.sendResetOtp(user, otp);
        });
    }

    /**
     * Completes recovery. The code is single-use and is spent even though the password
     * write succeeds - there is no "try again with the same code". A wrong code counts
     * against {@link #MAX_OTP_ATTEMPTS} rather than invalidating the code outright, so a
     * mistyped digit doesn't force a fresh email.
     * <p>
     * The email is required (unlike a link-based token) because a 6-digit code is not
     * globally unique - it's only meaningful scoped to one user's outstanding request.
     */
    @Transactional
    public void resetPassword(String email, String otp, String newPassword) {
        String normalizedEmail = email.trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired code"));

        PasswordResetOtp resetOtp = passwordResetOtpRepository
                .findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId())
                .filter(candidate -> candidate.isUsable(Instant.now(), MAX_OTP_ATTEMPTS))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired code"));

        if (!resetOtp.getCodeHash().equals(tokenGenerator.hash(otp))) {
            resetOtp.setAttempts(resetOtp.getAttempts() + 1);
            passwordResetOtpRepository.save(resetOtp);
            throw new AuthenticationFailedException("Invalid or expired code");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetOtp.setUsedAt(Instant.now());
        passwordResetOtpRepository.save(resetOtp);

        refreshTokenService.revokeAllForUser(user.getId());
    }

    /**
     * Changes the password of an already-authenticated user. The current password is
     * re-checked here even though the caller holds a valid token: a token proves the
     * session was authenticated at some point, not that the person holding the phone
     * right now knows the password.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthenticationFailedException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(userId);
    }
}
