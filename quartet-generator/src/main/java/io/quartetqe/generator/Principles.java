package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The engineering principles as data, so each team can tune severity without changing code. */
public final class Principles {
    private record Rule(String principle, String severity) {}

    private final JsonNode root;
    private final Map<String, Rule> rules = new LinkedHashMap<>();

    private Principles(JsonNode root) {
        this.root = root;
        for (JsonNode principle : root.path("principles")) {
            for (JsonNode rule : principle.path("rules")) {
                // YAML reads an unquoted off as the boolean false, so a team that wrote "severity: off" must still get off.
                JsonNode level = rule.path("severity");
                String severity = level.isBoolean() ? (level.asBoolean() ? "block" : "off") : level.asText("block");
                rules.put(rule.path("id").asText(), new Rule(principle.path("name").asText(), severity));
            }
        }
    }

    public static Principles load(Path contextRoot) {
        Path file = contextRoot.resolve("engineering-principles/principles.yaml");
        try {
            // The repo's own file wins. Without one, Quartet's default principles apply.
            String yaml = Files.isRegularFile(file) ? Files.readString(file) : Defaults.get("engineering-principles/principles.yaml");
            if (yaml == null) throw new IllegalStateException("No engineering-principles/principles.yaml in the context, and no default to fall back on.");
            return new Principles(Json.parseYaml(yaml));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds a finding from a rule ID. Returns empty when the team has set that rule to off. */
    public Optional<Finding> finding(String ruleId, String location, String message) {
        Rule rule = rules.get(ruleId);
        if (rule == null) throw new IllegalArgumentException("Rule " + ruleId + " is not defined in principles.yaml");
        if ("off".equals(rule.severity())) return Optional.empty();
        return Optional.of(new Finding(ruleId, rule.principle(),
                "warn".equals(rule.severity()) ? Finding.Severity.WARN : Finding.Severity.BLOCK, location, message));
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        root.path("principles").forEach(p -> names.add(p.path("name").asText()));
        return names;
    }

    /** The principles and their meaning for tests, as prompt text. */
    public String asPrompt() {
        StringBuilder out = new StringBuilder();
        for (JsonNode principle : root.path("principles")) {
            out.append("- ").append(principle.path("name").asText()).append(": ").append(principle.path("forTests").asText()).append('\n');
        }
        return out.toString();
    }
}
