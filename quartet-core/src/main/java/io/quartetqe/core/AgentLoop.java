package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** The model-tool loop shared by the test generator and agent.act(). */
public final class AgentLoop {
    public static final String FINISH = "finish";
    private static final int MAX_TOOL_RESULT_CHARS = 30_000;

    public record Step(String tool, JsonNode args, JsonNode result, boolean error) {}

    public record Outcome(JsonNode finish, List<Step> steps, int inputTokens, int outputTokens, int turns) {}

    public record Spec(String system, String prompt, List<Tool> tools, String finishSchema, String finishDescription,
                       BiFunction<JsonNode, List<Step>, String> finishGuard, int maxTurns, int maxTokens) {}

    public static final class LoopException extends RuntimeException {
        public final transient List<Step> steps;

        public LoopException(String message, List<Step> steps) {
            super(message);
            this.steps = steps;
        }
    }

    private AgentLoop() {}

    public static Outcome run(Llm.Client llm, Spec spec) {
        Map<String, Tool> tools = new LinkedHashMap<>();
        spec.tools().forEach(tool -> tools.put(tool.name(), tool));

        List<Llm.ToolDef> defs = new ArrayList<>();
        spec.tools().forEach(tool -> defs.add(tool.def()));
        defs.add(new Llm.ToolDef(FINISH, spec.finishDescription(), Json.parse(spec.finishSchema())));

        List<Llm.Message> messages = new ArrayList<>();
        messages.add(Llm.Message.user(Redactor.clean(spec.prompt())));
        List<Step> steps = new ArrayList<>();
        int input = 0;
        int output = 0;
        int nudges = 0;

        for (int turn = 1; turn <= spec.maxTurns(); turn++) {
            Llm.Response response = complete(llm, new Llm.Request(spec.system(), messages, defs, spec.maxTokens()), messages);
            input += response.inputTokens();
            output += response.outputTokens();
            messages.add(Llm.Message.assistant(response));

            if (response.toolCalls().isEmpty()) {
                if (++nudges > 2) throw new LoopException("The model stopped without calling finish.", steps);
                messages.add(Llm.Message.user("Continue with a tool call. Call finish when the task is complete."));
                continue;
            }

            JsonNode finished = null;
            List<Llm.ToolResult> results = new ArrayList<>();
            for (Llm.ToolCall call : response.toolCalls()) {
                if (FINISH.equals(call.name())) {
                    String rejection = spec.finishGuard() == null ? null : spec.finishGuard().apply(call.args(), steps);
                    if (rejection == null) {
                        finished = call.args();
                        results.add(new Llm.ToolResult(call.id(), call.name(), "accepted", false));
                    } else {
                        results.add(new Llm.ToolResult(call.id(), call.name(), rejection, true));
                    }
                    continue;
                }
                results.add(execute(tools.get(call.name()), call, steps));
            }
            messages.add(Llm.Message.tool(results));
            if (finished != null) return new Outcome(finished, steps, input, output, turn);
        }
        throw new LoopException("The model did not finish within " + spec.maxTurns() + " turns.", steps);
    }

    /** Adds tool errors to a model failure, because they usually explain it. */
    private static Llm.Response complete(Llm.Client llm, Llm.Request request, List<Llm.Message> messages) {
        try {
            return llm.complete(request);
        } catch (Llm.LlmException e) {
            StringBuilder errors = new StringBuilder();
            for (Llm.Message message : messages) {
                for (Llm.ToolResult result : message.toolResults()) {
                    if (result.error()) errors.append("\n  ").append(result.name()).append(": ").append(result.content(), 0, Math.min(600, result.content().length()));
                }
            }
            if (errors.isEmpty()) throw e;
            throw new Llm.LlmException(e.getMessage() + " Tool errors in this run:" + errors);
        }
    }

    private static Llm.ToolResult execute(Tool tool, Llm.ToolCall call, List<Step> steps) {
        if (tool == null) {
            return new Llm.ToolResult(call.id(), call.name(), "Unknown tool: " + call.name(), true);
        }
        System.err.println("  " + call.name() + progressDetail(call.args()));
        try {
            JsonNode result = tool.handler().call(call.args());
            steps.add(new Step(call.name(), call.args(), result, false));
            return new Llm.ToolResult(call.id(), call.name(), limit(Redactor.clean(result.toString())), false);
        } catch (Exception e) {
            String message = Redactor.clean(e.getClass().getSimpleName() + ": " + e.getMessage());
            steps.add(new Step(call.name(), call.args(), Json.obj().put("error", message), true));
            return new Llm.ToolResult(call.id(), call.name(), limit(message), true);
        }
    }

    /** A short hint so a person watching can see what the AI is doing. */
    private static String progressDetail(JsonNode args) {
        for (String field : new String[] {"file_name", "path", "instruction", "query"}) {
            if (args.hasNonNull(field)) return " " + args.path(field).asText().replaceAll("\\s+", " ").substring(0, Math.min(60, args.path(field).asText().length()));
        }
        return "";
    }

    private static String limit(String content) {
        if (content.length() <= MAX_TOOL_RESULT_CHARS) return content;
        return content.substring(0, MAX_TOOL_RESULT_CHARS) + "\n[output cut at " + MAX_TOOL_RESULT_CHARS
                + " characters; narrow the request to see the rest]";
    }
}
