package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.HttpJson;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** Jenkins parameterised jobs. Folders are written as "team/app/e2e". */
final class JenkinsConnector implements Connectors.Ci {
    private final Connectors.Rest rest;
    private final String jobPath;

    JenkinsConnector(JsonNode config) {
        this.rest = new Connectors.Rest(config.path("baseUrl").asText(), Map.of("authorization",
                Connectors.Rest.basic(config.path("user").asText(), config.path("token").asText())));
        this.jobPath = "/job/" + config.path("job").asText().replace("/", "/job/");
    }

    @Override
    public String trigger(String branch, Map<String, String> parameters) {
        String query = parameters.entrySet().stream().map(e -> e.getKey() + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        HttpJson.Resp response = rest.send("POST", jobPath + "/buildWithParameters?BRANCH=" + encode(branch) + (query.isEmpty() ? "" : "&" + query), null);
        String location = response.location().orElseThrow(() -> new IllegalStateException("Jenkins did not return a queue location."));
        return "queue:" + URI.create(location).getPath().replaceFirst("/$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public Connectors.CiStatus status(String runId) {
        String build = runId;
        if (build.startsWith("queue:")) {
            JsonNode queue = rest.get(build.substring(6) + "/api/json");
            if (queue.path("cancelled").asBoolean(false)) return new Connectors.CiStatus(true, false, "cancelled", "");
            if (queue.path("executable").isMissingNode()) return new Connectors.CiStatus(false, false, "queued", "");
            build = queue.path("executable").path("number").asText();
        }
        JsonNode run = rest.get(jobPath + "/" + build + "/api/json");
        boolean finished = !run.path("building").asBoolean(true);
        return new Connectors.CiStatus(finished, "SUCCESS".equals(run.path("result").asText()), finished ? run.path("result").asText() : "building", run.path("url").asText());
    }
}
