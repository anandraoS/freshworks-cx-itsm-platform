package com.example.cx.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OPTIONAL real provider STUB: selected only with {@code AI_PROVIDER=http} (property cx.ai.provider=http). It is NEVER required:
 * with the default configuration this bean does not exist, {@code AI_API_URL} / {@code AI_API_KEY} default to empty, and no test
 * ever calls it (no network in tests). If it is selected but not configured it fails closed with a non-retryable error, which
 * the service turns into a degraded response (HTTP 200, degraded:true).
 *
 * Contract with the remote endpoint (documented here because this is a stub): POST JSON
 * {task, title, description, categories[names], articles[{id,title,summary}]} (already sanitised/redacted; no tenant id, no user id,
 * no e-mail) -> JSON {summary, category, priority, confidence, explanation, reply, articles[{id,score}]}. The service validates every
 * returned field against the candidate lists and cleans the text, so a malicious or buggy endpoint cannot inject actions or ids.
 * Only https URLs are accepted; the key is sent as a Bearer header and is never logged. NOT executed in the authoring environment.
 */
@Component
@ConditionalOnProperty(name = "cx.ai.provider", havingValue = "http")
public class HttpAiProvider implements AiProvider {
    public static final String NAME = "HTTP";
    private static final int MAX_RESPONSE_CHARS = 64_000;

    private final String url;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpAiProvider(@Value("${cx.ai.http.url:}") String url, @Value("${cx.ai.http.api-key:}") String apiKey,
                          @Value("${cx.ai.timeout-ms:3000}") long timeoutMs, ObjectMapper mapper) {
        this.url = url == null ? "" : url.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() { return NAME; }

    boolean configured() { return url.startsWith("https://"); }

    @Override
    public AiResult analyze(AiRequest req) {
        if (!configured()) throw new AiProviderException("HTTP AI provider is not configured (AI_API_URL must be an https URL)", false);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task", "ticket_analysis");
            payload.put("title", req.title());
            payload.put("description", req.description());
            payload.put("categories", req.categories().stream().map(CategoryOption::name).toList());
            payload.put("articles", req.articles().stream().map(a -> Map.of("id", a.id().toString(), "title", a.title(), "summary", a.summary())).toList());
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 500 || res.statusCode() == 429) throw new AiProviderException("AI endpoint returned " + res.statusCode(), true);
            if (res.statusCode() != 200) throw new AiProviderException("AI endpoint returned " + res.statusCode(), false);
            if (res.body().length() > MAX_RESPONSE_CHARS) throw new AiProviderException("AI response too large", false);
            return parse(mapper.readTree(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("interrupted", false, e);
        } catch (IOException e) {
            throw new AiProviderException("AI endpoint unreachable or malformed response", true, e);
        }
    }

    private AiResult parse(JsonNode n) {
        List<ScoredArticle> arts = new ArrayList<>();
        for (JsonNode a : n.path("articles")) {
            try {
                arts.add(new ScoredArticle(UUID.fromString(a.path("id").asText()), "", a.path("score").asDouble(0)));
            } catch (IllegalArgumentException ignored) { /* invalid id: dropped */ }
        }
        return new AiResult(n.path("summary").asText(""), n.path("category").isNull() ? null : n.path("category").asText(null),
                n.path("priority").asText(null), n.path("confidence").asDouble(0), n.path("explanation").asText(""), arts,
                n.path("reply").asText(null), Map.of("remote", true));
    }
}
