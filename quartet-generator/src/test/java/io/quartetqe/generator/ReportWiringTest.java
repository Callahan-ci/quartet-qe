package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The report is written after every run and shown by Jenkins, without anyone asking. */
class ReportWiringTest {
    private static final Path REPO = Path.of("").toAbsolutePath().getParent();

    @Test
    void aNewTestsRepoWritesTheReportAfterEveryRunEvenWhenTestsFail() throws Exception {
        String build = Files.readString(REPO.resolve("skeleton/build.gradle.kts"));
        assertThat(build).contains("finalizedBy(\"quartetReport\")").contains("\"quartetReport\"").contains("args(\"report\")");
        // A report that cannot be made must never turn a green run red.
        assertThat(build).contains("isIgnoreExitValue = true");
    }

    @Test
    void theGeneratedJenkinsfileArchivesAndPublishesTheReportAndSaysWhatToDoWithoutThePlugin() throws Exception {
        String jenkinsfile = Files.readString(REPO.resolve("templates/Jenkinsfile"));
        assertThat(jenkinsfile).contains("build/quartet-report/**").contains("publishHTML").contains("report.html").contains("Quartet QE report");
        assertThat(jenkinsfile).contains("NoSuchMethodError").contains("HTML Publisher");
    }
}
