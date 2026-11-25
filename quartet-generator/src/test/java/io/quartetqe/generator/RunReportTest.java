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
        Files.writeString(results.resolve("TEST-t.xml"), XML.replace("expected: 409 but was: 200", "expected &lt;b&gt;409&lt;/b&gt; but was: 200"));

        RunReport.Data data = RunReport.read(results, null);
        String page = RunReport.html(data);

        assertThat(page).contains("<span class=\"tag warn\">1 test failed.</span>").contains("<strong>1 of 3</strong> tests passed, 1 skipped");
        assertThat(page).contains("Passed</span>").contains("Failed</span>").contains("Skipped</span>").contains("3.40 s");
        assertThat(page).contains("expected &lt;b&gt;409&lt;/b&gt; but was: 200").doesNotContain("<b>409</b>");
        assertThat(page).doesNotContain("at generated.t.T.ordersReject");
        assertThat(page).contains("tests passed").contains("steps replayed, no AI").contains("3.5 s");
    }

    @Test
    void anAllGreenRunWithNoAiSaysSo(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), "<testsuite name=\"s\" tests=\"1\"><testcase name=\"pageFits\" classname=\"generated.t.T\" time=\"0.1\"/></testsuite>");
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, "{\"test\":\"T.pageFits\",\"instruction\":\"Open it\",\"why\":\"w\",\"source\":\"REPLAYED\",\"steps\":1,\"inputTokens\":0,\"outputTokens\":0,\"model\":\"x\"}\n");
        String page = RunReport.html(RunReport.read(results, audit));
        assertThat(page).contains("All <span class=\"tag\">passed.</span>").contains("The AI was not needed: every step was replayed from a recording.");
    }

    @Test
    void eachTestShowsItsActsStepsAndScreenshotsWithAClickToEnlarge(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, """
            {"test":"T.pageFits","instruction":"Open the <home> page","why":"arrivals","source":"REPLAYED","steps":2,"inputTokens":0,"outputTokens":0,"model":"x","trace":[{"tool":"screen_open","args":"{\\"path\\":\\"/\\"}","result":"{\\"title\\":\\"Home\\"}"},{"tool":"screen_tap","args":"button Buy","error":true}],"shots":[{"target":"site-web","file":"screenshots/T.pageFits.act1.site-web.png"}]}
            {"test":"T.ordersReject","instruction":"Order too many","why":"stock","source":"HANDED_OFF","steps":3,"inputTokens":9,"outputTokens":1,"model":"m"}
            {"test":"Elsewhere.thing","instruction":"Not tied to a test","why":"","source":"LIVE","steps":1,"inputTokens":0,"outputTokens":0,"model":"m"}
            """);
        String page = RunReport.html(RunReport.read(results, audit));

        // The act sits under its own test, with its steps and its screenshot.
        int test = page.indexOf("pageFits"), act = page.indexOf("Open the &lt;home&gt; page"), other = page.indexOf("ordersReject");
        assertThat(test).isLessThan(act);
        assertThat(act).isLessThan(other);
        assertThat(page).contains("2 steps").contains("<b>screen_open</b>").contains("class=\"err\"");
        assertThat(page).contains("src=\"screenshots/T.pageFits.act1.site-web.png\"").contains("href=\"#shot1\"").contains("id=\"shot1\"");
        assertThat(page).contains("Replayed").contains("Handed to the AI").contains("Other acts").contains("Not tied to a test");
        assertThat(page).doesNotContain("<home>");
    }

    @Test
    void theStylesheetIsWrittenBesideThePageForJenkins(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path out = dir.resolve("out/report.html");
        RunReport.write(RunReport.read(results, null), out);
        assertThat(out).exists();
        assertThat(dir.resolve("out/quartet-report.css")).exists();
        assertThat(Files.readString(out)).contains("href=\"quartet-report.css\"").contains("<style>");
    }

    @Test
    void aFailureScreenshotIsShownUnderItsTest(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path screenshots = dir.resolve("screenshots");
        Files.createDirectories(screenshots);
        Files.write(screenshots.resolve("generated.t.T.ordersReject.desktop.png"), new byte[] {1});
        Files.write(screenshots.resolve("unrelated.name.png"), new byte[] {1});

        String page = RunReport.html(RunReport.read(results, null, screenshots));

        assertThat(page).contains("src=\"screenshots/generated.t.T.ordersReject.desktop.png\"").contains("when it failed");
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
    private static final String TRACED_XML = """
        <testsuite name="generated.t" tests="2" failures="0" skipped="0">
          <testcase name="orderFlows" classname="generated.t.T" time="1.000"/>
          <testcase name="pageFits" classname="generated.t.T" time="0.100"/>
        </testsuite>
        """;

    /** Three acts threading one order through the API, Postgres and Kafka, as TestAgent writes them, and one unrelated act. */
    private static final String TRACED_AUDIT = """
        {"test":"T.orderFlows","instruction":"Place an order","why":"w","source":"REPLAYED","steps":1,"types":{"orders-api":"api"},"data":{"orderId":"ORD-1234567","status":"RESERVED"},"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","hops":[{"target":"orders-api","kind":"http","detail":"POST /api/orders → 201","link":"SENT"}]}
        {"test":"T.orderFlows","instruction":"Find order {{orderId}} in the database","resolved":"Find order ORD-1234567 in the database","why":"w","source":"REPLAYED","steps":1,"types":{"orders-db":"postgres"},"vars":{"orderId":"ORD-1234567"},"data":{"status":"RESERVED"},"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","hops":[{"target":"orders-db","kind":"postgres","detail":"pg_query → rows:some","link":"READ"}]}
        {"test":"T.orderFlows","instruction":"Find the order event for {{orderId}}","resolved":"Find the order event for ORD-1234567","why":"w","source":"REPLAYED","steps":1,"types":{"order-events":"kafka"},"vars":{"orderId":"ORD-1234567"},"data":{"found":true},"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","hops":[{"target":"order-events","kind":"kafka","detail":"event on shop.orders.created","link":"SAME"}]}
        {"test":"T.pageFits","instruction":"Open the <home> page","why":"w","source":"REPLAYED","steps":1,"types":{"site":"screen"},"traceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","hops":[{"target":"site","kind":"screen","detail":"screen_open","link":"READ"}]}
        """;

    @Test
    void aTransactionThreadedThroughSystemsIsDrawnInOrderWithHowEachHopIsLinked(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), TRACED_XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, TRACED_AUDIT);

        RunReport.Data data = RunReport.read(results, audit);
        var paths = RunReport.transactions(data);
        assertThat(paths).hasSize(1);
        TransactionPath.Path path = paths.get(0);
        assertThat(path.name()).isEqualTo("orderId");
        assertThat(path.value()).isEqualTo("ORD-1234567");
        assertThat(path.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(path.steps()).extracting(TransactionPath.Step::target).containsExactly("orders-api", "orders-db", "order-events");
        assertThat(path.steps()).extracting(TransactionPath.Step::link)
                .containsExactly(TransactionPath.Link.SENT, TransactionPath.Link.READ, TransactionPath.Link.SAME);
        assertThat(path.carriedBy()).containsExactly("order-events");

        String page = RunReport.html(data);
        assertThat(page).contains("<span class=\"txn-k\">Transaction</span><b>orderId ORD-1234567</b>")
                .contains("3 systems · trace passed on by order-events")
                .contains("trace 4bf92f3577b34da6a3ce929d0e0e4736")
                .contains("<li class=\"hop l-same\"").contains("same trace").contains("trace sent").contains("linked by value")
                .contains("POST /api/orders → 201")
                .contains("transaction followed across systems");
        assertThat(page.indexOf("sys\">orders-api<")).isPositive().isLessThan(page.indexOf("sys\">orders-db<"));
        assertThat(page.indexOf("sys\">orders-db<")).isLessThan(page.indexOf("sys\">order-events<"));
        assertThat(page).as("a test that threads nothing gets no path").containsOnlyOnce("class=\"txn\"");

        assertThat(RunReport.markdown(data)).contains("Transactions followed across systems: 1, 1 with the trace passed on by the system itself.");
    }

    @Test
    void valuesThatMatchByChanceOrStayOnOneSystemAreNotATransaction(@TempDir Path dir) throws Exception {
        assertThat(TransactionPath.meaningful("ORD-1234567")).isTrue();
        assertThat(TransactionPath.meaningful("C-42")).isTrue();
        assertThat(TransactionPath.meaningful("2")).isFalse();
        assertThat(TransactionPath.meaningful("1200")).isFalse();
        assertThat(TransactionPath.meaningful("true")).isFalse();
        assertThat(TransactionPath.meaningful("RES")).isFalse();

        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), TRACED_XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, """
            {"test":"T.orderFlows","instruction":"Place an order","why":"w","source":"REPLAYED","steps":1,"types":{"orders-api":"api"},"data":{"orderId":"ORD-1234567"},"hops":[{"target":"orders-api","kind":"http","detail":"POST /api/orders → 201","link":"SENT"}]}
            {"test":"T.orderFlows","instruction":"Read order {{orderId}}","why":"w","source":"REPLAYED","steps":1,"types":{"orders-api":"api"},"vars":{"orderId":"ORD-1234567","quantity":"2"},"hops":[{"target":"orders-api","kind":"http","detail":"GET /api/orders/ORD-1234567 → 200","link":"SENT"}]}
            """);

        assertThat(RunReport.transactions(RunReport.read(results, audit))).as("one system is not a path across systems").isEmpty();
    }

    @Test
    void anOlderAuditWithoutTraceFieldsStillReads(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(results.resolve("TEST-t.xml"), XML);
        Path audit = dir.resolve("acts.jsonl");
        Files.writeString(audit, AUDIT);

        RunReport.Data data = RunReport.read(results, audit);
        assertThat(RunReport.transactions(data)).isEmpty();
        assertThat(RunReport.html(data)).doesNotContain("class=\"txn\"");
    }
}
