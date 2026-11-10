package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quartetqe.core.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ties what the model said to what the tools observed.
 *
 * Returned values are bound to the tool-result path they came from. A value with no source is
 * ungrounded and rejected, and replays read fresh values so assertions always see current behaviour.
 */
final class Binder {
    private static final Pattern EXPR = Pattern.compile("\\$\\{((?:vars|steps)\\.[^}]+)}");

    record Bound(JsonNode template, List<String> ungrounded) {}

    private record Leaf(String path, JsonNode value) {
        String key() {
            String last = path.substring(path.lastIndexOf('.') + 1);
            return normalize(last);
        }
    }

    private Binder() {}

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase();
    }

    private static boolean identifierLike(String text) {
        return text.length() >= 6 && text.chars().anyMatch(Character::isDigit);
    }

    /** Replaces variable values and earlier generated IDs in tool arguments with references. */
    static JsonNode parameterize(JsonNode args, Map<String, JsonNode> vars, List<JsonNode> prior) {
        Map<String, String> candidates = new LinkedHashMap<>();
        for (int i = 0; i < prior.size(); i++) {
            for (Leaf leaf : leaves("steps." + i, prior.get(i))) {
                if (leaf.value().isTextual() && identifierLike(leaf.value().asText()) && !leaf.path().endsWith("replayCheck")) {
                    candidates.putIfAbsent(leaf.value().asText(), leaf.path());
                }
            }
        }
        vars.forEach((name, value) -> {
            if (value.asText().length() >= 3) candidates.put(value.asText(), "vars." + name);
        });
        List<String> ordered = new ArrayList<>(candidates.keySet());
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return substitute(args, ordered, candidates, vars);
    }

    private static JsonNode substitute(JsonNode node, List<String> ordered, Map<String, String> candidates,
                                       Map<String, JsonNode> vars) {
        if (node.isObject()) {
            ObjectNode out = Json.obj();
            node.fields().forEachRemaining(f -> out.set(f.getKey(), substitute(f.getValue(), ordered, candidates, vars)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.arr();
            node.forEach(item -> out.add(substitute(item, ordered, candidates, vars)));
            return out;
        }
        if (node.isTextual()) {
            String text = node.asText();
            for (String value : ordered) text = text.replace(value, "${" + candidates.get(value) + "}");
            return TextNode.valueOf(text);
        }
        if (node.isNumber()) {
            for (var entry : vars.entrySet()) {
                if (entry.getValue().isNumber() && entry.getValue().equals(node)) {
                    return TextNode.valueOf("${vars." + entry.getKey() + "}");
                }
            }
        }
        return node;
    }

    /** Finds where each returned value came from. Unmatched values are reported as ungrounded. */
    static Bound bindData(JsonNode data, Map<String, JsonNode> vars, List<JsonNode> steps) {
        List<Leaf> pool = new ArrayList<>();
        vars.forEach((name, value) -> pool.add(new Leaf("vars." + name, value)));
        for (int i = steps.size() - 1; i >= 0; i--) pool.addAll(leaves("steps." + i, steps.get(i)));
        List<Leaf> containers = new ArrayList<>();
        for (int i = steps.size() - 1; i >= 0; i--) collectContainers("steps." + i, steps.get(i), containers);

        List<String> ungrounded = new ArrayList<>();
        JsonNode template = bindNode("", data, pool, containers, ungrounded);
        return new Bound(template, ungrounded);
    }

    private static JsonNode bindNode(String path, JsonNode node, List<Leaf> pool, List<Leaf> containers, List<String> ungrounded) {
        if (node.isObject() || node.isArray()) {
            if (!path.isEmpty() && !node.isEmpty()) {
                for (Leaf container : containers) {
                    if (container.value().equals(node)) return TextNode.valueOf("${" + container.path() + "}");
                }
            }
        }
        if (node.isObject()) {
            ObjectNode out = Json.obj();
            node.fields().forEachRemaining(f -> out.set(f.getKey(),
                    bindNode(path.isEmpty() ? f.getKey() : path + "." + f.getKey(), f.getValue(), pool, containers, ungrounded)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.arr();
            int index = 0;
            for (JsonNode item : node) out.add(bindNode(path + "." + index++, item, pool, containers, ungrounded));
            return out;
        }
        String source = findSource(path, node, pool);
        if (source == null) {
            ungrounded.add(path + "=" + node.asText());
            return node;
        }
        return TextNode.valueOf("${" + source + "}");
    }

    private static String findSource(String path, JsonNode value, List<Leaf> pool) {
        String wantedKey = normalize(path.substring(path.lastIndexOf('.') + 1));
        String text = value.asText();
        List<Leaf> matches = new ArrayList<>();
        for (Leaf leaf : pool) {
            if (leaf.value().asText().equals(text) && !leaf.path().endsWith("replayCheck")) matches.add(leaf);
        }
        for (Leaf match : matches) {
            if (match.key().equals(wantedKey)) return match.path();
        }
        boolean distinctive = value.isTextual() && (identifierLike(text) || (text.length() >= 4 && matches.size() == 1));
        return distinctive && !matches.isEmpty() ? matches.get(0).path() : null;
    }

    static List<Leaf> leaves(String prefix, JsonNode node) {
        List<Leaf> out = new ArrayList<>();
        collect(prefix, node, out);
        return out;
    }

    private static void collect(String path, JsonNode node, List<Leaf> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> collect(path + "." + f.getKey(), f.getValue(), out));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) collect(path + "." + index++, item, out);
        } else if (!node.isNull()) {
            out.add(new Leaf(path, node));
        }
    }

    private static void collectContainers(String path, JsonNode node, List<Leaf> out) {
        if (node.isObject()) {
            out.add(new Leaf(path, node));
            node.fields().forEachRemaining(f -> collectContainers(path + "." + f.getKey(), f.getValue(), out));
        } else if (node.isArray()) {
            out.add(new Leaf(path, node));
            int index = 0;
            for (JsonNode item : node) collectContainers(path + "." + index++, item, out);
        }
    }

    /** Fills ${vars.x} and ${steps.N.path} from the current run. A reference alone keeps the source's JSON type. */
    static JsonNode resolve(JsonNode template, JsonNode scope) {
        if (template.isObject()) {
            ObjectNode out = Json.obj();
            template.fields().forEachRemaining(f -> out.set(f.getKey(), resolve(f.getValue(), scope)));
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = Json.arr();
            template.forEach(item -> out.add(resolve(item, scope)));
            return out;
        }
        if (!template.isTextual()) return template;
        String text = template.asText();
        Matcher whole = EXPR.matcher(text);
        if (whole.matches()) return Json.at(scope, whole.group(1));
        return TextNode.valueOf(EXPR.matcher(text).replaceAll(m -> {
            JsonNode value = Json.at(scope, m.group(1));
            return Matcher.quoteReplacement(value instanceof MissingNode ? "" : value.asText());
        }));
    }
}
