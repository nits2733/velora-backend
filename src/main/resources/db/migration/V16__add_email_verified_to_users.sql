-- Registration now withholds tokens until the email is verified via OTP (see
-- AuthService.register / verifyEmail) - otherwise anyone could register with someone
-- else's address and get a working session for it. Existing rows default to true since
-- those accounts were already logging in successfully before this column existed.

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT true;
