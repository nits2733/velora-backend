package com.velora.backend.service;

import com.velora.backend.entity.OtpPurpose;

public interface EmailService {
    void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose);
}
