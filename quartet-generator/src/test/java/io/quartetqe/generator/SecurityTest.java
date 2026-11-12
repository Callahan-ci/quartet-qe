package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.HostClient;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each of these closes a hole that was demonstrated against the real thing before it was fixed. */
class SecurityTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();

    // ---------------------------------------------------------------- downloads cannot reach the user's own network

    @Test
    void aDownloadCannotReachLocalPrivateOrCloudMetadataAddressesUnlessTheUserListedTheHost() throws Exception {
        for (String address : List.of("http://127.0.0.1/x", "http://localhost/x", "http://169.254.169.254/latest/meta-data", "http://10.0.0.5/x", "http://192.168.1.1/x",
                "http://172.16.4.4/x", "http://[::1]/x", "http://100.64.1.1/x", "http://0.0.0.0/x", "http://[fc00::1]/x", "http://[fe80::1]/x")) {
            assertThatThrownBy(() -> UrlGuard.requirePublic(URI.create(address), host -> false)).as(address).hasMessageContaining("private or local address");
        }
        UrlGuard.requirePublic(URI.create("https://93.184.216.34/openapi.yaml"), host -> false);
        UrlGuard.requirePublic(URI.create("http://10.0.0.5/spec.yaml"), host -> host.equals("10.0.0.5"));
        assertThatThrownBy(() -> UrlGuard.requirePublic(URI.create("https://user:pw@93.184.216.34/x"), host -> false)).hasMessageContaining("no username or password");
        assertThatThrownBy(() -> UrlGuard.requirePublic(URI.create("ftp://93.184.216.34/x"), host -> false)).hasMessageContaining("http or https");
        assertThat(UrlGuard.isInternal(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    // ---------------------------------------------------------------- credentials are not sent in the clear

    @Test
    void credentialsAreNeverSentOverPlainHttpToAnotherMachineUnlessTheTeamSaysItIsTrusted() {
        assertThatThrownBy(() -> Connectors.requireEncrypted("jenkins", Json.parse("{\"baseUrl\":\"http://jenkins.example.com\"}")))
                .hasMessageContaining("plain http").hasMessageContaining("allowHttp");
        assertThatThrownBy(() -> Connectors.requireEncrypted("azureDevOps", Json.parse("{\"orgUrl\":\"http://dev.azure.example/org\"}"))).hasMessageContaining("plain http");
        Connectors.requireEncrypted("jenkins", Json.parse("{\"baseUrl\":\"https://jenkins.example.com\"}"));
        Connectors.requireEncrypted("jenkins", Json.parse("{\"baseUrl\":\"http://localhost:8080\"}"));
        Connectors.requireEncrypted("jenkins", Json.parse("{\"baseUrl\":\"http://127.0.0.1:8080\"}"));
        Connectors.requireEncrypted("jenkins", Json.parse("{\"baseUrl\":\"http://ci.internal\",\"allowHttp\":true}"));
        Connectors.requireEncrypted("github", Json.parse("{}"));
    }

    // ---------------------------------------------------------------- tests cannot do what only the agent should

    private static final Path CONTEXT = Path.of("../context");

    private static String test(String body) {
        return """
            package generated.t;
            import static io.quartetqe.runtime.Act.*;
            import static org.assertj.core.api.Assertions.assertThat;
            import io.quartetqe.runtime.*;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            @Story("S-1")
            @ExtendWith(AgentExtension.class)
            class T {
                @Test @AcceptanceCriteria("AC1")
                void t(TestAgent agent) throws Exception {
                    ActResult r = agent.act("Place an order for SKU-1001", why("Customers must be able to buy"), on("orders-api"), returns("status"));
                    assertThat(r.get("status")).isEqualTo("RESERVED");
                    %s
                }
            }
            """.formatted(body);
    }

    private static List<String> ruleIds(StaticRules rules, String source) {
        return rules.check(Map.of("T.java", source), null).stream().map(Finding::rule).toList();
    }

    @Test
    void generatedCodeCannotRunProgramsReadFilesOrVariablesOrOpenConnectionsOfItsOwn() {
        StaticRules rules = new StaticRules(Principles.load(CONTEXT), new ContextRepo(CONTEXT), Set.of("orders-api"));

        for (String code : List.of("Runtime.getRuntime().exec(\"id\");", "new ProcessBuilder(\"id\").start();", "String s = System.getenv(\"AI_API_KEY\");",
                "String h = System.getProperty(\"user.home\");", "String f = java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"));",
                "new java.io.FileReader(\"/etc/passwd\");", "java.net.http.HttpClient.newHttpClient();", "new java.net.Socket(\"evil.example\", 80);",
                "Class.forName(\"java.lang.Runtime\");", "System.exit(0);")) {
            assertThat(ruleIds(rules, test(code))).as(code).contains("SEC-NO-SYSTEM-ACCESS");
        }
        assertThat(ruleIds(rules, test("int x = 1;"))).as("a clean test").doesNotContain("SEC-NO-SYSTEM-ACCESS");
        String wordsOnly = test("assertThat(\"Open the Files app and process the Runtime notes\").isNotBlank();");
        assertThat(ruleIds(rules, wordsOnly)).as("ordinary words in text are not code").doesNotContain("SEC-NO-SYSTEM-ACCESS");
    }

    @Test
    void aRepoWithItsOwnOlderPrinciplesFileStillGetsTheNewSecurityRuleAsBlocking(@TempDir Path context) throws Exception {
        Path principles = context.resolve("engineering-principles/principles.yaml");
        Files.createDirectories(principles.getParent());
        Files.writeString(principles, Files.readString(CONTEXT.resolve("engineering-principles/principles.yaml")).lines()
                .filter(line -> !line.contains("SEC-NO-SYSTEM-ACCESS")).reduce((a, b) -> a + "\n" + b).orElseThrow());

        var finding = Principles.load(context).finding("SEC-NO-SYSTEM-ACCESS", "x", "y");

        assertThat(finding).isPresent();
        assertThat(finding.get().blocking()).isTrue();
        assertThatThrownBy(() -> Principles.load(context).finding("MADE-UP-RULE", "x", "y")).hasMessageContaining("not defined");
    }

    // ---------------------------------------------------------------- stories and new repos stay where they should

    @Test
    void aNewTestsRepoCanOnlyHaveAPlainNameAndLiveInsideTheUsersHomeOrATemporaryFolder(@TempDir Path folder) throws Exception {
        for (String bad : List.of("x@{[system(\"id\")]}", "a b", "a;b", "$(id)", "a`id`", "-rf", "..", "a|b", "name'quote")) {
            assertThatThrownBy(() -> ChatSession.requireSafeNewRepoPath(folder.resolve(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        ChatSession.requireSafeNewRepoPath(folder.resolve("books-tests"));
        ChatSession.requireSafeNewRepoPath(folder.resolve("My_Product.tests-2"));

        assertThatThrownBy(() -> ChatSession.requireSafeNewRepoPath(Path.of("/etc/quartet-test-repo"))).hasMessageContaining("inside your home folder");
        assertThatThrownBy(() -> ChatSession.requireSafeNewRepoPath(Path.of("/nonexistent-parent/books-tests"))).hasMessageContaining("does not exist");
        Path hidden = Files.createDirectories(folder.resolve(".ssh"));
        assertThatThrownBy(() -> ChatSession.requireSafeNewRepoPath(hidden.resolve("books-tests"))).hasMessageContaining("hidden folder");
    }

    @Test
    void theRepoScriptRefusesACraftedFolderNameBeforeItRunsAnythingAndNeverEvaluatesANameAsCode(@TempDir Path folder) throws Exception {
        String marker = folder.resolve("MARKER").toString();
        Path target = folder.resolve("x@{[system(\"touch " + marker + "\")]}");

        Process process = new ProcessBuilder("bash", REPO.resolve("new-tests-repo.sh").toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();

        assertThat(exit).isNotZero();
        assertThat(output).contains("only contain letters, digits");
        assertThat(Path.of(marker)).as("no command ran").doesNotExist();
        assertThat(target).as("nothing was created").doesNotExist();
    }

    @Test
    void aStoryFileOutsideTheRepoIsRefusedSoNoFileOnTheMachineCanBeReadThroughAStory(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("quartet.yaml"), Files.readString(REPO.resolve("skeleton/quartet.yaml")));
        Files.createDirectories(project.resolve("context/environments"));
        Files.copy(REPO.resolve("skeleton/context/environments/default.yaml"), project.resolve("context/environments/default.yaml"));
        SetupChoices.write(project, "here", false);
        Path outside = Files.createTempFile("secret", ".md");
        Files.writeString(outside, "# AWS_SECRET_ACCESS_KEY=abc123\n\n## Acceptance criteria\n\n- AC1: x\n");
        List<Tool> tools = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();

        assertThatThrownBy(() -> tools.stream().filter(t -> t.name().equals("quartet_start")).findFirst().orElseThrow().handler()
                .call(Json.obj().put("story", outside.toString())))
                .hasMessageContaining("must be inside this repo").hasMessageNotContaining("abc123");
        Files.delete(outside);
    }

    // ---------------------------------------------------------------- the chat folder is private

    @Test
    void whatTheChatAiIsAskedAndAnswersIsKeptInAFolderOnlyThisUserCanOpen(@TempDir Path folder) throws Exception {
        new HostClient(folder.resolve("host"), 5, 10);

        if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
            for (String name : List.of("host", "host/requests", "host/responses")) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve(name)))).as(name).isEqualTo("rwx------");
            }
        }
    }

    @Test
    void theChatSessionCreatesItsFolderPrivateFromTheStart(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("quartet.yaml"), Files.readString(REPO.resolve("skeleton/quartet.yaml")));
        Files.createDirectories(project.resolve("context/environments"));
        Files.copy(REPO.resolve("skeleton/context/environments/default.yaml"), project.resolve("context/environments/default.yaml"));
        new ChatSession(new Main(project, null), project.resolve("build/quartet-host"));

        if (Files.getFileStore(project).supportsFileAttributeView("posix")) {
            for (String name : List.of("build/quartet-host", "build/quartet-host/requests", "build/quartet-host/responses")) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(project.resolve(name)))).as(name).isEqualTo("rwx------");
            }
        }
    }

    // ---------------------------------------------------------------- the user is told what changed

    @Test
    void aNewAllowedHostAndTheAddressCredentialsGoToAreStatedSoTheUserCanCheckThem(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("quartet.yaml"), Files.readString(REPO.resolve("skeleton/quartet.yaml")));
        Files.createDirectories(project.resolve("context/environments"));
        Files.copy(REPO.resolve("skeleton/context/environments/default.yaml"), project.resolve("context/environments/default.yaml"));
        Files.copy(REPO.resolve("connectors.yaml"), project.resolve("connectors.yaml"));
        SetupChoices.write(project, "here", false);
        List<Tool> tools = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();
        Tool configure = tools.stream().filter(t -> t.name().equals("quartet_configure")).findFirst().orElseThrow();

        JsonNode host = configure.handler().call(Json.parse("{\"section\":\"environment_target\",\"values\":{\"name\":\"api\",\"type\":\"api\",\"baseUrl\":\"https://staging.acme.test\"}}"));
        assertThat(host.path("tell_the_user").asText()).contains("staging.acme.test").contains("check the change");

        JsonNode connector = configure.handler().call(Json.parse("{\"section\":\"connector\",\"values\":{\"kind\":\"jira\",\"settings\":{\"baseUrl\":\"https://acme.atlassian.net\"}}}"));
        assertThat(connector.path("credentials_go_to").asText()).isEqualTo("acme.atlassian.net");
        assertThat(connector.path("tell_the_user").asText()).contains("acme.atlassian.net").contains("right address");
    }
}
