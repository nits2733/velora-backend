package com.velora.backend.service.auth;

import com.velora.backend.entity.auth.OtpPurpose;

public interface EmailService {
    void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose);
}
