package com.skillsphere.service;

import com.skillsphere.exception.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Production-ready SMTP email delivery service using JavaMailSender.
 * Formats responsive, enterprise-styled HTML templates with SkillSphere branding.
 * STRICT SECURITY REQUIREMENT: Never logs the OTP code.
 */
@Service
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.mail.host:}')")
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final String fromName;

    public SmtpEmailService(JavaMailSender mailSender,
                            @Value("${app.mail.from-email:no-reply@skillsphere.com}") String fromEmail,
                            @Value("${app.mail.from-name:SkillSphere Security}") String fromName) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String fullName, String otp, int expiryMinutes) {
        String recipientName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : "SkillSphere User";
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject("SkillSphere Password Reset Verification Code");

            String htmlBody = buildOtpHtmlTemplate(recipientName, otp, expiryMinutes);
            String plainText = buildOtpPlainText(recipientName, otp, expiryMinutes);

            helper.setText(plainText, htmlBody);
            mailSender.send(message);

            log.info("Password reset OTP email successfully dispatched to {}.", maskEmail(toEmail));
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("SMTP error while delivering password reset OTP to {}: {}", maskEmail(toEmail), e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to deliver verification email. Please check mail configuration or try again later.");
        }
    }

    @Override
    public void sendPasswordReset(String toEmail, String fullName, String resetLink) {
        // Fallback for legacy link-based method
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject("SkillSphere Password Reset Request");
            helper.setText("Hello " + fullName + ",\n\nPlease reset your password using: " + resetLink);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send legacy reset link to {}: {}", maskEmail(toEmail), e.getMessage());
        }
    }

    private String buildOtpHtmlTemplate(String recipientName, String otp, int expiryMinutes) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>SkillSphere Password Reset</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f1f5f9; color: #0b2545; }
                .email-container { max-width: 580px; margin: 30px auto; background-color: #ffffff; border-radius: 12px; border: 1px solid #d3dded; overflow: hidden; box-shadow: 0 10px 25px rgba(11,37,69,0.08); }
                .email-header { background: #0b2545; padding: 28px 32px; text-align: center; }
                .brand-title { color: #ffffff; font-size: 22px; font-weight: 800; letter-spacing: -0.02em; margin: 0; }
                .brand-tagline { color: #94a3b8; font-size: 13px; margin-top: 4px; }
                .email-body { padding: 36px 32px; }
                .greeting { font-size: 16px; font-weight: 600; margin-bottom: 12px; color: #0b2545; }
                .message-text { font-size: 15px; line-height: 1.6; color: #334155; margin-bottom: 24px; }
                .otp-box { background: #f0f7ff; border: 2px dashed #2563eb; border-radius: 10px; padding: 20px; text-align: center; margin: 28px 0; }
                .otp-label { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: #2563eb; margin-bottom: 8px; }
                .otp-code { font-size: 34px; font-weight: 800; letter-spacing: 8px; color: #0b2545; font-family: 'Courier New', monospace; }
                .otp-expiry { font-size: 13px; color: #64748b; margin-top: 8px; }
                .security-notice { background-color: #fff4d6; border-left: 4px solid #f59e0b; padding: 14px 16px; border-radius: 4px; font-size: 13px; line-height: 1.5; color: #78350f; margin-top: 24px; }
                .email-footer { background-color: #f8fafc; border-top: 1px solid #e2e8f0; padding: 20px 32px; text-align: center; font-size: 12px; color: #94a3b8; }
              </style>
            </head>
            <body>
              <div class="email-container">
                <div class="email-header">
                  <h1 class="brand-title">SkillSphere</h1>
                  <p class="brand-tagline">Organizational Knowledge Gap Intelligence Platform</p>
                </div>
                <div class="email-body">
                  <p class="greeting">Hello %s,</p>
                  <p class="message-text">
                    We received a request to reset your password. Use the following One-Time Password (OTP) to verify your identity and proceed with setting a new password.
                  </p>
                  <div class="otp-box">
                    <div class="otp-label">Verification Code</div>
                    <div class="otp-code">%s</div>
                    <div class="otp-expiry">This code is valid for <strong>%d minutes</strong>.</div>
                  </div>
                  <div class="security-notice">
                    <strong>Security Notice:</strong> Never share this OTP with anyone. SkillSphere administrators and support staff will never ask for your verification code. If you did not initiate this request, you can safely disregard this message or contact your IT security team.
                  </div>
                </div>
                <div class="email-footer">
                  &copy; 2026 SkillSphere. All rights reserved.<br>
                  This is an automated system notification. Please do not reply directly to this email.
                </div>
              </div>
            </body>
            </html>
            """.formatted(recipientName, otp, expiryMinutes);
    }

    private String buildOtpPlainText(String recipientName, String otp, int expiryMinutes) {
        return """
            SkillSphere — Password Reset Verification

            Hello %s,

            We received a request to reset your SkillSphere password.
            Your One-Time Password (OTP) verification code is:

            %s

            This code expires in %d minutes.

            SECURITY NOTICE:
            Never share this OTP with anyone. SkillSphere staff will never ask for your code.
            If you did not request a password reset, please ignore this email.

            — SkillSphere Team
            """.formatted(recipientName, otp, expiryMinutes);
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
