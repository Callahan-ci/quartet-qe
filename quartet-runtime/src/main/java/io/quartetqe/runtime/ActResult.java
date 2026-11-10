package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;

/** What an act produced. Every value in {@code data} was read from a real tool result. */
public record ActResult(String summary, JsonNode data, Source source) {
    public enum Source { REPLAYED, HANDED_OFF, MISSED, LIVE }

    public JsonNode json(String path) {
        JsonNode value = Json.at(data, path);
        if (value.isMissingNode()) throw new IllegalArgumentException("No field '" + path + "' in result: " + data);
        return value;
    }

    public String get(String path) {
        return json(path).asText();
    }

    public int getInt(String path) {
        return json(path).asInt();
    }

    public double getDouble(String path) {
        return json(path).asDouble();
    }

    public boolean getBool(String path) {
        return json(path).asBoolean();
    }
}
