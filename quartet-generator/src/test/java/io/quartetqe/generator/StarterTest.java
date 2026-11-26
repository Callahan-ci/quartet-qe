package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A first-time user of a release install: no tests repo exists, and nothing may be written until they say where. */
class StarterTest {
    @TempDir Path temp;
    private Path release;
    private Path workspace;

    @BeforeEach
    void install() throws IOException {
        release = temp.resolve("release");
        // The real skeleton, as a release carries it.
        Path source = Path.of("").toAbsolutePath().getParent().resolve("skeleton");
        try (java.util.stream.Stream<Path> all = Files.walk(source)) {
            for (Path from : all.toList()) {
                Path to = release.resolve("skeleton").resolve(source.relativize(from).toString());
                if (Files.isDirectory(from)) Files.createDirectories(to);
                else Files.copy(from, to);
            }
        }
        workspace = temp.resolve("starter");
        System.setProperty("quartet.home", release.toString());
        System.setProperty("quartet.starter", workspace.toString());
    }

    @AfterEach
    void clear() {
        System.clearProperty("quartet.home");
        System.clearProperty("quartet.starter");
    }

    @Test
    void theWorkspaceIsMadeFromTheReleasesSkeletonAndIsMarked() throws IOException {
        Path made = Starter.prepare();
        assertThat(made).isEqualTo(workspace);
        assertThat(made.resolve("quartet.yaml")).exists();
        assertThat(Starter.isStarter(made)).isTrue();
    }

    @Test
    void preparingAgainKeepsWhatIsThere() throws IOException {
        Starter.prepare();
        Files.writeString(workspace.resolve(SetupChoices.FILE), "testsRepo: here\njenkins: false\n");
        Starter.prepare();
        assertThat(workspace.resolve(SetupChoices.FILE)).exists();
    }

    @Test
    void withoutAReleaseThereIsNothingToStartFromAndTheMessageSaysSo() {
        System.setProperty("quartet.home", temp.resolve("nowhere").toString());
        assertThatThrownBy(Starter::prepare).isInstanceOf(IllegalStateException.class).hasMessageContaining("nothing to start from");
    }

    @Test
    void aFirstStartAsksWhereTheTestsLiveAndWritesNothingToTheWorkspace() throws Exception {
        Path root = Starter.prepare();
        ChatSession session = new ChatSession(new Main(root, null), root.resolve("build/quartet-host"));
        List<Tool> tools = session.tools();
        JsonNode reply = call(tools, "quartet_start", "{\"site\":\"https://example.com\",\"story_text\":\"# Home\\n\\n## Acceptance criteria\\n\\n- AC1: It loads.\"}");
        assertThat(reply.path("needs_decisions").asBoolean()).isTrue();
        // The question must not offer to keep the tests in Quartet's own workspace.
        assertThat(reply.toString()).doesNotContain("\"value\":\"here\"");
        try (java.util.stream.Stream<Path> saved = Files.walk(root.resolve("stories"))) {
            assertThat(saved.filter(Files::isRegularFile).map(f -> { try { return Files.readString(f); } catch (IOException e) { return ""; } }))
                    .noneMatch(text -> text.contains("It loads"));
        }
        assertThat(root.resolve(SetupChoices.FILE)).doesNotExist();
    }

    @Test
    void settingUpInTheWorkspaceIsRefusedUntilTheUserHasChosen() throws Exception {
        Path root = Starter.prepare();
        ChatSession session = new ChatSession(new Main(root, null), root.resolve("build/quartet-host"));
        assertThatThrownBy(() -> call(session.tools(), "quartet_configure", "{\"section\":\"product\",\"values\":{\"name\":\"x\"}}"))
                .hasMessageContaining("No tests repo is set up yet");
    }

    private Path existingTestsRepo(String name) throws IOException {
        Path repo = temp.resolve(name);
        try (java.util.stream.Stream<Path> all = Files.walk(release.resolve("skeleton"))) {
            for (Path from : all.toList()) {
                Path to = repo.resolve(release.resolve("skeleton").relativize(from).toString());
                if (Files.isDirectory(from)) Files.createDirectories(to);
                else Files.copy(from, to);
            }
        }
        return repo;
    }

    private static final String BOOKS = "{\"site\":\"https://books.example.com\",\"story_text\":\"# Browse\\n\\n## Acceptance criteria\\n\\n- AC1: The catalogue loads.\"}";

    @Test
    void aNewSiteIsNotAddedSilentlyToATestsRepoChosenEarlierThatAlreadyHasTests() throws Exception {
        Path root = Starter.prepare();
        Path shop = existingTestsRepo("shop-tests");
        List<Tool> first = new ChatSession(new Main(root, null), root.resolve("build/quartet-host")).tools();
        call(first, "quartet_setup", "{\"name\":\"shop\",\"tests_repo\":\"existing\",\"path\":" + Json.str(shop.toString()) + "}");
        Path written = new Main(shop, null).testSourceRoot().resolve("generated/checkout/CheckoutTest.java");
        Files.createDirectories(written.getParent());
        Files.writeString(written, "class CheckoutTest {}");

        // A later conversation, about a different site.
        List<Tool> later = new ChatSession(new Main(root, null), root.resolve("build/quartet-host")).tools();
        JsonNode asked = call(later, "quartet_start", BOOKS);
        assertThat(asked.path("needs_decisions").asBoolean()).isTrue();
        assertThat(asked.toString()).contains(shop.toString()).contains("books.example.com").contains("\"value\":\"new\"").contains("\"value\":\"same\"");
        assertThat(shop.resolve("context/environments/books-example-com.yaml")).as("nothing is written before the user answers").doesNotExist();

        JsonNode started = call(later, "quartet_start", BOOKS.replace("{", "{\"use_tests_repo\":true,"));
        assertThat(started.path("needs_decisions").asBoolean(false)).isFalse();
        assertThat(started.path("tests_repo").asText()).isEqualTo(shop.toString());
        assertThat(started.path("tests_repo_note").asText()).contains(shop.toString()).contains("tests_repo=new");

        // The site is in that repo now, so the next conversation goes straight there.
        List<Tool> next = new ChatSession(new Main(root, null), root.resolve("build/quartet-host")).tools();
        assertThat(call(next, "quartet_start", BOOKS).path("needs_decisions").asBoolean(false)).isFalse();
    }

    @Test
    void aTestsRepoJustChosenOrStillEmptyIsUsedWithoutAskingAgain() throws Exception {
        Path root = Starter.prepare();
        Path fresh = existingTestsRepo("fresh-tests");
        List<Tool> tools = new ChatSession(new Main(root, null), root.resolve("build/quartet-host")).tools();
        call(tools, "quartet_setup", "{\"name\":\"books\",\"tests_repo\":\"existing\",\"path\":" + Json.str(fresh.toString()) + "}");
        assertThat(call(tools, "quartet_start", BOOKS).path("tests_repo").asText()).isEqualTo(fresh.toString());

        List<Tool> later = new ChatSession(new Main(root, null), root.resolve("build/quartet-host")).tools();
        assertThat(call(later, "quartet_start", BOOKS.replace("books.example.com", "other.example.com")).path("needs_decisions").asBoolean(false))
                .as("a repo with no tests yet has nothing to mix the new site with").isFalse();
    }

    private static JsonNode call(List<Tool> tools, String name, String arguments) throws Exception {
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return tool.handler().call(Json.parse(arguments));
    }
}
