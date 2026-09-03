-- Email OTP storage for 2FA login and password reset flows.
-- The OTP is stored as a BCrypt hash, never plain text.

CREATE TABLE otps (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    otp_hash      VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMP NOT NULL,
    purpose       VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_otps_email_purpose ON otps (email, purpose);
CREATE INDEX idx_otps_expires_at ON otps (expires_at);
