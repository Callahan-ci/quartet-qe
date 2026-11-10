package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Redactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The systems tests may touch. Hosts outside the allow-list are refused before any connection is made. */
public final class Environment {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(?::-([^}]*))?}");
    private static final Pattern HOST = Pattern.compile("^(?:[a-z:]+://)?(?:[^@/]+@)?([A-Za-z0-9.\\-]+)(?::\\d+)?(?:[/?].*)?$");

    private final String name;
    private final Path contextRoot;
    private final Map<String, JsonNode> targets = new LinkedHashMap<>();
    private final List<String> allowHosts = new ArrayList<>();

    private Environment(String name, Path contextRoot) {
        this.name = name;
        this.contextRoot = contextRoot;
    }

    public static Environment load(Path file, Map<String, String> env) {
        JsonNode root;
        try {
            root = interpolate(Json.parseYaml(Files.readString(file)), env);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path contextRoot = file.toAbsolutePath().normalize().getParent().getParent();
        Environment environment = new Environment(root.path("name").asText("unnamed"), contextRoot);

        if (root.path("guard").path("production").asBoolean(false)
                && !"true".equals(env.get("QUARTET_ALLOW_PRODUCTION"))) {
            throw new IllegalStateException("Environment '" + environment.name
                    + "' is marked production. Set QUARTET_ALLOW_PRODUCTION=true to run against it.");
        }
        root.path("guard").path("allowHosts").forEach(h -> environment.allowHosts.add(h.asText()));
        if (environment.allowHosts.isEmpty()) {
            throw new IllegalStateException("Environment file must list guard.allowHosts.");
        }

        root.path("targets").fields().forEachRemaining(entry -> {
            environment.checkHosts(entry.getKey(), entry.getValue());
            environment.targets.put(entry.getKey(), entry.getValue());
        });
        return environment;
    }

    private void checkHosts(String target, JsonNode config) {
        for (String field : List.of("baseUrl", "url", "brokers")) {
            if (!config.hasNonNull(field) || config.path(field).asText().isBlank()) continue;
            for (String item : config.path(field).asText().split(",")) {
                Matcher matcher = HOST.matcher(item.trim());
                if (!matcher.matches() || !allowed(matcher.group(1))) {
                    throw new IllegalStateException("Target '" + target + "' uses a host outside guard.allowHosts.");
                }
            }
        }
    }

    public boolean allowed(String host) {
        if (host == null) return false;
        for (String pattern : allowHosts) {
            if (pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) : host.equalsIgnoreCase(pattern)) return true;
        }
        return false;
    }

    public static JsonNode interpolate(JsonNode node, Map<String, String> env) {
        if (node.isObject()) {
            ObjectNode out = Json.obj();
            node.fields().forEachRemaining(f -> out.set(f.getKey(), interpolate(f.getValue(), env)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.arr();
            node.forEach(item -> out.add(interpolate(item, env)));
            return out;
        }
        if (!node.isTextual()) return node;
        Matcher matcher = PLACEHOLDER.matcher(node.asText());
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = env.get(matcher.group(1));
            if (value != null) {
                // Anything supplied from the environment may be a credential.
                Redactor.register(secretPart(value));
            } else {
                value = matcher.group(2) == null ? "" : matcher.group(2);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return TextNode.valueOf(out.toString());
    }

    /** A URL's address is useful in logs. Only an embedded password is hidden. */
    private static String secretPart(String value) {
        Matcher matcher = Pattern.compile("://[^:/@]+:([^@/]+)@").matcher(value);
        if (matcher.find()) return matcher.group(1);
        return value.matches("^[a-zA-Z]+://.*") || value.matches("^[\\w.\\-]+(:\\d+)?(,[\\w.\\-]+(:\\d+)?)*$") ? "" : value;
    }

    public String name() {
        return name;
    }

    public Path contextRoot() {
        return contextRoot;
    }

    public Map<String, JsonNode> targets() {
        return targets;
    }

    public JsonNode target(String targetName) {
        JsonNode config = targets.get(targetName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown target '" + targetName + "'. Known targets: " + targets.keySet());
        }
        return config;
    }

    /** Description and linked context files for a target, ready for a prompt. */
    public String describe(String targetName) {
        JsonNode config = target(targetName);
        StringBuilder out = new StringBuilder("### Target ").append(targetName)
                .append(" (").append(config.path("type").asText()).append(")\n")
                .append(config.path("description").asText()).append('\n');
        for (JsonNode link : config.path("context")) {
            try {
                out.append("\n--- ").append(link.asText()).append(" ---\n")
                        .append(Files.readString(contextRoot.resolve(link.asText()))).append('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out.toString();
    }
}
