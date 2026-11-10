package io.quartetqe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs each adapter through a two-turn tool loop against a local server speaking that provider's wire format. */
class ProviderAdaptersTest {
    private HttpServer server;
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<Map<String, List<String>>> headers = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            headers.add(Map.copyOf(exchange.getRequestHeaders()));
            paths.add(exchange.getRequestURI().toString());
            byte[] reply = replies.remove(0).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AgentLoop.Spec spec() {
        Tool echo = Tool.of("echo", "Echo", "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"v\":{\"type\":\"string\"}}}",
                args -> Json.obj().put("echoed", args.path("v").asText()));
        return new AgentLoop.Spec("sys", "go", List.of(echo), "{\"type\":\"object\"}", "done", null, 5, 1000);
    }

    @Test
    void anthropicReplaysThinkingAndSendsToolResults() {
        replies.add("""
            {"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":3},"content":[
              {"type":"thinking","thinking":"","signature":"sig-1"},
              {"type":"tool_use","id":"tu1","name":"echo","input":{"v":"hi"}}]}""");
        replies.add("""
            {"stop_reason":"tool_use","usage":{"input_tokens":20,"output_tokens":4},"content":[
              {"type":"tool_use","id":"tu2","name":"finish","input":{"ok":true}}]}""");

        var outcome = AgentLoop.run(new AnthropicClient(base, "key-123456", null, new HttpJson()), spec());

        assertThat(outcome.finish().path("ok").asBoolean()).isTrue();
        assertThat(outcome.inputTokens()).isEqualTo(30);
        assertThat(paths.get(0)).isEqualTo("/v1/messages");
        JsonNode first = bodies.get(0);
        assertThat(first.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(first.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(first.path("fallbacks").asText()).isEqualTo("default");
        assertThat(first.has("temperature")).isFalse();
        assertThat(first.path("tools").path(0).has("input_schema")).isTrue();
        JsonNode second = bodies.get(1).path("messages");
        assertThat(second.path(1).path("content").path(0).path("signature").asText()).isEqualTo("sig-1");
        JsonNode toolResult = second.path(2).path("content").path(0);
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("tu1");
        assertThat(toolResult.path("content").asText()).contains("echoed");
    }

    @Test
    void anthropicRefusalIsReported() {
        replies.add("{\"stop_reason\":\"refusal\",\"content\":[],\"stop_details\":{\"category\":\"cyber\"}}");
        assertThatThrownBy(() -> AgentLoop.run(new AnthropicClient(base, "k", null, new HttpJson()), spec()))
                .isInstanceOf(Llm.LlmException.class).hasMessageContaining("cyber");
    }

    @Test
    void openAiRoundTripsToolCalls() {
        replies.add("""
            {"usage":{"prompt_tokens":1,"completion_tokens":1},"choices":[{"finish_reason":"tool_calls","message":{"content":null,
              "tool_calls":[{"id":"c1","type":"function","function":{"name":"echo","arguments":"{\\"v\\":\\"x\\"}"}}]}}]}""");
        replies.add("""
            {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
              {"id":"c2","type":"function","function":{"name":"finish","arguments":"{\\"ok\\":true}"}}]}}]}""");

        AgentLoop.run(new OpenAiClient(OpenAiClient.Flavor.OPENAI, base, "secret-key", "my-model", null, new HttpJson()), spec());

        assertThat(paths.get(0)).isEqualTo("/chat/completions");
        assertThat(headers.get(0).get("Authorization")).containsExactly("Bearer secret-key");
        assertThat(bodies.get(0).path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(bodies.get(0).has("max_completion_tokens")).isTrue();
        JsonNode messages = bodies.get(1).path("messages");
        assertThat(messages.path(2).path("tool_calls").path(0).path("function").path("arguments").isTextual()).isTrue();
        assertThat(messages.path(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.path(3).path("tool_call_id").asText()).isEqualTo("c1");
    }

    @Test
    void azureUsesDeploymentUrlAndApiKeyHeader() {
        replies.add("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\",\"function\":{\"name\":\"finish\",\"arguments\":\"{}\"}}]}}]}");
        AgentLoop.run(new OpenAiClient(OpenAiClient.Flavor.AZURE, base, "azure-key", "dep1", "2024-10-21", new HttpJson()), spec());
        assertThat(paths.get(0)).isEqualTo("/openai/deployments/dep1/chat/completions?api-version=2024-10-21");
        assertThat(headers.get(0).get("Api-key")).containsExactly("azure-key");
        assertThat(bodies.get(0).has("model")).isFalse();
    }

    @Test
    void geminiRoundTripsFunctionCallsAndStripsUnsupportedSchema() {
        replies.add("""
            {"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":2},"candidates":[{"finishReason":"STOP","content":{"parts":[
              {"functionCall":{"name":"echo","args":{"v":"y"}},"thoughtSignature":"abc"}]}}]}""");
        replies.add("""
            {"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"finish","args":{"ok":true}}}]}}]}""");

        AgentLoop.run(new GeminiClient(base, "g-key-123", "gem-model", new HttpJson()), spec());

        assertThat(paths.get(0)).isEqualTo("/v1beta/models/gem-model:generateContent");
        assertThat(bodies.get(0).toString()).doesNotContain("additionalProperties");
        JsonNode contents = bodies.get(1).path("contents");
        assertThat(contents.path(1).path("parts").path(0).path("thoughtSignature").asText()).isEqualTo("abc");
        assertThat(contents.path(2).path("parts").path(0).path("functionResponse").path("name").asText()).isEqualTo("echo");
    }

    @Test
    void factoryPicksProviderFromKeyAndExplainsMissingSettings() {
        assertThat(LlmFactory.fromEnv(Map.of("ANTHROPIC_API_KEY", "abc")).model()).isEqualTo("claude-opus-5-5");
        assertThat(LlmFactory.fromEnv(Map.of("AI_PROVIDER", "ollama", "AI_BASE_URL", base, "AI_MODEL", "llama")).model()).isEqualTo("llama");
        assertThatThrownBy(() -> LlmFactory.fromEnv(Map.of("OPENAI_API_KEY", "abc")))
                .hasMessageContaining("AI_MODEL");
        assertThatThrownBy(() -> LlmFactory.fromEnv(Map.of())).hasMessageContaining("No AI configured");
    }

    @Test
    void guardRejectionLetsTheModelTryAgain() {
        Llm.Client scripted = new Llm.Client() {
            int calls;

            @Override
            public Llm.Response complete(Llm.Request request) {
                calls++;
                return new Llm.Response("", List.of(new Llm.ToolCall("c" + calls, "finish",
                        Json.obj().put("attempt", calls))), null, "tool_use", 0, 0);
            }

            @Override
            public String model() {
                return "x";
            }
        };
        var spec = new AgentLoop.Spec("s", "p", List.of(), "{}", "d",
                (args, steps) -> args.path("attempt").asInt() < 2 ? "not yet" : null, 5, 100);
        assertThat(AgentLoop.run(scripted, spec).finish().path("attempt").asInt()).isEqualTo(2);
    }
}
