package com.facecontrol.bridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Service
public class DeepFaceWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(DeepFaceWorkerClient.class);

    /** Facenet alignment with FastAPI DeepFace.verify default in python app. */
    static final String MODEL_HINT = "Facenet";

    private final RestTemplate workerRestTemplate;
    private final ObjectMapper objectMapper;
    private final String workerBaseUrl;

    public DeepFaceWorkerClient(
            ObjectMapper objectMapper,
            Environment env,
            @Qualifier("deepFaceWorkerRestTemplate") RestTemplate workerRestTemplate) {
        String base = env.getProperty("face.worker-base-url", "http://localhost:8054").strip();
        this.workerBaseUrl = base.replaceAll("/$", "");
        this.objectMapper = objectMapper;
        this.workerRestTemplate = workerRestTemplate;
    }

    /**
     * FastAPI expects multipart fields {@code reference} and {@code probe}. Spring {@code RestClient} multipart with the JDK
     * HTTP stack produced bodies Starlette parsed as empty → HTTP 422; {@link RestTemplate} multipart works reliably.
     */
    public VerificationResult verify(byte[] referenceJpeg, byte[] probeJpeg) throws Exception {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "reference",
                new ByteArrayResource(referenceJpeg) {
                    @Override
                    public String getFilename() {
                        return "ref.jpg";
                    }
                });
        parts.add(
                "probe",
                new ByteArrayResource(probeJpeg) {
                    @Override
                    public String getFilename() {
                        return "probe.jpg";
                    }
                });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(parts, headers);
        String url = workerBaseUrl + "/verify";
        byte[] resp;
        try {
            ResponseEntity<byte[]> out =
                    workerRestTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            resp = out.getBody();
        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.warn("deepface-worker-rs HTTP {} — {}", e.getStatusCode().value(), body == null || body.isBlank() ? "(empty body)" : body);
            throw e;
        } catch (ResourceAccessException e) {
            log.warn(
                    "deepface-worker-rs unreachable at {} — check container is running and healthy (docker compose logs deepface-worker-rs): {}",
                    workerBaseUrl,
                    e.getMessage());
            throw e;
        }
        if (resp == null) {
            throw new IllegalStateException("Empty response from deepface-worker-rs");
        }
        JsonNode json = objectMapper.readTree(resp);
        boolean verified = json.path("verified").asBoolean(false);
        double distance = jsonFiniteDouble(json, "distance");
        double thresh = jsonFiniteDouble(json, "threshold");
        return new VerificationResult(verified, distance, thresh);
    }

    /**
     * DeepFace occasionally emits non-finite metrics; passing those through to HTTP JSON breaks Jackson serialization
     * of {@link com.facecontrol.bridge.dto.VerifyResponse} and surfaces as opaque HTTP 500 from the bridge.
     */
    private static double jsonFiniteDouble(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (!n.isNumber()) {
            return 0d;
        }
        double v = n.doubleValue();
        return Double.isFinite(v) ? v : 0d;
    }

    public record VerificationResult(boolean verified, double distance, double threshold) {
        public VerificationResult {
            distance = Double.isFinite(distance) ? distance : 0d;
            threshold = Double.isFinite(threshold) ? threshold : 0d;
        }
    }

    public VerificationResult verifyOrThrow(byte[] referenceJpeg, byte[] probeJpeg) {
        try {
            return verify(referenceJpeg, probeJpeg);
        } catch (Exception e) {
            log.warn("deepface-worker-rs error: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
