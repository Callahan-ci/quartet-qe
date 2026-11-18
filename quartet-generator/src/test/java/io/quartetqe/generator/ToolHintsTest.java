package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every tool offered over MCP says what it does to the outside world, and the claims are consistent. */
class ToolHintsTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();
    @TempDir Path temp;

    private JsonNode listing() throws Exception {
        Path project = temp.resolve("p");
        Files.createDirectories(project);
        for (String name : List.of("quartet.yaml", "context", "stories")) copy(REPO.resolve("skeleton").resolve(name), project.resolve(name));
        List<Tool> tools = new ChatSession(new Main(project, null), project.resolve("build/quartet-host")).tools();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpServer(tools, "x", new PrintStream(out, true, StandardCharsets.UTF_8)).serve(new ByteArrayInputStream(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(StandardCharsets.UTF_8)));
        return Json.parse(out.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("\"tools\"")).findFirst().orElseThrow()).path("result").path("tools");
    }

    @Test
    void everyToolIsClassifiedSoNoneFallsBackToTheRiskiestGuess() throws Exception {
        List<String> missing = new ArrayList<>();
        listing().forEach(t -> { if (!ToolHints.classified(t.path("name").asText())) missing.add(t.path("name").asText()); });
        assertThat(missing).as("tools with no entry in ToolHints").isEmpty();
    }

    @Test
    void everyListedToolCarriesTitleAndTheFourHints() throws Exception {
        for (JsonNode tool : listing()) {
            JsonNode a = tool.path("annotations");
            assertThat(a.path("title").asText()).as(tool.path("name").asText() + " title").isNotBlank();
            assertThat(a.has("readOnlyHint")).isTrue();
            assertThat(a.has("idempotentHint")).isTrue();
            assertThat(a.has("openWorldHint")).isTrue();
            // destructive only means something for a tool that changes things.
            assertThat(a.has("destructiveHint")).as(tool.path("name").asText()).isEqualTo(!a.path("readOnlyHint").asBoolean());
        }
    }

    @Test
    void toolsThatWriteOrRunAgainstRealSystemsAreNeverCalledReadOnly() throws Exception {
        for (JsonNode tool : listing()) {
            String name = tool.path("name").asText();
            boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean();
            if (name.startsWith("write_") || name.equals("quartet_configure") || name.equals("quartet_setup") || name.equals("quartet_start")
                    || name.equals("quartet_finish") || name.equals("quartet_answer_step") || name.equals("validate_tests")) {
                assertThat(readOnly).as(name + " changes things").isFalse();
            }
            if (name.startsWith("context_") || name.equals("list_steps") || name.equals("quartet_secrets_status")) {
                assertThat(readOnly).as(name + " only reads").isTrue();
            }
        }
    }

    @Test
    void anUnclassifiedToolIsDescribedAsTheRiskiestKind() {
        var entry = Json.obj().put("name", "something_new");
        ToolHints.annotate(entry);
        assertThat(entry.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(entry.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(entry.path("annotations").path("openWorldHint").asBoolean()).isTrue();
    }

    private static void copy(Path from, Path to) throws IOException {
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
