package com.skillsphere.dto;

public record VerifyOtpResponse(
        String resetToken,
        String message
) {}
