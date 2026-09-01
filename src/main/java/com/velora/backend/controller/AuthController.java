package com.velora.backend.controller;

import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.ChangePasswordRequest;
import com.velora.backend.dto.auth.ForgotPasswordRequest;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.RefreshTokenRequest;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.dto.auth.ResetPasswordRequest;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.AuthService;
import com.velora.backend.service.PasswordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Auth", description = "Registration, login, sessions and password management")
public class AuthController {

    private final AuthService authService;
    private final PasswordService passwordService;

    @PostMapping("/register")
    @Operation(summary = "Register a new customer or professional account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive an access token + refresh token")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
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

    @PostMapping("/password/forgot")
    @Operation(summary = "Request a password reset token (always succeeds, even for unknown emails)")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordService.requestReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Set a new password using the emailed 6-digit code (revokes all sessions)")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.resetPassword(request.email(), request.otp(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    @Operation(summary = "Change your own password (requires the current one; revokes all sessions)")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                                @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changePassword(principal.getId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
