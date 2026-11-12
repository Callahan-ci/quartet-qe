package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A fake Jenkins that behaves like the real API where it matters: writes need the CSRF crumb, a job
 * must exist before a build can be started, and a branch only appears after a scan.
 */
class JenkinsSetupTest {
    private static final String JOB = "/job/team/job/app/job/e2e";
    private static final String BRANCH = JOB + "/job/tests%252Fcci-1";

    private record Call(String method, String path, String query, String crumb, String auth, String type, String body) {}

    private HttpServer server;
    private String base;
    private final List<Call> calls = new ArrayList<>();
    private final Set<String> items = new HashSet<>();
    private boolean crumbIssuer = true;
    private boolean scanned;
    private boolean branchExists = true;
    private boolean buildStarted;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String crumb = exchange.getRequestHeaders().getFirst("Jenkins-Crumb");
        calls.add(new Call(method, path, query, crumb, exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"), body));

        int status = 404;
        String reply = "{}";
        boolean write = method.equals("POST");
        if (write && crumbIssuer && !"abc123".equals(crumb)) {
            status = 403;
            reply = "No valid crumb was included in the request";
        } else if (path.equals("/crumbIssuer/api/json")) {
            if (crumbIssuer) {
                status = 200;
                reply = "{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"abc123\"}";
            }
        } else if (method.equals("GET") && path.endsWith("/api/json")) {
            String item = path.substring(0, path.length() - "/api/json".length());
            if (item.equals(BRANCH) && scanned && branchExists) {
                status = 200;
                reply = "{\"nextBuildNumber\":1,\"inQueue\":false,\"lastBuild\":null}";
            } else if (item.equals(BRANCH + "/1") && buildStarted) {
                status = 200;
                reply = "{\"building\":false,\"result\":\"SUCCESS\",\"url\":\"" + base + BRANCH + "/1/\"}";
            } else if (items.contains(item)) {
                status = 200;
            }
        } else if (write && path.endsWith("/createItem")) {
            String parent = path.substring(0, path.length() - "/createItem".length());
            String name = query.replaceFirst("^name=", "");
            items.add(parent + "/job/" + name);
            status = 200;
        } else if (write && path.endsWith("/config.xml")) {
            status = items.contains(path.substring(0, path.length() - "/config.xml".length())) ? 200 : 404;
        } else if (write && path.endsWith("/build")) {
            if (path.equals(JOB) || path.equals(JOB + "/build")) {
                scanned = true;
                status = 201;
            } else if (path.equals(BRANCH + "/build")) {
                buildStarted = true;
                status = 201;
            }
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Connectors.Ci jenkins(String extra) {
        return Connectors.of(Json.parseYaml("jenkins: {baseUrl: \"" + base + "\", job: team/app/e2e, user: bot, token: tok-123456" + extra + "}")).ci("jenkins");
    }

    private List<Call> posts() {
        return calls.stream().filter(c -> c.method().equals("POST")).toList();
    }

    @Test
    void createsTheFoldersAndAMultibranchJobWithTheCrumb() {
        String result = jenkins("").ensureReady("https://github.com/acme/tests.git");

        assertThat(result).startsWith("Created the Jenkins job").endsWith(JOB);
        List<Call> posts = posts();
        assertThat(posts).extracting(c -> c.path() + "?" + c.query()).containsExactly(
                "/createItem?name=team", "/job/team/createItem?name=app", "/job/team/job/app/createItem?name=e2e");
        assertThat(posts).allSatisfy(c -> {
            assertThat(c.crumb()).isEqualTo("abc123");
            assertThat(c.auth()).startsWith("Basic ");
            assertThat(c.type()).isEqualTo("application/xml");
        });
        String xml = posts.get(2).body();
        assertThat(xml).contains("WorkflowMultiBranchProject").contains("<remote>https://github.com/acme/tests.git</remote>")
                .contains("<scriptPath>Jenkinsfile</scriptPath>").contains("BranchDiscoveryTrait").doesNotContain("credentialsId");
    }

    @Test
    void updatesAnExistingJobInsteadOfCreatingADuplicate() {
        Connectors.Ci jenkins = jenkins("");
        jenkins.ensureReady("https://github.com/acme/tests.git");
        calls.clear();

        String second = jenkins.ensureReady("https://github.com/acme/tests.git");

        assertThat(second).startsWith("Updated the Jenkins job");
        assertThat(posts()).extracting(Call::path).containsExactly(JOB + "/config.xml");
    }

    @Test
    void worksWithoutACrumbIssuerAndEscapesEveryValueInTheXml() {
        crumbIssuer = false;
        jenkins(", credentialsId: \"cred<1>\"").ensureReady("https://git.example.com/a?x=1&y=2");

        String xml = posts().get(2).body();
        assertThat(xml).contains("<remote>https://git.example.com/a?x=1&amp;y=2</remote>").contains("<credentialsId>cred&lt;1&gt;</credentialsId>");
        assertThat(posts()).allSatisfy(c -> assertThat(c.crumb()).isNull());
    }

    @Test
    void refusesAnAddressThatCarriesASecret() {
        assertThatThrownBy(() -> jenkins("").ensureReady("https://user:token@github.com/acme/tests.git"))
                .hasMessageContaining("password or token").hasMessageContaining("credentialsId");
        assertThat(posts()).isEmpty();
        assertThat(Main.cleanRemote("https://user:token@github.com/acme/tests.git")).isEqualTo("https://github.com/acme/tests.git");
        assertThat(Main.cleanRemote("git@github.com:acme/tests.git")).isEqualTo("git@github.com:acme/tests.git");
    }

    @Test
    void asksForARepositoryAddressWhenNoneIsKnown() {
        assertThatThrownBy(() -> jenkins("").ensureReady(null)).hasMessageContaining("jenkins.repoUrl");
    }

    @Test
    void scansForBranchesWaitsForTheBranchThenStartsAndFollowsABuild() {
        Connectors.Ci jenkins = jenkins(", pollMs: 10, scanTimeoutMs: 3000");
        jenkins.ensureReady("https://github.com/acme/tests.git");
        calls.clear();

        String run = jenkins.trigger("tests/cci-1", Map.of("STORY", "CCI-1"));

        assertThat(run).isEqualTo("build:" + BRANCH + ":1");
        assertThat(posts()).extracting(Call::path).containsExactly(JOB + "/build", BRANCH + "/build");
        assertThat(posts()).allSatisfy(c -> assertThat(c.crumb()).isEqualTo("abc123"));
        Connectors.CiStatus status = jenkins.status(run);
        assertThat(status.finished()).isTrue();
        assertThat(status.success()).isTrue();
    }

    @Test
    void reportsAQueuedBuildBeforeItHasANumber() {
        Connectors.Ci jenkins = jenkins("");
        assertThat(jenkins.status("build:" + BRANCH + ":1")).extracting(Connectors.CiStatus::state, Connectors.CiStatus::finished).containsExactly("queued", false);
    }

    @Test
    void saysSoWhenTheBranchNeverAppears() {
        branchExists = false;
        Connectors.Ci jenkins = jenkins(", pollMs: 10, scanTimeoutMs: 200");
        assertThatThrownBy(() -> jenkins.trigger("tests/cci-1", Map.of()))
                .hasMessageContaining("did not find the branch 'tests/cci-1'").hasMessageContaining("pushed");
    }

    @Test
    void anAuthenticationFailureIsReportedWithoutLeakingTheToken() {
        Connectors.Ci jenkins = Connectors.of(Json.parseYaml("jenkins: {baseUrl: \"" + base + "\", job: team/app/e2e, user: bot, token: tok-123456}")).ci("jenkins");
        crumbIssuer = true;
        server.removeContext("/");
        server.createContext("/", exchange -> {
            byte[] bytes = "forbidden for tok-123456".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        assertThatThrownBy(() -> jenkins.ensureReady("https://github.com/acme/tests.git")).hasMessageContaining("403").hasMessageNotContaining("tok-123456");
    }
}
