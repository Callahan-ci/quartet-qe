package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions adapter. One class covers OpenAI, Azure OpenAI and every OpenAI-compatible server
 * (Ollama, vLLM, LiteLLM, Mistral, Groq, internal gateways).
 */
public final class OpenAiClient implements Llm.Client {
    public enum Flavor { OPENAI, AZURE, COMPATIBLE }

    private final Flavor flavor;
    private final String url;
    private final String apiKey;
    private final String model;
    private final HttpJson http;

    public OpenAiClient(Flavor flavor, String baseUrl, String apiKey, String model, String azureApiVersion, HttpJson http) {
        this.flavor = flavor;
        this.apiKey = apiKey;
        this.model = model;
        this.http = http;
        String base = baseUrl == null ? "https://api.openai.com/v1" : baseUrl.replaceAll("/$", "");
        this.url = flavor == Flavor.AZURE
                ? base + "/openai/deployments/" + model + "/chat/completions?api-version=" + azureApiVersion
                : base + "/chat/completions";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Llm.Response complete(Llm.Request request) {
        ObjectNode body = Json.obj();
        if (flavor != Flavor.AZURE) body.put("model", model);
        body.put(flavor == Flavor.COMPATIBLE ? "max_tokens" : "max_completion_tokens", request.maxTokens());

        ArrayNode tools = body.putArray("tools");
        for (Llm.ToolDef def : request.tools()) {
            ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
            fn.put("name", def.name()).put("description", def.description()).set("parameters", def.schema());
        }

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        for (Llm.Message message : request.messages()) addNative(messages, message);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        if (flavor == Flavor.AZURE) {
            headers.put("api-key", apiKey);
        } else if (apiKey != null && !apiKey.isBlank()) {
            headers.put("authorization", "Bearer " + apiKey);
        }

        HttpJson.Resp response = http.send("POST", url, headers, Json.str(body), Duration.ofMinutes(10));
        if (!response.ok()) {
            throw new Llm.LlmException("Model endpoint returned " + response.status() + ": " + Redactor.clean(response.body()));
        }
        return fromNative(response.json());
    }

    private void addNative(ArrayNode messages, Llm.Message message) {
        switch (message.role()) {
            case USER -> messages.addObject().put("role", "user").put("content", message.text());
            case ASSISTANT -> {
                ObjectNode out = messages.addObject().put("role", "assistant");
                out.put("content", message.text() == null ? "" : message.text());
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = out.putArray("tool_calls");
                    for (Llm.ToolCall call : message.toolCalls()) {
                        calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                                .put("name", call.name()).put("arguments", Json.str(call.args()));
                    }
                }
            }
            case TOOL -> {
                for (Llm.ToolResult result : message.toolResults()) {
                    messages.addObject().put("role", "tool").put("tool_call_id", result.callId())
                            .put("content", result.error() ? "ERROR: " + result.content() : result.content());
                }
            }
        }
    }

    private Llm.Response fromNative(JsonNode json) {
        JsonNode choice = json.path("choices").path(0);
        String finish = choice.path("finish_reason").asText();
        if ("length".equals(finish)) {
            throw new Llm.LlmException("The model ran out of output tokens before finishing its reply.");
        }
        JsonNode message = choice.path("message");
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            String arguments = call.path("function").path("arguments").asText("{}");
            calls.add(new Llm.ToolCall(call.path("id").asText(), call.path("function").path("name").asText(),
                    Json.parse(arguments.isBlank() ? "{}" : arguments)));
        }
        return new Llm.Response(message.path("content").asText(""), calls, null, finish,
                json.path("usage").path("prompt_tokens").asInt(), json.path("usage").path("completion_tokens").asInt());
    }
}
