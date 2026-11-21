package com.skillsphere.service;

import com.skillsphere.dto.ResetPasswordRequest;
import com.skillsphere.dto.VerifyOtpResponse;
import com.skillsphere.entity.AccountStatus;
import com.skillsphere.entity.PasswordResetOtp;
import com.skillsphere.entity.User;
import com.skillsphere.exception.ApiException;
import com.skillsphere.repository.PasswordResetOtpRepository;
import com.skillsphere.repository.PasswordResetTokenRepository;
import com.skillsphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private static final String TEST_SECRET = "test-only-hmac-secret-pepper-32-bytes-minimum";

    private UserRepository userRepository;
    private PasswordResetOtpRepository otpRepository;
    private PasswordResetTokenRepository legacyTokenRepository;
    private PasswordEncoder passwordEncoder;
    private EmailService emailService;
    private OtpRateLimiterService rateLimiter;
    private Environment environment;
    private PasswordResetService resetService;

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSha256(Long userId, String otp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            String message = (userId != null ? userId : "0") + ":" + otp;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        otpRepository = mock(PasswordResetOtpRepository.class);
        legacyTokenRepository = mock(PasswordResetTokenRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        emailService = mock(EmailService.class);
        rateLimiter = new OtpRateLimiterService(60, 5, 15, 15);
        environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        resetService = new PasswordResetService(
                userRepository,
                otpRepository,
                legacyTokenRepository,
                passwordEncoder,
                emailService,
                rateLimiter,
                environment,
                5,
                5,
                15,
                TEST_SECRET,
                false
        );
    }

    private User createTestUser(Long id, String email, AccountStatus status) {
        User user = new User();
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (Exception ignored) {}
        user.setEmail(email);
        user.setFullName("Test User");
        user.setPasswordHash(passwordEncoder.encode("OldPass123!"));
        user.setAccountStatus(status);
        return user;
    }

    @Test
    @DisplayName("1. Valid registered email request sends OTP and stores HMAC-SHA256 hashed OTP")
    void requestResetOtp_validRegisteredEmail_succeedsWithHmac() {
        User user = createTestUser(42L, "alice@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("alice@skillsphere.com")).thenReturn(Optional.of(user));

        resetService.requestResetOtp("alice@skillsphere.com", "127.0.0.1");

        verify(otpRepository).invalidateActiveOtpsByUserAndPurpose(user, "PASSWORD_RESET");
        ArgumentCaptor<PasswordResetOtp> otpCaptor = ArgumentCaptor.forClass(PasswordResetOtp.class);
        verify(otpRepository).save(otpCaptor.capture());

        PasswordResetOtp savedOtp = otpCaptor.getValue();
        assertEquals(user, savedOtp.getUser());
        assertEquals(64, savedOtp.getOtpHash().length());
        assertFalse(savedOtp.isOtpExpired());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetOtp(eq("alice@skillsphere.com"), eq("Test User"), codeCaptor.capture(), eq(5));
        String plainOtp = codeCaptor.getValue();
        assertTrue(plainOtp.matches("^\\d{6}$"));

        String expectedHmac = hmacSha256(42L, plainOtp, TEST_SECRET);
        assertEquals(expectedHmac, savedOtp.getOtpHash());
    }

    @Test
    @DisplayName("2. Unregistered email request completes silently without sending email")
    void requestResetOtp_unregisteredEmail_silentGenericResponse() {
        when(userRepository.findByEmail("unknown@skillsphere.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> resetService.requestResetOtp("unknown@skillsphere.com", "127.0.0.1"));

        verify(otpRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("3. Inactive account completes silently without sending email")
    void requestResetOtp_inactiveAccount_silentGenericResponse() {
        User user = createTestUser(99L, "inactive@skillsphere.com", AccountStatus.SUSPENDED);
        when(userRepository.findByEmail("inactive@skillsphere.com")).thenReturn(Optional.of(user));

        assertDoesNotThrow(() -> resetService.requestResetOtp("inactive@skillsphere.com", "127.0.0.1"));

        verify(otpRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("4. Resend cooldown enforces 60-second wait")
    void requestResetOtp_cooldownEnforced() {
        User user = createTestUser(1L, "bob@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("bob@skillsphere.com")).thenReturn(Optional.of(user));

        resetService.requestResetOtp("bob@skillsphere.com", "127.0.0.1");

        ApiException ex = assertThrows(ApiException.class, () ->
                resetService.requestResetOtp("bob@skillsphere.com", "127.0.0.1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertTrue(ex.getMessage().contains("Please wait"));
    }

    @Test
    @DisplayName("5. Successful OTP verification with HMAC returns single-use reset authorization token")
    void verifyOtp_correctOtpHmac_returnsResetToken() {
        User user = createTestUser(5L, "carol@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("carol@skillsphere.com")).thenReturn(Optional.of(user));

        String otp = "123456";
        String storedHmac = hmacSha256(5L, otp, TEST_SECRET);
        PasswordResetOtp record = new PasswordResetOtp(user, storedHmac, "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        VerifyOtpResponse resp = resetService.verifyOtp("carol@skillsphere.com", otp);

        assertNotNull(resp.resetToken());
        assertFalse(resp.resetToken().isBlank());
        assertTrue(resp.message().contains("verified"));
        assertNotNull(record.getVerifiedAt());
        assertNotNull(record.getResetTokenHash());
        assertEquals(sha256(resp.resetToken()), record.getResetTokenHash());
    }

    @Test
    @DisplayName("6. Backward compatibility: In-flight legacy SHA-256 OTP verifies within 5-minute window")
    void verifyOtp_legacySha256_verifiesGracefullyWithinWindow() {
        User user = createTestUser(7L, "legacy@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("legacy@skillsphere.com")).thenReturn(Optional.of(user));

        String otp = "654321";
        String legacyHash = sha256(otp);
        PasswordResetOtp record = new PasswordResetOtp(user, legacyHash, "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(3)), 5);

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        VerifyOtpResponse resp = resetService.verifyOtp("legacy@skillsphere.com", otp);
        assertNotNull(resp.resetToken());
        assertNotNull(record.getVerifiedAt());
    }

    @Test
    @DisplayName("7. Incorrect OTP increments attempt count and returns remaining attempts")
    void verifyOtp_incorrectOtp_incrementsAttempt() {
        User user = createTestUser(8L, "david@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("david@skillsphere.com")).thenReturn(Optional.of(user));

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(8L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        ApiException ex = assertThrows(ApiException.class, () ->
                resetService.verifyOtp("david@skillsphere.com", "999999"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Incorrect verification code"));
        assertTrue(ex.getMessage().contains("4 attempts remaining"));
        assertEquals(1, record.getAttemptCount());
    }

    @Test
    @DisplayName("8. Maximum OTP attempts (5) locks out further verification")
    void verifyOtp_maxAttemptsExceeded_locksOut() {
        User user = createTestUser(9L, "eve@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("eve@skillsphere.com")).thenReturn(Optional.of(user));

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(9L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        for (int i = 0; i < 5; i++) {
            record.incrementAttempt();
        }

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        ApiException ex = assertThrows(ApiException.class, () ->
                resetService.verifyOtp("eve@skillsphere.com", "123456"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("Maximum verification attempts exceeded"));
    }

    @Test
    @DisplayName("9. Expired OTP is rejected")
    void verifyOtp_expiredOtp_rejected() {
        User user = createTestUser(10L, "frank@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("frank@skillsphere.com")).thenReturn(Optional.of(user));

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(10L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().minus(Duration.ofSeconds(10)), 5);

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        ApiException ex = assertThrows(ApiException.class, () ->
                resetService.verifyOtp("frank@skillsphere.com", "123456"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    @DisplayName("10. Already consumed OTP cannot be verified")
    void verifyOtp_consumedOtp_rejected() {
        User user = createTestUser(11L, "grace@skillsphere.com", AccountStatus.ACTIVE);
        when(userRepository.findByEmail("grace@skillsphere.com")).thenReturn(Optional.of(user));

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(11L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        record.markConsumed();

        when(otpRepository.findLatestByUserAndPurpose(user, "PASSWORD_RESET")).thenReturn(Optional.of(record));

        ApiException ex = assertThrows(ApiException.class, () ->
                resetService.verifyOtp("grace@skillsphere.com", "123456"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("already been used"));
    }

    @Test
    @DisplayName("11. Successful password reset with valid reset token updates password and invalidates tokens")
    void resetPassword_validToken_succeeds() {
        User user = createTestUser(12L, "helen@skillsphere.com", AccountStatus.ACTIVE);
        String resetToken = "valid-reset-token-sample";
        String tokenHash = sha256(resetToken);

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(12L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        record.markVerified(tokenHash, Instant.now().plus(Duration.ofMinutes(15)));

        when(otpRepository.findByResetTokenHash(tokenHash)).thenReturn(Optional.of(record));

        ResetPasswordRequest req = new ResetPasswordRequest(resetToken, "NewSecurePassword123!", "NewSecurePassword123!");
        assertDoesNotThrow(() -> resetService.resetPassword(req));

        assertTrue(passwordEncoder.matches("NewSecurePassword123!", user.getPasswordHash()));
        verify(userRepository).save(user);
        assertNotNull(record.getConsumedAt());
        verify(otpRepository).invalidateAllForUser(user);
    }

    @Test
    @DisplayName("12. Password reset fails when passwords do not match")
    void resetPassword_mismatchedPassword_throwsBadRequest() {
        ResetPasswordRequest req = new ResetPasswordRequest("token", "NewSecurePassword123!", "Different123!");
        ApiException ex = assertThrows(ApiException.class, () -> resetService.resetPassword(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getFieldErrors().containsKey("confirmPassword"));
    }

    @Test
    @DisplayName("13. Expired reset token is rejected")
    void resetPassword_expiredResetToken_rejected() {
        User user = createTestUser(13L, "ivan@skillsphere.com", AccountStatus.ACTIVE);
        String resetToken = "expired-token";
        String tokenHash = sha256(resetToken);

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(13L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        record.markVerified(tokenHash, Instant.now().minus(Duration.ofSeconds(1)));

        when(otpRepository.findByResetTokenHash(tokenHash)).thenReturn(Optional.of(record));

        ResetPasswordRequest req = new ResetPasswordRequest(resetToken, "NewSecure123!", "NewSecure123!");
        ApiException ex = assertThrows(ApiException.class, () -> resetService.resetPassword(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("invalid or has expired"));
    }

    @Test
    @DisplayName("14. Reused reset token is rejected on second attempt")
    void resetPassword_reusedResetToken_rejected() {
        User user = createTestUser(14L, "judy@skillsphere.com", AccountStatus.ACTIVE);
        String resetToken = "reused-token";
        String tokenHash = sha256(resetToken);

        PasswordResetOtp record = new PasswordResetOtp(user, hmacSha256(14L, "123456", TEST_SECRET), "PASSWORD_RESET",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        record.markVerified(tokenHash, Instant.now().plus(Duration.ofMinutes(15)));
        record.markConsumed();

        when(otpRepository.findByResetTokenHash(tokenHash)).thenReturn(Optional.of(record));

        ResetPasswordRequest req = new ResetPasswordRequest(resetToken, "NewSecure123!", "NewSecure123!");
        ApiException ex = assertThrows(ApiException.class, () -> resetService.resetPassword(req));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("invalid or has expired"));
    }

    @Test
    @DisplayName("15. Production mode with missing OTP_HASH_SECRET fails fast with IllegalStateException")
    void constructor_missingSecretInProduction_throwsIllegalStateException() {
        Environment prodEnv = mock(Environment.class);
        when(prodEnv.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () ->
                new PasswordResetService(
                        userRepository,
                        otpRepository,
                        legacyTokenRepository,
                        passwordEncoder,
                        emailService,
                        rateLimiter,
                        prodEnv,
                        5,
                        5,
                        15,
                        "",    // Missing secret
                        false  // Production mode: allowDevFallback = false
                ));
    }

    @Test
    @DisplayName("16. Secret shorter than 32 characters is rejected with IllegalStateException")
    void constructor_weakSecret_throwsIllegalStateException() {
        Environment anyEnv = mock(Environment.class);
        assertThrows(IllegalStateException.class, () ->
                new PasswordResetService(
                        userRepository,
                        otpRepository,
                        legacyTokenRepository,
                        passwordEncoder,
                        emailService,
                        rateLimiter,
                        anyEnv,
                        5,
                        5,
                        15,
                        "too-short-secret-key",
                        false
                ));
    }

    @Test
    @DisplayName("17. Hardcoded development fallback refused when production profile is active")
    void constructor_fallbackRefusedInProductionProfile() {
        Environment prodProfileEnv = mock(Environment.class);
        when(prodProfileEnv.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                new PasswordResetService(
                        userRepository,
                        otpRepository,
                        legacyTokenRepository,
                        passwordEncoder,
                        emailService,
                        rateLimiter,
                        prodProfileEnv,
                        5,
                        5,
                        15,
                        "",
                        true // Even if allowDevFallback is true, prod profile MUST refuse it!
                ));
    }

    @Test
    @DisplayName("18. Rate limiter blocks IP flooding after window limit")
    void rateLimiter_blocksIpFlooding() {
        OtpRateLimiterService limiter = new OtpRateLimiterService(0, 10, 3, 15);
        String ip = "192.168.1.100";

        limiter.assertCanRequestOtp("user1@example.com", ip);
        limiter.recordRequest("user1@example.com", ip);

        limiter.assertCanRequestOtp("user2@example.com", ip);
        limiter.recordRequest("user2@example.com", ip);

        limiter.assertCanRequestOtp("user3@example.com", ip);
        limiter.recordRequest("user3@example.com", ip);

        ApiException ex = assertThrows(ApiException.class, () ->
                limiter.assertCanRequestOtp("user4@example.com", ip));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertTrue(ex.getMessage().contains("IP address"));
    }
}
