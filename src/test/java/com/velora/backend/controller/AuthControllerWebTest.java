package com.velora.backend.controller;

import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.entity.Role;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.service.AuthService;
import com.velora.backend.service.PasswordService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The auth endpoints are the only writes reachable without a token, so this pins that
 * they really are public, that bean validation runs before the service does, and that a
 * failed login is reported vaguely enough not to confirm which emails have accounts.
 */
@WebMvcTest(AuthController.class)
@WebLayerTest
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private PasswordService passwordService;

    @Test
    void registrationIsReachableWithoutAToken() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(AuthResponse.of("token", "refresh", 1L, "new@velora.test", "New User", Role.CUSTOMER));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@velora.test","password":"secret123","fullName":"New User","role":"CUSTOMER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));
    }

    @Test
    void everyInvalidFieldIsReportedAtOnceNotJustTheFirst() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","fullName":"","role":"CUSTOMER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                // "short" trips both @Size and @Pattern, so password appears twice -
                // every violation is reported, not one per field.
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        containsInAnyOrder("email", "password", "password", "fullName")));

        verifyNoInteractions(authService);
    }

    @Test
    void aPasswordWithoutADigitIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@velora.test","password":"onlyletters","fullName":"New User","role":"CUSTOMER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("Password must contain at least one letter and one digit"));

        verifyNoInteractions(authService);
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
                // The client has to tell "refresh me" apart from "log in again"; only
                // login itself stays deliberately vague.
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

        org.mockito.Mockito.verify(authService).logout("some-refresh");
    }

    @Test
    void logoutEverywhereRequiresAnAccessTokenUnlikeTheOtherAuthEndpoints() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/logout-all").with(as(42L, Role.CUSTOMER)))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(authService).logoutEverywhere(42L);
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

        org.mockito.Mockito.verify(passwordService).changePassword(42L, "current123", "brandnew1");
    }

    @Test
    void forgotPasswordIsAcceptedForAnyEmailWhetherItExistsOrNot() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@velora.test"}
                                """))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(passwordService).requestReset("nobody@velora.test");
    }

    @Test
    void aResetStillEnforcesTheSamePasswordRulesAsRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@velora.test","otp":"123456","newPassword":"onlyletters"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        verifyNoInteractions(passwordService);
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
