package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jenkins: creates the job for a tests repo, finds branches, and starts and follows builds.
 *
 * The job is a multibranch pipeline that reads the Jenkinsfile in the repo, so every branch and
 * pull request gets its own build with nothing created by hand. Folders are written as "team/app/e2e".
 * Needs the Pipeline: Multibranch, Git and Folders plugins, which most installations already have.
 */
final class JenkinsConnector implements Connectors.Ci {
    private static final String XML = "application/xml";

    private final Connectors.Rest rest;
    private final String baseUrl;
    private final List<String> folders = new ArrayList<>();
    private final String name;
    private final String jobPath;
    private final String credentialsId;
    private final String configuredRepo;
    private final long pollMs;
    private final long scanTimeoutMs;

    JenkinsConnector(JsonNode config) {
        this.baseUrl = config.path("baseUrl").asText().replaceAll("/$", "");
        this.rest = new Connectors.Rest(baseUrl, Map.of("authorization",
                Connectors.Rest.basic(config.path("user").asText(), config.path("token").asText())));
        String[] parts = config.path("job").asText().split("/");
        for (int i = 0; i < parts.length - 1; i++) folders.add(parts[i]);
        this.name = parts[parts.length - 1];
        StringBuilder path = new StringBuilder();
        for (String part : parts) path.append("/job/").append(encode(part));
        this.jobPath = path.toString();
        this.credentialsId = config.path("credentialsId").asText("");
        this.configuredRepo = config.path("repoUrl").asText("");
        this.pollMs = config.path("pollMs").asLong(1000);
        this.scanTimeoutMs = config.path("scanTimeoutMs").asLong(120_000);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Jenkins asks for a crumb on writes unless an API token is used. A server without one answers 404. */
    private Map<String, String> crumb() {
        try {
            JsonNode crumb = rest.get("/crumbIssuer/api/json");
            return Map.of(crumb.path("crumbRequestField").asText("Jenkins-Crumb"), crumb.path("crumb").asText());
        } catch (IllegalStateException noCrumbIssuer) {
            return Map.of();
        }
    }

    @Override
    public String ensureReady(String repoUrl) {
        String repo = configuredRepo.isBlank() ? repoUrl : configuredRepo;
        if (repo == null || repo.isBlank()) {
            throw new IllegalStateException("Jenkins needs the address of the tests repository. Set jenkins.repoUrl in connectors.yaml, or run from a git checkout that has an origin remote.");
        }
        if (repo.matches("(?s).*://[^/@\\s]+@.*")) {
            throw new IllegalStateException("The repository address contains a password or token. Use a Jenkins credential and set jenkins.credentialsId instead.");
        }
        Map<String, String> crumb = crumb();
        StringBuilder parent = new StringBuilder();
        for (String folder : folders) {
            String folderPath = parent + "/job/" + encode(folder);
            if (!rest.exists(folderPath + "/api/json")) {
                rest.sendRaw("POST", parent + "/createItem?name=" + encode(folder), folderXml(), XML, crumb);
            }
            parent.append("/job/").append(encode(folder));
        }
        String xml = multibranchXml(repo);
        if (rest.exists(jobPath + "/api/json")) {
            rest.sendRaw("POST", jobPath + "/config.xml", xml, XML, crumb);
            return "Updated the Jenkins job " + baseUrl + jobPath;
        }
        rest.sendRaw("POST", parent + "/createItem?name=" + encode(name), xml, XML, crumb);
        return "Created the Jenkins job " + baseUrl + jobPath;
    }

    @Override
    public String ping() {
        JsonNode root = rest.get("/api/json");
        return "Jenkins at " + baseUrl + " answered and accepted the credentials (" + root.path("mode").asText("ready") + ")";
    }

    private static String folderXml() {
        return "<?xml version='1.1' encoding='UTF-8'?>\n<com.cloudbees.hudson.plugins.folder.Folder plugin=\"cloudbees-folder\"><properties/><folderViews/></com.cloudbees.hudson.plugins.folder.Folder>";
    }

    private String multibranchXml(String repo) {
        String id = UUID.nameUUIDFromBytes(repo.getBytes(StandardCharsets.UTF_8)).toString();
        String credentials = credentialsId.isBlank() ? "" : "<credentialsId>" + escape(credentialsId) + "</credentialsId>";
        return """
            <?xml version='1.1' encoding='UTF-8'?>
            <org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject plugin="workflow-multibranch">
              <description>End-to-end tests. Created by Quartet QE.</description>
              <properties/>
              <folderViews class="jenkins.branch.MultiBranchProjectViewHolder" plugin="branch-api"/>
              <healthMetrics/>
              <icon class="jenkins.branch.MetadataActionFolderIcon" plugin="branch-api"/>
              <orphanedItemStrategy class="com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy" plugin="cloudbees-folder">
                <pruneDeadBranches>true</pruneDeadBranches>
                <daysToKeep>30</daysToKeep>
                <numToKeep>20</numToKeep>
              </orphanedItemStrategy>
              <triggers>
                <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder">
                  <spec>H/15 * * * *</spec>
                  <interval>900000</interval>
                </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
              </triggers>
              <sources class="jenkins.branch.MultiBranchProject$BranchSourceList" plugin="branch-api">
                <data>
                  <jenkins.branch.BranchSource>
                    <source class="jenkins.plugins.git.GitSCMSource" plugin="git">
                      <id>%s</id>
                      <remote>%s</remote>
                      %s
                      <traits>
                        <jenkins.plugins.git.traits.BranchDiscoveryTrait/>
                      </traits>
                    </source>
                    <strategy class="jenkins.branch.DefaultBranchPropertyStrategy">
                      <properties class="empty-list"/>
                    </strategy>
                  </jenkins.branch.BranchSource>
                </data>
                <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
              </sources>
              <factory class="org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory">
                <owner class="org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject" reference="../.."/>
                <scriptPath>Jenkinsfile</scriptPath>
              </factory>
            </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject>
            """.formatted(id, escape(repo), credentials);
    }

    @Override
    /** Asks Jenkins to look for branches, waits until this one exists, and starts a build on it. */
    public String trigger(String branch, Map<String, String> parameters) {
        Map<String, String> crumb = crumb();
        rest.sendRaw("POST", jobPath + "/build?delay=0", null, null, crumb);
        // A branch name with a slash is one path segment in Jenkins, so it is encoded twice.
        String branchPath = jobPath + "/job/" + encode(encode(branch));
        long deadline = System.currentTimeMillis() + scanTimeoutMs;
        while (!rest.exists(branchPath + "/api/json")) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Jenkins did not find the branch '" + branch + "' within " + scanTimeoutMs / 1000
                        + " seconds. Check that the branch is pushed and that Jenkins can read the repository.");
            }
            sleep(pollMs);
        }
        JsonNode job = rest.get(branchPath + "/api/json");
        int number = job.path("nextBuildNumber").asInt(1);
        if (!job.path("inQueue").asBoolean(false)) rest.sendRaw("POST", branchPath + "/build?delay=0", null, null, crumb);
        return "build:" + branchPath + ":" + number;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Connectors.CiStatus status(String runId) {
        int split = runId.lastIndexOf(':');
        String buildPath = runId.substring(6, split) + "/" + runId.substring(split + 1);
        if (!rest.exists(buildPath + "/api/json")) return new Connectors.CiStatus(false, false, "queued", "");
        return fromBuild(rest.get(buildPath + "/api/json"));
    }

    private static Connectors.CiStatus fromBuild(JsonNode run) {
        boolean finished = !run.path("building").asBoolean(true);
        return new Connectors.CiStatus(finished, "SUCCESS".equals(run.path("result").asText()), finished ? run.path("result").asText() : "building", run.path("url").asText());
    }
}
