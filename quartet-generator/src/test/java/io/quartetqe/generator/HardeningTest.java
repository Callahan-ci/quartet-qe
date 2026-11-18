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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Things an AI that has been steered by hostile text must not be able to do through Quartet's tools: read or write outside
 * the tests repo, including through symbolic links, or read files that hold credentials. Each was tried against the
 * code before the fix.
 */
class HardeningTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    @TempDir Path temp;
    private Path project;
    private Path outside;
    private List<Tool> tools;
    private String realHome;

    @org.junit.jupiter.api.AfterEach
    void restore() {
        System.setProperty("user.home", realHome);
    }

    @BeforeEach
    void open() throws IOException {
        // A local repo must be inside the home folder, so the test's folder stands in for it.
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        project = temp.resolve("tests-repo");
        Files.createDirectories(project);
        for (String name : List.of("quartet.yaml", "context", "stories", "src")) copy(REPO.resolve("skeleton").resolve(name), project.resolve(name));
        outside = temp.resolve("somewhere-else");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("private.txt"), "TOP-SECRET-VALUE");
        Files.createDirectories(outside.resolve(".ssh"));
        Files.writeString(outside.resolve(".ssh/id_rsa"), "-----BEGIN PRIVATE KEY-----");
    }

    private void start() throws Exception {
        tools = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();
    }

    private JsonNode call(String name, String arguments) throws Exception {
        Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        return tool.handler().call(Json.parse(arguments));
    }

    private void registerLocalRepo(String path) throws Exception {
        call("quartet_configure", "{\"section\":\"repo\",\"values\":{\"name\":\"src\",\"provider\":\"local\",\"path\":" + Json.str(path) + "}}");
    }

    @Test
    void aLocalRepoReferenceMustBeASourceCheckoutNotAnyFolderOnTheComputer() throws Exception {
        start();
        assertThatThrownBy(() -> registerLocalRepo(outside.toString())).hasMessageContaining("git");
        assertThatThrownBy(() -> registerLocalRepo("/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registerLocalRepo(System.getProperty("user.home"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registerLocalRepo(outside.resolve(".ssh").toString())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRealSourceCheckoutCanBeReferencedButItsSecretsAndLinksAreNotReadable() throws Exception {
        Path checkout = temp.resolve("service");
        Files.createDirectories(checkout.resolve(".git"));
        Files.createDirectories(checkout.resolve("src"));
        Files.writeString(checkout.resolve("src/Orders.java"), "class Orders {}");
        Files.writeString(checkout.resolve(".env"), "DB_PASSWORD=hunter2");
        Files.writeString(checkout.resolve(".git/config"), "[remote] url = https://user:tok@github.com/x/y");
        Files.writeString(checkout.resolve("server.pem"), "PEM");
        Files.createSymbolicLink(checkout.resolve("src/escape.txt"), outside.resolve("private.txt"));
        start();
        registerLocalRepo(checkout.toString());
        start();

        assertThat(call("repo_read", "{\"repo\":\"src\",\"path\":\"src/Orders.java\"}").path("content").asText()).contains("class Orders");
        for (String secret : List.of(".env", ".git/config", "server.pem")) {
            assertThatThrownBy(() -> call("repo_read", "{\"repo\":\"src\",\"path\":\"" + secret + "\"}")).as(secret).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> call("repo_read", "{\"repo\":\"src\",\"path\":\"src/escape.txt\"}")).hasMessageContaining("outside the folder");
        assertThatThrownBy(() -> call("repo_read", "{\"repo\":\"src\",\"path\":\"../private.txt\"}")).isInstanceOf(IllegalArgumentException.class);
        String listing = call("repo_list", "{\"repo\":\"src\"}").toString();
        assertThat(listing).contains("src/Orders.java").doesNotContain(".env").doesNotContain("pem").doesNotContain(".git/").doesNotContain("escape.txt");
    }

    @Test
    void aLinkInsideTheContextFolderThatPointsOutIsNotLoadedIntoContext() throws Exception {
        Files.createSymbolicLink(project.resolve("context/notes.md"), outside.resolve("private.txt"));
        Files.writeString(project.resolve("context/.env.md"), "TOKEN=abc");
        start();
        String all = call("context_list", "{}").toString();
        assertThat(all).doesNotContain("notes.md").doesNotContain(".env.md");
        assertThat(call("context_search", "{\"query\":\"TOP-SECRET-VALUE\"}").toString()).doesNotContain("TOP-SECRET-VALUE");
    }

    @Test
    void savingAContextNoteNeverWritesThroughALinkOutOfTheRepo() throws Exception {
        Files.createSymbolicLink(project.resolve("context/shared"), outside);
        start();
        assertThatThrownBy(() -> call("quartet_configure", "{\"section\":\"context_file\",\"values\":{\"path\":\"shared/owned.md\",\"content\":\"hello\"}}"))
                .hasMessageContaining("outside the folder");
        assertThat(outside.resolve("owned.md")).doesNotExist();
    }

    @Test
    void generatedTestsAndStepsAreNeverWrittenThroughALink() throws Exception {
        Path generated = project.resolve("src/test/java/generated");
        Files.createDirectories(generated.getParent());
        if (Files.exists(generated)) {
            try (var all = Files.walk(generated)) {
                for (Path old : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(old);
            }
        }
        Files.createSymbolicLink(generated, outside);
        start();
        String story = "{\"story_text\":\"# DEMO-9: x\\n\\n## Acceptance criteria\\n\\n- AC1: It loads.\",\"site\":\"https://example.com\"}";
        call("quartet_start", story);
        assertThatThrownBy(() -> call("write_test", "{\"file_name\":\"X.java\",\"content\":\"package generated.demo9;\\nclass X {}\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside.resolve("demo9/X.java")).doesNotExist();
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.exists(from)) return;
        try (var all = Files.walk(from)) {
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
}
