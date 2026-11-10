package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quartetqe.core.HttpJson;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP APIs. The model supplies only a path, so it cannot reach another host. */
final class ApiDriver implements Driver {
    private static final int MAX_TEXT_BODY = 8000;

    private final String baseUrl;
    private final JsonNode defaultHeaders;
    private final HttpJson http = new HttpJson();

    ApiDriver(JsonNode config) {
        this.baseUrl = config.path("baseUrl").asText().replaceAll("/$", "");
        this.defaultHeaders = config.path("headers");
    }

    @Override
    public List<Tool> tools() {
        return List.of(Tool.of("http_request",
                "Send an HTTP request to this target. The result has status, body and durationMs. "
                        + "Use 'until' to re-poll an eventually consistent value.",
                """
                {"type":"object","required":["method","path"],"properties":{
                  "method":{"type":"string","enum":["GET","POST","PUT","PATCH","DELETE"]},
                  "path":{"type":"string","description":"Path and query, starting with /"},
                  "headers":{"type":"object"},
                  "body":{"description":"JSON request body"},
                  "until":{"type":"object","description":"Re-send until the result field at 'path' equals 'equals'",
                    "properties":{"path":{"type":"string","description":"For example body.status"},"equals":{},"timeoutMs":{"type":"integer"}}}
                }}""", this::request));
    }

    private JsonNode request(JsonNode args) throws InterruptedException {
        String path = args.path("path").asText();
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("path must start with a single /");

        JsonNode until = args.path("until");
        long deadline = System.currentTimeMillis() + until.path("timeoutMs").asLong(10_000);
        while (true) {
            ObjectNode result = send(args, path);
            if (until.isMissingNode()
                    || Json.at(result, until.path("path").asText()).asText().equals(until.path("equals").asText())
                    || System.currentTimeMillis() >= deadline) {
                return result;
            }
            Thread.sleep(200);
        }
    }

    private ObjectNode send(JsonNode args, String path) {
        Map<String, String> headers = new LinkedHashMap<>();
        defaultHeaders.fields().forEachRemaining(f -> headers.put(f.getKey(), f.getValue().asText()));
        args.path("headers").fields().forEachRemaining(f -> headers.put(f.getKey(), f.getValue().asText()));
        String body = args.hasNonNull("body") ? Json.str(args.get("body")) : null;
        if (body != null) headers.putIfAbsent("content-type", "application/json");

        long start = System.nanoTime();
        HttpJson.Resp response = http.send(args.path("method").asText(), baseUrl + path, headers, body, Duration.ofSeconds(30));
        ObjectNode result = Json.obj();
        result.put("status", response.status());
        result.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        result.set("body", parseBody(response.body()));
        return Driver.check(result, "status:" + response.status());
    }

    private static JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) return Json.obj();
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException e) {
            // Long pages are noise to the agent. The status and timing are what matter.
            return TextNode.valueOf(body.length() <= MAX_TEXT_BODY ? body : body.substring(0, MAX_TEXT_BODY) + "\n[response cut]");
        }
    }
}
