package com.velora.backend.service;

import com.velora.backend.dto.auth.AuthResponse;
import com.velora.backend.dto.auth.LoginRequest;
import com.velora.backend.dto.auth.OtpResponse;
import com.velora.backend.dto.auth.RegisterRequest;
import com.velora.backend.dto.auth.VerifyOtpRequest;
import com.velora.backend.entity.OtpPurpose;
import com.velora.backend.entity.ProfessionalProfile;
import com.velora.backend.entity.Role;
import com.velora.backend.entity.User;
import com.velora.backend.exception.AuthenticationFailedException;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.exception.UserNotFoundException;
import com.velora.backend.repository.ProfessionalProfileRepository;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.security.JwtService;
import com.velora.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final OtpService otpService;

    /**
     * Creates the account but withholds tokens until the email is verified via OTP -
     * otherwise anyone could register with someone else's address and get a working
     * session for it.
     */
    @Transactional
    public OtpResponse register(RegisterRequest request) {
        if (request.role() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot self-register as an admin account");
        }

        String normalizedEmail = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with this email already exists");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .role(request.role())
                .emailVerified(false)
                .build();

        user = userRepository.save(user);

        if (user.getRole() == Role.PROFESSIONAL) {
            ProfessionalProfile profile = ProfessionalProfile.builder()
                    .user(user)
                    .build();
            professionalProfileRepository.save(profile);
        }

        otpService.generateAndSendOtp(normalizedEmail, OtpPurpose.EMAIL_VERIFY);
        return new OtpResponse("Verification OTP sent to your email", true);
    }

    /**
     * Verifies the registration OTP, activates the account and issues its first session.
     */
    @Transactional
    public AuthResponse verifyEmail(VerifyOtpRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        otpService.verifyOtp(normalizedEmail, request.otp(), OtpPurpose.EMAIL_VERIFY);

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UserNotFoundException("Invalid email or OTP"));

        user.setEmailVerified(true);
        userRepository.save(user);

        return issueSession(user);
    }

    /** Always succeeds outwardly, even for an unknown or already-verified email, to avoid account enumeration. */
    @Transactional
    public void resendVerificationOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        userRepository.findByEmail(normalizedEmail)
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> otpService.generateAndSendOtp(normalizedEmail, OtpPurpose.EMAIL_VERIFY));
    }

    /**
     * Authenticates email and password, then triggers 2FA email OTP.
     */
    public OtpResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.password()));

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UserNotFoundException("Invalid email or password"));

        if (!user.isEmailVerified()) {
            throw new AuthenticationFailedException(
                    "Email not verified. Check your inbox for the verification OTP, or request a new one");
        }

        otpService.generateAndSendOtp(normalizedEmail, OtpPurpose.LOGIN);
        return new OtpResponse("OTP sent to your email", true);
    }

    /**
     * Verifies login OTP and issues access token + refresh token session.
     */
    @Transactional
    public AuthResponse verifyLoginOtp(VerifyOtpRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        otpService.verifyOtp(normalizedEmail, request.otp(), OtpPurpose.LOGIN);

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UserNotFoundException("Invalid email or password"));

        return issueSession(user);
    }

    /**
     * Trades a refresh token for a new pair.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        User user = refreshTokenService.consumeAndRotate(refreshToken);
        return issueSession(user);
    }

    /** Ends one session. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /** Ends every session for this user. */
    @Transactional
    public void logoutEverywhere(Long userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    private AuthResponse issueSession(User user) {
        String accessToken = jwtService.generateToken(UserPrincipal.fromEntity(user));
        String refreshToken = refreshTokenService.issue(user);

        return AuthResponse.of(accessToken, refreshToken,
                user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
