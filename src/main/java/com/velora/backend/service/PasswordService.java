package com.velora.backend.service;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.PasswordResetToken;
import com.velora.backend.entity.User;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.InvalidResetTokenException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.repository.PasswordResetTokenRepository;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.security.SecureTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Password recovery and change service.
 */
@Service
@RequiredArgsConstructor
public class PasswordService {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).+$");

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final SecureTokenGenerator tokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    /**
     * Issues a short-lived, single-use password reset token after OTP verification.
     */
    @Transactional
    public String createResetToken(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        passwordResetTokenRepository.invalidateOutstandingForUser(user.getId(), Instant.now());

        String rawToken = tokenGenerator.generate();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(tokenGenerator.hash(rawToken))
                .expiresAt(Instant.now().plus(authProperties.getPasswordResetTtl()))
                .build());

        return rawToken;
    }

    /**
     * Completes password reset using verified token and new password.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidResetTokenException("Invalid or expired reset token");
        }

        if (newPassword == null || !PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException("Password must be between 8 and 100 characters and contain at least one letter and one digit");
        }

        String tokenHash = tokenGenerator.hash(rawToken.trim());
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidResetTokenException("Invalid or expired reset token"));

        if (token.getUsedAt() != null) {
            throw new InvalidResetTokenException("Reset token already used");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidResetTokenException("Reset token has expired");
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        refreshTokenService.revokeAllForUser(user.getId());
    }

    /**
     * Changes password for an authenticated session.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthenticationFailedException("Current password is incorrect");
        }

        if (newPassword == null || !PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException("Password must be between 8 and 100 characters and contain at least one letter and one digit");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(userId);
    }
}
