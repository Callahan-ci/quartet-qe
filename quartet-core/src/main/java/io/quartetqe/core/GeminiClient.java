package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Google Gemini generateContent adapter. */
public final class GeminiClient implements Llm.Client {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpJson http;

    public GeminiClient(String baseUrl, String apiKey, String model, HttpJson http) {
        this.baseUrl = baseUrl == null ? "https://generativelanguage.googleapis.com" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.http = http;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Llm.Response complete(Llm.Request request) {
        ObjectNode body = Json.obj();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        body.putObject("generationConfig").put("maxOutputTokens", request.maxTokens());

        ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
        for (Llm.ToolDef def : request.tools()) {
            declarations.addObject().put("name", def.name()).put("description", def.description())
                    .set("parameters", sanitize(def.schema()));
        }

        ArrayNode contents = body.putArray("contents");
        for (Llm.Message message : request.messages()) contents.add(toNative(message));

        HttpJson.Resp response = http.send("POST", baseUrl + "/v1beta/models/" + model + ":generateContent",
                Map.of("content-type", "application/json", "x-goog-api-key", apiKey), Json.str(body),
                Duration.ofMinutes(10));
        if (!response.ok()) {
            throw new Llm.LlmException("Gemini returned " + response.status() + ": " + Redactor.clean(response.body()));
        }
        return fromNative(response.json());
    }

    /** Gemini accepts a subset of JSON Schema, so unsupported keywords are removed recursively. */
    private JsonNode sanitize(JsonNode schema) {
        if (schema.isObject()) {
            ObjectNode out = Json.obj();
            schema.fields().forEachRemaining(field -> {
                if (!field.getKey().equals("additionalProperties") && !field.getKey().equals("$schema")) {
                    out.set(field.getKey(), sanitize(field.getValue()));
                }
            });
            return out;
        }
        if (schema.isArray()) {
            ArrayNode out = Json.arr();
            schema.forEach(item -> out.add(sanitize(item)));
            return out;
        }
        return schema;
    }

    private JsonNode toNative(Llm.Message message) {
        ObjectNode out = Json.obj();
        switch (message.role()) {
            case USER -> out.put("role", "user").putArray("parts").addObject().put("text", message.text());
            case ASSISTANT -> {
                out.put("role", "model");
                if (message.nativeContent() != null) {
                    out.set("parts", message.nativeContent());
                } else {
                    ArrayNode parts = out.putArray("parts");
                    if (message.text() != null && !message.text().isEmpty()) parts.addObject().put("text", message.text());
                    for (Llm.ToolCall call : message.toolCalls()) {
                        parts.addObject().putObject("functionCall").put("name", call.name()).set("args", call.args());
                    }
                }
            }
            case TOOL -> {
                out.put("role", "user");
                ArrayNode parts = out.putArray("parts");
                for (Llm.ToolResult result : message.toolResults()) {
                    ObjectNode fn = parts.addObject().putObject("functionResponse").put("name", result.name());
                    fn.putObject("response").put(result.error() ? "error" : "result", result.content());
                }
            }
        }
        return out;
    }

    private Llm.Response fromNative(JsonNode json) {
        JsonNode candidate = json.path("candidates").path(0);
        String finish = candidate.path("finishReason").asText();
        if ("MAX_TOKENS".equals(finish)) {
            throw new Llm.LlmException("The model ran out of output tokens before finishing its reply.");
        }
        if (candidate.isMissingNode()) {
            throw new Llm.LlmException("Gemini returned no candidates: " + json.path("promptFeedback"));
        }
        StringBuilder text = new StringBuilder();
        List<Llm.ToolCall> calls = new ArrayList<>();
        JsonNode parts = candidate.path("content").path("parts");
        for (JsonNode part : parts) {
            if (part.has("text")) text.append(part.path("text").asText());
            if (part.has("functionCall")) {
                calls.add(new Llm.ToolCall("call_" + calls.size(), part.path("functionCall").path("name").asText(),
                        part.path("functionCall").path("args")));
            }
        }
        return new Llm.Response(text.toString(), calls, parts, finish,
                json.path("usageMetadata").path("promptTokenCount").asInt(),
                json.path("usageMetadata").path("candidatesTokenCount").asInt());
    }
}
