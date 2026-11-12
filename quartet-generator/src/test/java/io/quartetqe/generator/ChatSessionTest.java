package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chat AI's side of Quartet QE, using a copy of the ShopFlow demo project and no API key. */
class ChatSessionTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();

    @TempDir Path project;
    private ChatSession session;
    private List<Tool> tools;

    @BeforeEach
    void open() throws IOException {
        for (String name : List.of("quartet.yaml", "connectors.yaml", "context", "stories")) copy(REPO.resolve(name), project.resolve(name));
        Files.createDirectories(project.resolve("e2e-tests/src/test/java"));
        Files.createDirectories(project.resolve("e2e-tests/build/test-results/test"));
        SetupChoices.write(project, "here", false); // the setup questions have their own tests below
        session = new ChatSession(new Main(project, null), project.resolve("build/quartet-host"));
        tools = session.tools();
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
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return tool.handler().call(Json.parse(arguments));
    }

    private String story(String name) {
        return "{\"story\":" + Json.str(project.resolve("stories/" + name + ".md").toString()) + "}";
    }

    private String finalTest() throws IOException {
        return Files.readString(REPO.resolve("demo-shopflow/scripted-model/Final.java.txt"));
    }

    private void readTheContext() throws Exception {
        call("context_list", "{}");
        call("context_read", "{\"path\":\"engineering-principles/principles.yaml\"}");
        call("context_read", "{\"path\":\"contracts/openapi/orders-service.yaml\"}");
        call("context_read", "{\"path\":\"telemetry/orders-service.yaml\"}");
    }

    @Test
    void theChatAiGetsTheStoryTheTargetsAndTheSameRulesAnApiModelGets() throws Exception {
        JsonNode start = call("quartet_start", story("SHOP-142"));

        assertThat(start.path("blocked").asBoolean()).isFalse();
        assertThat(start.path("story").path("acceptance_criteria").size()).isEqualTo(7);
        assertThat(start.path("targets").toString()).contains("orders-api").contains("orders-db");
        assertThat(start.path("rules_for_writing_tests").asText()).contains("walking skeleton").contains("agent.act");
    }

    @Test
    void aVagueStoryComesBackWithQuestionsForTheUserBeforeAnyTestIsWritten() throws Exception {
        JsonNode start = call("quartet_start", story("SHOP-200-needs-work"));

        assertThat(start.path("questions").toString()).contains("'quickly' cannot be tested").contains("so that");
        assertThat(start.path("next").asText()).contains("Ask the user to clarify");
    }

    @Test
    void nothingWorksBeforeAStoryIsStarted() {
        assertThatThrownBy(() -> call("write_test", "{\"file_name\":\"A.java\",\"content\":\"x\"}")).hasMessageContaining("quartet_start");
        assertThatThrownBy(() -> call("quartet_finish", "{\"traceability\":[]}")).hasMessageContaining("quartet_start");
    }

    @Test
    void testsMustBeInTheStoriesPackageAndAreCheckedAgainstThePrinciples() throws Exception {
        call("quartet_start", story("SHOP-142"));

        assertThatThrownBy(() -> call("write_test", "{\"file_name\":\"../Evil.java\",\"content\":\"x\"}")).hasMessageContaining("simple .java name");
        assertThatThrownBy(() -> call("write_test", "{\"file_name\":\"A.java\",\"content\":\"class A {}\"}")).hasMessageContaining("package generated.shop142;");

        call("write_test", "{\"file_name\":\"CheckoutReservesStockTest.java\",\"content\":" + Json.str(finalTest()) + "}");
        assertThat(project.resolve("e2e-tests/src/test/java/generated/shop142/CheckoutReservesStockTest.java")).exists();
        JsonNode validation = call("validate_tests", "{}");
        assertThat(validation.path("blocking").asInt()).as(validation.toString()).isZero();
    }

    @Test
    void finishIsRefusedUntilThePrinciplesContractsAndTelemetryWereRead() throws Exception {
        call("quartet_start", story("SHOP-142"));
        call("write_test", "{\"file_name\":\"CheckoutReservesStockTest.java\",\"content\":" + Json.str(finalTest()) + "}");
        call("validate_tests", "{}");

        assertThatThrownBy(() -> call("quartet_finish", "{\"traceability\":[]}"))
                .hasMessageContaining("engineering principles").hasMessageContaining("context_read");
    }

    @Test
    void theIndependentReviewAndTheReviewPageGoThroughTheChatAiWithNoKey() throws Exception {
        call("quartet_start", story("SHOP-142"));
        readTheContext();
        call("write_test", "{\"file_name\":\"CheckoutReservesStockTest.java\",\"content\":" + Json.str(finalTest()) + "}");
        call("validate_tests", "{}");

        call("quartet_finish", "{\"traceability\":[],\"run\":false}");

        // The chat AI sees the review as a step, exactly as an API model would receive it.
        JsonNode step = call("quartet_next_step", "{\"wait_seconds\":20}");
        assertThat(step.path("type").asText()).isEqualTo("step");
        assertThat(step.path("messages").get(0).path("text").asText()).contains("Independent review").contains("CheckoutReservesStockTest");
        assertThat(step.path("tools").toString()).contains("finish").contains("context_read");

        assertThatThrownBy(() -> call("quartet_answer_step", "{\"id\":\"nope\",\"text\":\"x\"}")).hasMessageContaining("not waiting");
        call("quartet_answer_step", "{\"id\":\"" + step.path("id").asText() + "\",\"tool_calls\":[{\"name\":\"finish\",\"arguments\":{\"findings\":[]}}]}");

        JsonNode done = call("quartet_next_step", "{\"wait_seconds\":30}");
        assertThat(done.path("type").asText()).as(done.toString()).isEqualTo("finished");
        assertThat(done.path("result").path("review_page").asText()).isEqualTo("reports/SHOP-142/review.md");
        assertThat(project.resolve("reports/SHOP-142/review.md")).exists();
        assertThat(call("quartet_next_step", "{\"wait_seconds\":1}").path("type").asText()).isEqualTo("idle");
    }

    @Test
    void aBlockingReviewFindingSendsTheChatAiBackToFixTheTestsAndNothingIsRun() throws Exception {
        call("quartet_start", story("SHOP-142"));
        readTheContext();
        call("write_test", "{\"file_name\":\"CheckoutReservesStockTest.java\",\"content\":" + Json.str(finalTest()) + "}");
        call("validate_tests", "{}");
        call("quartet_finish", "{\"traceability\":[],\"run\":false}");

        JsonNode step = call("quartet_next_step", "{\"wait_seconds\":20}");
        String principle = Json.parseYaml(Files.readString(project.resolve("context/engineering-principles/principles.yaml")))
                .path("principles").get(0).path("name").asText();
        call("quartet_answer_step", "{\"id\":\"" + step.path("id").asText() + "\",\"tool_calls\":[{\"name\":\"finish\",\"arguments\":{\"findings\":["
                + "{\"lens\":\"QA\",\"severity\":\"block\",\"principle\":" + Json.str(principle) + ",\"message\":\"No negative case\",\"location\":\"SHOP-142\"}]}}]}");

        JsonNode done = call("quartet_next_step", "{\"wait_seconds\":30}");
        assertThat(done.path("result").path("status").asText()).isEqualTo("needs_changes");
        assertThat(done.path("result").path("review_findings").toString()).contains("No negative case");
        assertThat(project.resolve("reports/SHOP-142/review.md")).doesNotExist();
    }

    @Test
    void theHostFolderIsEmptiedWhenASessionStarts() throws Exception {
        Path stale = project.resolve("build/quartet-host/requests/old.json");
        Files.writeString(stale, "{}");
        new ChatSession(new Main(project, null), project.resolve("build/quartet-host"));
        assertThat(stale).doesNotExist();
    }

    @Test
    void runningTestsIsRefusedWithAClearFixWhenTheSystemUnderTestIsNotUp() throws Exception {
        call("quartet_start", story("SHOP-142"));
        readTheContext();
        call("write_test", "{\"file_name\":\"CheckoutReservesStockTest.java\",\"content\":" + Json.str(finalTest()) + "}");
        call("validate_tests", "{}");

        JsonNode status = call("quartet_check_environment", "{}");
        assertThat(status.path("all_reachable").asBoolean()).isFalse();
        assertThat(status.path("not_reachable").toString()).contains("orders-api");
        assertThat(status.path("how_to_start").asText()).contains("demo-shop.sh");

        assertThatThrownBy(() -> call("quartet_finish", "{\"traceability\":[]}"))
                .hasMessageContaining("not reachable").hasMessageContaining("demo-shop.sh").hasMessageContaining("run=false");
    }

    private static final String SITE_STORY = """
        # EX-1: A visitor can find the install command

        As a visitor I want to see how to install the tool, so that I can try it.

        ## Acceptance criteria

        - AC1: The home page shows a heading.
        - AC2: A page that does not exist tells the visitor it was not found.
        """;

    @Test
    void aWebAddressAndAStoryAreAllItTakesToStartTestingASite() throws Exception {
        JsonNode start = call("quartet_start", Json.obj().put("site", "https://www.example.org").put("story_text", SITE_STORY).toString());

        assertThat(start.path("story").path("id").asText()).isEqualTo("EX-1");
        assertThat(start.path("story").path("acceptance_criteria").size()).isEqualTo(2);
        assertThat(start.path("targets").toString()).contains("site-web").contains("site-http").doesNotContain("orders-api");
        assertThat(start.path("rules_for_writing_tests").asText()).contains("package generated.ex1;");
        assertThat(project.resolve("stories/EX-1.md")).exists();

        String environment = Files.readString(project.resolve("context/environments/example-org.yaml"));
        assertThat(environment).contains("allowHosts: [www.example.org, example.org]").contains("iphone").contains("android-phone");
        assertThat(environment).doesNotContain("localhost");
    }

    @Test
    void theSiteStaysSelectedForLaterStoriesAndOtherSettingsAreNotTouched() throws Exception {
        call("quartet_start", Json.obj().put("site", "example.org").put("story_text", SITE_STORY).toString());
        Files.writeString(project.resolve("context/environments/example-org.yaml"),
                Files.readString(project.resolve("context/environments/example-org.yaml")).replace("name: example.org", "name: edited by the team"));

        JsonNode again = call("quartet_start", Json.obj().put("site", "example.org").put("story_text", SITE_STORY).toString());

        assertThat(again.path("targets").toString()).contains("site-web");
        assertThat(Files.readString(project.resolve("context/environments/example-org.yaml"))).contains("edited by the team");
        assertThat(Files.readString(project.resolve("context/environments/demo.yaml"))).contains("orders-api");
    }

    @Test
    void editsToTheEnvironmentAreSeenOnTheNextStartWithoutRestartingAnything() throws Exception {
        assertThat(call("quartet_start", story("SHOP-142")).path("targets").toString()).doesNotContain("extra-api");

        Path environment = project.resolve("context/environments/demo.yaml");
        Files.writeString(environment, Files.readString(environment)
                + "  extra-api:\n    type: api\n    description: Added while the server was running.\n    baseUrl: \"http://localhost:1\"\n");

        assertThat(call("quartet_start", story("SHOP-142")).path("targets").toString()).contains("extra-api");
    }

    @Test
    void aStoryWithoutAnIdGetsOneAndBadAddressesAreRefusedWithAClearMessage() throws Exception {
        JsonNode start = call("quartet_start", Json.obj().put("site", "https://www.example.org")
                .put("story_text", "As a visitor I want to read the pricing, so that I can budget.\n\n## Acceptance criteria\n\n- AC1: The page shows a price.\n").toString());
        assertThat(start.path("story").path("id").asText()).startsWith("SITE-");

        assertThatThrownBy(() -> call("quartet_start", Json.obj().put("site", "ftp://example.org").put("story_text", SITE_STORY).toString()))
                .hasMessageContaining("not a web address");
        assertThatThrownBy(() -> call("quartet_start", "{}")).hasMessageContaining("story_text");
    }

    private static final String SITE = "{\"site\":\"https://www.example.org\",\"story_text\":" + Json.str(SITE_STORY) + "}";

    @Test
    void aTestsRepoNeedsNoQuestionsBeforeItsFirstStoryBecauseTheRecommendedDefaultsApply() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));

        JsonNode reply = call("quartet_start", SITE);

        assertThat(reply.has("needs_decisions")).isFalse();
        assertThat(reply.path("story").path("id").asText()).isEqualTo("EX-1");
        assertThat(reply.path("targets").toString()).contains("site-web");
    }

    @Test
    void theAnswersAreRememberedSoTheUserIsNeverAskedTwice() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));

        JsonNode saved = call("quartet_setup", "{\"jenkins\":false}");
        assertThat(saved.path("saved").asBoolean()).isTrue();
        assertThat(saved.path("nothing_committed_or_pushed").asBoolean()).isTrue();
        assertThat(Files.readString(project.resolve(SetupChoices.FILE))).contains("testsRepo: here").contains("jenkins: false");

        JsonNode start = call("quartet_start", SITE);
        assertThat(start.has("needs_decisions")).isFalse();
        assertThat(start.path("targets").toString()).contains("site-web");
        assertThat(start.path("jenkins_wanted").asBoolean()).isFalse();
        assertThat(call("quartet_start", SITE).has("needs_decisions")).isFalse();
    }

    @Test
    void wantingJenkinsWritesTheJenkinsfileAndSaysPlainlyWhatIsMissingForTheJob() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));

        JsonNode saved = call("quartet_setup", "{\"jenkins\":true}");

        assertThat(project.resolve("Jenkinsfile")).exists();
        assertThat(saved.path("jenkins_setup").path("job").asText()).isEqualTo("not created yet");
        assertThat(saved.path("jenkins_setup").path("reason").asText()).contains("address of the tests repository");
        assertThat(saved.path("jenkins_setup").path("to_finish").asText()).contains("connectors.yaml").contains("setup-jenkins");
        assertThat(Files.readString(project.resolve(SetupChoices.FILE))).contains("jenkins: true");
    }

    /** Makes the temp project look like the framework repo, with a stand-in for the tests repo script. */
    private void becomeTheFramework() throws IOException {
        Files.createDirectories(project.resolve("demo-shopflow"));
        Files.createDirectories(project.resolve("quartet-core"));
        Files.writeString(project.resolve("new-tests-repo.sh"), """
            #!/usr/bin/env bash
            set -e
            mkdir -p "$1/context" "$1/stories"
            cp %1$s/quartet.yaml %1$s/connectors.yaml "$1/"
            cp -R %1$s/context/. "$1/context/"
            """.formatted(project));
    }

    @Test
    void theFrameworkRepoAsksWhereTheTestsLiveAndCreatesANewTestsRepoWhenToldTo() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();

        JsonNode reply = call("quartet_start", SITE);
        assertThat(reply.path("ask_the_user").toString()).contains("What is your product called").contains("Where should the tests live")
                .contains("\"new\"").contains("\"existing\"").contains("\"here\"").doesNotContain("Jenkins");
        assertThat(reply.path("next").asText()).contains("Do not ask about Jenkins").contains("recommended default");

        Path created = project.resolveSibling(project.getFileName() + "-site-tests");
        JsonNode saved = call("quartet_setup", "{\"tests_repo\":\"new\",\"path\":" + Json.str(created.toString()) + ",\"jenkins\":false}");
        assertThat(saved.path("tests_repo").asText()).isEqualTo(created.toString());
        assertThat(created.resolve("quartet.yaml")).exists();

        JsonNode start = call("quartet_start", SITE);
        assertThat(start.path("tests_repo").asText()).isEqualTo(created.toString());
        assertThat(created.resolve("stories/EX-1.md")).exists();
        assertThat(created.resolve("context/environments/example-org.yaml")).exists();
        assertThat(project.resolve("stories/EX-1.md")).as("nothing leaks into the framework repo").doesNotExist();
        assertThat(Files.readString(project.resolve(SetupChoices.FILE))).contains(created.toString());
        assertThat(Files.readString(created.resolve(SetupChoices.FILE))).contains("testsRepo: here");
    }

    @Test
    void anExistingTestsRepoCanBeChosenAndAWrongFolderIsRefusedClearly() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();
        Path existing = project.resolveSibling(project.getFileName() + "-existing");
        copy(project.resolve("quartet.yaml"), existing.resolve("quartet.yaml"));
        copy(project.resolve("context"), existing.resolve("context"));
        copy(project.resolve("connectors.yaml"), existing.resolve("connectors.yaml"));

        assertThatThrownBy(() -> call("quartet_setup", "{\"tests_repo\":\"existing\",\"path\":\"/no/such/folder\",\"jenkins\":false}"))
                .hasMessageContaining("not a Quartet QE tests repo");
        assertThatThrownBy(() -> call("quartet_setup", "{\"tests_repo\":\"existing\",\"jenkins\":false}"))
                .hasMessageContaining("Give the folder");

        call("quartet_setup", "{\"tests_repo\":\"existing\",\"path\":" + Json.str(existing.toString()) + ",\"jenkins\":false}");
        assertThat(call("quartet_start", SITE).path("tests_repo").asText()).isEqualTo(existing.toString());
    }

    @Test
    void theBundledSampleStoriesStillRunInTheFrameworkWithoutAnyQuestions() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();

        JsonNode start = call("quartet_start", story("SHOP-142"));

        assertThat(start.has("needs_decisions")).isFalse();
        assertThat(start.path("story").path("id").asText()).isEqualTo("SHOP-142");
    }

    @Test
    void doctorTreatsAMissingAiKeyAsInformationBecauseTheChatPathNeedsNone() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(List.of("AI_PROVIDER", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY",
                "QUARTET_HOST_DIR").stream().allMatch(name -> System.getenv(name) == null) && !Files.exists(Path.of(".env")));
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        System.setOut(new java.io.PrintStream(captured, true));
        int exit;
        try {
            exit = new Main(project, null).doctor();
        } finally {
            System.setOut(original);
        }
        assertThat(exit).isZero();
        assertThat(captured.toString()).contains("none configured").contains("chat tool").contains("quartet-qe get-started --connect");
    }

    @Test
    void settingUpInTheFrameworkFolderAsksWhereTheTestsLiveFirstAndNeverOverwritesTheDemo() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();
        String demoManifest = Files.readString(project.resolve("context/manifest.yaml"));

        JsonNode checklist = call("quartet_onboarding", "{}");
        assertThat(checklist.path("needs_decisions").asBoolean()).isTrue();
        assertThat(checklist.path("why").asText()).contains("sample shop").contains("not to the user's product");
        assertThat(checklist.path("ask_the_user").toString()).contains("Where should the tests live");

        String values = "{\"section\":\"product\",\"values\":{\"name\":\"Acme\",\"owner\":\"me\"}}";
        assertThatThrownBy(() -> call("quartet_configure", values)).hasMessageContaining("would change the demo").hasMessageContaining("quartet_setup");
        assertThat(Files.readString(project.resolve("context/manifest.yaml"))).isEqualTo(demoManifest);

        Path created = project.resolveSibling(project.getFileName() + "-acme-tests");
        call("quartet_setup", "{\"tests_repo\":\"new\",\"path\":" + Json.str(created.toString()) + ",\"jenkins\":false}");

        JsonNode afterwards = call("quartet_onboarding", "{}");
        assertThat(afterwards.has("needs_decisions")).isFalse();
        assertThat(afterwards.path("items").size()).isGreaterThan(5);
        call("quartet_configure", values);
        assertThat(Files.readString(created.resolve("context/manifest.yaml"))).contains("Acme");
        assertThat(Files.readString(project.resolve("context/manifest.yaml"))).as("the demo is untouched").isEqualTo(demoManifest);
    }

    @Test
    void theProductNameAloneNamesTheNewRepoAndDescribesTheProductSoNothingIsAskedTwice() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();

        // No tests_repo and no path: the recommended default is a new repo named after the product.
        JsonNode saved = call("quartet_setup", "{\"name\":\"Zed Widgets 7381\"}");

        Path created = project.resolveSibling("zed-widgets-7381-tests");
        assertThat(saved.path("tests_repo").asText()).isEqualTo(created.toString());
        assertThat(saved.path("product").asText()).isEqualTo("Zed Widgets 7381");
        assertThat(saved.path("next").asText()).contains("without waiting to be asked").contains("quartet_onboarding");
        assertThat(Files.readString(created.resolve("context/manifest.yaml"))).contains("Zed Widgets 7381");

        JsonNode checklist = call("quartet_onboarding", "{}");
        assertThat(checklist.path("items").get(0).path("id").asText()).isEqualTo("product");
        assertThat(checklist.path("items").get(0).path("status").asText()).isEqualTo("done");
    }

    @Test
    void jenkinsIsAskedLaterNotUpFrontAndCanBeSwitchedOnWhenTheUserIsReady() throws Exception {
        JsonNode checklist = call("quartet_onboarding", "{}");
        JsonNode jenkins = null;
        for (JsonNode item : checklist.path("items")) if (item.path("id").asText().equals("jenkins")) jenkins = item;
        assertThat(jenkins.path("status").asText()).isEqualTo("optional");
        assertThat(jenkins.path("ask").asText()).contains("Skip this if they do not");
        assertThat(jenkins.path("how").asText()).contains("quartet_setup jenkins=true");

        JsonNode saved = call("quartet_setup", "{\"jenkins\":true}");
        assertThat(saved.path("jenkins_setup").path("job").asText()).isEqualTo("not created yet");
        assertThat(Files.readString(project.resolve(SetupChoices.FILE))).contains("jenkins: true");
        assertThat(call("quartet_setup", "{}").path("jenkins").asBoolean()).as("a later call keeps the earlier choice").isTrue();
    }

    @Test
    void startingAStoryBrieflySuggestsHowToGetMoreFromQuartetButTheSiteQuickStartDoesNot() throws Exception {
        JsonNode start = call("quartet_start", story("SHOP-142"));

        assertThat(start.path("get_more_from_quartet").size()).isEqualTo(2);
        assertThat(start.path("get_more_from_quartet").toString()).contains("pull request");
        assertThat(start.path("advice_note").asText()).contains("mention this briefly").contains("skip");

        JsonNode site = call("quartet_start", SITE);
        assertThat(site.has("get_more_from_quartet")).isFalse();
    }

    @Test
    void aNameThatAlreadyEndsInTestsDoesNotMakeAFolderCalledTestsTests() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();

        JsonNode saved = call("quartet_setup", "{\"name\":\"Delta Widgets Tests\"}");

        assertThat(saved.path("tests_repo").asText()).endsWith("/delta-widgets-tests").doesNotContain("tests-tests");
        assertThat(saved.path("product").asText()).isEqualTo("Delta Widgets Tests");
    }

    @Test
    void aSitesEnvironmentIsRememberedByTheRepoSoAPlainGradleTestFindsItsTargets() throws Exception {
        assertThat(Files.readString(project.resolve("quartet.yaml"))).contains("environment: context/environments/demo.yaml");

        call("quartet_start", SITE);

        assertThat(Files.readString(project.resolve("quartet.yaml")))
                .contains("environment: context/environments/example-org.yaml").doesNotContain("demo.yaml").contains("tests:");
        assertThat(Files.readString(project.resolve("quartet.yaml")).lines().filter(l -> l.startsWith("environment:")).count()).isEqualTo(1);
    }

    @Test
    void theFrameworkFoldersOwnDemoEnvironmentIsNeverRepointedBySiteWork() throws Exception {
        Files.delete(project.resolve(SetupChoices.FILE));
        becomeTheFramework();
        String before = Files.readString(project.resolve("quartet.yaml"));
        Path created = project.resolveSibling("env-books-tests");
        call("quartet_setup", "{\"name\":\"Env Books\",\"path\":" + Json.str(created.toString()) + "}");

        call("quartet_start", SITE);

        assertThat(Files.readString(project.resolve("quartet.yaml"))).as("the demo is untouched").isEqualTo(before);
        assertThat(Files.readString(created.resolve("quartet.yaml"))).contains("environment: context/environments/example-org.yaml");
    }

    private static final String ORDERS_STEPS = """
        package steps;
        import static io.quartetqe.runtime.Act.*;
        import io.quartetqe.runtime.*;
        public final class Orders {
            private final TestAgent agent;
            public Orders(TestAgent agent) { this.agent = agent; }
            public ActResult placeOrder() {
                return agent.act("Place an order for 2 units of SKU-1001 for customer C-42", why("A customer checking out starts the order journey"),
                        on("orders-api"), returns("orderId", "status"));
            }
            public ActResult findOrder(String orderId) {
                return agent.act("Find order {{orderId}} in the orders database", with("orderId", orderId),
                        why("A reserved order must survive beyond the request"), on("orders-db"), returns("status"));
            }
        }
        """;

    private static final String TEST_USING_STEPS = """
        package generated.shop142;
        import static io.quartetqe.runtime.Act.*;
        import static org.assertj.core.api.Assertions.assertThat;
        import io.quartetqe.runtime.*;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.extension.ExtendWith;
        import steps.Orders;
        @Story("SHOP-142")
        @ExtendWith(AgentExtension.class)
        class CheckoutWithStepsTest {
            @Test @WalkingSkeleton @AcceptanceCriteria({"AC1", "AC2"})
            void orderIsReservedAndSaved(TestAgent agent) {
                Orders orders = new Orders(agent);
                ActResult placed = orders.placeOrder();
                assertThat(placed.get("status")).isEqualTo("RESERVED");
                assertThat(orders.findOrder(placed.get("orderId")).get("status")).isEqualTo("RESERVED");
            }
        }
        """;

    @Test
    void reusableStepsAreListedWrittenCheckedReviewedAndReportedAlongsideTheTests() throws Exception {
        assertThat(call("quartet_start", story("SHOP-142")).path("existing_steps").asText()).isEqualTo("[]");
        assertThat(call("list_steps", "{}").path("how_to_use").asText()).contains("No steps classes yet");

        assertThatThrownBy(() -> call("write_steps", "{\"file_name\":\"Orders.java\",\"content\":\"package generated.shop142;\"}")).hasMessageContaining("package steps;");
        call("write_steps", "{\"file_name\":\"Orders.java\",\"content\":" + Json.str(ORDERS_STEPS) + "}");
        assertThat(project.resolve("e2e-tests/src/test/java/steps/Orders.java")).exists();
        assertThat(call("list_steps", "{}").toString()).contains("placeOrder").contains("findOrder");

        readTheContext();
        call("write_test", "{\"file_name\":\"CheckoutWithStepsTest.java\",\"content\":" + Json.str(TEST_USING_STEPS) + "}");
        JsonNode validation = call("validate_tests", "{}");
        assertThat(validation.path("blocking").asInt()).as(validation.toString()).isZero();

        // A second story starts knowing the steps already exist.
        assertThat(call("quartet_start", story("SHOP-142")).path("existing_steps").asText()).contains("steps/Orders.java");
        readTheContext();
        call("write_test", "{\"file_name\":\"CheckoutWithStepsTest.java\",\"content\":" + Json.str(TEST_USING_STEPS) + "}");
        call("validate_tests", "{}");
        call("quartet_finish", "{\"traceability\":[],\"not_covered\":[{\"ac\":\"AC3\",\"reason\":\"later\"},{\"ac\":\"AC4\",\"reason\":\"later\"},{\"ac\":\"AC5\",\"reason\":\"later\"},{\"ac\":\"AC6\",\"reason\":\"later\"},{\"ac\":\"AC7\",\"reason\":\"later\"}],\"run\":false}");

        JsonNode step = call("quartet_next_step", "{\"wait_seconds\":20}");
        assertThat(step.path("messages").get(0).path("text").asText()).contains("Reusable steps the tests call").contains("class Orders");
    }
}
