package com.skillsphere.service;

import com.skillsphere.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter and cooldown enforcer for OTP password reset requests.
 * Enforces:
 * 1. 60-second cooldown per email between OTP requests.
 * 2. Sliding window limit per email (e.g. max 5 requests / 15 minutes).
 * 3. Sliding window limit per client IP (e.g. max 15 requests / 15 minutes).
 */
@Service
public class OtpRateLimiterService {

    private final long cooldownSeconds;
    private final int maxEmailRequestsPerWindow;
    private final int maxIpRequestsPerWindow;
    private final Duration windowDuration;

    private final Map<String, Instant> emailLastRequested = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> emailRequestHistory = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> ipRequestHistory = new ConcurrentHashMap<>();

    public OtpRateLimiterService(
            @Value("${app.otp.resend-cooldown-seconds:60}") long cooldownSeconds,
            @Value("${app.otp.max-requests-per-window:5}") int maxEmailRequestsPerWindow,
            @Value("${app.otp.max-ip-requests-per-window:15}") int maxIpRequestsPerWindow,
            @Value("${app.otp.rate-limit-window-minutes:15}") long windowMinutes) {
        this.cooldownSeconds = cooldownSeconds;
        this.maxEmailRequestsPerWindow = maxEmailRequestsPerWindow;
        this.maxIpRequestsPerWindow = maxIpRequestsPerWindow;
        this.windowDuration = Duration.ofMinutes(windowMinutes);
    }

    /**
     * Checks if a new OTP request is allowed for the given email and IP.
     * Throws ApiException with TOO_MANY_REQUESTS if cooldown or rate limit is breached.
     */
    public void assertCanRequestOtp(String rawEmail, String clientIp) {
        String email = rawEmail != null ? rawEmail.trim().toLowerCase() : "";
        Instant now = Instant.now();

        // 1. Check Cooldown
        Instant lastTime = emailLastRequested.get(email);
        if (lastTime != null) {
            long elapsedSeconds = Duration.between(lastTime, now).toSeconds();
            if (elapsedSeconds < cooldownSeconds) {
                long waitSeconds = cooldownSeconds - elapsedSeconds;
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait " + waitSeconds + " second" + (waitSeconds == 1 ? "" : "s") + " before requesting another code.");
            }
        }

        // 2. Check per-email rate limit
        if (!email.isEmpty()) {
            cleanAndCheckLimit(emailRequestHistory, email, now, maxEmailRequestsPerWindow,
                    "Too many password recovery requests for this email. Please try again later.");
        }

        // 3. Check per-IP rate limit
        if (clientIp != null && !clientIp.isBlank()) {
            cleanAndCheckLimit(ipRequestHistory, clientIp.trim(), now, maxIpRequestsPerWindow,
                    "Too many requests from your IP address. Please try again in a few minutes.");
        }
    }

    /**
     * Records a successful OTP issuance.
     */
    public void recordRequest(String rawEmail, String clientIp) {
        String email = rawEmail != null ? rawEmail.trim().toLowerCase() : "";
        Instant now = Instant.now();

        if (!email.isEmpty()) {
            emailLastRequested.put(email, now);
            emailRequestHistory.compute(email, (k, deque) -> {
                if (deque == null) deque = new ArrayDeque<>();
                deque.addLast(now);
                return deque;
            });
        }

        if (clientIp != null && !clientIp.isBlank()) {
            String ip = clientIp.trim();
            ipRequestHistory.compute(ip, (k, deque) -> {
                if (deque == null) deque = new ArrayDeque<>();
                deque.addLast(now);
                return deque;
            });
        }
    }

    /**
     * Clears rate limit state for an email upon successful password reset.
     */
    public void reset(String rawEmail) {
        if (rawEmail == null) return;
        String email = rawEmail.trim().toLowerCase();
        emailLastRequested.remove(email);
        emailRequestHistory.remove(email);
    }

    private void cleanAndCheckLimit(Map<String, Deque<Instant>> map, String key, Instant now, int maxRequests, String message) {
        Deque<Instant> deque = map.get(key);
        if (deque == null) return;

        synchronized (deque) {
            Instant cutoff = now.minus(windowDuration);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
            }
        }
    }
}
