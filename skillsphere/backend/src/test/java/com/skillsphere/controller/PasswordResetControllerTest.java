package com.skillsphere.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillsphere.config.SecurityConfig;
import com.skillsphere.dto.*;
import com.skillsphere.exception.ApiException;
import com.skillsphere.exception.GlobalExceptionHandler;
import com.skillsphere.security.JsonErrorWriter;
import com.skillsphere.service.AuthService;
import com.skillsphere.service.PasswordResetService;
import com.skillsphere.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JsonErrorWriter.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173"
})
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private AuthService authService;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    @DisplayName("POST /api/auth/forgot-password with valid email returns 200 generic message")
    void forgotPassword_validEmail_returns200() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest("valid@skillsphere.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an account exists for that email, a 6-digit verification code has been sent."));

        verify(passwordResetService).requestResetOtp(eq("valid@skillsphere.com"), any());
    }

    @Test
    @DisplayName("POST /api/auth/forgot-password with invalid email returns 400 Bad Request")
    void forgotPassword_invalidEmail_returns400() throws Exception {
        ForgotPasswordRequest req = new ForgotPasswordRequest("not-an-email");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(passwordResetService, never()).requestResetOtp(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/resend-otp with valid email returns 200")
    void resendOtp_validEmail_returns200() throws Exception {
        ResendOtpRequest req = new ResendOtpRequest("user@skillsphere.com");

        mockMvc.perform(post("/api/auth/resend-otp")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an account exists for that email, a fresh 6-digit verification code has been sent."));

        verify(passwordResetService).resendOtp(eq("user@skillsphere.com"), any());
    }

    @Test
    @DisplayName("POST /api/auth/resend-otp during cooldown returns 429 Too Many Requests")
    void resendOtp_duringCooldown_returns429() throws Exception {
        ResendOtpRequest req = new ResendOtpRequest("user@skillsphere.com");
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 45 seconds before requesting another code."))
                .when(passwordResetService).resendOtp(eq("user@skillsphere.com"), any());

        mockMvc.perform(post("/api/auth/resend-otp")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Please wait 45 seconds before requesting another code."));
    }

    @Test
    @DisplayName("POST /api/auth/verify-otp with valid 6-digit OTP returns 200 and reset token")
    void verifyOtp_validCode_returns200() throws Exception {
        VerifyOtpRequest req = new VerifyOtpRequest("user@skillsphere.com", "123456");
        when(passwordResetService.verifyOtp("user@skillsphere.com", "123456"))
                .thenReturn(new VerifyOtpResponse("secure-reset-token-xyz", "Code verified successfully."));

        mockMvc.perform(post("/api/auth/verify-otp")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").value("secure-reset-token-xyz"))
                .andExpect(jsonPath("$.message").value("Code verified successfully."));
    }

    @Test
    @DisplayName("POST /api/auth/verify-otp with invalid OTP returns 400 Bad Request")
    void verifyOtp_invalidCode_returns400() throws Exception {
        VerifyOtpRequest req = new VerifyOtpRequest("user@skillsphere.com", "999999");
        when(passwordResetService.verifyOtp("user@skillsphere.com", "999999"))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "Incorrect verification code. 4 attempts remaining."));

        mockMvc.perform(post("/api/auth/verify-otp")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Incorrect verification code. 4 attempts remaining."));
    }

    @Test
    @DisplayName("POST /api/auth/verify-otp with malformed non-numeric OTP returns 400")
    void verifyOtp_malformedCode_returns400() throws Exception {
        VerifyOtpRequest req = new VerifyOtpRequest("user@skillsphere.com", "abcdef");

        mockMvc.perform(post("/api/auth/verify-otp")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.otp").exists());

        verify(passwordResetService, never()).verifyOtp(any(), any());
    }

    @Test
    @DisplayName("POST /api/auth/reset-password with valid token and strong password returns 200")
    void resetPassword_validRequest_returns200() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest("valid-token", "NewPass123!@", "NewPass123!@");

        mockMvc.perform(post("/api/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Your password has been updated. You can sign in now."));

        verify(passwordResetService).resetPassword(any(ResetPasswordRequest.class));
    }

    @Test
    @DisplayName("POST /api/auth/reset-password with weak password returns 400")
    void resetPassword_weakPassword_returns400() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest("valid-token", "weak", "weak");

        mockMvc.perform(post("/api/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(passwordResetService, never()).resetPassword(any());
    }

    @Test
    @DisplayName("POST /api/auth/reset-password with mismatched passwords returns 400")
    void resetPassword_mismatchedPasswords_returns400() throws Exception {
        ResetPasswordRequest req = new ResetPasswordRequest("valid-token", "StrongPass123!", "MismatchPass123!");
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "Please correct the highlighted fields.",
                Map.of("confirmPassword", "Passwords do not match.")))
                .when(passwordResetService).resetPassword(any(ResetPasswordRequest.class));

        mockMvc.perform(post("/api/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.confirmPassword").value("Passwords do not match."));
    }
}
