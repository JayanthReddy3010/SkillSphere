-- ============================================================================
-- V12: Create password_reset_otps table for secure OTP-based password recovery
-- ============================================================================

CREATE TABLE password_reset_otps (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    otp_hash                VARCHAR(64)  NOT NULL,      -- SHA-256 of the 6-digit OTP code (never plain text)
    purpose                 VARCHAR(32)  NOT NULL DEFAULT 'PASSWORD_RESET',
    expires_at              TIMESTAMPTZ  NOT NULL,      -- 5-minute lifespan from creation
    attempt_count           INT          NOT NULL DEFAULT 0,
    max_attempts            INT          NOT NULL DEFAULT 5,
    verified_at             TIMESTAMPTZ,                -- Timestamp when OTP was successfully verified
    reset_token_hash        VARCHAR(64)  UNIQUE,        -- SHA-256 of the short-lived reset authorization token
    reset_token_expires_at  TIMESTAMPTZ,                -- Lifespan for the reset token (e.g., 15 minutes)
    consumed_at             TIMESTAMPTZ,                -- Timestamp when the password reset was executed
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pro_user ON password_reset_otps (user_id);
CREATE INDEX idx_pro_reset_token ON password_reset_otps (reset_token_hash);
CREATE INDEX idx_pro_user_created ON password_reset_otps (user_id, created_at DESC);
