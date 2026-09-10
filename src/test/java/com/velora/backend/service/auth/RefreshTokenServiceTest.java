package com.velora.backend.service.auth;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.auth.RefreshToken;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.repository.auth.RefreshTokenRepository;
import com.velora.backend.security.SecureTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.velora.backend.exception.AuthenticationFailedException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final SecureTokenGenerator tokenGenerator = new SecureTokenGenerator();

    private RefreshTokenService refreshTokenService;
    private User user;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setRefreshTokenTtl(Duration.ofDays(30));

        refreshTokenService = new RefreshTokenService(refreshTokenRepository, tokenGenerator, properties);
        user = User.builder().id(42L).email("user@velora.test").role(Role.CUSTOMER).build();
    }

    @Test
    void theStoredRowNeverContainsTheTokenItself() {
        String rawToken = refreshTokenService.issue(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());

        assertThat(captor.getValue().getTokenHash())
                .isNotEqualTo(rawToken)
                .hasSize(64)
                .isEqualTo(tokenGenerator.hash(rawToken));
    }

    @Test
    void everyIssuedTokenIsDifferent() {
        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void usingATokenRevokesItSoItCannotBeReplayed() {
        String rawToken = "the-token";
        RefreshToken stored = usableToken(rawToken);
        when(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(stored));

        User returned = refreshTokenService.consumeAndRotate(rawToken);

        assertThat(returned).isSameAs(user);
        assertThat(stored.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void anAlreadyRevokedTokenIsRejected() {
        String rawToken = "revoked-token";
        RefreshToken stored = usableToken(rawToken);
        stored.setRevokedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> refreshTokenService.consumeAndRotate(rawToken))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String rawToken = "expired-token";
        RefreshToken stored = usableToken(rawToken);
        stored.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(refreshTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> refreshTokenService.consumeAndRotate(rawToken))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void anUnknownTokenIsRejectedTheSameWayAsAnExpiredOne() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.consumeAndRotate("never-issued"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid or expired refresh token");
    }

    @Test
    void loggingOutWithAnUnknownTokenIsNotAnError() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        refreshTokenService.revoke("never-issued");

        verify(refreshTokenRepository, never()).save(any());
    }

    private RefreshToken usableToken(String rawToken) {
        return RefreshToken.builder()
                .id(1L)
                .user(user)
                .tokenHash(tokenGenerator.hash(rawToken))
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();
    }
}
