package io.quartetqe.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-page run report, from the build's own JUnit XML and audit log. */
class RunReportTest {
    private static final String XML = """
        <testsuite name="generated.t" tests="3" failures="1" skipped="1">
          <testcase name="pageFits" classname="generated.t.T" time="0.120"/>
          <testcase name="ordersReject" classname="generated.t.T" time="3.400">
            <failure message="expected: 409 but was: 200">org.opentest4j.AssertionFailedError: expected: 409 but was: 200
	at generated.t.T.ordersReject(T.java:12)</failure>
          </testcase>
          <testcase name="loadTest" classname="generated.t.T" time="0.000"><skipped/></testcase>
        </testsuite>
        """;

    private static final String AUDIT = """
        {"test":"T.pageFits","instruction":"Open the site home page","why":"arrivals","source":"REPLAYED","steps":2,"inputTokens":0,"outputTokens":0,"model":"x"}
        {"test":"T.ordersReject","instruction":"Try to order beyond stock","why":"customers must not oversell","source":"HANDED_OFF","steps":4,"inputTokens":900,"outputTokens":120,"model":"m1","stopReason":"target-not-found"}
        {"test":"T.pageFits","instruction":"Read the page","why":"layout","source":"MISSED","steps":3,"inputTokens":500,"outputTokens":80,"model":"m1"}
        """;

    @Test
    void totalsComeFromTheAuditAndFailuresComeFromTheBuild(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-generated.t.T.xml"), XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, AUDIT);

        RunReport.Data data = RunReport.read(results, audit);

        assertThat(data.passed()).isEqualTo(1);
        assertThat(data.failed()).isEqualTo(1);
        assertThat(data.skipped()).isEqualTo(1);
        assertThat(data.totals().bySource().get("REPLAYED")).isEqualTo(1);
        assertThat(data.totals().bySource().get("HANDED_OFF")).isEqualTo(1);
        assertThat(data.totals().bySource().get("MISSED")).isEqualTo(1);
        assertThat(data.totals().tokens()).isEqualTo(1600);
        assertThat(data.totals().modelCalls()).isEqualTo(2);
        assertThat(data.seconds()).isEqualTo(3.52, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void theHtmlPageLeadsWithTheVerdictAndEscapesFailureText(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);

        RunReport.Data data = RunReport.read(results, null);
        String page = RunReport.html(data);

        assertThat(page).contains("1</span> passed").contains("1</span> failed").contains("1</span> skipped");
        assertThat(page).contains("<span class=\"bad\">Failed</span>").contains("expected: 409 but was: 200");
        assertThat(page).doesNotContain("at generated.t.T.ordersReject");
        assertThat(page).contains("0</span> recordings replayed");
        assertThat(page).contains("<th>Time</th>").contains("3.40 s").contains("3.5</span> s run time");
    }

    @Test
    void aFailureScreenshotIsLinkedFromItsTest(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path screenshots = dir.resolve("screenshots");
        Files.createDirectories(screenshots);
        Files.write(screenshots.resolve("generated.t.T.ordersReject.desktop.png"), new byte[] {1});
        Files.write(screenshots.resolve("unrelated.name.png"), new byte[] {1});

        RunReport.Data data = RunReport.read(results, null, screenshots);
        String page = RunReport.html(data);

        assertThat(page).contains("<a href=\"screenshots/generated.t.T.ordersReject.desktop.png\">screenshot</a>");
        assertThat(page).doesNotContain("unrelated.name");
    }

    @Test
    void theMarkdownCommentIsShortAndNamesTheFailure(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, AUDIT);

        String markdown = RunReport.markdown(RunReport.read(results, audit));

        assertThat(markdown).contains("| 3 | 1 | 1 | 1 |").contains("`generated.t.T.ordersReject`: expected: 409 but was: 200");
        assertThat(markdown).contains("| 1 | 1 | 1 |").contains("2 model calls, 1600 tokens").contains("Run time: 3.5 s");
    }
}
