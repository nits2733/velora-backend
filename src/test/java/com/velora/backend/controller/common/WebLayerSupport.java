package com.velora.backend.controller.common;

import com.velora.backend.config.CorsProperties;
import com.velora.backend.config.SecurityConfig;
import com.velora.backend.config.StorageProperties;
import com.velora.backend.entity.user.Role;
import com.velora.backend.security.JwtAuthFilter;
import com.velora.backend.security.JwtService;
import com.velora.backend.security.RestSecurityErrorHandler;
import com.velora.backend.security.UserPrincipal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.mock;

/**
 * Shared wiring for the web-layer slices, pulled in by {@link WebLayerTest}.
 * <p>
 * Everything below the controller is mocked, but the security chain is <em>real</em>:
 * the actual {@link SecurityConfig} rules, the actual {@link JwtAuthFilter}, and the
 * actual {@link RestSecurityErrorHandler}. That's the whole point of these tests - which
 * endpoints are public, which roles may call what, and what an authentication or
 * authorization failure looks like on the wire are all decided in that chain, and no
 * service-level test can see any of it.
 */
@TestConfiguration
public class WebLayerSupport {

    /**
     * A real filter, not a mock: mocking {@code OncePerRequestFilter} would swallow the
     * chain and every request would hang on an empty response. With no Authorization
     * header it does what it does in production - leaves the request anonymous and
     * continues.
     */
    @Bean
    JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(mock(JwtService.class), mock(UserDetailsService.class));
    }

    /** Required by {@code SecurityConfig}'s DaoAuthenticationProvider; unused in slices. */
    @Bean
    UserDetailsService userDetailsService() {
        return mock(UserDetailsService.class);
    }

    @Bean
    CorsProperties corsProperties() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins("http://localhost:5173");
        return properties;
    }

    /** Required by {@code WebConfig}, which @WebMvcTest auto-detects as a WebMvcConfigurer. */
    @Bean
    StorageProperties storageProperties() {
        return new StorageProperties();
    }

    /**
     * Authenticate a request as the given user. Tests populate the security context
     * directly rather than minting real tokens - token mechanics are already covered by
     * {@code JwtServiceTest}, and what these tests care about is what the chain does once
     * an identity (or none) is established.
     */
    public static RequestPostProcessor as(Long userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, "user" + userId + "@velora.test", "hash", role);
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
