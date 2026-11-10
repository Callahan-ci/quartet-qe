package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Thin HTTP wrapper used by AI providers and connectors. */
public final class HttpJson {
    private static final long MAX_WAIT_MS = 60_000;
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504, 529);

    public record Resp(int status, String body, Optional<String> location) {
        public JsonNode json() {
            return Json.parse(body);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final int maxRetries;
    private final long baseBackoffMs;

    public HttpJson() {
        this(0, 1000);
    }

    public HttpJson(int maxRetries, long baseBackoffMs) {
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
    }

    public Resp send(String method, String url, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        headers.forEach(builder::header);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpRequest request = builder.build();

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (attempt < maxRetries && RETRYABLE.contains(response.statusCode())) {
                    long wait = Math.min(backoff(attempt, response), MAX_WAIT_MS);
                    if (maxRetries > 2) System.err.println("  The model service is busy (" + response.statusCode() + "). Retrying in " + wait / 1000 + "s.");
                    sleep(wait);
                    continue;
                }
                return new Resp(response.statusCode(), response.body(), response.headers().firstValue("location"));
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw new IllegalStateException(method + " " + stripQuery(url) + " failed: " + e, e);
                }
                sleep(baseBackoffMs << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted calling " + stripQuery(url), e);
            }
        }
    }

    private long backoff(int attempt, HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .filter(v -> v.matches("\\d+"))
                .map(v -> Long.parseLong(v) * 1000)
                .orElse(baseBackoffMs << attempt);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Query strings can carry credentials, so they never appear in error messages. */
    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
