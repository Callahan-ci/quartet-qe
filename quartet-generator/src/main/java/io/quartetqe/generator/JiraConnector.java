package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.util.Map;

/** Jira Cloud REST v3. */
final class JiraConnector implements Connectors.WorkItems {
    private final Connectors.Rest rest;
    private final String acceptanceField;
    private final String baseUrl;

    JiraConnector(JsonNode config) {
        this.baseUrl = config.path("baseUrl").asText();
        this.rest = new Connectors.Rest(baseUrl, Map.of("authorization",
                Connectors.Rest.basic(config.path("email").asText(), config.path("token").asText()), "accept", "application/json"));
        this.acceptanceField = config.path("acceptanceCriteriaField").asText("");
    }

    @Override
    public WorkItem get(String key) {
        JsonNode fields = rest.get("/rest/api/3/issue/" + key).path("fields");
        StringBuilder description = new StringBuilder(plainText(fields.path("description")));
        if (!acceptanceField.isBlank() && !fields.path(acceptanceField).isMissingNode()) {
            description.append("\n\nAcceptance criteria\n").append(plainText(fields.path(acceptanceField)));
        }
        return WorkItem.of(key, fields.path("summary").asText(), description.toString(), baseUrl + "/browse/" + key);
    }

    /** Jira stores rich text as a document tree. */
    static String plainText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString().trim();
    }

    private static void collect(JsonNode node, StringBuilder out) {
        if ("text".equals(node.path("type").asText())) out.append(node.path("text").asText());
        if ("listItem".equals(node.path("type").asText())) out.append("- ");
        node.path("content").forEach(child -> collect(child, out));
        if (node.path("type").asText().matches("paragraph|heading")) out.append('\n');
    }

    @Override
    public void comment(String key, String markdown) {
        ObjectNode body = Json.obj();
        ObjectNode document = body.putObject("body").put("type", "doc").put("version", 1);
        document.putArray("content").addObject().put("type", "paragraph")
                .putArray("content").addObject().put("type", "text").put("text", markdown);
        rest.post("/rest/api/3/issue/" + key + "/comment", body);
    }
}
