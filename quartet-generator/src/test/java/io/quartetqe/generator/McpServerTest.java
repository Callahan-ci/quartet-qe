package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpServerTest {
    private final List<Tool> tools = List.of(
            Tool.of("echo", "Echo the text back.", "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                    args -> Json.obj().put("said", args.path("text").asText())),
            Tool.of("boom", "Always fails.", "{\"type\":\"object\"}", args -> {
                throw new IllegalStateException("Something specific to fix");
            }));
    private final McpServer server = new McpServer(tools, "Be helpful.", new ByteArrayOutputStream());

    private JsonNode send(String json) {
        return server.handle(json);
    }

    @Test
    void initializeAgreesAVersionAndSharesTheInstructions() {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
        assertThat(reply.path("id").asInt()).isEqualTo(1);
        assertThat(reply.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(reply.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(reply.path("result").path("serverInfo").path("name").asText()).isEqualTo("quartet-qe");
        assertThat(reply.path("result").path("instructions").asText()).isEqualTo("Be helpful.");
        assertThat(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
    }

    @Test
    void notificationsGetNoReply() {
        assertThat(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void toolsAreListedWithTheirSchemas() {
        JsonNode list = send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}").path("result").path("tools");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("name").asText()).isEqualTo("echo");
        assertThat(list.get(0).path("inputSchema").path("required").get(0).asText()).isEqualTo("text");
    }

    @Test
    void aToolCallReturnsTextTheChatAiCanRead() {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
        assertThat(reply.path("result").path("isError").asBoolean()).isFalse();
        assertThat(reply.path("result").path("content").get(0).path("text").asText()).contains("\"said\"").contains("hi");
    }

    @Test
    void aFailingToolIsReportedAsAnErrorResultNotAProtocolError() {
        JsonNode reply = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"boom\",\"arguments\":{}}}");
        assertThat(reply.has("error")).isFalse();
        assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
        assertThat(reply.path("result").path("content").get(0).path("text").asText()).contains("Something specific to fix");
    }

    @Test
    void unknownToolsMethodsAndBadJsonGetClearErrors() {
        assertThat(send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}")
                .path("result").path("isError").asBoolean()).isTrue();
        assertThat(send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}").path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(send("{not json").path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void serveAnswersEachLineAndStopsWhenTheChatToolCloses() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n\n{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";
        new McpServer(tools, "x", out).serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        assertThat(out.toString(StandardCharsets.UTF_8).strip().split("\n")).hasSize(2);
    }
}
