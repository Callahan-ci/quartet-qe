package io.quartetqe.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Shared JSON/YAML helpers. Paths are dot separated and use numbers for array indexes: {@code items.0.sku}. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private Json() {}

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode parseYaml(String yaml) {
        try {
            return YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getOriginalMessage(), e);
        }
    }

    public static String str(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    /** Reads a dot path. Returns a MissingNode when any segment is absent. */
    public static JsonNode at(JsonNode root, String path) {
        JsonNode current = root;
        if (path == null || path.isEmpty()) return current;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) return MissingNode.getInstance();
            if (current.isArray() && segment.matches("\\d+")) {
                current = current.path(Integer.parseInt(segment));
            } else {
                current = current.path(segment);
            }
        }
        return current;
    }

    /** Writes a value at a dot path, creating intermediate objects. */
    public static void set(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (next == null || !next.isObject()) next = current.putObject(parts[i]);
            current = (ObjectNode) next;
        }
        current.set(parts[parts.length - 1], value);
    }
}
