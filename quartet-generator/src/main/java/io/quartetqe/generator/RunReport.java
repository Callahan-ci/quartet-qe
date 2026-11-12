package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One page for a run: what the tests decided, and what the AI did.
 *
 * Written as HTML for people and as markdown for a pull request comment. Every number comes
 * from the build's own JUnit XML and the audit log, never from a model.
 */
public final class RunReport {
    private RunReport() {}

    private static final List<String> SOURCES = List.of("REPLAYED", "HANDED_OFF", "MISSED", "LIVE");

    /** One audited act. */
    public record Act(String test, String instruction, String why, String source, int steps, int tokens,
                      String model, String stopReason) {}

    /** Totals across the audit log. */
    public record Totals(Map<String, Integer> bySource, int acts, int tokens, int modelCalls) {}

    public record Data(List<TestRunner.TestOutcome> tests, List<Act> acts, Totals totals,
                       Map<String, String> screenshots) {
        public int passed() {
            return (int) tests.stream().filter(t -> t.passed()).count();
        }

        public int failed() {
            return (int) tests.stream().filter(t -> !t.passed() && !t.skipped()).count();
        }

        public int skipped() {
            return (int) tests.stream().filter(TestRunner.TestOutcome::skipped).count();
        }

        public double seconds() {
            return tests.stream().mapToDouble(TestRunner.TestOutcome::seconds).sum();
        }
    }

    /** Reads the build's JUnit XML and the audit log. A missing audit file yields no acts. */
    public static Data read(Path resultsDir, Path auditFile) throws IOException {
        return read(resultsDir, auditFile, null);
    }

    /** As {@link #read}, plus a directory of failure screenshots named Class.method.target.png. */
    public static Data read(Path resultsDir, Path auditFile, Path screenshotsDir) throws IOException {
        List<TestRunner.TestOutcome> tests = TestRunner.readResults(resultsDir);
        Map<String, String> screenshots = new LinkedHashMap<>();
        if (screenshotsDir != null && Files.isDirectory(screenshotsDir)) {
            try (var files = Files.list(screenshotsDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
                    String name = p.getFileName().toString().replaceFirst("\\.png$", "");
                    int firstDot = name.indexOf('.');
                    int lastDot = name.lastIndexOf('.');
                    if (firstDot > 0 && lastDot > firstDot) {
                        screenshots.put(name.substring(0, lastDot), p.getFileName().toString());
                    }
                });
            }
        }
        List<Act> acts = new ArrayList<>();
        if (auditFile != null && Files.isRegularFile(auditFile)) {
            for (String line : Files.readAllLines(auditFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode entry = Json.parse(line);
                acts.add(new Act(entry.path("test").asText(), entry.path("instruction").asText(),
                        entry.path("why").asText(), entry.path("source").asText("LIVE"),
                        entry.path("steps").asInt(), entry.path("inputTokens").asInt() + entry.path("outputTokens").asInt(),
                        entry.path("model").asText(""), entry.path("stopReason").asText(null)));
            }
        }
        Map<String, Integer> bySource = new LinkedHashMap<>();
        SOURCES.forEach(source -> bySource.put(source, 0));
        int tokens = 0;
        int modelCalls = 0;
        for (Act act : acts) {
            bySource.merge(act.source(), 1, Integer::sum);
            tokens += act.tokens();
            if (!"REPLAYED".equals(act.source())) modelCalls++;
        }
        return new Data(tests, acts, new Totals(bySource, acts.size(), tokens, modelCalls), screenshots);
    }

    /** Markdown for a pull request comment. */
    public static String markdown(Data data) {
        StringBuilder out = new StringBuilder();
        out.append("## Quartet QE run\n\n");
        out.append("| Tests | Passed | Failed | Skipped |\n| --- | --- | --- | --- |\n");
        out.append("| ").append(data.tests().size()).append(" | ").append(data.passed())
                .append(" | ").append(data.failed()).append(" | ").append(data.skipped()).append(" |\n\n");
        if (data.failed() > 0) {
            out.append("### Failed\n\n");
            data.tests().stream().filter(t -> !t.passed() && !t.skipped()).forEach(test -> {
                out.append("- `").append(test.className()).append(".").append(test.method()).append("`");
                if (test.failure() != null) out.append(": ").append(firstLine(test.failure()));
                out.append('\n');
            });
            out.append('\n');
        }
        Totals totals = data.totals();
        out.append("| Recordings replayed | Handed to the AI | Ran live |\n| --- | --- | --- |\n");
        out.append("| ").append(totals.bySource().get("REPLAYED")).append(" | ").append(totals.bySource().get("HANDED_OFF"))
                .append(" | ").append(totals.bySource().get("MISSED") + totals.bySource().get("LIVE"))
                .append(" |\n\n");
        out.append("AI: ").append(totals.modelCalls()).append(" model calls, ").append(totals.tokens()).append(" tokens. Run time: ")
                .append(data.seconds() >= 1 ? String.format("%.1f", data.seconds()) + " s" : String.format("%.0f ms", data.seconds() * 1000))
                .append(".\n");
        return out.toString();
    }

    /** One standalone HTML page. */
    public static String html(Data data) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Quartet QE run</title><style>");
        out.append("body{font-family:ui-sans-serif,system-ui,sans-serif;margin:24px auto;max-width:960px;color:#1a1a1a}");
        out.append("h1{font-size:20px}h2{font-size:15px;margin-top:28px}");
        out.append("table{border-collapse:collapse;width:100%;font-size:13px}");
        out.append("th,td{border:1px solid #d8d4c9;padding:4px 8px;text-align:left;vertical-align:top}");
        out.append("th{background:#f5f3ee;text-align:left}");
        out.append("code{font-family:ui-monospace,SFMono-Regular,monospace;font-size:12px}");
        out.append(".bad{color:#8a1f1f}.good{color:#1f6b3a}.skip{color:#8a6a1f}");
        out.append(".card{display:inline-block;border:1px solid #d8d4c9;padding:8px 14px;margin:0 8px 8px 0;background:#fff}");
        out.append(".num{font-size:20px;font-weight:600}");
        out.append("</style></head><body>");
        out.append("<h1>Quartet QE run</h1>");

        Totals totals = data.totals();
        out.append("<div><span class=\"card\"><span class=\"num\">").append(data.tests().size()).append("</span> tests</span>");
        out.append("<span class=\"card good\"><span class=\"num\">").append(data.passed()).append("</span> passed</span>");
        out.append("<span class=\"card").append(data.failed() > 0 ? " bad" : "").append("\"><span class=\"num\">")
                .append(data.failed()).append("</span> failed</span>");
        out.append("<span class=\"card skip\"><span class=\"num\">").append(data.skipped()).append("</span> skipped</span></div>");
        out.append("<div><span class=\"card\"><span class=\"num\">").append(totals.bySource().get("REPLAYED"))
                .append("</span> recordings replayed</span>");
        out.append("<span class=\"card\"><span class=\"num\">").append(totals.bySource().get("HANDED_OFF"))
                .append("</span> handed to the AI</span>");
        out.append("<span class=\"card\"><span class=\"num\">").append(totals.bySource().get("MISSED") + totals.bySource().get("LIVE"))
                .append("</span> ran live</span>");
        out.append("<span class=\"card\"><span class=\"num\">").append(totals.modelCalls()).append("</span> model calls, ")
                .append(totals.tokens()).append(" tokens</span>");
        out.append("<span class=\"card\"><span class=\"num\">")
                .append(data.seconds() >= 1 ? String.format("%.1f", data.seconds()) : String.format("%.2f", data.seconds()))
                .append("</span> ").append(data.seconds() >= 1 ? "s run time</span>" : "s run time</span>").append("</div>");

        out.append("<h2>Tests</h2><table><tr><th>Test</th><th>Result</th><th>Time</th><th>Failure</th></tr>");
        for (TestRunner.TestOutcome test : data.tests()) {
            String status = test.skipped() ? "<span class=\"skip\">Skipped</span>"
                    : test.passed() ? "<span class=\"good\">Passed</span>" : "<span class=\"bad\">Failed</span>";
            out.append("<tr><td><code>").append(esc(test.className())).append(".").append(esc(test.method())).append("</code>");
            String shot = data.screenshots().get(test.className() + "." + test.method());
            if (shot != null) out.append(" <a href=\"screenshots/").append(esc(shot)).append("\">screenshot</a>");
            out.append("</td><td>").append(status).append("</td><td>").append(String.format("%.2f s", test.seconds())).append("</td><td>");
            if (test.failure() != null) out.append("<code>").append(esc(firstLine(test.failure()))).append("</code>");
            out.append("</td></tr>");
        }
        out.append("</table>");

        out.append("<h2>Every act, from the audit log</h2><table><tr><th>Test</th><th>Instruction</th><th>Source</th><th>Steps</th><th>Tokens</th><th>Model</th></tr>");
        for (Act act : data.acts()) {
            out.append("<tr><td><code>").append(esc(act.test())).append("</code></td><td>").append(esc(act.instruction()))
                    .append("</td><td>").append(esc(act.source()));
            if (act.stopReason() != null) out.append(" (").append(esc(act.stopReason())).append(")");
            out.append("</td><td>").append(act.steps()).append("</td><td>").append(act.tokens())
                    .append("</td><td>").append(esc(act.model())).append("</td></tr>");
        }
        out.append("</table></body></html>");
        return out.toString();
    }

    public static void write(Data data, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, html(data), StandardCharsets.UTF_8);
    }

    private static String firstLine(String failure) {
        if (failure == null) return "";
        String text = failure.replaceFirst("^[\\w.$]+(Error|Exception):\\s*", "").split("\n\\s*at ")[0].replaceAll("\\s+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}