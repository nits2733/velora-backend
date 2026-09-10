package com.velora.backend.security;

import com.velora.backend.config.JwtProperties;
import com.velora.backend.entity.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-32-bytes-long!!");
        properties.setExpirationMs(1000 * 60 * 60);
        properties.setIssuer("velora-test");
        jwtService = new JwtService(properties);
    }

    @Test
    void generatesTokenThatExtractsSameEmailAndIsValid() {
        UserPrincipal principal = new UserPrincipal(1L, "professional@velora.test", "hash", Role.PROFESSIONAL);

        String token = jwtService.generateToken(principal);

        assertThat(jwtService.extractEmail(token)).isEqualTo("professional@velora.test");
        assertThat(jwtService.isTokenValid(token, "professional@velora.test")).isTrue();
    }

    @Test
    void tokenInvalidForDifferentEmail() {
        UserPrincipal principal = new UserPrincipal(2L, "customer@velora.test", "hash", Role.CUSTOMER);
        String token = jwtService.generateToken(principal);

        assertThat(jwtService.isTokenValid(token, "someone-else@velora.test")).isFalse();
    }

    @Test
    void expiredTokenThrowsOnParse() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-32-bytes-long!!");
        properties.setExpirationMs(-1000);
        properties.setIssuer("velora-test");
        JwtService expiredService = new JwtService(properties);

        UserPrincipal principal = new UserPrincipal(3L, "expired@velora.test", "hash", Role.CUSTOMER);
        String token = expiredService.generateToken(principal);

        assertThatThrownBy(() -> expiredService.parseClaims(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
