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
        return new TestGenerator.Result(Map.of("T.java", TEST), Json.arr(), Json.arr(), List.of(), List.of(), List.of(), 1, 3, 10, 5, 0, Map.of());
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

    @Test
    void theBrandedPageSaysWhatNeedsAttentionAndGroupsTestsUnderTheirCriteria(@TempDir Path dir) throws Exception {
        TestRunner.Result run = new TestRunner.Result(1, List.of(
                new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null, false, 0.42),
                new TestRunner.TestOutcome("T", "pageFits [iphone]", false, "org.opentest4j.AssertionFailedError: expected <b>bold</b> but was: 23\n\tat x.y", false, 1.5)), "");
        Scorecard.write(dir, story, principles, List.of(), generated(), run, null);
        String page = Files.readString(dir.resolve("review.html"));

        assertThat(page).contains("Needs <span class=\"tag warn\">your attention.</span>").contains("S-1").contains("AC1").contains("AC2");
        assertThat(page).contains("pageFits").contains("iphone").contains("Not covered").contains("420 ms").contains("1.5 s");
        // What a test printed can never become markup.
        assertThat(page).contains("expected &lt;b&gt;bold&lt;/b&gt; but was: 23").doesNotContain("<b>bold</b>");
        assertThat(page.indexOf("Needs your attention")).isLessThan(page.indexOf("What was tested"));
    }

    @Test
    void theBrandedPageOfACleanRunIsReadyAndSelfContained(@TempDir Path dir) throws Exception {
        WorkItem covered = WorkItem.of("S-1", "Title", "- AC1: Page fits", "u");
        TestRunner.Result run = new TestRunner.Result(0, List.of(new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null, false, 0.2)), "");
        Scorecard.write(dir, covered, principles, List.of(), generated(), run, null);
        String page = Files.readString(dir.resolve("review.html"));

        assertThat(page).contains("Ready <span class=\"tag\">to merge.</span>").contains("Nothing. Every check passed.").contains("A person approves.");
        // One file that opens anywhere: nothing to fetch, nothing to run.
        assertThat(page).doesNotContain("<script").doesNotContain("http://").doesNotContain("https://").doesNotContain("<link").doesNotContain("<img");
        assertThat(page).contains("prefers-color-scheme:light").contains("@media print");
    }

    @Test
    void aRunThatDidNotHappenIsNotShownAsPassed(@TempDir Path dir) throws Exception {
        WorkItem covered = WorkItem.of("S-1", "Title", "- AC1: Page fits", "u");
        Scorecard.write(dir, covered, principles, List.of(), generated(), null, null);
        String page = Files.readString(dir.resolve("review.html"));
        assertThat(page).contains("The tests were written but not run.").contains("Not run").doesNotContain("tests passed");
    }
    @Test
    void aFindingThatDoesNotBlockMergingIsShownUnderNeedsYourAttentionNotHidden(@TempDir Path dir) throws Exception {
        WorkItem covered = WorkItem.of("S-1", "Title", "- AC1: Page fits", "u");
        TestRunner.Result run = new TestRunner.Result(0, List.of(new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null, false, 0.2)), "");
        Finding gap = new Finding("REVIEW-QA", "Quality built in from day one", Finding.Severity.WARN, "T.java",
                "No test covers two customers buying the last unit at the same time.");
        TestGenerator.Result reviewed = new TestGenerator.Result(Map.of("T.java", TEST), Json.arr(), Json.arr(), List.of(), List.of(gap), List.of(), 1, 3, 0, 0, 0, Map.of());

        assertThat(Scorecard.write(dir, covered, principles, List.of(), reviewed, run, null).approved()).as("a note does not block merging").isTrue();
        String md = Files.readString(dir.resolve("review.md"));
        assertThat(md).contains("Nothing blocks merging.").contains("**Worth a look** (does not block merging):")
                .contains("- **Quality built in from day one:** No test covers two customers buying the last unit at the same time.")
                .doesNotContain("Nothing.\n");
        assertThat(md.indexOf("Worth a look")).isLessThan(md.indexOf("What was tested"));

        String page = Files.readString(dir.resolve("review.html"));
        assertThat(page).contains("Nothing blocks merging.").doesNotContain("Nothing. Every check passed.").contains("Worth a look");
        assertThat(page.indexOf("two customers buying the last unit")).isLessThan(page.indexOf("What was tested"));
    }

    @Test
    void onTheChatPathTheTokensAreSaidToBeCountedByTheChatToolNotShownAsZero(@TempDir Path dir) throws Exception {
        assertThat(Scorecard.aiUse(3, 0)).isEqualTo("The AI in your chat tool took 3 model turns; your chat tool counts its tokens.");
        assertThat(Scorecard.aiUse(1, 0)).contains("1 model turn;");
        assertThat(Scorecard.aiUse(3, 15)).isEqualTo("The AI used 3 model turns and 15 tokens.");
        assertThat(Scorecard.aiUse(0, 0)).isEqualTo("The AI used 0 model turns and 0 tokens.");

        WorkItem covered = WorkItem.of("S-1", "Title", "- AC1: Page fits", "u");
        TestRunner.Result run = new TestRunner.Result(0, List.of(new TestRunner.TestOutcome("T", "pageFits [desktop]", true, null)), "");
        Scorecard.write(dir, covered, principles, List.of(), new TestGenerator.Result(Map.of("T.java", TEST), Json.arr(), Json.arr(),
                List.of(), List.of(), List.of(), 1, 3, 0, 0, 0, Map.of()), run, null);
        assertThat(Files.readString(dir.resolve("review.md"))).contains("your chat tool counts its tokens").doesNotContain("0 tokens");
        assertThat(Files.readString(dir.resolve("review.html"))).contains("your chat tool counts its tokens").doesNotContain("0 tokens");
    }
}
