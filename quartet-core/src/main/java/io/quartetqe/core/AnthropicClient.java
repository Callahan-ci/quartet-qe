package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude Messages API adapter. Uses plain HTTP so every provider adapter has the same footprint. */
public final class AnthropicClient implements Llm.Client {
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpJson http;

    public AnthropicClient(String baseUrl, String apiKey, String model, HttpJson http) {
        this.baseUrl = baseUrl == null ? "https://api.anthropic.com" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.model = model == null ? DEFAULT_MODEL : model;
        this.http = http;
    }

    @Override
    public String model() {
        return model;
    }

    /** Adaptive thinking, effort and server-side fallbacks only exist on current-generation models. */
    private boolean currentGeneration() {
        return model.matches("claude-(opus-5|sonnet-5|fable-5).*");
    }

    @Override
    public Llm.Response complete(Llm.Request request) {
        ObjectNode body = Json.obj();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("system", request.system());
        if (currentGeneration()) {
            body.putObject("thinking").put("type", "adaptive");
            body.putObject("output_config").put("effort", "high");
            body.put("fallbacks", "default");
        }

        ArrayNode tools = body.putArray("tools");
        for (Llm.ToolDef def : request.tools()) {
            tools.addObject().put("name", def.name()).put("description", def.description())
                    .set("input_schema", def.schema());
        }

        ArrayNode messages = body.putArray("messages");
        for (Llm.Message message : request.messages()) messages.add(toNative(message));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2026-10-21");
        if (currentGeneration()) headers.put("anthropic-beta", FALLBACK_BETA);

        HttpJson.Resp response = http.send("POST", baseUrl + "/v1/messages", headers, Json.str(body),
                Duration.ofMinutes(10));
        if (!response.ok()) {
            throw new Llm.LlmException("Anthropic returned " + response.status() + ": " + Redactor.clean(response.body()));
        }
        return fromNative(response.json());
    }

    private JsonNode toNative(Llm.Message message) {
        ObjectNode out = Json.obj();
        switch (message.role()) {
            case USER -> out.put("role", "user").put("content", message.text());
            case ASSISTANT -> {
                out.put("role", "assistant");
                if (message.nativeContent() != null) {
                    out.set("content", message.nativeContent());
                } else {
                    ArrayNode content = out.putArray("content");
                    if (message.text() != null && !message.text().isEmpty()) {
                        content.addObject().put("type", "text").put("text", message.text());
                    }
                    for (Llm.ToolCall call : message.toolCalls()) {
                        content.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name())
                                .set("input", call.args());
                    }
                }
            }
            case TOOL -> {
                out.put("role", "user");
                ArrayNode content = out.putArray("content");
                for (Llm.ToolResult result : message.toolResults()) {
                    content.addObject().put("type", "tool_result").put("tool_use_id", result.callId())
                            .put("content", result.content()).put("is_error", result.error());
                }
            }
        }
        return out;
    }

    private Llm.Response fromNative(JsonNode json) {
        String stop = json.path("stop_reason").asText();
        if ("refusal".equals(stop)) {
            throw new Llm.LlmException("The model declined this request: " + json.path("stop_details"));
        }
        if ("max_tokens".equals(stop)) {
            throw new Llm.LlmException("The model ran out of output tokens before finishing its reply.");
        }
        StringBuilder text = new StringBuilder();
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (JsonNode block : json.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> text.append(block.path("text").asText());
                case "tool_use" -> calls.add(new Llm.ToolCall(block.path("id").asText(), block.path("name").asText(),
                        block.path("input")));
                default -> { /* thinking blocks stay in nativeContent and are replayed untouched */ }
            }
        }
        return new Llm.Response(text.toString(), calls, json.path("content"), stop,
                json.path("usage").path("input_tokens").asInt(), json.path("usage").path("output_tokens").asInt());
    }
}
