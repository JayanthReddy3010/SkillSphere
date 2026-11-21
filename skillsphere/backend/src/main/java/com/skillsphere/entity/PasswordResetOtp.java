package com.skillsphere.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "password_reset_otps")
public class PasswordResetOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(nullable = false, length = 32)
    private String purpose = "PASSWORD_RESET";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "reset_token_hash", unique = true, length = 64)
    private String resetTokenHash;

    @Column(name = "reset_token_expires_at")
    private Instant resetTokenExpiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PasswordResetOtp() {}

    public PasswordResetOtp(User user, String otpHash, String purpose, Instant expiresAt, int maxAttempts) {
        this.user = user;
        this.otpHash = otpHash;
        this.purpose = purpose != null ? purpose : "PASSWORD_RESET";
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getOtpHash() { return otpHash; }
    public String getPurpose() { return purpose; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getResetTokenHash() { return resetTokenHash; }
    public Instant getResetTokenExpiresAt() { return resetTokenExpiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isOtpExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isOtpValidForVerification() {
        return verifiedAt == null && consumedAt == null && !isOtpExpired() && attemptCount < maxAttempts;
    }

    public void incrementAttempt() {
        this.attemptCount++;
    }

    public boolean isMaxAttemptsExceeded() {
        return this.attemptCount >= this.maxAttempts;
    }

    public void markVerified(String tokenHash, Instant tokenExpiresAt) {
        this.verifiedAt = Instant.now();
        this.resetTokenHash = tokenHash;
        this.resetTokenExpiresAt = tokenExpiresAt;
    }

    public boolean isResetTokenValid() {
        return verifiedAt != null && consumedAt == null &&
                resetTokenExpiresAt != null && Instant.now().isBefore(resetTokenExpiresAt);
    }

    public void markConsumed() {
        this.consumedAt = Instant.now();
    }
}
