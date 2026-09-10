package com.velora.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Brevo's transactional email HTTPS API, not SMTP - see BrevoEmailService for why.
 */
@Configuration
public class BrevoApiConfig {

    @Bean
    public RestClient brevoRestClient(@Value("${app.brevo.api-key:}") String apiKey) {
        return RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
