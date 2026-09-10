package com.velora.backend.service.auth;

import com.velora.backend.entity.auth.OtpPurpose;
import com.velora.backend.exception.EmailSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrevoEmailServiceTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private BrevoEmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new BrevoEmailService(restClient, "test@velora.test");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/smtp/email")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
    }

    @Test
    void sendsLoginOtpEmailSuccessfully() {
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        emailService.sendOtpEmail("user@velora.test", "123456", OtpPurpose.LOGIN);

        // No exception means the Brevo API call went through as expected;
        // the fluent chain wiring above is the assertion.
    }

    @Test
    void sendsPasswordResetOtpEmailSuccessfully() {
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        emailService.sendOtpEmail("user@velora.test", "654321", OtpPurpose.PASSWORD_RESET);
    }

    @Test
    void throwsEmailSendExceptionWhenBrevoApiCallFails() {
        doThrow(new RestClientException("connect timed out")).when(requestBodySpec).retrieve();

        assertThatThrownBy(() -> emailService.sendOtpEmail("user@velora.test", "123456", OtpPurpose.LOGIN))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("Failed to send OTP email");
    }
}
