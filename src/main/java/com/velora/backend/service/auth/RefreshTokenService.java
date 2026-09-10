package com.velora.backend.service.auth;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.auth.RefreshToken;
import com.velora.backend.entity.user.User;
import com.velora.backend.repository.auth.RefreshTokenRepository;
import com.velora.backend.security.SecureTokenGenerator;
import lombok.RequiredArgsConstructor;
import com.velora.backend.exception.AuthenticationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the refresh-token half of a session. Access tokens stay stateless and short-lived;
 * everything that needs to be revocable lives here, in rows that can be struck out.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureTokenGenerator tokenGenerator;
    private final AuthProperties authProperties;

    /** Mints a session and returns the raw token - the only time it exists in plaintext. */
    @Transactional
    public String issue(User user) {
        String rawToken = tokenGenerator.generate();

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(tokenGenerator.hash(rawToken))
                .expiresAt(Instant.now().plus(authProperties.getRefreshTokenTtl()))
                .build());

        return rawToken;
    }

    /**
     * Validates a presented token and immediately revokes it, returning its owner.
     * <p>
     * Rotation is the point: a refresh token is single-use, so a stolen one stops working
     * as soon as the real client refreshes. Expired, revoked and unknown tokens are all
     * rejected identically - the client can't learn anything from which it was.
     */
    @Transactional
    public User consumeAndRotate(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .filter(candidate -> candidate.isUsable(Instant.now()))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid or expired refresh token"));

        token.setRevokedAt(Instant.now());
        refreshTokenRepository.save(token);

        return token.getUser();
    }

    /**
     * Logout. Deliberately silent about tokens it can't find: logging out with a token
     * that was already revoked, or was never valid, is not an error worth reporting - the
     * caller's intent (be logged out) is satisfied either way.
     */
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    /** Logout everywhere - also used whenever a password changes. */
    @Transactional
    public int revokeAllForUser(Long userId) {
        return refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }
}
