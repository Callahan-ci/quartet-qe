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
}
