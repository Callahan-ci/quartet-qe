package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.quartetqe.core.DotEnv;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** First-time setup, from a brand new tests repo. No real secret ever appears in anything the AI sees. */
class OnboardingTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    private static final String SECRET = "s3cr3t-value-that-must-never-appear";

    @TempDir Path project;
    private List<Tool> tools;

    @BeforeEach
    void open() throws IOException {
        copy(REPO.resolve("skeleton/quartet.yaml"), project.resolve("quartet.yaml"));
        copy(REPO.resolve("skeleton/context"), project.resolve("context"));
        copy(REPO.resolve("context/engineering-principles"), project.resolve("context/engineering-principles"));
        copy(REPO.resolve("connectors.yaml"), project.resolve("connectors.yaml"));
        SetupChoices.write(project, "here", false);
        tools = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path source : all.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    private JsonNode call(String name, String arguments) throws Exception {
        JsonNode result = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow().handler().call(Json.parse(arguments));
        assertThat(result.toString()).as("a secret must never be echoed").doesNotContain(SECRET);
        return result;
    }

    private JsonNode configure(String section, String values) throws Exception {
        return call("quartet_configure", "{\"section\":\"" + section + "\",\"values\":" + values + "}");
    }

    private static JsonNode item(JsonNode checklist, String id) {
        for (JsonNode item : checklist.path("items")) if (item.path("id").asText().equals(id)) return item;
        throw new AssertionError("No checklist item " + id);
    }

    @Test
    void aBrandNewProjectShowsWhatIsMissingAndWhatToAskFirst() throws Exception {
        JsonNode checklist = call("quartet_onboarding", "{}");

        assertThat(checklist.path("ready_to_write_first_test").asBoolean()).isFalse();
        assertThat(item(checklist, "product").path("status").asText()).isEqualTo("todo");
        assertThat(item(checklist, "product").path("ask").asText()).contains("called");
        assertThat(item(checklist, "environment").path("status").asText()).isEqualTo("todo");
        assertThat(item(checklist, "principles").path("status").asText()).isEqualTo("done");
        assertThat(item(checklist, "ai").path("detail").asText()).contains("none is needed");
        assertThat(checklist.path("integrations").path("jira").path("configured").asBoolean()).isFalse();
        assertThat(checklist.path("integrations").path("jira").path("secrets_to_save").toString()).contains("JIRA_TOKEN");
        assertThat(checklist.path("rules").asText()).contains("Never ask for or accept a token");
    }

    @Test
    void theProductAndAnApiAreEnoughToBeReadyForTheFirstTest() throws Exception {
        configure("product", "{\"name\":\"Acme Store\",\"owner\":\"checkout-team\",\"description\":\"Online shop\"}");
        JsonNode saved = configure("environment_target", "{\"name\":\"store-api\",\"type\":\"api\",\"description\":\"The store API\",\"baseUrl\":\"https://staging.acme.test/api\"}");

        assertThat(saved.path("hosts_now_allowed").toString()).contains("staging.acme.test");
        String environment = Files.readString(project.resolve("context/environments/default.yaml"));
        assertThat(environment).contains("store-api").contains("staging.acme.test").doesNotContain("example-api");
        assertThat(Files.readString(project.resolve("context/manifest.yaml"))).contains("Acme Store").contains("checkout-team");

        JsonNode checklist = call("quartet_onboarding", "{}");
        assertThat(checklist.path("ready_to_write_first_test").asBoolean()).isTrue();
        assertThat(item(checklist, "environment").path("detail").asText()).contains("store-api");
    }

    @Test
    void aDatabasePasswordIsNamedNeverTypedAndTheUserSavesItWithTheScript() throws Exception {
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"orders-db\",\"type\":\"postgres\",\"url\":\"jdbc:postgresql://db.test:5432/orders\",\"password\":\"" + SECRET + "\"}"))
                .hasMessageContaining("Do not put 'password' in a chat").hasMessageContaining("set-secret.sh").hasMessageNotContaining(SECRET);
        assertThat(Files.readString(project.resolve("context/environments/default.yaml"))).doesNotContain("orders-db");

        JsonNode saved = configure("environment_target", "{\"name\":\"orders-db\",\"type\":\"postgres\",\"url\":\"jdbc:postgresql://db.test:5432/orders\",\"user\":\"readonly\",\"password_env\":\"ORDERS_DB_PASSWORD\"}");
        assertThat(saved.path("secrets_to_save").toString()).contains("ORDERS_DB_PASSWORD");
        assertThat(saved.path("next").asText()).contains("./set-secret.sh ORDERS_DB_PASSWORD");
        assertThat(Files.readString(project.resolve("context/environments/default.yaml"))).contains("${ORDERS_DB_PASSWORD}").contains("db.test");

        JsonNode before = call("quartet_secrets_status", "{}");
        assertThat(before.path("secrets").toString()).contains("ORDERS_DB_PASSWORD").contains("\"saved\":false");

        DotEnv.set(project.resolve(".env"), "ORDERS_DB_PASSWORD", SECRET);
        JsonNode after = call("quartet_secrets_status", "{}");
        assertThat(after.path("secrets").toString()).contains("\"saved\":true");
    }

    @Test
    void badTargetsAreRefusedWithAMessageTheAiCanActOn() throws Exception {
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"Bad Name\",\"type\":\"api\",\"baseUrl\":\"https://x.test\"}")).hasMessageContaining("lowercase letters");
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"a\",\"type\":\"soap\",\"baseUrl\":\"https://x.test\"}")).hasMessageContaining("type must be one of");
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"a\",\"type\":\"api\",\"baseUrl\":\"not a url\"}")).hasMessageContaining("not a web address");
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"a\",\"type\":\"api\"}")).hasMessageContaining("'baseUrl' is needed");
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"a\",\"type\":\"postgres\",\"url\":\"jdbc:postgresql://h:1/d\",\"password_env\":\"lower\"}")).hasMessageContaining("not a variable name");
    }

    @Test
    void aConnectorTakesItsSettingsButNeverATokenAndSaysWhichSecretIsStillNeeded() throws Exception {
        assertThatThrownBy(() -> configure("connector", "{\"kind\":\"jira\",\"settings\":{\"token\":\"" + SECRET + "\"}}"))
                .hasMessageContaining("Do not put 'token' in a chat").hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> configure("connector", "{\"kind\":\"jira\",\"settings\":{\"nonsense\":\"x\"}}")).hasMessageContaining("not a setting of jira");
        assertThatThrownBy(() -> configure("connector", "{\"kind\":\"bitbucket\",\"settings\":{\"a\":\"b\"}}")).hasMessageContaining("kind must be one of");

        JsonNode saved = configure("connector", "{\"kind\":\"jira\",\"settings\":{\"baseUrl\":\"https://acme.atlassian.net\",\"email\":\"qa@acme.test\"}}");
        assertThat(saved.path("secrets_to_save").toString()).contains("JIRA_TOKEN");
        String yaml = Files.readString(project.resolve("connectors.yaml"));
        assertThat(yaml).contains("${JIRA_URL:-https://acme.atlassian.net}").contains("${JIRA_EMAIL:-qa@acme.test}").contains("${JIRA_TOKEN:-}");
        assertThat(call("quartet_onboarding", "{}").path("integrations").path("jira").path("configured").asBoolean()).isFalse();

        DotEnv.set(project.resolve(".env"), "JIRA_TOKEN", SECRET);
        JsonNode integrations = call("quartet_onboarding", "{}").path("integrations");
        assertThat(integrations.path("jira").path("configured").asBoolean()).as("a token saved in .env reaches the connector").isTrue();
        assertThat(integrations.path("github").path("configured").asBoolean()).isFalse();
    }

    @Test
    void sourceReposAreAddedAndReplacedByName() throws Exception {
        configure("repo", "{\"name\":\"shop\",\"provider\":\"local\",\"path\":\"../shop/src\",\"purpose\":\"The service\"}");
        configure("repo", "{\"name\":\"web\",\"provider\":\"github\",\"owner\":\"acme\",\"repo\":\"web\",\"ref\":\"main\"}");
        configure("repo", "{\"name\":\"shop\",\"provider\":\"local\",\"path\":\"../shop/new\"}");

        String yaml = Files.readString(project.resolve("context/repos/repos.yaml"));
        assertThat(yaml).contains("../shop/new").doesNotContain("../shop/src").contains("acme");
        assertThatThrownBy(() -> configure("repo", "{\"name\":\"x\",\"provider\":\"svn\"}")).hasMessageContaining("provider must be");
        assertThatThrownBy(() -> configure("repo", "{\"name\":\"x\",\"provider\":\"local\"}")).hasMessageContaining("'path' is needed");
    }

    @Test
    void contextNotesStayInsideTheContextFolderAndMustBeValid() throws Exception {
        configure("context_file", "{\"path\":\"domain/glossary.md\",\"content\":\"# Terms\\nOrder: a purchase.\"}");
        assertThat(Files.readString(project.resolve("context/domain/glossary.md"))).contains("Order: a purchase.");

        assertThatThrownBy(() -> configure("context_file", "{\"path\":\"../escape.md\",\"content\":\"x\"}")).hasMessageContaining("inside the context folder");
        assertThatThrownBy(() -> configure("context_file", "{\"path\":\"/etc/passwd.md\",\"content\":\"x\"}")).hasMessageContaining("inside the context folder");
        assertThatThrownBy(() -> configure("context_file", "{\"path\":\"run.sh\",\"content\":\"x\"}")).hasMessageContaining(".md, .yaml");
        assertThatThrownBy(() -> configure("context_file", "{\"path\":\"a.json\",\"content\":\"{not json\"}")).isInstanceOf(RuntimeException.class);
        assertThat(project.resolveSibling("escape.md")).doesNotExist();
    }

    @Test
    void anOpenApiFileCanBeDownloadedIntoTheContextWithoutLogins() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openapi.yaml", exchange -> {
            byte[] body = "openapi: 3.0.3\ninfo: {title: Store, version: 1}\npaths:\n  /orders: {get: {}}\n  /items: {get: {}}\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/private", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonNode saved = configure("contract_from_url", "{\"url\":\"" + base + "/openapi.yaml\"}");
            assertThat(saved.path("looks_like").asText()).contains("OpenAPI Store").contains("2 paths");
            assertThat(project.resolve("context/contracts/openapi/openapi.yaml")).exists();

            assertThatThrownBy(() -> configure("contract_from_url", "{\"url\":\"" + base + "/private\"}")).hasMessageContaining("401");
            assertThatThrownBy(() -> configure("contract_from_url", "{\"url\":\"https://user:pw@example.com/x.yaml\"}")).hasMessageContaining("no username or password");
            assertThatThrownBy(() -> configure("contract_from_url", "{\"url\":\"file:///etc/passwd\"}")).hasMessageContaining("http or https");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifyingTheEnvironmentNamesWhatDoesNotAnswer() throws Exception {
        try (ServerSocket open = new ServerSocket(0)) {
            int closed;
            try (ServerSocket probe = new ServerSocket(0)) {
                closed = probe.getLocalPort();
            }
            configure("environment_target", "{\"name\":\"up-api\",\"type\":\"api\",\"baseUrl\":\"http://127.0.0.1:" + open.getLocalPort() + "\"}");
            JsonNode good = call("quartet_verify", "{\"what\":\"environment\"}");
            assertThat(good.path("ok").asBoolean()).isTrue();

            configure("environment_target", "{\"name\":\"down-api\",\"type\":\"api\",\"baseUrl\":\"http://127.0.0.1:" + closed + "\"}");
            JsonNode bad = call("quartet_verify", "{\"what\":\"environment\"}");
            assertThat(bad.path("ok").asBoolean()).isFalse();
            assertThat(bad.path("not_reachable").toString()).contains("down-api").doesNotContain("up-api");
            assertThat(bad.path("hint").asText()).contains("VPN").contains("running");
        }
    }

    @Test
    void verifyingJenkinsReportsSuccessOrAHintThatNeverAsksForTheTokenInChat() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] allow = {true};
        server.createContext("/api/json", exchange -> {
            byte[] body = "{\"mode\":\"NORMAL\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(allow[0] ? 200 : 403, allow[0] ? body.length : -1);
            if (allow[0]) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            configure("connector", "{\"kind\":\"jenkins\",\"settings\":{\"baseUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\",\"user\":\"bot\"}}");
            DotEnv.set(project.resolve(".env"), "JENKINS_TOKEN", SECRET);

            JsonNode ok = call("quartet_verify", "{\"what\":\"jenkins\"}");
            assertThat(ok.path("ok").asBoolean()).isTrue();
            assertThat(ok.path("found").asText()).contains("accepted the credentials");

            allow[0] = false;
            JsonNode denied = call("quartet_verify", "{\"what\":\"jenkins\"}");
            assertThat(denied.path("ok").asBoolean()).isFalse();
            assertThat(denied.path("hint").asText()).contains("set-secret.sh").contains("Never ask them to paste it here");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifyingAnIntegrationThatIsNotSetUpSaysHowToSetItUpAndAskingWithoutDetailsIsRefused() throws Exception {
        JsonNode missing = call("quartet_verify", "{\"what\":\"code_host\",\"provider\":\"bitbucket\"}");
        assertThat(missing.path("ok").asBoolean()).isFalse();
        assertThatThrownBy(() -> call("quartet_verify", "{\"what\":\"tracker\"}")).hasMessageContaining("id of a real story");
        assertThatThrownBy(() -> call("quartet_verify", "{\"what\":\"mystery\"}")).hasMessageContaining("what must be");
        assertThat(call("quartet_verify", "{\"what\":\"repos\"}").path("hint").asText()).contains("No repositories");
    }
}
