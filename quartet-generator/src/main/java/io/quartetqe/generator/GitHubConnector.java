package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** GitHub Issues, repositories and Actions. Set apiUrl for GitHub Enterprise. */
final class GitHubConnector implements Connectors.WorkItems, Connectors.CodeHost, Connectors.Ci {
    private final Connectors.Rest rest;
    private final String repo;
    private final String workflow;

    GitHubConnector(JsonNode config) {
        this.rest = new Connectors.Rest(config.path("apiUrl").asText("https://api.github.com"), Map.of(
                "authorization", "Bearer " + config.path("token").asText(),
                "accept", "application/vnd.github+json",
                "x-github-api-version", "2022-11-28"));
        this.repo = "/repos/" + config.path("owner").asText() + "/" + config.path("repo").asText();
        this.workflow = config.path("workflow").asText();
    }

    @Override
    public WorkItem get(String number) {
        JsonNode issue = rest.get(repo + "/issues/" + number);
        return WorkItem.of("GH-" + number, issue.path("title").asText(), issue.path("body").asText(""), issue.path("html_url").asText());
    }

    @Override
    public void comment(String number, String markdown) {
        rest.post(repo + "/issues/" + number.replaceFirst("^GH-", "") + "/comments", Json.obj().put("body", markdown));
    }

    @Override
    public String readFile(String path, String ref) {
        JsonNode file = rest.get(repo + "/contents/" + path + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8));
        return new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8);
    }

    @Override
    public List<String> listFiles(String prefix, String ref) {
        List<String> files = new ArrayList<>();
        for (JsonNode item : rest.get(repo + "/git/trees/" + ref + "?recursive=1").path("tree")) {
            if ("blob".equals(item.path("type").asText()) && item.path("path").asText().startsWith(prefix)) files.add(item.path("path").asText());
        }
        return files;
    }

    @Override
    public void commitToNewBranch(String branch, String base, Map<String, String> files, String message) {
        String sha = rest.get(repo + "/git/ref/heads/" + base).path("object").path("sha").asText();
        rest.post(repo + "/git/refs", Json.obj().put("ref", "refs/heads/" + branch).put("sha", sha));
        files.forEach((path, content) -> {
            ObjectNode body = Json.obj().put("message", message).put("branch", branch)
                    .put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
            String existing = existingSha(path, branch);
            if (existing != null) body.put("sha", existing);
            rest.send("PUT", repo + "/contents/" + path, body);
        });
    }

    private String existingSha(String path, String branch) {
        String lookup = repo + "/contents/" + path + "?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8);
        return rest.exists(lookup) ? rest.get(lookup).path("sha").asText() : null;
    }

    @Override
    public String openPullRequest(String branch, String base, String title, String body) {
        return rest.post(repo + "/pulls", Json.obj().put("head", branch).put("base", base).put("title", title).put("body", body)).path("html_url").asText();
    }

    @Override
    public String trigger(String branch, Map<String, String> parameters) {
        ObjectNode body = Json.obj().put("ref", branch);
        body.set("inputs", Json.MAPPER.valueToTree(parameters));
        rest.post(repo + "/actions/workflows/" + workflow + "/dispatches", body);
        return "latest:" + branch;
    }

    @Override
    public Connectors.CiStatus status(String runId) {
        String id = runId;
        if (id.startsWith("latest:")) {
            JsonNode runs = rest.get(repo + "/actions/workflows/" + workflow + "/runs?event=workflow_dispatch&per_page=1&branch="
                    + URLEncoder.encode(id.substring(7), StandardCharsets.UTF_8)).path("workflow_runs");
            if (runs.isEmpty()) return new Connectors.CiStatus(false, false, "queued", "");
            id = runs.path(0).path("id").asText();
        }
        JsonNode run = rest.get(repo + "/actions/runs/" + id);
        return new Connectors.CiStatus("completed".equals(run.path("status").asText()), "success".equals(run.path("conclusion").asText()),
                run.path("status").asText(), run.path("html_url").asText());
    }
}
