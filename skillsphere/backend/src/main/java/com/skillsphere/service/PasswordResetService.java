package com.skillsphere.service;

import com.skillsphere.dto.ResetPasswordRequest;
import com.skillsphere.dto.VerifyOtpResponse;
import com.skillsphere.entity.AccountStatus;
import com.skillsphere.entity.PasswordResetOtp;
import com.skillsphere.entity.PasswordResetToken;
import com.skillsphere.entity.User;
import com.skillsphere.exception.ApiException;
import com.skillsphere.repository.PasswordResetOtpRepository;
import com.skillsphere.repository.PasswordResetTokenRepository;
import com.skillsphere.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final String DEV_FALLBACK_PEPPER = "dev-insecure-test-pepper-do-not-use-in-production-32bytes";
    private static final int MIN_SECRET_LENGTH = 32;

    private final UserRepository userRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final PasswordResetTokenRepository legacyTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final OtpRateLimiterService rateLimiter;
    private final int expiryMinutes;
    private final int maxAttempts;
    private final Duration resetTokenLifetime;
    private final byte[] secretKeyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetOtpRepository otpRepository,
            PasswordResetTokenRepository legacyTokenRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            OtpRateLimiterService rateLimiter,
            Environment env,
            @Value("${app.otp.expiry-minutes:5}") int expiryMinutes,
            @Value("${app.otp.max-attempts:5}") int maxAttempts,
            @Value("${app.otp.reset-token-lifetime-minutes:15}") long resetTokenLifetimeMinutes,
            @Value("${app.otp.hash-secret:}") String configuredSecret,
            @Value("${app.otp.allow-dev-fallback:false}") boolean allowDevFallbackProperty) {
        this.userRepository = userRepository;
        this.otpRepository = otpRepository;
        this.legacyTokenRepository = legacyTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.expiryMinutes = expiryMinutes;
        this.maxAttempts = maxAttempts;
        this.resetTokenLifetime = Duration.ofMinutes(resetTokenLifetimeMinutes);

        // Explicit environment profile evaluation
        boolean isExplicitProduction = env != null && env.acceptsProfiles(Profiles.of("prod", "production"));
        boolean isExplicitDevOrTest = env != null && env.acceptsProfiles(Profiles.of("dev", "development", "local", "test"));
        boolean allowFallback = !isExplicitProduction && (isExplicitDevOrTest || allowDevFallbackProperty);

        if (configuredSecret != null && !configuredSecret.trim().isEmpty()) {
            String trimmedSecret = configuredSecret.trim();
            if (trimmedSecret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException("CRITICAL SECURITY CONFIGURATION ERROR: OTP_HASH_SECRET must be at least "
                        + MIN_SECRET_LENGTH + " characters long for cryptographic security.");
            }
            this.secretKeyBytes = trimmedSecret.getBytes(StandardCharsets.UTF_8);
        } else if (allowFallback) {
            log.warn("[DEV SECURITY NOTICE] OTP_HASH_SECRET is not configured; running with isolated development fallback pepper. Set OTP_HASH_SECRET in production.");
            this.secretKeyBytes = DEV_FALLBACK_PEPPER.getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalStateException("CRITICAL SECURITY CONFIGURATION ERROR: A non-empty, strong OTP_HASH_SECRET (at least "
                    + MIN_SECRET_LENGTH + " characters) is strictly required in production mode. Set OTP_HASH_SECRET in your environment.");
        }
    }

    /**
     * Initiates the OTP password reset flow.
     * Always completes silently to prevent email enumeration.
     */
    @Transactional
    public void requestResetOtp(String rawEmail, String clientIp) {
        String email = rawEmail != null ? rawEmail.trim().toLowerCase() : "";
        if (email.isBlank()) {
            return;
        }

        rateLimiter.assertCanRequestOtp(email, clientIp);
        rateLimiter.recordRequest(email, clientIp);

        Optional<User> optionalUser = userRepository.findByEmail(email)
                .filter(u -> u.getAccountStatus() == AccountStatus.ACTIVE);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();

            // Invalidate any existing active OTP for this user and purpose
            otpRepository.invalidateActiveOtpsByUserAndPurpose(user, "PASSWORD_RESET");

            // Generate cryptographically secure 6-digit OTP code
            int code = 100000 + secureRandom.nextInt(900000);
            String plainOtp = String.valueOf(code);

            // Store HMAC-SHA256 hash using server-side pepper and userId
            String otpHash = hmacSha256(user.getId(), plainOtp);
            Instant expiresAt = Instant.now().plus(Duration.ofMinutes(expiryMinutes));

            PasswordResetOtp otpEntity = new PasswordResetOtp(user, otpHash, "PASSWORD_RESET", expiresAt, maxAttempts);
            otpRepository.save(otpEntity);

            // Deliver branded email containing the OTP
            emailService.sendPasswordResetOtp(user.getEmail(), user.getFullName(), plainOtp, expiryMinutes);
        }
    }

    /**
     * Resends an OTP verification code with cooldown enforcement.
     */
    @Transactional
    public void resendOtp(String rawEmail, String clientIp) {
        requestResetOtp(rawEmail, clientIp);
    }

    /**
     * Verifies the 6-digit OTP code against the stored HMAC-SHA256 cryptographic hash.
     * Upon success, issues a single-use, short-lived reset authorization token.
     */
    @Transactional
    public VerifyOtpResponse verifyOtp(String rawEmail, String rawOtp) {
        String email = rawEmail != null ? rawEmail.trim().toLowerCase() : "";
        String enteredOtp = rawOtp != null ? rawOtp.trim() : "";

        if (email.isBlank() || !enteredOtp.matches("^\\d{6}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please enter a valid 6-digit verification code.");
        }

        User user = userRepository.findByEmail(email)
                .filter(u -> u.getAccountStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Invalid or expired verification code. Please request a new one."));

        PasswordResetOtp otpRecord = otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active verification code found. Please request a new code."));

        // Check if already consumed
        if (otpRecord.getConsumedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This verification code has already been used. Please request a new one.");
        }

        // Check attempt limit
        if (otpRecord.isMaxAttemptsExceeded()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Maximum verification attempts exceeded. Please request a new code.");
        }

        // Check expiration
        if (otpRecord.isOtpExpired()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Verification code has expired. Please request a new code.");
        }

        // Constant-time HMAC-SHA256 verification (keyed with server-side pepper and userId)
        String expectedHash = otpRecord.getOtpHash();
        String candidateHmac = hmacSha256(user.getId(), enteredOtp);
        boolean matches = MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                candidateHmac.getBytes(StandardCharsets.UTF_8)
        );

        // Temporary backward compatibility: During rollout transition, allow in-flight legacy SHA-256 OTPs.
        // SAFE REMOVAL MILESTONE: All OTP records expire within 5 minutes of issuance.
        // Once all outstanding pre-HMAC records are expired (> 5 minutes post-deployment), this fallback
        // block can be safely removed in the next release without user impact.
        if (!matches && otpRecord.getCreatedAt() != null) {
            long ageSeconds = Duration.between(otpRecord.getCreatedAt(), Instant.now()).getSeconds();
            if (ageSeconds >= 0 && ageSeconds <= (expiryMinutes * 60L)) {
                String legacySha = sha256(enteredOtp);
                if (MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8), legacySha.getBytes(StandardCharsets.UTF_8))) {
                    matches = true;
                }
            }
        }

        if (!matches) {
            otpRecord.incrementAttempt();
            otpRepository.save(otpRecord);
            if (otpRecord.isMaxAttemptsExceeded()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Maximum verification attempts exceeded. Please request a new code.");
            }
            int remaining = otpRecord.getMaxAttempts() - otpRecord.getAttemptCount();
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Incorrect verification code. " + remaining + " attempt" + (remaining == 1 ? "" : "s") + " remaining.");
        }

        // Check if previously verified
        if (otpRecord.getVerifiedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This verification code has already been verified. Please proceed to set a new password.");
        }

        // Generate cryptographically secure random 256-bit reset authorization token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String resetToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        String resetTokenHash = sha256(resetToken);
        Instant resetTokenExpiry = Instant.now().plus(resetTokenLifetime);

        otpRecord.markVerified(resetTokenHash, resetTokenExpiry);
        otpRepository.save(otpRecord);

        return new VerifyOtpResponse(resetToken, "Verification code verified successfully. Please set your new password.");
    }

    /**
     * Executes the password reset using the single-use reset authorization token.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        if (!req.password().equals(req.confirmPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please correct the highlighted fields.",
                    Map.of("confirmPassword", "Passwords do not match."));
        }

        String rawToken = req.token() != null ? req.token().trim() : "";
        if (rawToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Reset authorization token is missing.");
        }

        String tokenHash = sha256(rawToken);

        // 1. Check in OTP table
        Optional<PasswordResetOtp> otpRecordOpt = otpRepository.findByResetTokenHash(tokenHash);
        if (otpRecordOpt.isPresent()) {
            PasswordResetOtp otpRecord = otpRecordOpt.get();
            if (!otpRecord.isResetTokenValid()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This reset session is invalid or has expired. Please request a new verification code.");
            }

            User user = otpRecord.getUser();
            if (user.getAccountStatus() != AccountStatus.ACTIVE) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This account is not active.");
            }

            user.setPasswordHash(passwordEncoder.encode(req.password()));
            userRepository.save(user);

            // Mark single-use token consumed
            otpRecord.markConsumed();
            otpRepository.save(otpRecord);

            // Invalidate any other active OTPs and reset tokens for this user
            otpRepository.invalidateAllForUser(user);
            legacyTokenRepository.deleteByUser(user);

            // Reset rate limiter for user email
            rateLimiter.reset(user.getEmail());
            return;
        }

        // 2. Check legacy password_reset_tokens table (backward compatibility)
        Optional<PasswordResetToken> legacyTokenOpt = legacyTokenRepository.findByTokenHash(tokenHash);
        if (legacyTokenOpt.isPresent()) {
            PasswordResetToken legacyToken = legacyTokenOpt.get();
            if (!legacyToken.isUsable()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This reset link is invalid or has expired. Request a new one.");
            }

            User user = legacyToken.getUser();
            user.setPasswordHash(passwordEncoder.encode(req.password()));
            userRepository.save(user);
            legacyToken.markUsed();
            legacyTokenRepository.save(legacyToken);
            return;
        }

        throw new ApiException(HttpStatus.BAD_REQUEST,
                "This reset session is invalid or has expired. Please request a new verification code.");
    }

    /**
     * Legacy link-based method kept for backward compatibility.
     */
    @Transactional
    public void requestReset(String rawEmail) {
        requestResetOtp(rawEmail, null);
    }

    /**
     * Computes HMAC-SHA256 for OTP hashing bound to the user ID and server-side secret pepper.
     */
    private String hmacSha256(Long userId, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(this.secretKeyBytes, "HmacSHA256");
            mac.init(secretKey);
            String message = (userId != null ? userId : "0") + ":" + otp;
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 for OTP", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
