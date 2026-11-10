package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises each connector against a local server using the shapes documented by that product. */
class ConnectorsTest {
    private record Call(String method, String uri, String auth, JsonNode body) {}

    private HttpServer server;
    private String base;
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, String> replies = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private final Map<String, String> locations = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String uri = exchange.getRequestURI().toString();
            String key = exchange.getRequestMethod() + " " + uri.replaceFirst("\\?.*", "");
            calls.add(new Call(exchange.getRequestMethod(), uri, exchange.getRequestHeaders().getFirst("authorization") + "|" + exchange.getRequestHeaders().getFirst("private-token"),
                    body.isBlank() ? Json.obj() : Json.parse(body)));
            byte[] reply = replies.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            if (locations.containsKey(key)) exchange.getResponseHeaders().set("Location", locations.get(key));
            exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Call call(String method, String uriContains) {
        return calls.stream().filter(c -> c.method().equals(method) && c.uri().contains(uriContains)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + method + " containing " + uriContains + " in " + calls));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private Connectors connectors() {
        return Connectors.of(Json.parseYaml("""
            jira: {baseUrl: "%1$s", email: me@example.com, token: jira-token}
            azureDevOps: {orgUrl: "%1$s/org", project: proj, repository: repo1, pipelineId: 7, token: ado-token}
            github: {apiUrl: "%1$s", owner: acme, repo: shop, workflow: e2e.yml, token: gh-token}
            gitlab: {apiUrl: "%1$s", project: group/shop, token: gl-token}
            jenkins: {baseUrl: "%1$s", job: team/e2e, user: bot, token: jk-token}
            """.formatted(base)));
    }

    @Test
    void jiraReadsRichTextStoriesAndComments() {
        replies.put("GET /rest/api/3/issue/SHOP-1", """
            {"fields":{"summary":"Checkout","description":{"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"So that I can buy"}]},
              {"type":"paragraph","content":[{"type":"text","text":"AC1: Returns 201"}]}]}}}""");
        var jira = connectors().workItems("jira");

        WorkItem story = jira.get("SHOP-1");
        assertThat(story.title()).isEqualTo("Checkout");
        assertThat(story.acceptanceCriteria()).containsEntry("AC1", "Returns 201");
        assertThat(calls.get(0).auth()).startsWith(basic("me@example.com", "jira-token"));

        jira.comment("SHOP-1", "Done");
        assertThat(call("POST", "/comment").body().path("body").path("type").asText()).isEqualTo("doc");
    }

    @Test
    void azureDevOpsCoversBoardsReposAndPipelines() {
        replies.put("GET /org/proj/_apis/wit/workitems/42", """
            {"fields":{"System.Title":"T","System.Description":"<p>So that</p>","Microsoft.VSTS.Common.AcceptanceCriteria":"<ul><li>Returns 201</li></ul>"}}""");
        replies.put("GET /org/proj/_apis/git/repositories/repo1/refs", "{\"value\":[{\"objectId\":\"base-sha\"}]}");
        statuses.put("GET /org/proj/_apis/git/repositories/repo1/items", 404);
        replies.put("POST /org/proj/_apis/git/repositories/repo1/pullrequests", "{\"pullRequestId\":9}");
        replies.put("POST /org/proj/_apis/pipelines/7/runs", "{\"id\":55}");
        replies.put("GET /org/proj/_apis/pipelines/7/runs/55", "{\"state\":\"completed\",\"result\":\"succeeded\",\"_links\":{\"web\":{\"href\":\"u\"}}}");
        Connectors connectors = connectors();

        assertThat(connectors.workItems("ado").get("42").acceptanceCriteria()).containsEntry("AC1", "Returns 201");
        assertThat(calls.get(0).auth()).startsWith(basic("", "ado-token"));

        var code = connectors.codeHost("ado");
        code.commitToNewBranch("b1", "main", Map.of("a/T.java", "class T {}"), "msg");
        JsonNode push = call("POST", "/pushes").body();
        assertThat(push.path("refUpdates").path(0).path("name").asText()).isEqualTo("refs/heads/b1");
        assertThat(push.path("commits").path(0).path("parents").path(0).asText()).isEqualTo("base-sha");
        assertThat(push.path("commits").path(0).path("changes").path(0).path("changeType").asText()).isEqualTo("add");
        assertThat(code.openPullRequest("b1", "main", "t", "b")).endsWith("/pullrequest/9");

        var ci = connectors.ci("ado");
        assertThat(ci.trigger("b1", Map.of("STORY", "S"))).isEqualTo("55");
        assertThat(call("POST", "/pipelines/7/runs").body().path("resources").path("repositories").path("self").path("refName").asText()).isEqualTo("refs/heads/b1");
        assertThat(ci.status("55").success()).isTrue();
    }

    @Test
    void gitHubCoversIssuesContentAndActions() {
        replies.put("GET /repos/acme/shop/issues/5", "{\"title\":\"T\",\"body\":\"AC1: Returns 201\",\"html_url\":\"u\"}");
        replies.put("GET /repos/acme/shop/contents/README.md", "{\"content\":\"" + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)) + "\"}");
        replies.put("GET /repos/acme/shop/git/ref/heads/main", "{\"object\":{\"sha\":\"abc\"}}");
        statuses.put("GET /repos/acme/shop/contents/a/T.java", 404);
        replies.put("POST /repos/acme/shop/pulls", "{\"html_url\":\"https://pr\"}");
        statuses.put("POST /repos/acme/shop/actions/workflows/e2e.yml/dispatches", 204);
        replies.put("GET /repos/acme/shop/actions/workflows/e2e.yml/runs", "{\"workflow_runs\":[{\"id\":99}]}");
        replies.put("GET /repos/acme/shop/actions/runs/99", "{\"status\":\"completed\",\"conclusion\":\"success\",\"html_url\":\"u\"}");
        Connectors connectors = connectors();

        assertThat(connectors.workItems("github").get("5").id()).isEqualTo("GH-5");
        assertThat(calls.get(0).auth()).startsWith("Bearer gh-token");

        var code = connectors.codeHost("github");
        assertThat(code.readFile("README.md", "main")).isEqualTo("hello");
        code.commitToNewBranch("b1", "main", Map.of("a/T.java", "class T {}"), "msg");
        assertThat(call("POST", "/git/refs").body().path("sha").asText()).isEqualTo("abc");
        JsonNode put = call("PUT", "/contents/a/T.java").body();
        assertThat(new String(Base64.getDecoder().decode(put.path("content").asText()), StandardCharsets.UTF_8)).isEqualTo("class T {}");
        assertThat(put.has("sha")).isFalse();
        assertThat(code.openPullRequest("b1", "main", "t", "b")).isEqualTo("https://pr");

        var ci = connectors.ci("github");
        String run = ci.trigger("b1", Map.of("STORY", "S"));
        assertThat(call("POST", "/dispatches").body().path("ref").asText()).isEqualTo("b1");
        assertThat(ci.status(run).success()).isTrue();
    }

    @Test
    void gitLabEncodesProjectAndCoversIssuesCommitsAndPipelines() {
        String project = "/projects/group%2Fshop";
        replies.put("GET " + project + "/issues/3", "{\"title\":\"T\",\"description\":\"AC1: Returns 201\",\"web_url\":\"u\"}");
        replies.put("POST " + project + "/merge_requests", "{\"web_url\":\"https://mr\"}");
        replies.put("POST " + project + "/pipeline", "{\"id\":77}");
        replies.put("GET " + project + "/pipelines/77", "{\"status\":\"failed\",\"web_url\":\"u\"}");
        statuses.put("GET " + project + "/repository/files/a%2FT.java", 404);
        Connectors connectors = connectors();

        assertThat(connectors.workItems("gitlab").get("3").id()).isEqualTo("GL-3");
        assertThat(calls.get(0).auth()).endsWith("gl-token");

        var code = connectors.codeHost("gitlab");
        code.commitToNewBranch("b1", "main", Map.of("a/T.java", "class T {}"), "msg");
        assertThat(call("POST", "/repository/branches?branch=b1&ref=main")).isNotNull();
        assertThat(call("POST", "/repository/commits").body().path("actions").path(0).path("action").asText()).isEqualTo("create");
        assertThat(code.openPullRequest("b1", "main", "t", "b")).isEqualTo("https://mr");

        var ci = connectors.ci("gitlab");
        assertThat(ci.trigger("b1", Map.of("STORY", "S"))).isEqualTo("77");
        var status = ci.status("77");
        assertThat(status.finished()).isTrue();
        assertThat(status.success()).isFalse();
    }

    @Test
    void jenkinsFollowsTheQueueToTheBuild() {
        statuses.put("POST /job/team/job/e2e/buildWithParameters", 201);
        locations.put("POST /job/team/job/e2e/buildWithParameters", base + "/queue/item/12/");
        replies.put("GET /queue/item/12/api/json", "{\"executable\":{\"number\":31}}");
        replies.put("GET /job/team/job/e2e/31/api/json", "{\"building\":false,\"result\":\"SUCCESS\",\"url\":\"u\"}");
        var jenkins = connectors().ci("jenkins");

        String run = jenkins.trigger("b1", Map.of("STORY", "SHOP 1"));
        assertThat(calls.get(0).uri()).contains("BRANCH=b1").contains("STORY=SHOP+1");
        assertThat(calls.get(0).auth()).startsWith(basic("bot", "jk-token"));
        assertThat(jenkins.status(run).success()).isTrue();
    }

    @Test
    void failuresDoNotLeakCredentials() {
        statuses.put("GET /rest/api/3/issue/X", 401);
        replies.put("GET /rest/api/3/issue/X", "{\"message\":\"bad token " + basic("me@example.com", "jira-token").substring(6) + "\"}");
        assertThatThrownBy(() -> connectors().workItems("jira").get("X"))
                .hasMessageContaining("401").hasMessageNotContaining(basic("me@example.com", "jira-token").substring(6));
    }
}
