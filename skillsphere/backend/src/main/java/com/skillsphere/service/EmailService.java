package com.skillsphere.service;

public interface EmailService {

    /**
     * Sends a SkillSphere branded email containing the one-time password (OTP) for password recovery.
     *
     * @param toEmail       Recipient email address.
     * @param fullName      Recipient full name.
     * @param otp           Cryptographically generated 6-digit OTP code.
     * @param expiryMinutes Validity duration in minutes.
     */
    void sendPasswordResetOtp(String toEmail, String fullName, String otp, int expiryMinutes);

    /**
     * Legacy link-based password reset email method.
     */
    void sendPasswordReset(String toEmail, String fullName, String resetLink);
}
