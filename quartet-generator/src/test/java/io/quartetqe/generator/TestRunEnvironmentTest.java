package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The team's test run, which executes AI-written code, is started with the environment it needs and no more. */
class TestRunEnvironmentTest {
    @TempDir Path dir;

    private String environmentOfARun(Map<String, String> explicit) throws Exception {
        Path results = dir.resolve("results");
        Files.createDirectories(results);
        Path out = dir.resolve("env.txt");
        TestRunner runner = new TestRunner(dir, List.of("sh", "-c", "env > " + out), results).withEnvironment(explicit);
        runner.run("x", Map.of());
        return Files.readString(out);
    }

    @Test
    void anUnrelatedVariableOfTheUsersSessionDoesNotReachTheTests() throws Exception {
        String unrelated = System.getenv().entrySet().stream()
                .filter(e -> !io.quartetqe.core.ChildEnvironment.always(e.getKey()) && !e.getValue().isBlank() && !e.getValue().contains("\n"))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        Assumptions.assumeTrue(unrelated != null, "this machine's session has no variable outside the basics");

        String seen = environmentOfARun(Map.of("QUARTET_MODE", "replay"));

        assertThat(seen).contains("PATH=").contains("QUARTET_MODE=replay").doesNotContain("\n" + unrelated + "=");
    }

    @Test
    void whatQuartetSetsForTheRunIsKept() throws Exception {
        Path file = dir.resolve("env.yaml");
        Files.writeString(file, "targets:\n  db:\n    password: ${DB_PASSWORD}\n");
        String seen = environmentOfARun(Map.of("QUARTET_ENV", file.toString(), "DB_PASSWORD", "from-env-file", "QUARTET_MODE", "auto"));
        assertThat(seen).contains("DB_PASSWORD=from-env-file").contains("QUARTET_ENV=").contains("QUARTET_MODE=auto");
    }
}
