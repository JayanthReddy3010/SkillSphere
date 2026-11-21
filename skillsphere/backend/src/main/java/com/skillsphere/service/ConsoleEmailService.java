package com.skillsphere.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Development fallback when no real SMTP provider is configured.
 * STRICT SECURITY REQUIREMENT: Never prints the OTP value to console or logs.
 */
@Service
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).hasText('${spring.mail.host:}')")
public class ConsoleEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailService.class);
    private final boolean logLinks;

    public ConsoleEmailService(@Value("${app.password-reset.log-links-to-console:true}") boolean logLinks) {
        this.logLinks = logLinks;
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String fullName, String otp, int expiryMinutes) {
        String masked = maskEmail(toEmail);
        if (logLinks) {
            log.info("[DEV EMAIL NOTIFICATION] Password reset OTP sent to recipient: {}. Code expires in {} minutes.", masked, expiryMinutes);
        } else {
            log.error("No SMTP provider configured in production environment; cannot deliver password reset OTP for {}.", masked);
            throw new com.skillsphere.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery service is not configured on this server. Please contact your system administrator.");
        }
    }

    @Override
    public void sendPasswordReset(String toEmail, String fullName, String resetLink) {
        String masked = maskEmail(toEmail);
        if (logLinks) {
            log.info("[DEV MAIL] Legacy password reset link generated for {}.", masked);
        } else {
            log.error("No mail provider configured; password reset e-mail for {} was not delivered.", masked);
            throw new com.skillsphere.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery service is not configured on this server. Please contact your system administrator.");
        }
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 2) return name.charAt(0) + "***" + domain;
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + domain;
    }
}
