package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Provider-neutral conversation model. Each provider adapter translates to and from these types. */
public final class Llm {
    private Llm() {}

    public interface Client {
        Response complete(Request request);

        String model();
    }

    public record ToolDef(String name, String description, JsonNode schema) {}

    public record ToolCall(String id, String name, JsonNode args) {}

    public record ToolResult(String callId, String name, String content, boolean error) {}

    public enum Role { USER, ASSISTANT, TOOL }

    /**
     * nativeContent keeps the provider's own assistant payload so it is replayed unchanged on the next
     * turn. Reasoning blocks and signatures that some providers require survive that way.
     */
    public record Message(Role role, String text, List<ToolCall> toolCalls, List<ToolResult> toolResults,
                          JsonNode nativeContent) {
        public static Message user(String text) {
            return new Message(Role.USER, text, List.of(), List.of(), null);
        }

        public static Message assistant(Response r) {
            return new Message(Role.ASSISTANT, r.text(), r.toolCalls(), List.of(), r.nativeContent());
        }

        public static Message tool(List<ToolResult> results) {
            return new Message(Role.TOOL, null, List.of(), results, null);
        }
    }

    public record Request(String system, List<Message> messages, List<ToolDef> tools, int maxTokens) {}

    public record Response(String text, List<ToolCall> toolCalls, JsonNode nativeContent, String stopReason,
                           int inputTokens, int outputTokens) {}

    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }
    }
}
