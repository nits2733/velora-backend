package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.exception.VisionFailureCategory;
import com.velora.backend.exception.VisionProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * No real network: the RestClient handed to the provider is bound to a MockRestServiceServer.
 * Deliberately agnostic about Stability's exact request format - only prompt, strength,
 * auth header, URL-from-properties and error mapping are asserted.
 */
class StabilityAiVisionProviderTest {

    private static final String API_URL = "https://vision.velora.test/v2/edit";
    private static final byte[] INPUT = "input-image".getBytes();
    private static final byte[] OUTPUT = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private MockRestServiceServer server;
    private VisionProperties props;
    private StabilityAiVisionProvider provider;

    @BeforeEach
    void setUp() {
        props = new VisionProperties();
        props.setApiUrl(API_URL);
        props.setApiKey("sk-test-key");
        props.setEngine("test-engine");
        props.setImageStrength(0.35);
        props.setTimeout(Duration.ofSeconds(5));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new StabilityAiVisionProvider(props, builder.build());
    }

    @Test
    void transform_sendsBearerKeyPromptAndStrengthToConfiguredUrl() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-key"))
                .andExpect(content().string(containsString("modern oak living room")))
                .andExpect(content().string(containsString("0.35")))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "modern oak living room");

        server.verify();
    }

    @Test
    void transform_rawImageResponseIsReturnedAsBytes() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        assertThat(provider.transform(INPUT, "image/jpeg", "p")).isEqualTo(OUTPUT);
    }

    @Test
    void transform_base64JsonResponseIsDecodedToBytes() {
        String json = "{\"image\":\"" + Base64.getEncoder().encodeToString(OUTPUT) + "\"}";
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThat(provider.transform(INPUT, "image/jpeg", "p")).isEqualTo(OUTPUT);
    }

    @Test
    void transform_non2xx_throwsVisionProviderExceptionWithoutLeakingBody() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"bad key sk-test-key leaked-body-marker\"}"));

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
                        .doesNotContain("leaked-body-marker")
                        .doesNotContain("sk-test-key"));
    }

    @Test
    void transform_serverError_throwsVisionProviderException() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom-body-marker"));

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("boom-body-marker"));
    }

    @Test
    void transform_timeoutIsMappedToVisionProviderException() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(request -> { throw new SocketTimeoutException("read timed out"); });

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform_ioFailureIsMappedToVisionProviderException() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(request -> { throw new IOException("connection reset"); });

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform_blankApiKey_failsWithoutCallingProvider() {
        props.setApiKey("   ");

        // No expectation is registered, so any HTTP call would surface as an AssertionError,
        // not a VisionProviderException - this proves the key check happens first.
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform_nullApiKey_failsWithoutCallingProvider() {
        props.setApiKey(null);

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform_emptyResponseBody_throwsVisionProviderException() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p"))
                .isInstanceOf(VisionProviderException.class);
    }

    // ---------- 5-arg: negative prompt + per-request strength ----------

    @Test
    void transform5_sendsPassedStrengthNotThePropertyAndNegativePrompt() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-key"))
                .andExpect(content().string(containsString("name=\"strength\"")))
                .andExpect(content().string(containsString("0.8")))
                .andExpect(content().string(not(containsString("0.35"))))
                .andExpect(content().string(containsString("name=\"negative_prompt\"")))
                .andExpect(content().string(containsString("blurry, warped")))
                .andExpect(content().string(containsString("name=\"prompt\"")))
                .andExpect(content().string(containsString("a lovely room")))
                .andExpect(content().string(containsString("image-to-image")))
                .andExpect(content().string(containsString("png")))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        assertThat(provider.transform(INPUT, "image/jpeg", "a lovely room", "blurry, warped", 0.8)).isEqualTo(OUTPUT);

        server.verify();
    }

    @Test
    void transform5_nullNegativePromptOmitsField() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(not(containsString("negative_prompt"))))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p", null, 0.5);

        server.verify();
    }

    @Test
    void transform5_blankNegativePromptOmitsField() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(not(containsString("negative_prompt"))))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p", "   ", 0.5);

        server.verify();
    }

    @Test
    void transform3_delegatesWithConfiguredStrengthAndNoNegativePrompt() {
        props.setImageStrength(0.42);
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(containsString("0.42")))
                .andExpect(content().string(not(containsString("negative_prompt"))))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p");

        server.verify();
    }

    @Test
    void transform5_non2xxIsMappedWithoutLeakingBodyOrKey() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("leaked-body-marker sk-test-key"));

        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5))
                .isInstanceOf(VisionProviderException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
                        .doesNotContain("leaked-body-marker").doesNotContain("sk-test-key"));
    }

    @Test
    void transform5_timeoutIsMapped() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(request -> { throw new SocketTimeoutException("read timed out"); });
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform5_emptyBodyIsMapped() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5))
                .isInstanceOf(VisionProviderException.class);
    }

    @Test
    void transform5_blankKeyFailsWithoutCallingProvider() {
        props.setApiKey(" ");
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5))
                .isInstanceOf(VisionProviderException.class);
    }

    // ---------- 6-arg: seed ----------

    @Test
    void transform6_sendsSeedFormFieldWhenGiven() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(containsString("name=\"seed\"")))
                .andExpect(content().string(containsString("3735928559")))
                .andExpect(content().string(containsString("name=\"prompt\"")))
                .andExpect(content().string(containsString("name=\"strength\"")))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        assertThat(provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 3735928559L)).isEqualTo(OUTPUT);

        server.verify();
    }

    @Test
    void transform6_sendsSeedZero() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(containsString("name=\"seed\"")))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p", null, 0.5, 0L);

        server.verify();
    }

    @Test
    void transform6_nullSeedOmitsField() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(not(containsString("name=\"seed\""))))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, null);

        server.verify();
    }

    @Test
    void transform5_neverSendsSeed() {
        server.expect(requestTo(startsWith(API_URL)))
                .andExpect(content().string(not(containsString("name=\"seed\""))))
                .andRespond(withSuccess(OUTPUT, MediaType.IMAGE_PNG));

        provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5);

        server.verify();
    }

    // ---------- error classification ----------

    private static final String MODERATION_BODY_MARKER = "flagged-user-content-marker";

    private VisionProviderException failureFor(HttpStatus status, String body) {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(
                () -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 1L));
        assertThat(t).isInstanceOf(VisionProviderException.class);
        return (VisionProviderException) t;
    }

    @Test
    void category_http403_isModeration() {
        VisionProviderException e = failureFor(HttpStatus.FORBIDDEN,
                "{\"name\":\"content_moderation\",\"errors\":[\"" + MODERATION_BODY_MARKER + "\"]}");
        assertThat(e.category()).isEqualTo(VisionFailureCategory.MODERATION);
        assertThat(String.valueOf(e.getMessage())).doesNotContain(MODERATION_BODY_MARKER)
                .doesNotContainIgnoringCase("content_moderation");
    }

    @Test
    void category_http400WithContentModerationName_isModeration() {
        VisionProviderException e = failureFor(HttpStatus.BAD_REQUEST,
                "{\"name\":\"content_moderation\",\"errors\":[\"" + MODERATION_BODY_MARKER + "\"]}");
        assertThat(e.category()).isEqualTo(VisionFailureCategory.MODERATION);
        assertThat(String.valueOf(e.getMessage())).doesNotContain(MODERATION_BODY_MARKER);
    }

    @Test
    void category_http400WithModerationInDescriptionAnyCase_isModeration() {
        VisionProviderException e = failureFor(HttpStatus.BAD_REQUEST,
                "{\"name\":\"bad_request\",\"errors\":[\"Your request was flagged by our Moderation system "
                        + MODERATION_BODY_MARKER + "\"]}");
        assertThat(e.category()).isEqualTo(VisionFailureCategory.MODERATION);
        assertThat(String.valueOf(e.getMessage())).doesNotContain(MODERATION_BODY_MARKER);
    }

    @Test
    void category_http400WithoutModeration_isGeneric() {
        VisionProviderException e = failureFor(HttpStatus.BAD_REQUEST,
                "{\"name\":\"invalid_parameter\",\"errors\":[\"strength must be between 0 and 1\"]}");
        assertThat(e.category()).isEqualTo(VisionFailureCategory.GENERIC);
    }

    @Test
    void category_http400WithNonJsonBody_isGeneric() {
        VisionProviderException e = failureFor(HttpStatus.BAD_REQUEST, "not json at all");
        assertThat(e.category()).isEqualTo(VisionFailureCategory.GENERIC);
    }

    @Test
    void category_http402_isUnavailable() {
        assertThat(failureFor(HttpStatus.PAYMENT_REQUIRED, "{\"name\":\"insufficient_balance\"}").category())
                .isEqualTo(VisionFailureCategory.UNAVAILABLE);
    }

    @Test
    void category_http408_isUnavailable() {
        assertThat(failureFor(HttpStatus.REQUEST_TIMEOUT, "{}").category()).isEqualTo(VisionFailureCategory.UNAVAILABLE);
    }

    @Test
    void category_http429_isUnavailable() {
        assertThat(failureFor(HttpStatus.TOO_MANY_REQUESTS, "{\"name\":\"rate_limit\"}").category())
                .isEqualTo(VisionFailureCategory.UNAVAILABLE);
    }

    @Test
    void category_http500_isUnavailable() {
        assertThat(failureFor(HttpStatus.INTERNAL_SERVER_ERROR, "boom").category())
                .isEqualTo(VisionFailureCategory.UNAVAILABLE);
    }

    @Test
    void category_http503_isUnavailable() {
        assertThat(failureFor(HttpStatus.SERVICE_UNAVAILABLE, "{}").category())
                .isEqualTo(VisionFailureCategory.UNAVAILABLE);
    }

    @Test
    void category_http401_isGeneric() {
        assertThat(failureFor(HttpStatus.UNAUTHORIZED, "{\"name\":\"unauthorized\"}").category())
                .isEqualTo(VisionFailureCategory.GENERIC);
    }

    @Test
    void category_http404_isGeneric() {
        assertThat(failureFor(HttpStatus.NOT_FOUND, "{}").category()).isEqualTo(VisionFailureCategory.GENERIC);
    }

    @Test
    void category_timeout_isUnavailable() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(request -> { throw new SocketTimeoutException("read timed out"); });
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 1L))
                .isInstanceOfSatisfying(VisionProviderException.class,
                        e -> assertThat(e.category()).isEqualTo(VisionFailureCategory.UNAVAILABLE));
    }

    @Test
    void category_ioFailure_isUnavailable() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(request -> { throw new IOException("connection reset"); });
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 1L))
                .isInstanceOfSatisfying(VisionProviderException.class,
                        e -> assertThat(e.category()).isEqualTo(VisionFailureCategory.UNAVAILABLE));
    }

    @Test
    void category_emptyBody_isGeneric() {
        server.expect(requestTo(startsWith(API_URL)))
                .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 1L))
                .isInstanceOfSatisfying(VisionProviderException.class,
                        e -> assertThat(e.category()).isEqualTo(VisionFailureCategory.GENERIC));
    }

    @Test
    void category_blankKey_isGeneric() {
        props.setApiKey("  ");
        assertThatThrownBy(() -> provider.transform(INPUT, "image/jpeg", "p", "neg", 0.5, 1L))
                .isInstanceOfSatisfying(VisionProviderException.class,
                        e -> assertThat(e.category()).isEqualTo(VisionFailureCategory.GENERIC));
    }

    @Test
    void category_existingConstructorsDefaultToGeneric() {
        assertThat(new VisionProviderException("m").category()).isEqualTo(VisionFailureCategory.GENERIC);
        assertThat(new VisionProviderException("m", new RuntimeException()).category())
                .isEqualTo(VisionFailureCategory.GENERIC);
        assertThat(new VisionProviderException("m", VisionFailureCategory.MODERATION, null).category())
                .isEqualTo(VisionFailureCategory.MODERATION);
    }
}
