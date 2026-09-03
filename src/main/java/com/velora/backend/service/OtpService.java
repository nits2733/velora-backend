package com.velora.backend.service;

import com.velora.backend.config.OtpProperties;
import com.velora.backend.entity.Otp;
import com.velora.backend.entity.OtpPurpose;
import com.velora.backend.exception.InvalidOtpException;
import com.velora.backend.exception.MaxOtpAttemptsException;
import com.velora.backend.exception.OtpExpiredException;
import com.velora.backend.exception.ResendCooldownException;
import com.velora.backend.repository.OtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final OtpRepository otpRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final OtpProperties otpProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String generateAndSendOtp(String email, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        Instant now = Instant.now();

        // 1. Resend cooldown check
        Optional<Otp> existingOtp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(normalizedEmail, purpose);
        if (existingOtp.isPresent() && existingOtp.get().getCreatedAt() != null) {
            Instant cooldownThreshold = existingOtp.get().getCreatedAt().plusSeconds(otpProperties.getCooldownSeconds());
            if (cooldownThreshold.isAfter(now)) {
                throw new ResendCooldownException("Please wait " + otpProperties.getCooldownSeconds() + " seconds before requesting another OTP");
            }
        }

        // 2. Generate 6-digit OTP using SecureRandom
        String rawOtp = String.valueOf(100000 + secureRandom.nextInt(900000));

        // 3. Delete previous OTP records for same email + purpose
        otpRepository.deleteByEmailAndPurpose(normalizedEmail, purpose);

        // 4. Save new OTP with BCrypt hash
        Otp otpEntity = Otp.builder()
                .email(normalizedEmail)
                .otpHash(passwordEncoder.encode(rawOtp))
                .purpose(purpose)
                .attemptCount(0)
                .expiresAt(now.plus(Duration.ofMinutes(otpProperties.getExpiryMinutes())))
                .createdAt(now)
                .build();

        otpRepository.save(otpEntity);

        // 5. Send raw OTP via email service
        emailService.sendOtpEmail(normalizedEmail, rawOtp, purpose);

        return rawOtp;
    }

    @Transactional
    public boolean verifyOtp(String email, String rawOtp, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        Instant now = Instant.now();

        Otp savedOtp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(normalizedEmail, purpose)
                .orElseThrow(() -> new InvalidOtpException("Invalid OTP"));

        // Check expiration
        if (savedOtp.isExpired(now)) {
            otpRepository.delete(savedOtp);
            throw new OtpExpiredException("OTP has expired. Request a new one");
        }

        // Increment attempt count
        int newAttemptCount = savedOtp.getAttemptCount() + 1;
        savedOtp.setAttemptCount(newAttemptCount);

        if (newAttemptCount > otpProperties.getMaxAttempts()) {
            otpRepository.delete(savedOtp);
            throw new MaxOtpAttemptsException("Too many failed attempts. Please request a new OTP");
        }

        // Verify BCrypt hash match
        if (!passwordEncoder.matches(rawOtp, savedOtp.getOtpHash())) {
            otpRepository.save(savedOtp);
            throw new InvalidOtpException("Invalid OTP");
        }

        // Successfully verified: immediately delete OTP record
        otpRepository.delete(savedOtp);
        return true;
    }
}
