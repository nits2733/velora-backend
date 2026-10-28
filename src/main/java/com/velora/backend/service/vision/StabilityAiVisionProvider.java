package com.velora.backend.service.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.backend.config.VisionProperties;
import com.velora.backend.exception.VisionFailureCategory;
import com.velora.backend.exception.VisionProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;

/**
 * PARTLY VERIFIED: one real call confirmed that a v2beta stable-image/generate/sd3 multipart request
 * (prompt, strength, mode, output_format, image) with Bearer auth is accepted and returns an image.
 * STILL UNVERIFIED: the exact semantics of strength on the default model (values are placeholders to be
 * calibrated against real photos) and whether negative_prompt is supported on it. All provider-format
 * knowledge is isolated in this class; endpoint and engine come from {@link VisionProperties}.
 */
@Component
@Slf4j
public class StabilityAiVisionProvider implements VisionProvider {

    private static final String SAFE_MESSAGE = "Vision provider request failed";

    private final VisionProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StabilityAiVisionProvider(VisionProperties props,
                                    @org.springframework.beans.factory.annotation.Qualifier("visionRestClient") RestClient visionRestClient) {
        this.props = props;
        this.restClient = visionRestClient;
    }

    private static VisionFailureCategory classify(int status, String body) {
        if (status == 403) {
            return VisionFailureCategory.MODERATION;
        }
        if (status == 400 && body != null && body.toLowerCase().contains("moderation")) {
            return VisionFailureCategory.MODERATION;
        }
        if (status == 402 || status == 408 || status == 429 || status >= 500) {
            return VisionFailureCategory.UNAVAILABLE;
        }
        return VisionFailureCategory.GENERIC;
    }

    @Override
    public byte[] transform(byte[] inputImage, String contentType, String prompt) {
        return transform(inputImage, contentType, prompt, null, props.getImageStrength(), null);
    }

    @Override
    public byte[] transform(byte[] inputImage, String contentType, String prompt, String negativePrompt,
                            double strength, Long seed) {
        String apiKey = props.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Vision provider API key is not configured");
            throw new VisionProviderException(SAFE_MESSAGE);
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("prompt", prompt);
        form.add("strength", String.valueOf(strength));
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            form.add("negative_prompt", negativePrompt);
        }
        if (seed != null) {
            form.add("seed", String.valueOf(seed));
        }
        form.add("mode", "image-to-image");
        form.add("output_format", "png");
        form.add("image", new ByteArrayResource(inputImage) {
            @Override
            public String getFilename() {
                return "input";
            }
        });

        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(props.getApiUrl() + "/" + props.getEngine())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.parseMediaType("image/*"), MediaType.APPLICATION_JSON)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                log.warn("Vision provider returned an empty body");
                throw new VisionProviderException(SAFE_MESSAGE);
            }
            MediaType type = response.getHeaders().getContentType();
            if (type != null && "image".equals(type.getType())) {
                return body;
            }
            JsonNode json = objectMapper.readTree(body);
            String encoded = json.hasNonNull("image") ? json.get("image").asText() : null;
            if (encoded == null || encoded.isBlank()) {
                log.warn("Vision provider returned no image field");
                throw new VisionProviderException(SAFE_MESSAGE);
            }
            return Base64.getDecoder().decode(encoded);
        } catch (VisionProviderException e) {
            throw e;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            VisionFailureCategory category = classify(status, e.getResponseBodyAsString());
            if (status == 402) {
                log.error("Vision provider credits exhausted (status 402)");
            } else {
                log.warn("Vision provider call failed: {} status={}", e.getClass().getSimpleName(), status);
            }
            throw new VisionProviderException(SAFE_MESSAGE, category, e);
        } catch (ResourceAccessException e) {
            log.warn("Vision provider call failed: {}", e.getClass().getSimpleName());
            throw new VisionProviderException(SAFE_MESSAGE, VisionFailureCategory.UNAVAILABLE, e);
        } catch (Exception e) {
            log.warn("Vision provider call failed: {}", e.getClass().getSimpleName());
            throw new VisionProviderException(SAFE_MESSAGE, e);
        }
    }
}
