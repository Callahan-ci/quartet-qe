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

/** Azure DevOps Boards, Repos and Pipelines. */
final class AzureDevOpsConnector implements Connectors.WorkItems, Connectors.CodeHost, Connectors.Ci {
    private static final String VERSION = "api-version=7.1";
    private static final String ZERO_ID = "0000000000000000000000000000000000000000";

    private final Connectors.Rest rest;
    private final String baseUrl;
    private final String repository;
    private final String pipelineId;

    AzureDevOpsConnector(JsonNode config) {
        this.baseUrl = config.path("orgUrl").asText().replaceAll("/$", "") + "/" + config.path("project").asText();
        this.rest = new Connectors.Rest(baseUrl, Map.of("authorization", Connectors.Rest.basic("", config.path("token").asText())));
        this.repository = config.path("repository").asText();
        this.pipelineId = config.path("pipelineId").asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public WorkItem get(String id) {
        JsonNode fields = rest.get("/_apis/wit/workitems/" + id + "?" + VERSION).path("fields");
        String description = strip(fields.path("System.Description").asText(""));
        String criteria = strip(fields.path("Microsoft.VSTS.Common.AcceptanceCriteria").asText(""));
        return WorkItem.of(id, fields.path("System.Title").asText(), description + "\n\nAcceptance criteria\n" + criteria, baseUrl + "/_workitems/edit/" + id);
    }

    static String strip(String html) {
        return html.replaceAll("(?i)<li[^>]*>", "\n- ").replaceAll("(?i)</(p|div|br)>|<br\\s*/?>", "\n").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    @Override
    public void comment(String id, String markdown) {
        rest.post("/_apis/wit/workItems/" + id + "/comments?api-version=7.1-preview.4", Json.obj().put("text", markdown));
    }

    private String repoPath() {
        return "/_apis/git/repositories/" + repository;
    }

    @Override
    public String readFile(String path, String ref) {
        return rest.get(repoPath() + "/items?path=" + encode(path) + "&includeContent=true&versionDescriptor.version=" + encode(ref) + "&$format=json&" + VERSION).path("content").asText();
    }

    @Override
    public List<String> listFiles(String prefix, String ref) {
        List<String> files = new ArrayList<>();
        for (JsonNode item : rest.get(repoPath() + "/items?recursionLevel=Full&versionDescriptor.version=" + encode(ref) + "&" + VERSION).path("value")) {
            String path = item.path("path").asText().replaceFirst("^/", "");
            if (!item.path("isFolder").asBoolean(false) && path.startsWith(prefix)) files.add(path);
        }
        return files;
    }

    @Override
    public void commitToNewBranch(String branch, String base, Map<String, String> files, String message) {
        String baseId = rest.get(repoPath() + "/refs?filter=heads/" + encode(base) + "&" + VERSION).path("value").path(0).path("objectId").asText();
        ObjectNode push = Json.obj();
        push.putArray("refUpdates").addObject().put("name", "refs/heads/" + branch).put("oldObjectId", ZERO_ID);
        ObjectNode commit = push.putArray("commits").addObject().put("comment", message);
        commit.putArray("parents").add(baseId);
        ArrayNode changes = commit.putArray("changes");
        files.forEach((path, content) -> {
            boolean exists = rest.exists(repoPath() + "/items?path=" + encode(path) + "&versionDescriptor.version=" + encode(base) + "&" + VERSION);
            ObjectNode change = changes.addObject().put("changeType", exists ? "edit" : "add");
            change.putObject("item").put("path", "/" + path);
            change.putObject("newContent").put("content", content).put("contentType", "rawtext");
        });
        rest.post(repoPath() + "/pushes?" + VERSION, push);
    }

    @Override
    public String openPullRequest(String branch, String base, String title, String body) {
        JsonNode pr = rest.post(repoPath() + "/pullrequests?" + VERSION, Json.obj().put("sourceRefName", "refs/heads/" + branch)
                .put("targetRefName", "refs/heads/" + base).put("title", title).put("description", body));
        return baseUrl + "/_git/" + repository + "/pullrequest/" + pr.path("pullRequestId").asText();
    }

    @Override
    public String trigger(String branch, Map<String, String> parameters) {
        ObjectNode body = Json.obj();
        body.putObject("resources").putObject("repositories").putObject("self").put("refName", "refs/heads/" + branch);
        body.set("templateParameters", Json.MAPPER.valueToTree(parameters));
        return rest.post("/_apis/pipelines/" + pipelineId + "/runs?" + VERSION, body).path("id").asText();
    }

    @Override
    public Connectors.CiStatus status(String runId) {
        JsonNode run = rest.get("/_apis/pipelines/" + pipelineId + "/runs/" + runId + "?" + VERSION);
        boolean finished = "completed".equals(run.path("state").asText());
        return new Connectors.CiStatus(finished, "succeeded".equals(run.path("result").asText()), run.path("state").asText(), run.path("_links").path("web").path("href").asText());
    }
}
