package io.quartetqe.core;

import com.fasterxml.jackson.databind.JsonNode;

/** A capability exposed to the model. Schemas are JSON Schema, written as JSON strings. */
public record Tool(Llm.ToolDef def, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        JsonNode call(JsonNode args) throws Exception;
    }

    public static Tool of(String name, String description, String schemaJson, Handler handler) {
        return new Tool(new Llm.ToolDef(name, description, Json.parse(schemaJson)), handler);
    }

    public String name() {
        return def.name();
    }
}
