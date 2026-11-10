package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import io.quartetqe.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPageTest {
    private static final String TEST = """
        package generated.t;
        import io.quartetqe.runtime.*;
        class T {
            @OnEverySurface("site") @AcceptanceCriteria("AC1") void pageFits(TestAgent agent) {}
        }
        """;

    private final Principles principles = Principles.load(Path.of("../context"));
    private final WorkItem story = WorkItem.of("S-1", "Title", "- AC1: Page fits\n- AC2: Never tested", "u");

    private TestGenerator.Result generated() {
        return new TestGenerator.Result(Map.of("T.java", TEST), Json.arr(), Json.arr(), List.of(), List.of(), List.of(), 1, 3, 10, 5, 0);
    }

    @Test
    void failuresComeFirstAndResultsAreGroupedByDevice(@TempDir Path dir) throws Exception {
        TestRunner.Result run = new TestRunner.Result(1, List.of(
                new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null),
                new TestRunner.TestOutcome("T", "pageFits [iphone]", false, "org.opentest4j.AssertionFailedError: \nexpected: 0\n but was: 23\n\tat x.y"),
                new TestRunner.TestOutcome("T", "pageFits [ios]", false, null, true)), "");

        Scorecard.Summary summary = Scorecard.write(dir, story, principles, List.of(), generated(), run, null);
        String page = Files.readString(dir.resolve("review.md"));

        assertThat(summary.approved()).isFalse();
        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(page).contains("**Needs your attention.**").contains("`pageFits [iphone]`: expected: 0 but was: 23");
        assertThat(page).contains("Passed (desktop)<br>**Failed** (iphone)<br>Skipped (ios)");
        assertThat(page).contains("| AC2: Never tested | None | Not covered |");
        assertThat(page.indexOf("Needs your attention")).isLessThan(page.indexOf("What was tested"));
    }

    @Test
    void aCleanRunIsReadyToMerge(@TempDir Path dir) throws Exception {
        WorkItem covered = WorkItem.of("S-1", "Title", "- AC1: Page fits", "u");
        TestRunner.Result run = new TestRunner.Result(0, List.of(new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null)), "");
        assertThat(Scorecard.write(dir, covered, principles, List.of(), generated(), run, null).approved()).isTrue();
        assertThat(Files.readString(dir.resolve("review.md"))).contains("**Ready to merge.**").contains("Nothing.");
    }
}
