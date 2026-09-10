package com.velora.backend.service.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.velora.backend.config.JwtProperties;
import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.GoogleLoginRequest;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.OtpResponse;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.dto.auth.VerifyOtpRequest;
import com.velora.backend.entity.auth.OtpPurpose;
import com.velora.backend.entity.user.AuthProvider;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ProfessionalProfileRepository professionalProfileRepository;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private OtpService otpService;
    @Mock
    private GoogleTokenVerifier googleTokenVerifier;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-32-bytes-long!!");
        properties.setExpirationMs(1000 * 60 * 60);
        properties.setIssuer("velora-test");
        JwtService jwtService = new JwtService(properties);

        authService = new AuthService(userRepository, professionalProfileRepository,
                new BCryptPasswordEncoder(), jwtService, authenticationManager, refreshTokenService, otpService,
                googleTokenVerifier);
    }

    private static GoogleIdToken.Payload googlePayload(String subject, String email) {
        return (GoogleIdToken.Payload) new GoogleIdToken.Payload()
                .setSubject(subject)
                .setEmail(email)
                .setEmailVerified(true)
                .set("name", "Google User")
                .set("picture", "https://example.com/avatar.png");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("taken@velora.test")).thenReturn(true);

        RegisterRequest request = new RegisterRequest("taken@velora.test", "password1", "Someone", null, Role.CUSTOMER);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void registerCustomerDoesNotCreateProfessionalProfileAndWithholdsTokens() {
        when(userRepository.existsByEmail("new@velora.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        RegisterRequest request = new RegisterRequest("new@velora.test", "password1", "New Customer", null, Role.CUSTOMER);

        OtpResponse response = authService.register(request);

        assertThat(response.requiresOtp()).isTrue();
        verify(otpService).generateAndSendOtp("new@velora.test", OtpPurpose.EMAIL_VERIFY);
        org.mockito.Mockito.verifyNoInteractions(professionalProfileRepository);
    }

    @Test
    void registerProfessionalCreatesProfessionalProfile() {
        when(userRepository.existsByEmail("professional@velora.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        RegisterRequest request = new RegisterRequest("professional@velora.test", "password1", "New Professional", null, Role.PROFESSIONAL);

        authService.register(request);

        org.mockito.Mockito.verify(professionalProfileRepository).save(any());
    }

    @Test
    void verifyEmailActivatesAccountAndIssuesTokens() {
        User user = User.builder().id(42L).email("new@velora.test").fullName("New Customer")
                .role(Role.CUSTOMER).emailVerified(false).build();
        when(userRepository.findByEmail("new@velora.test")).thenReturn(Optional.of(user));
        when(refreshTokenService.issue(user)).thenReturn("refresh-token-123");

        AuthResponse response = authService.verifyEmail(new VerifyOtpRequest("new@velora.test", "123456"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(user.isEmailVerified()).isTrue();
        verify(otpService).verifyOtp("new@velora.test", "123456", OtpPurpose.EMAIL_VERIFY);
        org.mockito.Mockito.verify(userRepository).save(user);
    }

    @Test
    void resendVerificationOtpSkipsAlreadyVerifiedAccount() {
        User user = User.builder().id(42L).email("verified@velora.test").fullName("Verified")
                .role(Role.CUSTOMER).emailVerified(true).build();
        when(userRepository.findByEmail("verified@velora.test")).thenReturn(Optional.of(user));

        authService.resendVerificationOtp("verified@velora.test");

        org.mockito.Mockito.verifyNoInteractions(otpService);
    }

    @Test
    void registerRejectsAdminRole() {
        RegisterRequest request = new RegisterRequest("wannabe.admin@velora.test", "password1", "Someone", null, Role.ADMIN);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot self-register as an admin");

        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }

    @Test
    void loginDispatchesOtpAndReturnsRequiresOtpResponse() {
        User user = User.builder().id(42L).email("user@velora.test").fullName("User")
                .role(Role.CUSTOMER).emailVerified(true).build();
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));

        OtpResponse response = authService.login(new LoginRequest("user@velora.test", "Password@123"));

        assertThat(response.requiresOtp()).isTrue();
        assertThat(response.message()).contains("OTP sent");
        verify(otpService).generateAndSendOtp("user@velora.test", OtpPurpose.LOGIN);
    }

    @Test
    void loginRejectsUnverifiedEmail() {
        User user = User.builder().id(42L).email("unverified@velora.test").fullName("User")
                .role(Role.CUSTOMER).emailVerified(false).build();
        when(userRepository.findByEmail("unverified@velora.test")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("unverified@velora.test", "Password@123")))
                .isInstanceOf(com.velora.backend.exception.AuthenticationFailedException.class);

        org.mockito.Mockito.verify(otpService, org.mockito.Mockito.never())
                .generateAndSendOtp(any(), eq(OtpPurpose.LOGIN));
    }

    @Test
    void verifyLoginOtpIssuesTokensOnValidOtp() {
        User user = User.builder().id(42L).email("user@velora.test").fullName("User").role(Role.CUSTOMER).build();
        when(userRepository.findByEmail("user@velora.test")).thenReturn(Optional.of(user));
        when(refreshTokenService.issue(user)).thenReturn("refresh-token-123");

        AuthResponse response = authService.verifyLoginOtp(new VerifyOtpRequest("user@velora.test", "123456"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isEqualTo("refresh-token-123");
        verify(otpService).verifyOtp("user@velora.test", "123456", OtpPurpose.LOGIN);
    }

    @Test
    void refreshingRotatesTheTokenAndReissuesBoth() {
        User user = User.builder().id(42L).email("new@velora.test").fullName("New Customer")
                .role(Role.CUSTOMER).build();
        when(refreshTokenService.consumeAndRotate("old-refresh")).thenReturn(user);
        when(refreshTokenService.issue(user)).thenReturn("new-refresh");

        AuthResponse response = authService.refresh("old-refresh");

        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.user().id()).isEqualTo(42L);
        org.mockito.Mockito.verify(refreshTokenService).consumeAndRotate("old-refresh");
    }

    @Test
    void logoutRevokesOnlyThePresentedSession() {
        authService.logout("some-refresh");

        org.mockito.Mockito.verify(refreshTokenService).revoke("some-refresh");
        org.mockito.Mockito.verify(refreshTokenService, org.mockito.Mockito.never()).revokeAllForUser(any());
    }

    @Test
    void logoutEverywhereRevokesEverySessionForTheUser() {
        authService.logoutEverywhere(42L);

        org.mockito.Mockito.verify(refreshTokenService).revokeAllForUser(42L);
    }

    @Test
    void googleLoginCreatesANewCustomerWithNoProfessionalProfile() {
        when(googleTokenVerifier.verify("valid-token")).thenReturn(googlePayload("sub-1", "newgoogle@velora.test"));
        when(userRepository.findByGoogleId("sub-1")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newgoogle@velora.test")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(99L);
            return u;
        });
        when(refreshTokenService.issue(any(User.class))).thenReturn("refresh-token-google");

        AuthResponse response = authService.googleLogin(new GoogleLoginRequest("valid-token"));

        assertThat(response.accessToken()).isNotBlank();
        org.mockito.Mockito.verifyNoInteractions(professionalProfileRepository);

        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(captor.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(captor.getValue().isEmailVerified()).isTrue();
        assertThat(captor.getValue().getGoogleId()).isEqualTo("sub-1");
    }

    @Test
    void googleLoginLinksAnExistingLocalCustomerInsteadOfDuplicating() {
        User existing = User.builder().id(5L).email("existing@velora.test").fullName("Existing")
                .role(Role.CUSTOMER).authProvider(AuthProvider.LOCAL).build();

        when(googleTokenVerifier.verify("valid-token")).thenReturn(googlePayload("sub-2", "existing@velora.test"));
        when(userRepository.findByGoogleId("sub-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("existing@velora.test")).thenReturn(Optional.of(existing));
        when(refreshTokenService.issue(existing)).thenReturn("refresh-token-linked");

        AuthResponse response = authService.googleLogin(new GoogleLoginRequest("valid-token"));

        assertThat(response.user().id()).isEqualTo(5L);
        assertThat(existing.getGoogleId()).isEqualTo("sub-2");
        verify(userRepository).save(existing);
    }

    @Test
    void googleLoginRejectsAnExistingProfessionalAccount() {
        User professional = User.builder().id(6L).email("pro@velora.test").fullName("Pro")
                .role(Role.PROFESSIONAL).build();

        when(googleTokenVerifier.verify("valid-token")).thenReturn(googlePayload("sub-3", "pro@velora.test"));
        when(userRepository.findByGoogleId("sub-3")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("pro@velora.test")).thenReturn(Optional.of(professional));

        assertThatThrownBy(() -> authService.googleLogin(new GoogleLoginRequest("valid-token")))
                .isInstanceOf(AuthenticationFailedException.class);

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(refreshTokenService);
    }

    @Test
    void googleLoginRejectsAnInvalidToken() {
        when(googleTokenVerifier.verify("bad-token"))
                .thenThrow(new AuthenticationFailedException("Invalid Google sign-in token"));

        assertThatThrownBy(() -> authService.googleLogin(new GoogleLoginRequest("bad-token")))
                .isInstanceOf(AuthenticationFailedException.class);

        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }
}
