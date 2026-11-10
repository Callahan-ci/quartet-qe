package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays back a hand-written YAML script instead of calling a model, so the demo and tests run with no
 * API key. It exercises every real tool and driver. It does no reasoning.
 *
 * Each entry has "match" (one substring of the first user message, or a list that must all appear) and "turns" (tool calls in order).
 * Arguments may use {{vars.name}}, {{steps.N.path}} and {"@file": "relative/path"}.
 */
public final class ScriptedClient implements Llm.Client {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern VARS_LINE = Pattern.compile("Variables: (\\{.*})");

    private final JsonNode script;
    private final Path baseDir;

    public ScriptedClient(Path scriptFile) {
        try {
            this.script = Json.parseYaml(Files.readString(scriptFile));
            this.baseDir = scriptFile.toAbsolutePath().getParent();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String model() {
        return "scripted";
    }

    @Override
    public Llm.Response complete(Llm.Request request) {
        String prompt = request.messages().get(0).text();
        JsonNode entry = null;
        for (JsonNode candidate : script) {
            if (matches(candidate.path("match"), prompt)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) throw new Llm.LlmException("No scripted entry matches this prompt:\n" + prompt);

        int turn = (int) request.messages().stream().filter(m -> m.role() == Llm.Role.ASSISTANT).count();
        JsonNode planned = entry.path("turns").path(turn);
        if (planned.isMissingNode()) throw new Llm.LlmException("Script ran out of turns for: " + entry.path("match"));

        ObjectNode scope = Json.obj();
        Matcher vars = VARS_LINE.matcher(prompt);
        scope.set("vars", vars.find() ? Json.parse(vars.group(1)) : Json.obj());
        ArrayNode steps = scope.putArray("steps");
        for (Llm.Message message : request.messages()) {
            for (Llm.ToolResult result : message.toolResults()) {
                steps.add(result.content().startsWith("{") ? Json.parse(result.content()) : TextNode.valueOf(result.content()));
            }
        }

        JsonNode args = resolve(planned.path("args"), scope);
        List<Llm.ToolCall> calls = new ArrayList<>();
        calls.add(new Llm.ToolCall("scripted_" + turn, planned.path("tool").asText(), args));
        return new Llm.Response("", calls, null, "tool_use", 0, 0);
    }

    private static boolean matches(JsonNode match, String prompt) {
        if (!match.isArray()) return prompt.contains(match.asText());
        for (JsonNode part : match) {
            if (!prompt.contains(part.asText())) return false;
        }
        return true;
    }

    private JsonNode resolve(JsonNode node, JsonNode scope) {
        if (node.isObject()) {
            if (node.has("@file")) return TextNode.valueOf(readFile(node.path("@file").asText()));
            ObjectNode out = Json.obj();
            node.fields().forEachRemaining(f -> out.set(f.getKey(), resolve(f.getValue(), scope)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.arr();
            node.forEach(item -> out.add(resolve(item, scope)));
            return out;
        }
        if (node.isTextual()) {
            String text = node.asText();
            Matcher whole = TEMPLATE.matcher(text);
            if (whole.matches()) return Json.at(scope, whole.group(1).trim());
            return TextNode.valueOf(TEMPLATE.matcher(text).replaceAll(m ->
                    Matcher.quoteReplacement(Json.at(scope, m.group(1).trim()).asText())));
        }
        return node;
    }

    private String readFile(String relative) {
        try {
            return Files.readString(baseDir.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
