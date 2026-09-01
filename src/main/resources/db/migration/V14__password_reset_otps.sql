-- Replaces the opaque-link password reset flow with a 6-digit OTP emailed to the user.
-- `password_reset_tokens` (V9) is left in place, unused, rather than dropped: this
-- migration only adds, per the append-only convention, and the old table is harmless
-- as an orphan.
--
-- Only the SHA-256 digest of the code is stored, same reasoning as every other token
-- table in this schema - a leaked database dump must not be a usable reset code.
-- `attempts` caps brute-forcing a 6-digit space (1,000,000 possibilities) against a
-- single OTP row before it must be re-requested.

CREATE TABLE password_reset_otps (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   CHAR(64) NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    used_at     TIMESTAMP,
    attempts    INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_otps_user_id ON password_reset_otps (user_id);
