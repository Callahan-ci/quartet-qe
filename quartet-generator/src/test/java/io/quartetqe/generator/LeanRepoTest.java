package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A tests repo holds only what is its own. Quartet provides the rest, and the README is about the repo. */
class LeanRepoTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();

    @TempDir Path project;
    private Main main;
    private List<Tool> tools;

    @BeforeEach
    void open() throws IOException {
        // Exactly what a new repo starts with: settings, one placeholder environment, a README template. Nothing else.
        copy(REPO.resolve("skeleton/quartet.yaml"), project.resolve("quartet.yaml"));
        copy(REPO.resolve("skeleton/context"), project.resolve("context"));
        copy(REPO.resolve("skeleton/README.md"), project.resolve("README.md"));
        SetupChoices.write(project, "here", false);
        main = new Main(project, null);
        tools = new ChatSession(main, project.resolve("build/quartet-host")).tools();
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
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow().handler().call(Json.parse(arguments));
    }

    private JsonNode configure(String section, String values) throws Exception {
        return call("quartet_configure", "{\"section\":\"" + section + "\",\"values\":" + values + "}");
    }

    // ---------------------------------------------------------------- built-in defaults

    @Test
    void aNewRepoHasNoPrinciplesOrPlaybooksOfItsOwnYetTheyAllApply() throws Exception {
        assertThat(project.resolve("context/engineering-principles")).doesNotExist();
        assertThat(project.resolve("context/playbooks")).doesNotExist();

        ContextRepo context = new ContextRepo(project.resolve("context"));
        assertThat(context.list()).contains("engineering-principles/principles.yaml", "engineering-principles/quality-engineering.md", "playbooks/api.md", "playbooks/web.md");
        assertThat(context.isDefault("engineering-principles/principles.yaml")).isTrue();
        assertThat(context.read("playbooks/web.md")).isNotBlank();
        assertThat(context.search("walking skeleton", 3)).isNotEmpty();
        assertThat(main.principles.names()).hasSize(12);
    }

    @Test
    void aFileInTheRepoReplacesTheBuiltInOneAtTheSamePath() throws Exception {
        String own = Defaults.get("engineering-principles/principles.yaml").replace("id: SIMPLE-FOCUS, severity: block", "id: SIMPLE-FOCUS, severity: off");
        Path file = project.resolve("context/engineering-principles/principles.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, own);

        ContextRepo context = new ContextRepo(project.resolve("context"));
        assertThat(context.isDefault("engineering-principles/principles.yaml")).isFalse();
        assertThat(context.read("engineering-principles/principles.yaml")).contains("severity: off");
        assertThat(context.isDefault("playbooks/api.md")).as("the rest stays built in").isTrue();
        assertThat(Principles.load(project.resolve("context")).finding("SIMPLE-FOCUS", "x", "y")).isEmpty();
    }

    @Test
    void aDefaultIsCopiedIntoTheRepoOnlyWhenTheTeamWantsToChangeIt() throws Exception {
        JsonNode saved = configure("customize_default", "{\"path\":\"engineering-principles/principles.yaml\"}");

        assertThat(saved.path("saved").asText()).isEqualTo("context/engineering-principles/principles.yaml");
        assertThat(Files.readString(project.resolve("context/engineering-principles/principles.yaml"))).contains("SIMPLE-FOCUS");
        assertThatThrownBy(() -> configure("customize_default", "{\"path\":\"engineering-principles/principles.yaml\"}")).hasMessageContaining("already in this repo");
        assertThatThrownBy(() -> configure("customize_default", "{\"path\":\"nope.md\"}")).hasMessageContaining("No built-in file");
    }

    @Test
    void theDefaultsCommandListsThemAndCopiesOneWithoutOverwriting() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true));
        try {
            main.defaults(Map.of());
            main.defaults(Map.of("copy", "playbooks/api.md"));
        } finally {
            System.setOut(original);
        }
        assertThat(captured.toString()).contains("engineering-principles/principles.yaml").contains("Copied to context/playbooks/api.md");
        assertThat(project.resolve("context/playbooks/api.md")).exists();
        assertThatThrownBy(() -> main.defaults(Map.of("copy", "playbooks/api.md"))).hasMessageContaining("already exists");
        assertThatThrownBy(() -> main.defaults(Map.of("copy", "../outside.md"))).hasMessageContaining("No built-in file");
    }

    // ---------------------------------------------------------------- templates only when asked

    @Test
    void connectorsAndTheJenkinsfileAppearOnlyWhenTheUserAsksForThem() throws Exception {
        assertThat(project.resolve("connectors.yaml")).doesNotExist();
        assertThat(project.resolve("Jenkinsfile")).doesNotExist();

        JsonNode before = call("quartet_onboarding", "{}");
        assertThat(before.path("integrations").path("jira").path("secrets_to_save").toString()).as("secret names come from the template").contains("JIRA_TOKEN");
        assertThat(call("quartet_secrets_status", "{}").path("secrets").toString()).contains("GITHUB_TOKEN");
        assertThat(project.resolve("connectors.yaml")).as("looking does not create it").doesNotExist();

        configure("connector", "{\"kind\":\"jira\",\"settings\":{\"baseUrl\":\"https://acme.atlassian.net\"}}");
        assertThat(Files.readString(project.resolve("connectors.yaml"))).contains("${JIRA_URL:-https://acme.atlassian.net}");

        call("quartet_setup", "{\"jenkins\":true}");
        assertThat(Files.readString(project.resolve("Jenkinsfile"))).contains("pipeline");
    }

    // ---------------------------------------------------------------- README

    @Test
    void theReadmeIsAboutThisRepoItsProductItsStoriesItsResultsAndHowToRunIt() throws Exception {
        configure("product", "{\"name\":\"Acme Store\",\"owner\":\"checkout-team\",\"description\":\"The Acme online shop.\"}");
        configure("environment_target", "{\"name\":\"store-web\",\"type\":\"screen\",\"description\":\"The shop website\",\"baseUrl\":\"https://staging.acme.test\"}");
        Files.createDirectories(project.resolve("stories"));
        Files.writeString(project.resolve("stories/ACME-1.md"), "# ACME-1: A shopper can find a product\n\nAs a shopper I want to search, so that I can buy.\n\n## Acceptance criteria\n\n- AC1: Search finds it.\n- AC2: Empty search says so.\n");
        Path tests = project.resolve("src/test/java/generated/acme1");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("SearchTest.java"), "package generated.acme1;\nimport org.junit.jupiter.api.Test;\nclass SearchTest {\n @Test void a() {}\n @Test void b() {}\n}\n");
        Files.createDirectories(project.resolve("reports/ACME-1"));
        Files.writeString(project.resolve("reports/ACME-1/review.json"), "{\"story\":\"ACME-1\",\"approved\":true,\"passed\":2,\"failed\":0,\"skipped\":0}");

        Readme.update(new Main(project, null), false);
        String readme = Files.readString(project.resolve("README.md"));

        assertThat(readme).startsWith("# Acme Store end-to-end tests").contains("The Acme online shop.").contains("Owner: checkout-team");
        assertThat(readme).contains("| ACME-1: A shopper can find a product | 2 | 2 | Ready to merge: 2 passed, 0 failed |");
        assertThat(readme).contains("| `store-web` | The shop website | `https://staging.acme.test` |");
        assertThat(readme).contains("./gradlew test -Pwatch").contains("Write tests for stories/NEW-STORY.md");
        assertThat(readme).contains("| `stories/` |").contains("| `quartet.yaml` |").doesNotContain("| `Jenkinsfile` |");
        assertThat(readme).as("about the repo, not a copy of Quartet's own README").doesNotContain("Give it context").doesNotContain("How it works");
        assertThat(readme.lines().count()).isLessThan(45);
    }

    @Test
    void aFreshRepoReadmeSaysWhatIsMissingInsteadOfBeingEmpty() throws Exception {
        Readme.update(main, false);
        String readme = Files.readString(project.resolve("README.md"));

        assertThat(readme).contains("No stories yet").contains("./gradlew test");
        assertThat(readme).doesNotContain("## Systems tested");
    }

    @Test
    void whatAPersonWritesOutsideTheMarkersSurvivesEveryRebuild() throws Exception {
        Readme.update(main, false);
        Path file = project.resolve("README.md");
        Files.writeString(file, Files.readString(file).replace("<!-- quartet:end -->", "<!-- quartet:end -->\n\n## Our team notes\n\nSee the wiki.\n"));
        Files.writeString(file, "Intro we wrote.\n\n" + Files.readString(file));

        configure("product", "{\"name\":\"Zed\",\"owner\":\"me\"}");
        Readme.update(new Main(project, null), false);

        String readme = Files.readString(file);
        assertThat(readme).contains("Intro we wrote.").contains("## Our team notes").contains("See the wiki.").contains("Owner: me");
        assertThat(readme).as("one block, rebuilt in place").containsOnlyOnce(Readme.END);
    }

    @Test
    void aReadmeWithNoMarkersIsAPersonsOwnAndIsLeftAloneUnlessForced() throws Exception {
        Path file = project.resolve("README.md");
        Files.writeString(file, "# Our hand-written README\n\nDo not touch.\n");

        assertThat(Readme.update(main, false)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("# Our hand-written README\n\nDo not touch.\n");

        assertThat(Readme.update(main, true)).isTrue();
        assertThat(Files.readString(file)).contains(Readme.START).doesNotContain("Do not touch.");
    }

    @Test
    void theReadmeNeverShowsASecretFromAnAddressAndAPasswordInAddressIsRefusedInChat() throws Exception {
        assertThatThrownBy(() -> configure("environment_target", "{\"name\":\"orders-db\",\"type\":\"postgres\",\"url\":\"jdbc:postgresql://admin:hunter2@db.test:5432/orders\"}"))
                .hasMessageContaining("password inside the address").hasMessageNotContaining("hunter2");

        // A hand-edited file may still hold one. The README shows the address without it.
        Files.writeString(project.resolve("context/environments/default.yaml"), """
            name: default
            guard: {allowHosts: [db.test, shop.test]}
            targets:
              old-api: {type: api, description: Old, baseUrl: "http://admin:hunter2@db.test:8080"}
              shop: {type: api, description: Shop, baseUrl: "${SHOP_URL:-https://shop.test}"}
            """);
        Readme.update(new Main(project, null), false);

        String readme = Files.readString(project.resolve("README.md"));
        assertThat(readme).contains("`http://db.test:8080`").contains("`https://shop.test`").doesNotContain("hunter2").doesNotContain("admin:");
    }

    @Test
    void aRuleSetToOffReallyIsOffEvenThoughYamlReadsOffAsFalse() throws Exception {
        Path own = project.resolve("context/engineering-principles/principles.yaml");
        Files.createDirectories(own.getParent());
        Files.writeString(own, Defaults.get("engineering-principles/principles.yaml").replace("id: SIMPLE-FOCUS, severity: block", "id: SIMPLE-FOCUS, severity: off"));

        assertThat(Principles.load(project.resolve("context")).finding("SIMPLE-FOCUS", "x", "y")).as("off").isEmpty();
        assertThat(Principles.load(project.resolve("context")).finding("SIMPLE-ONE-OUTCOME", "x", "y")).as("others unchanged").isPresent();
    }

    // ---------------------------------------------------------------- launcher

    @Test
    void theHelperCommandsRunFromTheInstalledToolSoTheRepoCarriesNoScripts(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("scripts"));
        Files.writeString(home.resolve("bin/quartet-qe"), "#!/usr/bin/env bash\necho \"cli $*\"\n");
        for (String script : List.of("get-started", "set-secret", "set-ai-key")) {
            Files.writeString(home.resolve("scripts/" + script + ".sh"), "#!/usr/bin/env bash\necho \"" + script + " in $QUARTET_REPO with $*\"\n");
            home.resolve("scripts/" + script + ".sh").toFile().setExecutable(true);
        }
        home.resolve("bin/quartet-qe").toFile().setExecutable(true);
        Path launcher = project.resolve("quartet-qe");
        Files.copy(REPO.resolve("skeleton/quartet-qe"), launcher);
        launcher.toFile().setExecutable(true);

        assertThat(run(launcher, home, "secret", "JIRA_TOKEN")).startsWith("set-secret in ").contains(project.getFileName().toString()).endsWith(" with JIRA_TOKEN");
        assertThat(run(launcher, home, "get-started", "--check")).startsWith("get-started in").endsWith("with --check");
        assertThat(run(launcher, home, "ai-key")).startsWith("set-ai-key in");
        assertThat(run(launcher, home, "doctor")).isEqualTo("cli doctor");
        assertThat(run(launcher, Path.of("/nonexistent"), "doctor")).contains("not installed on this machine");
    }

    private static String run(Path launcher, Path home, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("bash", launcher.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(launcher.getParent().toFile()).redirectErrorStream(true);
        builder.environment().put("QUARTET_HOME", home.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        process.waitFor();
        return output;
    }

    @Test
    void aReadmeForANativeAppSaysWhichDeviceAndAppAreNeeded() throws Exception {
        configure("environment_target", "{\"name\":\"calculator\",\"type\":\"screen\",\"description\":\"The calculator app\",\"app\":{\"android\":\"org.example.calc\"}}");
        Readme.update(new Main(project, null), false);
        String readme = Files.readString(project.resolve("README.md"));

        assertThat(readme).contains("### Before you run: a device with the app").contains("`adb devices`").contains("ANDROID_HOME")
                .contains("`org.example.calc` (android)").contains("start the app from cold".replace("start the", "starts the"));
        assertThat(readme).as("the run commands still come first").containsSubsequence("## Run the tests", "./gradlew test", "### Before you run");

        Files.writeString(project.resolve("context/environments/default.yaml"), "name: default\nguard: {allowHosts: [shop.test]}\ntargets:\n  shop: {type: api, description: Shop, baseUrl: \"https://shop.test\"}\n");
        Readme.update(new Main(project, null), false);
        assertThat(Files.readString(project.resolve("README.md"))).as("a web-only repo says nothing about devices").doesNotContain("Before you run");
    }
}
