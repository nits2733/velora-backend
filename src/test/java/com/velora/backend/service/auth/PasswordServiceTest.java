package com.velora.backend.service.auth;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.auth.PasswordResetToken;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.InvalidResetTokenException;
import com.velora.backend.repository.auth.PasswordResetTokenRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.security.SecureTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private RefreshTokenService refreshTokenService;

    private final SecureTokenGenerator tokenGenerator = new SecureTokenGenerator();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private PasswordService passwordService;
    private User user;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setPasswordResetTtl(Duration.ofMinutes(30));

        passwordService = new PasswordService(userRepository, passwordResetTokenRepository,
                refreshTokenService, tokenGenerator, passwordEncoder, properties);

        user = User.builder()
                .id(42L).email("user@velora.test").role(Role.CUSTOMER)
                .passwordHash(passwordEncoder.encode("Current@123"))
                .build();
    }

    @Test
    void createResetTokenStoresHashedTokenAndReturnsRaw() {
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));

        String rawToken = passwordService.createResetToken("user@velora.test");

        assertThat(rawToken).isNotBlank();
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(saved.capture());

        assertThat(saved.getValue().getTokenHash()).isEqualTo(tokenGenerator.hash(rawToken));
        verify(passwordResetTokenRepository).invalidateOutstandingForUser(eq(42L), any(Instant.class));
    }

    @Test
    void resettingSetsTheNewPasswordSpendsTheTokenAndEndsEverySession() {
        String rawToken = "reset-token";
        PasswordResetToken token = usableResetToken(rawToken);
        when(passwordResetTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(token));

        passwordService.resetPassword(rawToken, "BrandNew@123");

        assertThat(passwordEncoder.matches("BrandNew@123", user.getPasswordHash())).isTrue();
        assertThat(token.getUsedAt()).isNotNull();
        verify(refreshTokenService).revokeAllForUser(42L);
    }

    @Test
    void aResetTokenCannotBeUsedTwice() {
        String rawToken = "spent-token";
        PasswordResetToken token = usableResetToken(rawToken);
        token.setUsedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(passwordResetTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordService.resetPassword(rawToken, "BrandNew@123"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessageContaining("already used");

        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void anExpiredResetTokenIsRejected() {
        String rawToken = "old-token";
        PasswordResetToken token = usableResetToken(rawToken);
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(passwordResetTokenRepository.findByTokenHash(tokenGenerator.hash(rawToken)))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> passwordService.resetPassword(rawToken, "BrandNew@123"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void changingAPasswordRequiresTheCurrentOne() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> passwordService.changePassword(42L, "wrongpass1", "BrandNew@123"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(passwordEncoder.matches("Current@123", user.getPasswordHash())).isTrue();
        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void changingAPasswordEndsEverySessionIncludingTheCallersOwn() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        passwordService.changePassword(42L, "Current@123", "BrandNew@123");

        assertThat(passwordEncoder.matches("BrandNew@123", user.getPasswordHash())).isTrue();
        verify(refreshTokenService).revokeAllForUser(42L);
    }

    private PasswordResetToken usableResetToken(String rawToken) {
        return PasswordResetToken.builder()
                .id(1L)
                .user(user)
                .tokenHash(tokenGenerator.hash(rawToken))
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build();
    }
}
