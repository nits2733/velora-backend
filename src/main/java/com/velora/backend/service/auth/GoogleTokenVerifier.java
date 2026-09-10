package com.velora.backend.service.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.velora.backend.config.GoogleAuthProperties;
import com.velora.backend.exception.AuthenticationFailedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Thin wrapper around Google's own verifier - it checks the token's signature against
 * Google's published keys, issuer, audience and expiry, so none of that crypto is
 * hand-rolled here.
 */
@Component
@RequiredArgsConstructor
public class GoogleTokenVerifier {

    private final GoogleAuthProperties googleAuthProperties;

    public GoogleIdToken.Payload verify(String idToken) {
        if (googleAuthProperties.getClientId() == null || googleAuthProperties.getClientId().isBlank()) {
            throw new IllegalStateException(
                    "app.google.client-id (GOOGLE_CLIENT_ID) is not configured - Google Sign-In is disabled");
        }

        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleAuthProperties.getClientId()))
                .build();

        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            throw new AuthenticationFailedException("Invalid Google sign-in token");
        }

        if (token == null) {
            throw new AuthenticationFailedException("Invalid Google sign-in token");
        }

        GoogleIdToken.Payload payload = token.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new AuthenticationFailedException("Google account email is not verified");
        }

        return payload;
    }
}
