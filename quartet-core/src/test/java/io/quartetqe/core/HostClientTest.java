package io.quartetqe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostClientTest {
    @TempDir Path dir;

    private static Llm.Request request() {
        return new Llm.Request("Be careful.", List.of(Llm.Message.user("Instruction: place an order")),
                List.of(new Llm.ToolDef("http_request", "Call the API", Json.parse("{\"type\":\"object\"}"))), 1000);
    }

    /** Plays the chat AI: waits for a request file and writes an answer. */
    private Thread chatAi(String answer) {
        Thread thread = new Thread(() -> {
            try {
                Path requests = dir.resolve("requests");
                while (true) {
                    try (var found = Files.list(requests)) {
                        var next = found.filter(p -> p.toString().endsWith(".json")).findFirst();
                        if (next.isPresent()) {
                            Files.writeString(dir.resolve("responses").resolve(next.get().getFileName()), answer);
                            return;
                        }
                    }
                    Thread.sleep(20);
                }
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        return thread;
    }

    @Test
    void theChatAiAnswersWithToolCallsAndNoApiKeyIsNeeded() throws Exception {
        HostClient client = new HostClient(dir, 10, 10);
        Thread ai = chatAi("{\"tool_calls\":[{\"name\":\"http_request\",\"arguments\":{\"method\":\"GET\",\"path\":\"/x\"}}]}");

        Llm.Response response = client.complete(request());
        ai.join();

        assertThat(client.model()).isEqualTo("host");
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("http_request");
        assertThat(response.toolCalls().get(0).args().path("path").asText()).isEqualTo("/x");
        try (var left = Files.list(dir.resolve("requests"))) {
            assertThat(left.toList()).as("the request is cleaned up once answered").isEmpty();
        }
    }

    @Test
    void aTextAnswerEndsTheTurnWithoutToolCalls() throws Exception {
        HostClient client = new HostClient(dir, 10, 10);
        Thread ai = chatAi("{\"text\":\"Nothing more to do.\"}");
        Llm.Response response = client.complete(request());
        ai.join();
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.text()).isEqualTo("Nothing more to do.");
        assertThat(response.stopReason()).isEqualTo("end_turn");
    }

    @Test
    void theRequestShowsTheChatAiTheConversationAndTheToolsItMayCall() {
        Llm.Message result = Llm.Message.tool(List.of(new Llm.ToolResult("c1", "http_request", "{\"status\":201}", false)));
        Llm.Request request = new Llm.Request("System text", List.of(Llm.Message.user("Instruction: x"), result),
                request().tools(), 1000);

        JsonNode rendered = HostClient.render("abc", request);

        assertThat(rendered.path("id").asText()).isEqualTo("abc");
        assertThat(rendered.path("system").asText()).isEqualTo("System text");
        assertThat(rendered.path("messages").get(0).path("text").asText()).isEqualTo("Instruction: x");
        assertThat(rendered.path("messages").get(1).path("tool_results").get(0).path("content").asText()).contains("201");
        assertThat(rendered.path("tools").get(0).path("name").asText()).isEqualTo("http_request");
    }

    @Test
    void anUnansweredStepTimesOutWithAMessageThatSaysWhatToDo() {
        HostClient client = new HostClient(dir, 0, 10);
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(Llm.LlmException.class).hasMessageContaining("quartet_next_step");
    }

    @Test
    void aToolCallWithoutANameIsRejected() {
        assertThatThrownBy(() -> HostClient.toResponse("1", Json.parse("{\"tool_calls\":[{\"arguments\":{}}]}")))
                .hasMessageContaining("needs a name");
    }

    @Test
    void theFactoryUsesTheChatAiWhenAFolderIsGivenAndExplainsWhenItIsMissing() {
        assertThat(LlmFactory.fromEnv(Map.of(HostClient.DIR_ENV, dir.toString())).model()).isEqualTo("host");
        assertThat(LlmFactory.fromEnv(Map.of("AI_PROVIDER", "host", HostClient.DIR_ENV, dir.toString())).model()).isEqualTo("host");
        assertThatThrownBy(() -> LlmFactory.fromEnv(Map.of("AI_PROVIDER", "host")))
                .hasMessageContaining("MCP server").hasMessageContaining(HostClient.DIR_ENV);
    }
}
