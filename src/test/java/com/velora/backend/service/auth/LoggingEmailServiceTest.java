package com.velora.backend.service.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.velora.backend.entity.auth.OtpPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailServiceTest {

    private final LoggingEmailService service = new LoggingEmailService();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingEmailService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void sendOtpEmailLogsRecipientPurposeAndCode() {
        service.sendOtpEmail("someone@example.com", "482913", OtpPurpose.LOGIN);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("someone@example.com", "482913", "LOGIN");
    }

    @Test
    void sendOtpEmailNeverThrows() {
        for (OtpPurpose purpose : OtpPurpose.values()) {
            service.sendOtpEmail("a@b.co", "000000", purpose);
        }
        assertThat(appender.list).hasSize(OtpPurpose.values().length);
    }

    @Test
    void isRegisteredOnlyUnderLocalProfile() {
        Profile profile = LoggingEmailService.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }

    @Test
    void brevoServiceIsDisabledUnderLocalProfile() {
        Profile profile = BrevoEmailService.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!local");
    }
}
