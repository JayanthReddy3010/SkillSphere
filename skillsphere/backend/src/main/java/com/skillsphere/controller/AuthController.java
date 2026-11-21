package com.skillsphere.controller;

import com.skillsphere.dto.*;
import com.skillsphere.exception.ApiException;
import com.skillsphere.service.AuthService;
import com.skillsphere.service.PasswordResetService;
import com.skillsphere.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(UserService userService, AuthService authService,
                          PasswordResetService passwordResetService) {
        this.userService = userService;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    /** Issues the XSRF-TOKEN cookie. The SPA calls this once before its first state-changing request. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
    }

    // POST /api/auth/logout is handled by Spring Security's logout filter (see SecurityConfig).

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return authService.currentUser(authentication);
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                          HttpServletRequest httpRequest) {
        passwordResetService.requestResetOtp(request.email(), getClientIp(httpRequest));
        return new MessageResponse("If an account exists for that email, a 6-digit verification code has been sent.");
    }

    @PostMapping("/resend-otp")
    public MessageResponse resendOtp(@Valid @RequestBody ResendOtpRequest request,
                                     HttpServletRequest httpRequest) {
        passwordResetService.resendOtp(request.email(), getClientIp(httpRequest));
        return new MessageResponse("If an account exists for that email, a fresh 6-digit verification code has been sent.");
    }

    @PostMapping("/verify-otp")
    public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return passwordResetService.verifyOtp(request.email(), request.otp());
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return new MessageResponse("Your password has been updated. You can sign in now.");
    }

    @PostMapping("/change-password")
    public MessageResponse changePassword(Authentication authentication,
                                          @Valid @RequestBody ChangePasswordRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in to continue.");
        }
        userService.changePassword(authentication.getName(), request);
        return new MessageResponse("Password updated successfully.");
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
