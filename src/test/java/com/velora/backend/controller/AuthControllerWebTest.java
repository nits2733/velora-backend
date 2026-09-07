package com.velora.backend.controller;

import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.OtpResponse;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.dto.auth.VerifyOtpRequest;
import com.velora.backend.dto.auth.VerifyResetOtpRequest;
import com.velora.backend.entity.OtpPurpose;
import com.velora.backend.entity.Role;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.service.AuthService;
import com.velora.backend.service.OtpService;
import com.velora.backend.service.PasswordService;
import com.velora.backend.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import static com.velora.backend.controller.WebLayerSupport.as;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@WebLayerTest
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private OtpService otpService;

    @MockBean
    private PasswordService passwordService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RateLimiterService rateLimiterService;

    @Test
    void registrationIsReachableWithoutATokenAndWithholdsTokensUntilVerified() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new OtpResponse("Verification OTP sent to your email", true));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@velora.test","password":"secret123","fullName":"New User","role":"CUSTOMER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiresOtp").value(true))
                .andExpect(jsonPath("$.message").value("Verification OTP sent to your email"));
    }

    @Test
    void verifyEmailReturnsTokens() throws Exception {
        when(authService.verifyEmail(any(VerifyOtpRequest.class)))
                .thenReturn(AuthResponse.of("jwt-token", "refresh-token", 1L, "new@velora.test", "New User", Role.CUSTOMER));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@velora.test","otp":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void resendVerificationOtpIsPublicAndAlwaysSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/resend-verification-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@velora.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());

        verify(authService).resendVerificationOtp("new@velora.test");
    }

    @Test
    void loginDispatchesOtpAndReturnsRequiresOtp() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new OtpResponse("OTP sent to your email", true));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@velora.test","password":"Password@123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OTP sent to your email"))
                .andExpect(jsonPath("$.requiresOtp").value(true));
    }

    @Test
    void verifyLoginOtpReturnsTokens() throws Exception {
        when(authService.verifyLoginOtp(any(VerifyOtpRequest.class)))
                .thenReturn(AuthResponse.of("jwt-token", "refresh-token", 1L, "user@velora.test", "User", Role.CUSTOMER));

        mockMvc.perform(post("/api/auth/verify-login-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@velora.test","otp":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void forgotPasswordReturnsSuccessAndTriggersOtpIfUserExists() throws Exception {
        when(userRepository.existsByEmail("user@velora.test")).thenReturn(true);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@velora.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If this email is registered, an OTP has been sent"));

        verify(otpService).generateAndSendOtp("user@velora.test", OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void verifyResetOtpReturnsResetToken() throws Exception {
        when(passwordService.createResetToken("user@velora.test")).thenReturn("raw-reset-uuid");

        mockMvc.perform(post("/api/auth/verify-reset-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@velora.test","otp":"654321"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetToken").value("raw-reset-uuid"));

        verify(otpService).verifyOtp("user@velora.test", "654321", OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void resetPasswordWorksAndCallsService() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resetToken":"raw-reset-uuid","newPassword":"BrandNew@123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successful. Please log in."));

        verify(passwordService).resetPassword("raw-reset-uuid", "BrandNew@123");
    }

    @Test
    void aDuplicateEmailIsReportedAsAConflict() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new DuplicateResourceException("Email already registered"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"taken@velora.test","password":"secret123","fullName":"Taken","role":"CUSTOMER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void refreshIsPublicSinceTheRefreshTokenIsItselfTheCredential() throws Exception {
        when(authService.refresh("some-refresh"))
                .thenReturn(AuthResponse.of("new-access", "new-refresh", 1L,
                        "new@velora.test", "New User", Role.CUSTOMER));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"some-refresh"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
    }

    @Test
    void anInvalidRefreshTokenSaysSoRatherThanBlamingTheEmailAndPassword() throws Exception {
        when(authService.refresh("bad"))
                .thenThrow(new AuthenticationFailedException("Invalid or expired refresh token"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"bad"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired refresh token"));
    }

    @Test
    void logoutIsPublicAndReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"some-refresh"}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).logout("some-refresh");
    }

    @Test
    void logoutEverywhereRequiresAnAccessTokenUnlikeTheOtherAuthEndpoints() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/logout-all").with(as(42L, Role.CUSTOMER)))
                .andExpect(status().isNoContent());

        verify(authService).logoutEverywhere(42L);
    }

    @Test
    void changingAPasswordRequiresAnAccessToken() throws Exception {
        mockMvc.perform(post("/api/auth/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"current123","newPassword":"brandnew1"}
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(passwordService);
    }

    @Test
    void changingAPasswordUsesTheIdFromTheTokenNotTheBody() throws Exception {
        mockMvc.perform(post("/api/auth/password/change")
                        .with(as(42L, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"current123","newPassword":"brandnew1"}
                                """))
                .andExpect(status().isNoContent());

        verify(passwordService).changePassword(42L, "current123", "brandnew1");
    }

    @Test
    void aFailedLoginNeverRevealsWhichHalfWasWrong() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"someone@velora.test","password":"wrongpass1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }
}
