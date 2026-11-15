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

    private static JsonNode call(List<Tool> tools, String name, String arguments) throws Exception {
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return tool.handler().call(Json.parse(arguments));
    }
}
