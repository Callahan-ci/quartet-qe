package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quartetqe.core.Json;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/** Produces valid events from a JSON Schema. Seeded, so a failing event can be reproduced exactly. */
public final class KafkaGenerator {
    private KafkaGenerator() {}

    public static JsonNode generate(JsonNode schema, JsonNode overrides, long seed) {
        JsonNode event = value(schema, new Random(seed));
        if (event instanceof ObjectNode object && overrides != null) {
            overrides.fields().forEachRemaining(f -> Json.set(object, f.getKey(), f.getValue()));
        }
        return event;
    }

    private static JsonNode value(JsonNode schema, Random random) {
        if (schema.has("const")) return schema.get("const");
        if (schema.has("enum")) return schema.get("enum").get(random.nextInt(schema.get("enum").size()));
        return switch (schema.path("type").asText("object")) {
            case "object" -> object(schema, random);
            case "array" -> array(schema, random);
            case "integer" -> {
                long min = schema.path("minimum").asLong(0);
                long max = schema.path("maximum").asLong(min + 1000);
                yield Json.MAPPER.getNodeFactory().numberNode(min + (long) (random.nextDouble() * (max - min + 1)));
            }
            case "number" -> {
                double min = schema.path("minimum").asDouble(0);
                double max = schema.path("maximum").asDouble(min + 1000);
                yield Json.MAPPER.getNodeFactory().numberNode(Math.round((min + random.nextDouble() * (max - min)) * 100) / 100.0);
            }
            case "boolean" -> Json.MAPPER.getNodeFactory().booleanNode(random.nextBoolean());
            default -> TextNode.valueOf(string(schema, random));
        };
    }

    private static ObjectNode object(JsonNode schema, Random random) {
        ObjectNode out = Json.obj();
        schema.path("properties").fields().forEachRemaining(f -> out.set(f.getKey(), value(f.getValue(), random)));
        return out;
    }

    private static ArrayNode array(JsonNode schema, Random random) {
        ArrayNode out = Json.arr();
        int count = Math.max(1, schema.path("minItems").asInt(1));
        for (int i = 0; i < count; i++) out.add(value(schema.path("items"), random));
        return out;
    }

    private static String string(JsonNode schema, Random random) {
        return switch (schema.path("format").asText("")) {
            case "uuid" -> new UUID(random.nextLong(), random.nextLong()).toString();
            case "date-time" -> Instant.ofEpochSecond(1_700_000_000L + random.nextInt(31_536_000)).toString();
            default -> {
                int length = Math.max(schema.path("minLength").asInt(8), 1);
                StringBuilder text = new StringBuilder("gen");
                while (text.length() < length) text.append((char) ('a' + random.nextInt(26)));
                yield text.toString();
            }
        };
    }
}
