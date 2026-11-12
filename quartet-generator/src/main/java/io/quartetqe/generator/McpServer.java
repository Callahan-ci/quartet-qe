package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Redactor;
import io.quartetqe.core.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A Model Context Protocol server over standard input and output, so any chat tool that supports MCP
 * can drive Quartet QE with the AI it is already talking to. One JSON message per line.
 */
final class McpServer {
    private static final String LATEST = "2025-06-18";
    private static final List<String> SUPPORTED = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final List<Tool> tools;
    private final String instructions;
    private final PrintStream out;

    McpServer(List<Tool> tools, String instructions, OutputStream out) {
        this.tools = tools;
        this.instructions = instructions;
        this.out = new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    /** Reads requests until the chat tool closes the connection. */
    void serve(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode reply = handle(line);
            if (reply != null) send(reply);
        }
    }

    private synchronized void send(JsonNode message) {
        out.println(Json.str(message));
    }

    /** One incoming message. Returns the reply, or null for a notification. */
    JsonNode handle(String line) {
        JsonNode request;
        try {
            request = Json.parse(line);
        } catch (RuntimeException e) {
            return error(null, -32700, "Parse error");
        }
        JsonNode id = request.get("id");
        String method = request.path("method").asText();
        if (id == null || id.isNull()) return null;
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(request.path("params")));
                case "ping" -> result(id, Json.obj());
                case "tools/list" -> result(id, list());
                case "tools/call" -> result(id, call(request.path("params")));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (RuntimeException e) {
            return error(id, -32603, Redactor.clean(String.valueOf(e.getMessage())));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText(LATEST);
        ObjectNode out = Json.obj().put("protocolVersion", SUPPORTED.contains(asked) ? asked : LATEST);
        out.putObject("capabilities").putObject("tools");
        out.putObject("serverInfo").put("name", "quartet-qe").put("version", "0.1.0");
        out.put("instructions", instructions);
        return out;
    }

    private ObjectNode list() {
        ObjectNode out = Json.obj();
        ArrayNode array = out.putArray("tools");
        for (Tool tool : tools) {
            array.addObject().put("name", tool.name()).put("description", tool.def().description())
                    .set("inputSchema", tool.def().schema());
        }
        return out;
    }

    private ObjectNode call(JsonNode params) {
        String name = params.path("name").asText();
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) return content("Unknown tool '" + name + "'.", true);
        try {
            JsonNode value = tool.handler().call(params.path("arguments"));
            return content(value.isTextual() ? value.asText() : Json.pretty(value), false);
        } catch (Exception e) {
            // Tool problems are reported to the chat AI as a result it can act on, not as a protocol error.
            return content(Redactor.clean(String.valueOf(e.getMessage() == null ? e : e.getMessage())), true);
        }
    }

    private static ObjectNode content(String text, boolean error) {
        ObjectNode out = Json.obj();
        out.putArray("content").addObject().put("type", "text").put("text", Redactor.clean(text));
        out.put("isError", error);
        return out;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode out = Json.obj().put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode out = Json.obj().put("jsonrpc", "2.0");
        out.set("id", id == null ? Json.MAPPER.nullNode() : id);
        out.putObject("error").put("code", code).put("message", message);
        return out;
    }
}
