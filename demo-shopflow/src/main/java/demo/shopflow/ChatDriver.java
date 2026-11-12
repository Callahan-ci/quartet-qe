package demo.shopflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Llm;
import io.quartetqe.core.ScriptedClient;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a chat AI, with no API key. It starts the real "quartet-qe mcp" server, speaks MCP to it
 * exactly as a chat tool would, and answers each model step from a hand-written script.
 *
 * This proves the whole chat path: story, context, tests, independent review, a test run in which every
 * step is answered through the chat, and the review page. It shows the plumbing, not AI judgement.
 */
public final class ChatDriver {
    private final Process server;
    private final BufferedWriter in;
    private final BufferedReader out;
    private int nextId = 1;

    private ChatDriver(String command, Path root) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command, "mcp").directory(root.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("AI_"));
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        server = builder.start();
        in = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
    }

    private JsonNode rpc(String method, ObjectNode params) throws Exception {
        int id = nextId++;
        ObjectNode request = Json.obj().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params);
        in.write(Json.str(request));
        in.newLine();
        in.flush();
        String line;
        while ((line = out.readLine()) != null) {
            JsonNode reply = Json.parse(line);
            if (reply.path("id").asInt(-1) == id) {
                if (reply.has("error")) throw new IllegalStateException(reply.path("error").path("message").asText());
                return reply.path("result");
            }
        }
        throw new IllegalStateException("The Quartet QE server closed the connection.");
    }

    /** Calls one tool and returns its text, as a chat AI would read it. */
    private String tool(String name, ObjectNode arguments) throws Exception {
        ObjectNode params = Json.obj().put("name", name);
        params.set("arguments", arguments);
        JsonNode result = rpc("tools/call", params);
        String text = result.path("content").get(0).path("text").asText();
        if (result.path("isError").asBoolean()) throw new IllegalStateException(name + " failed: " + text);
        return text;
    }

    private static ObjectNode args(Object... pairs) {
        ObjectNode node = Json.obj();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof JsonNode json) node.set((String) pairs[i], json);
            else if (value instanceof Boolean flag) node.put((String) pairs[i], flag);
            else node.put((String) pairs[i], String.valueOf(value));
        }
        return node;
    }

    /** What the scripted "model" needs: the conversation so far, rebuilt from the step the server showed. */
    private static Llm.Request toRequest(JsonNode step) {
        List<Llm.Message> messages = new ArrayList<>();
        for (JsonNode message : step.path("messages")) {
            switch (message.path("role").asText()) {
                case "user" -> messages.add(Llm.Message.user(message.path("text").asText()));
                case "assistant" -> messages.add(new Llm.Message(Llm.Role.ASSISTANT, message.path("text").asText(""), List.of(), List.of(), null));
                default -> {
                    List<Llm.ToolResult> results = new ArrayList<>();
                    message.path("tool_results").forEach(r -> results.add(new Llm.ToolResult("", r.path("name").asText(), r.path("content").asText(), r.path("error").asBoolean())));
                    messages.add(Llm.Message.tool(results));
                }
            }
        }
        return new Llm.Request(step.path("system").asText(), messages, List.of(), 0);
    }

    public static void main(String[] argv) throws Exception {
        Path root = Path.of(argv[0]).toAbsolutePath();
        Path script = Path.of(argv[1]).toAbsolutePath();
        ChatDriver chat = new ChatDriver(argv[2], root);
        ScriptedClient model = new ScriptedClient(script);
        String finalTest = Files.readString(script.getParent().resolve("Final.java.txt"));

        JsonNode init = chat.rpc("initialize", args("protocolVersion", "2025-06-18"));
        System.out.println("Connected to " + init.path("serverInfo").path("name").asText() + ", no API key");
        System.out.println("Tools    " + chat.rpc("tools/list", Json.obj()).path("tools").size() + " available to the chat AI");

        JsonNode start = Json.parse(chat.tool("quartet_start", args("story", "stories/SHOP-142.md")));
        System.out.println("Story    " + start.path("story").path("id").asText() + ": " + start.path("story").path("acceptance_criteria").size()
                + " acceptance criteria, " + start.path("questions").size() + " questions");
        for (String file : List.of("engineering-principles/principles.yaml", "contracts/openapi/orders-service.yaml", "telemetry/orders-service.yaml")) {
            chat.tool("context_read", args("path", file));
        }
        chat.tool("write_test", args("file_name", "CheckoutReservesStockTest.java", "content", finalTest));
        JsonNode validation = Json.parse(chat.tool("validate_tests", Json.obj()));
        System.out.println("Tests    written, " + validation.path("blocking").asInt() + " blocking findings");

        chat.tool("quartet_finish", args("traceability", Json.arr(), "run", true));
        int steps = 0;
        JsonNode finished = null;
        while (finished == null) {
            JsonNode reply = Json.parse(chat.tool("quartet_next_step", args("wait_seconds", "60")));
            switch (reply.path("type").asText()) {
                case "step" -> {
                    Llm.Response answer = model.complete(toRequest(reply));
                    ObjectNode body = Json.obj().put("id", reply.path("id").asText());
                    var calls = body.putArray("tool_calls");
                    answer.toolCalls().forEach(c -> calls.addObject().put("name", c.name()).set("arguments", c.args()));
                    chat.tool("quartet_answer_step", body);
                    steps++;
                }
                case "finished" -> finished = reply.path("result");
                case "waiting" -> { }
                default -> throw new IllegalStateException("Unexpected: " + reply);
            }
        }
        System.out.println("Steps    " + steps + " model steps answered through the chat");
        System.out.println("Result   " + finished.path("status").asText() + ": " + finished.path("passed").asInt() + " passed, "
                + finished.path("failed").asInt() + " failed, " + finished.path("skipped").asInt() + " skipped");
        System.out.println("Review   " + finished.path("review_page").asText());
        chat.server.destroy();
        System.exit("ready_to_merge".equals(finished.path("status").asText()) || "needs_attention".equals(finished.path("status").asText())
                && finished.path("failed").asInt() == 0 ? 0 : 2);
    }
}
