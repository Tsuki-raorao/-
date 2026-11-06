package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedding provider used by the knowledge repository.
 *
 * <p>The local hash provider is always available and deterministic. A remote
 * OpenAI-compatible provider is attempted only when both embedding model and
 * base URL are configured; every configuration, transport, and response
 * failure falls back to the local provider.</p>
 */
@Service
public class AiEmbeddingService {
    public static final String LOCAL_MODEL = "local-hash-v1";
    private static final int LOCAL_DIMENSION = 64;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Autowired
    public AiEmbeddingService(AiProperties properties, ObjectMapper mapper) {
        this(properties, mapper, buildHttpClient(properties));
    }

    /** Visible for deterministic tests that provide a non-networking client. */
    AiEmbeddingService(AiProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    public Embedding embed(String text) {
        String input = text == null ? "" : text;
        if (remoteConfigured()) {
            Embedding remote = remoteEmbedding(input);
            if (remote != null) return remote;
        }
        return new Embedding(localEmbedding(input), LOCAL_MODEL);
    }

    public record Embedding(double[] vector, String model) {
        public Embedding {
            vector = vector == null ? new double[0] : vector.clone();
            model = model == null || model.isBlank() ? LOCAL_MODEL : model;
        }

        @Override
        public double[] vector() { return vector.clone(); }
    }

    private boolean remoteConfigured() {
        return nonBlank(properties.getEmbeddingModel()) && nonBlank(properties.getEmbeddingBaseUrl());
    }

    private Embedding remoteEmbedding(String input) {
        try {
            String endpoint = embeddingsEndpoint(properties.getEmbeddingBaseUrl());
            var body = mapper.createObjectNode();
            body.put("model", properties.getEmbeddingModel().trim());
            body.put("input", input);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout())
                    .header("Content-Type", "application/json");
            String key = properties.getEmbeddingApiKey();
            if (!nonBlank(key)) key = properties.getApiKey();
            if (nonBlank(key)) request.header("Authorization", "Bearer " + key.trim());
            HttpResponse<String> response = httpClient.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            JsonNode root = mapper.readTree(response.body());
            JsonNode values = root.path("data").path(0).path("embedding");
            if (!values.isArray() || values.isEmpty()) return null;
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (!values.get(i).isNumber()) return null;
                vector[i] = values.get(i).doubleValue();
            }
            return new Embedding(vector, properties.getEmbeddingModel().trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String embeddingsEndpoint(String baseUrl) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.endsWith("/embeddings") ? base : base + "/embeddings";
    }

    private Duration timeout() {
        Duration value = properties.getEmbeddingTimeout();
        if (value == null || value.isNegative() || value.isZero()) return Duration.ofSeconds(10);
        return value;
    }

    private static HttpClient buildHttpClient(AiProperties properties) {
        Duration timeout = properties.getEmbeddingTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) timeout = Duration.ofSeconds(10);
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private double[] localEmbedding(String text) {
        double[] vector = new double[LOCAL_DIMENSION];
        for (String term : tokenize(text)) {
            byte[] digest = digest(term);
            int index = ((digest[0] & 0xff) << 8 | (digest[1] & 0xff)) % LOCAL_DIMENSION;
            double magnitude = 1d + Math.min(term.length(), 8) / 8d;
            vector[index] += (digest[2] & 1) == 0 ? magnitude : -magnitude;
        }
        double norm = Math.sqrt(Arrays.stream(vector).map(value -> value * value).sum());
        if (norm == 0d) return vector;
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1) terms.add(token);
            for (int i = 0; i + 1 < token.length(); i++) {
                if (isCjk(token.charAt(i)) && isCjk(token.charAt(i + 1))) {
                    terms.add(token.substring(i, i + 2));
                }
            }
        }
        return List.copyOf(terms);
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isCjk(char c) {
        return (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xf900 && c <= 0xfaff);
    }

    private boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
