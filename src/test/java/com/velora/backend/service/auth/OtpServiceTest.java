package com.velora.backend.service.auth;

import com.velora.backend.config.OtpProperties;
import com.velora.backend.entity.auth.Otp;
import com.velora.backend.entity.auth.OtpPurpose;
import com.velora.backend.exception.InvalidOtpException;
import com.velora.backend.exception.MaxOtpAttemptsException;
import com.velora.backend.exception.OtpExpiredException;
import com.velora.backend.exception.ResendCooldownException;
import com.velora.backend.repository.auth.OtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private OtpRepository otpRepository;
    @Mock
    private EmailService emailService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private OtpProperties otpProperties;
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpProperties = new OtpProperties();
        otpProperties.setExpiryMinutes(5);
        otpProperties.setMaxAttempts(5);
        otpProperties.setCooldownSeconds(60);
        otpProperties.setLength(6);

        otpService = new OtpService(otpRepository, emailService, passwordEncoder, otpProperties);
    }

    @Test
    void generatesAndSendsSixDigitOtpAndSavesHash() {
        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.empty());

        otpService.generateAndSendOtp("user@velora.test", OtpPurpose.LOGIN);

        verify(otpRepository).deleteByEmailAndPurpose("user@velora.test", OtpPurpose.LOGIN);

        ArgumentCaptor<Otp> otpCaptor = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepository).save(otpCaptor.capture());

        Otp savedOtp = otpCaptor.getValue();
        assertThat(savedOtp.getEmail()).isEqualTo("user@velora.test");
        assertThat(savedOtp.getPurpose()).isEqualTo(OtpPurpose.LOGIN);
        assertThat(savedOtp.getOtpHash()).isNotBlank();

        ArgumentCaptor<String> rawOtpCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq("user@velora.test"), rawOtpCaptor.capture(), eq(OtpPurpose.LOGIN));

        String rawOtp = rawOtpCaptor.getValue();
        assertThat(rawOtp).hasSize(6);
        assertThat(passwordEncoder.matches(rawOtp, savedOtp.getOtpHash())).isTrue();
    }

    @Test
    void throwsResendCooldownExceptionIfRequestedTooSoon() {
        Otp recentOtp = Otp.builder()
                .email("user@velora.test")
                .purpose(OtpPurpose.LOGIN)
                .createdAt(Instant.now().minus(30, ChronoUnit.SECONDS))
                .build();

        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(recentOtp));

        assertThatThrownBy(() -> otpService.generateAndSendOtp("user@velora.test", OtpPurpose.LOGIN))
                .isInstanceOf(ResendCooldownException.class)
                .hasMessageContaining("Please wait 60 seconds");
    }

    @Test
    void verifiesValidOtpAndDeletesRecord() {
        String rawOtp = "123456";
        Otp otpEntity = Otp.builder()
                .id(1L)
                .email("user@velora.test")
                .otpHash(passwordEncoder.encode(rawOtp))
                .purpose(OtpPurpose.LOGIN)
                .attemptCount(0)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(otpEntity));

        boolean verified = otpService.verifyOtp("user@velora.test", rawOtp, OtpPurpose.LOGIN);

        assertThat(verified).isTrue();
        verify(otpRepository).delete(otpEntity);
    }

    @Test
    void rejectsInvalidOtpAndIncrementsAttemptCount() {
        Otp otpEntity = Otp.builder()
                .id(1L)
                .email("user@velora.test")
                .otpHash(passwordEncoder.encode("123456"))
                .purpose(OtpPurpose.LOGIN)
                .attemptCount(0)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(otpEntity));

        assertThatThrownBy(() -> otpService.verifyOtp("user@velora.test", "999999", OtpPurpose.LOGIN))
                .isInstanceOf(InvalidOtpException.class)
                .hasMessageContaining("Invalid OTP");

        assertThat(otpEntity.getAttemptCount()).isEqualTo(1);
        verify(otpRepository).save(otpEntity);
    }

    @Test
    void throwsMaxOtpAttemptsExceptionAndDeletesWhenExceeded() {
        Otp otpEntity = Otp.builder()
                .id(1L)
                .email("user@velora.test")
                .otpHash(passwordEncoder.encode("123456"))
                .purpose(OtpPurpose.LOGIN)
                .attemptCount(5)
                .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                .build();

        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(otpEntity));

        assertThatThrownBy(() -> otpService.verifyOtp("user@velora.test", "999999", OtpPurpose.LOGIN))
                .isInstanceOf(MaxOtpAttemptsException.class)
                .hasMessageContaining("Too many failed attempts");

        verify(otpRepository).delete(otpEntity);
    }

    @Test
    void throwsOtpExpiredExceptionWhenExpired() {
        Otp otpEntity = Otp.builder()
                .id(1L)
                .email("user@velora.test")
                .otpHash(passwordEncoder.encode("123456"))
                .purpose(OtpPurpose.LOGIN)
                .attemptCount(0)
                .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                .build();

        when(otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("user@velora.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(otpEntity));

        assertThatThrownBy(() -> otpService.verifyOtp("user@velora.test", "123456", OtpPurpose.LOGIN))
                .isInstanceOf(OtpExpiredException.class)
                .hasMessageContaining("expired");

        verify(otpRepository).delete(otpEntity);
    }
}
