package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GitLab issues, repositories and pipelines. Set apiUrl for self-managed GitLab. */
final class GitLabConnector implements Connectors.WorkItems, Connectors.CodeHost, Connectors.Ci {
    private final Connectors.Rest rest;
    private final String project;

    GitLabConnector(JsonNode config) {
        this.rest = new Connectors.Rest(config.path("apiUrl").asText("https://gitlab.com/api/v4"), Map.of("private-token", config.path("token").asText()));
        this.project = "/projects/" + encode(config.path("project").asText());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public WorkItem get(String iid) {
        JsonNode issue = rest.get(project + "/issues/" + iid);
        return WorkItem.of("GL-" + iid, issue.path("title").asText(), issue.path("description").asText(""), issue.path("web_url").asText());
    }

    @Override
    public void comment(String iid, String markdown) {
        rest.post(project + "/issues/" + iid.replaceFirst("^GL-", "") + "/notes", Json.obj().put("body", markdown));
    }

    @Override
    public String readFile(String path, String ref) {
        return rest.send("GET", project + "/repository/files/" + encode(path) + "/raw?ref=" + encode(ref), null).body();
    }

    @Override
    public List<String> listFiles(String prefix, String ref) {
        List<String> files = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode items = rest.get(project + "/repository/tree?recursive=true&per_page=100&page=" + page + "&ref=" + encode(ref));
            if (items.isEmpty()) return files;
            for (JsonNode item : items) {
                if ("blob".equals(item.path("type").asText()) && item.path("path").asText().startsWith(prefix)) files.add(item.path("path").asText());
            }
        }
    }

    @Override
    public void commitToNewBranch(String branch, String base, Map<String, String> files, String message) {
        rest.post(project + "/repository/branches?branch=" + encode(branch) + "&ref=" + encode(base), null);
        ObjectNode body = Json.obj().put("branch", branch).put("commit_message", message);
        ArrayNode actions = body.putArray("actions");
        files.forEach((path, content) -> {
            boolean exists = rest.exists(project + "/repository/files/" + encode(path) + "?ref=" + encode(base));
            actions.addObject().put("action", exists ? "update" : "create").put("file_path", path).put("content", content);
        });
        rest.post(project + "/repository/commits", body);
    }

    @Override
    public String openPullRequest(String branch, String base, String title, String body) {
        return rest.post(project + "/merge_requests", Json.obj().put("source_branch", branch).put("target_branch", base)
                .put("title", title).put("description", body)).path("web_url").asText();
    }

    @Override
    public String trigger(String branch, Map<String, String> parameters) {
        ObjectNode body = Json.obj().put("ref", branch);
        ArrayNode variables = body.putArray("variables");
        parameters.forEach((key, value) -> variables.addObject().put("key", key).put("value", value));
        return rest.post(project + "/pipeline", body).path("id").asText();
    }

    @Override
    public Connectors.CiStatus status(String runId) {
        JsonNode pipeline = rest.get(project + "/pipelines/" + runId);
        String state = pipeline.path("status").asText();
        return new Connectors.CiStatus(state.matches("success|failed|canceled|skipped"), "success".equals(state), state, pipeline.path("web_url").asText());
    }
}
