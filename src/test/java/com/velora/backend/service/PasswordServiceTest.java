package com.velora.backend.service;

import com.velora.backend.config.AuthProperties;
import com.velora.backend.entity.PasswordResetOtp;
import com.velora.backend.entity.Role;
import com.velora.backend.entity.User;
import com.velora.backend.repository.PasswordResetOtpRepository;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.security.SecureTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.velora.backend.exception.AuthenticationFailedException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetOtpRepository passwordResetOtpRepository;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private PasswordResetNotifier passwordResetNotifier;

    private final SecureTokenGenerator tokenGenerator = new SecureTokenGenerator();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private PasswordService passwordService;
    private User user;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setPasswordResetTtl(Duration.ofMinutes(30));

        passwordService = new PasswordService(userRepository, passwordResetOtpRepository,
                refreshTokenService, tokenGenerator, passwordEncoder, passwordResetNotifier, properties);

        user = User.builder()
                .id(42L).email("user@velora.test").role(Role.CUSTOMER)
                .passwordHash(passwordEncoder.encode("current123"))
                .build();
    }

    @Test
    void requestingAResetForAnUnknownEmailIsSilentlyIgnored() {
        when(userRepository.findByEmail("nobody@velora.test")).thenReturn(Optional.empty());

        passwordService.requestReset("nobody@velora.test");

        // No exception, no code, no notification - the response must not reveal
        // whether an account exists.
        verifyNoInteractions(passwordResetOtpRepository, passwordResetNotifier);
    }

    @Test
    void requestingAResetStoresOnlyAHashAndSendsTheRawCode() {
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));

        passwordService.requestReset("  User@Velora.test  ");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(passwordResetNotifier).sendResetOtp(eq(user), sent.capture());
        assertThat(sent.getValue()).matches("\\d{6}");

        ArgumentCaptor<PasswordResetOtp> saved = ArgumentCaptor.forClass(PasswordResetOtp.class);
        verify(passwordResetOtpRepository).save(saved.capture());

        assertThat(saved.getValue().getCodeHash())
                .isNotEqualTo(sent.getValue())
                .isEqualTo(tokenGenerator.hash(sent.getValue()));
    }

    @Test
    void requestingAResetInvalidatesAnyEarlierOutstandingOne() {
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));

        passwordService.requestReset("user@velora.test");

        verify(passwordResetOtpRepository).invalidateOutstandingForUser(eq(42L), any(Instant.class));
    }

    @Test
    void resettingSetsTheNewPasswordSpendsTheCodeAndEndsEverySession() {
        String rawCode = "123456";
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));
        PasswordResetOtp otp = usableOtp(rawCode);
        when(passwordResetOtpRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.of(otp));

        passwordService.resetPassword("user@velora.test", rawCode, "brandnew1");

        assertThat(passwordEncoder.matches("brandnew1", user.getPasswordHash())).isTrue();
        assertThat(otp.getUsedAt()).isNotNull();
        verify(refreshTokenService).revokeAllForUser(42L);
    }

    @Test
    void aResetCodeCannotBeUsedTwice() {
        String rawCode = "654321";
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));
        PasswordResetOtp otp = usableOtp(rawCode);
        otp.setUsedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(passwordResetOtpRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> passwordService.resetPassword("user@velora.test", rawCode, "brandnew1"))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void anExpiredResetCodeIsRejected() {
        String rawCode = "111222";
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));
        PasswordResetOtp otp = usableOtp(rawCode);
        otp.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(passwordResetOtpRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> passwordService.resetPassword("user@velora.test", rawCode, "brandnew1"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void aWrongCodeIsRejectedAndCountsAsAnAttempt() {
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));
        PasswordResetOtp otp = usableOtp("999999");
        when(passwordResetOtpRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(42L))
                .thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> passwordService.resetPassword("user@velora.test", "000000", "brandnew1"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(otp.getAttempts()).isEqualTo(1);
        assertThat(otp.getUsedAt()).isNull();
        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void resetRejectsAnUnknownEmailWithTheSameVagueMessage() {
        when(userRepository.findByEmail("nobody@velora.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordService.resetPassword("nobody@velora.test", "123456", "brandnew1"))
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(passwordResetOtpRepository);
    }

    @Test
    void changingAPasswordRequiresTheCurrentOne() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> passwordService.changePassword(42L, "wrongpass1", "brandnew1"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(passwordEncoder.matches("current123", user.getPasswordHash())).isTrue();
        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }

    @Test
    void changingAPasswordEndsEverySessionIncludingTheCallersOwn() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        passwordService.changePassword(42L, "current123", "brandnew1");

        assertThat(passwordEncoder.matches("brandnew1", user.getPasswordHash())).isTrue();
        verify(refreshTokenService).revokeAllForUser(42L);
    }

    private PasswordResetOtp usableOtp(String rawCode) {
        return PasswordResetOtp.builder()
                .id(1L)
                .user(user)
                .codeHash(tokenGenerator.hash(rawCode))
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .attempts(0)
                .build();
    }
}
