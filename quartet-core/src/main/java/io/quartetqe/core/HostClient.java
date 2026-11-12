package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The AI the user is already chatting with, used as the model. No API key.
 *
 * Each model turn becomes a request file in a shared folder. The chat AI reads it through the
 * quartet-qe MCP server, decides what to do next, and the MCP server writes the answer back. This
 * client waits for that answer. Everything else is unchanged: tools, grounding, recordings.
 */
public final class HostClient implements Llm.Client {
    public static final String DIR_ENV = "QUARTET_HOST_DIR";
    public static final String TIMEOUT_ENV = "QUARTET_HOST_TIMEOUT_SECONDS";

    private final Path requests;
    private final Path responses;
    private final long timeoutMs;
    private final long pollMs;

    public HostClient(Path directory, long timeoutSeconds, long pollMs) {
        this.requests = directory.resolve("requests");
        this.responses = directory.resolve("responses");
        this.timeoutMs = timeoutSeconds * 1000;
        this.pollMs = pollMs;
        try {
            Files.createDirectories(requests);
            Files.createDirectories(responses);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HostClient fromEnv(java.util.Map<String, String> env) {
        String directory = env.get(DIR_ENV);
        if (directory == null || directory.isBlank()) {
            throw new Llm.LlmException("AI_PROVIDER=host works through the quartet-qe MCP server, which supplies "
                    + DIR_ENV + ". Start it with ./quartet-qe mcp from your chat tool. See GETTING-STARTED.md.");
        }
        long timeout = Long.parseLong(env.getOrDefault(TIMEOUT_ENV, "900"));
        return new HostClient(Path.of(directory), timeout, 100);
    }

    @Override
    public String model() {
        return "host";
    }

    @Override
    public Llm.Response complete(Llm.Request request) {
        String id = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        write(requests.resolve(id + ".json"), render(id, request));
        Path answer = responses.resolve(id + ".json");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(answer)) {
            if (System.currentTimeMillis() > deadline) {
                delete(requests.resolve(id + ".json"));
                throw new Llm.LlmException("The chat AI did not answer step " + id + " within " + timeoutMs / 1000
                        + " seconds. Call quartet_next_step and quartet_answer_step to continue, or raise "
                        + TIMEOUT_ENV + ".");
            }
            sleep();
        }
        JsonNode reply;
        try {
            reply = Json.parse(Files.readString(answer));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(answer);
            delete(requests.resolve(id + ".json"));
        }
        return toResponse(id, reply);
    }

    /** What the chat AI sees for one turn. */
    static ObjectNode render(String id, Llm.Request request) {
        ObjectNode out = Json.obj().put("id", id);
        out.put("system", request.system() == null ? "" : request.system());
        ArrayNode messages = out.putArray("messages");
        for (Llm.Message message : request.messages()) {
            ObjectNode item = messages.addObject().put("role", message.role().name().toLowerCase());
            if (message.text() != null && !message.text().isBlank()) item.put("text", message.text());
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                message.toolCalls().forEach(call -> calls.addObject().put("name", call.name()).set("arguments", call.args()));
            }
            if (!message.toolResults().isEmpty()) {
                ArrayNode results = item.putArray("tool_results");
                message.toolResults().forEach(result -> results.addObject().put("name", result.name())
                        .put("error", result.error()).put("content", result.content()));
            }
        }
        ArrayNode tools = out.putArray("tools");
        request.tools().forEach(tool -> tools.addObject().put("name", tool.name())
                .put("description", tool.description()).set("input_schema", tool.schema()));
        return out;
    }

    static Llm.Response toResponse(String id, JsonNode reply) {
        List<Llm.ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode call : reply.path("tool_calls")) {
            String name = call.path("name").asText();
            if (name.isBlank()) throw new Llm.LlmException("An answer's tool call needs a name.");
            JsonNode arguments = call.has("arguments") ? call.path("arguments") : Json.obj();
            calls.add(new Llm.ToolCall("host_" + id + "_" + index++, name, arguments));
        }
        String text = reply.path("text").asText("");
        return new Llm.Response(text, calls, null, calls.isEmpty() ? "end_turn" : "tool_use", 0, 0);
    }

    private static void write(Path file, JsonNode content) {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.str(content), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is cleaned up by the MCP server on its next start.
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Llm.LlmException("Interrupted while waiting for the chat AI.");
        }
    }
}
