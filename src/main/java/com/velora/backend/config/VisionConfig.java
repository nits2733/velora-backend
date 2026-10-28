package com.velora.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class VisionConfig {

    @Bean
    public RestClient visionRestClient(VisionProperties props) {
        int timeoutMillis = (int) props.getTimeout().toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return RestClient.builder().requestFactory(factory).build();
    }
}
