package com.velora.backend.controller;

import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.ChangePasswordRequest;
import com.velora.backend.dto.auth.ForgotPasswordRequest;
import com.velora.backend.dto.auth.ForgotPasswordResponse;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.MessageResponse;
import com.velora.backend.dto.auth.OtpResponse;
import com.velora.backend.dto.auth.RefreshTokenRequest;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.dto.auth.ResetPasswordRequest;
import com.velora.backend.dto.auth.ResetTokenResponse;
import com.velora.backend.dto.auth.VerifyOtpRequest;
import com.velora.backend.dto.auth.VerifyResetOtpRequest;
import com.velora.backend.entity.OtpPurpose;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.AuthService;
import com.velora.backend.service.OtpService;
import com.velora.backend.service.PasswordService;
import com.velora.backend.service.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, 2FA OTP login, sessions and password management")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final PasswordService passwordService;
    private final UserRepository userRepository;
    private final RateLimiterService rateLimiterService;

    @PostMapping("/register")
    @Operation(summary = "Register a new customer or professional account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate credentials and dispatch 2FA login OTP")
    public ResponseEntity<OtpResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/verify-login-otp")
    @Operation(summary = "Verify login OTP and receive JWT access token + refresh token")
    public ResponseEntity<AuthResponse> verifyLoginOtp(@Valid @RequestBody VerifyOtpRequest request,
                                                       HttpServletRequest httpRequest) {
        rateLimiterService.checkOtpVerifyRateLimit(extractClientIp(httpRequest));
        return ResponseEntity.ok(authService.verifyLoginOtp(request));
    }

    @PostMapping({"/forgot-password", "/password/forgot"})
    @Operation(summary = "Request password reset OTP (always returns success to prevent enumeration)")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                                  HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        rateLimiterService.checkForgotPasswordRateLimit(clientIp);

        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            otpService.generateAndSendOtp(normalizedEmail, OtpPurpose.PASSWORD_RESET);
        }

        return ResponseEntity.ok(new ForgotPasswordResponse("If this email is registered, an OTP has been sent"));
    }

    @PostMapping("/verify-reset-otp")
    @Operation(summary = "Verify password reset OTP and obtain a single-use reset token")
    public ResponseEntity<ResetTokenResponse> verifyResetOtp(@Valid @RequestBody VerifyResetOtpRequest request,
                                                             HttpServletRequest httpRequest) {
        rateLimiterService.checkOtpVerifyRateLimit(extractClientIp(httpRequest));
        String normalizedEmail = request.email().trim().toLowerCase();

        otpService.verifyOtp(normalizedEmail, request.otp(), OtpPurpose.PASSWORD_RESET);
        String rawResetToken = passwordService.createResetToken(normalizedEmail);

        return ResponseEntity.ok(new ResetTokenResponse(rawResetToken));
    }

    @PostMapping({"/reset-password", "/password/reset"})
    @Operation(summary = "Reset password using single-use reset token and new password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.resetPassword(request.effectiveToken(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Password reset successful. Please log in."));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "End this session (idempotent - an unknown token is still a success)")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "End every session for the current user (requires a valid access token)")
    public ResponseEntity<Void> logoutEverywhere(@AuthenticationPrincipal UserPrincipal principal) {
        authService.logoutEverywhere(principal.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    @Operation(summary = "Change your own password (requires current password; revokes all sessions)")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changePassword(principal.getId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
