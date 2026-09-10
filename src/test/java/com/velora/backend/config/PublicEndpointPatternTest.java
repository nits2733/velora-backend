package com.velora.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public-browsing rules in {@link SecurityConfig} are written as {@code /api/x/**}.
 * Collection endpoints sit at the bare {@code /api/x} with no trailing segment, so this
 * pins the behaviour that {@code /**} also matches zero segments - otherwise the
 * category list would demand a token despite being listed as public, which no
 * service-level test would catch. {@code /api/professionals/**} and
 * {@code /api/portfolio/**} are deliberately not covered here any more - the
 * admin-controlled assignment model moved both to admin-only.
 */
class PublicEndpointPatternTest {

    private final PathPatternParser parser = PathPatternParser.defaultInstance;

    @Test
    void publicGetPatternsMatchTheirCollectionEndpoints() {
        assertThat(matches("/api/categories/**", "/api/categories")).isTrue();
    }

    @Test
    void publicGetPatternsStillMatchTheirNestedEndpoints() {
        assertThat(matches("/api/categories/**", "/api/categories/7")).isTrue();
    }

    @Test
    void publicGetPatternsDoNotLeakIntoOtherResources() {
        assertThat(matches("/api/categories/**", "/api/bookings")).isFalse();
    }

    private boolean matches(String pattern, String path) {
        PathPattern parsed = parser.parse(pattern);
        return parsed.matches(PathContainer.parsePath(path));
    }
}
