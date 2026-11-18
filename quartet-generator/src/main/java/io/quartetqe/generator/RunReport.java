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
                      String model, String stopReason, List<Note> trace, List<Shot> shots) {
        public Act(String test, String instruction, String why, String source, int steps, int tokens, String model, String stopReason) {
            this(test, instruction, why, source, steps, tokens, model, stopReason, List.of(), List.of());
        }
    }

    /** One step an act took, in a line. */
    public record Note(String tool, String args, String result, boolean error) {}

    /** A screenshot taken at the end of an act, relative to the report folder. */
    public record Shot(String target, String file) {}

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
                List<Note> trace = new ArrayList<>();
                entry.path("trace").forEach(n -> trace.add(new Note(n.path("tool").asText(), n.path("args").asText(""),
                        n.path("result").asText(""), n.path("error").asBoolean(false))));
                List<Shot> shots = new ArrayList<>();
                entry.path("shots").forEach(sh -> shots.add(new Shot(sh.path("target").asText(), sh.path("file").asText())));
                acts.add(new Act(entry.path("test").asText(), entry.path("resolved").asText(entry.path("instruction").asText()),
                        entry.path("why").asText(), entry.path("source").asText("LIVE"),
                        entry.path("steps").asInt(), entry.path("inputTokens").asInt() + entry.path("outputTokens").asInt(),
                        entry.path("model").asText(""), entry.path("stopReason").asText(null), trace, shots));
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

    /** One page for people: the verdict, then every test with what it did and what the screen looked like. */
    public static String html(Data data) {
        Totals totals = data.totals();
        int total = data.tests().size();
        StringBuilder h = new StringBuilder(16_000);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Run report · Quartet QE</title><link rel=\"stylesheet\" href=\"").append(CSS_FILE).append("\"><style>")
                .append(BrandedReport.css()).append("</style></head><body><div class=\"wrap\">");
        h.append("<header class=\"top\"><div class=\"brand\"><span class=\"mark\">Q<b>E</b></span><span class=\"word\">Quartet <b>QE</b></span></div>")
                .append("<div class=\"meta\">Run report · ").append(java.time.ZonedDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", java.util.Locale.ENGLISH))).append("</div></header>");

        h.append("<section class=\"hero\"><div class=\"eyebrow\">Run report</div><h1 class=\"verdict\">");
        if (total == 0) h.append("No tests <span class=\"tag warn\">ran.</span>");
        else if (data.failed() > 0) h.append("<span class=\"tag warn\">").append(data.failed()).append(data.failed() == 1 ? " test failed." : " tests failed.").append("</span>");
        else h.append("All <span class=\"tag\">passed.</span>");
        h.append("</h1><p class=\"lede\"><strong>").append(data.passed()).append(" of ").append(total).append("</strong> tests passed");
        if (data.skipped() > 0) h.append(", ").append(data.skipped()).append(" skipped");
        h.append(". ").append(totals.modelCalls() == 0 ? "The AI was not needed: every step was replayed from a recording." : "The AI was used for " + totals.modelCalls() + " act(s).").append("</p></section>");

        h.append("<section class=\"tiles\">");
        tile(h, data.passed() + (data.passed() == total ? "" : " / " + total), "tests passed", data.failed() > 0 ? "bad" : "good");
        if (data.failed() > 0) tile(h, String.valueOf(data.failed()), "failed", "bad");
        if (data.skipped() > 0) tile(h, String.valueOf(data.skipped()), "skipped", "dim");
        tile(h, String.valueOf(totals.bySource().get("REPLAYED")), "steps replayed, no AI", "good");
        int ai = totals.bySource().get("HANDED_OFF") + totals.bySource().get("MISSED") + totals.bySource().get("LIVE");
        tile(h, String.valueOf(ai), "acts that used the AI", ai > 0 ? "warn" : "dim");
        tile(h, String.valueOf(totals.modelCalls()), "model calls", "dim");
        tile(h, data.seconds() >= 1 ? String.format(java.util.Locale.ENGLISH, "%.1f s", data.seconds()) : Math.round(data.seconds() * 1000) + " ms", "run time", "dim");
        h.append("</section>");

        h.append("<section><h2>Tests</h2>");
        List<Act> used = new ArrayList<>();
        StringBuilder boxes = new StringBuilder();
        int[] counter = {0};
        for (TestRunner.TestOutcome test : data.tests()) {
            String state = test.skipped() ? "dim" : test.passed() ? "good" : "bad";
            String label = test.skipped() ? "Skipped" : test.passed() ? "Passed" : "Failed";
            h.append("<article class=\"ac s-").append(state).append("\"><div class=\"achead\"><span class=\"acid\">").append(esc(simple(test.className()))).append("</span><p>")
                    .append(esc(test.method())).append("</p><span class=\"time\">").append(String.format(java.util.Locale.ENGLISH, "%.2f s", test.seconds()))
                    .append("</span><span class=\"chip ").append(state).append("\">").append(label).append("</span></div>");
            if (test.failure() != null) h.append("<pre class=\"fail\">").append(esc(firstLine(test.failure()))).append("</pre>");
            List<Act> mine = actsFor(test, data.acts());
            used.addAll(mine);
            String legacy = data.screenshots().get(test.className() + "." + test.method());
            if (!mine.isEmpty() || legacy != null) {
                h.append("<div class=\"acts\">");
                for (Act act : mine) actBlock(h, boxes, counter, act);
                if (legacy != null && mine.stream().noneMatch(a -> !a.shots().isEmpty())) {
                    h.append("<div class=\"shots\">");
                    thumb(h, boxes, counter, "screenshots/" + legacy, "when it failed", false);
                    h.append("</div>");
                }
                h.append("</div>");
            }
            h.append("</article>");
        }
        h.append("</section>");

        List<Act> other = data.acts().stream().filter(a -> !used.contains(a)).toList();
        if (!other.isEmpty()) {
            h.append("<section><h2>Other acts</h2><article class=\"ac s-dim\"><div class=\"acts\" style=\"border-top:0\">");
            for (Act act : other) actBlock(h, boxes, counter, act);
            h.append("</div></article></section>");
        }
        h.append("<footer><div class=\"fine\">Quartet QE ").append(esc(McpServer.version())).append(" · every number on this page comes from the build's own results and the audit log, never from a model.</div></footer>");
        h.append("</div>").append(boxes).append("</body></html>");
        return h.toString();
    }

    static final String CSS_FILE = "quartet-report.css";

    private static void tile(StringBuilder h, String value, String label, String tone) {
        h.append("<div class=\"tile\"><div class=\"num ").append(tone).append("\">").append(esc(value)).append("</div><div class=\"lbl\">").append(esc(label)).append("</div></div>");
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** The acts a test made: the audit names them Class.method, with @device when it ran on a device. */
    private static List<Act> actsFor(TestRunner.TestOutcome test, List<Act> acts) {
        String base = test.method().replaceAll(" \\[.*$", "");
        String prefix = simple(test.className()) + "." + base;
        String device = test.method().contains("[") ? test.method().substring(test.method().indexOf('[') + 1, test.method().lastIndexOf(']')) : null;
        return acts.stream().filter(a -> a.test().equals(prefix) || (device != null ? a.test().equals(prefix + "@" + device) : a.test().startsWith(prefix + "@"))).toList();
    }

    private static void actBlock(StringBuilder h, StringBuilder boxes, int[] counter, Act act) {
        String source = switch (act.source()) {
            case "REPLAYED" -> "Replayed";
            case "HANDED_OFF" -> "Handed to the AI";
            case "MISSED" -> "Recorded by the AI";
            default -> "Ran live";
        };
        String tone = act.source().equals("REPLAYED") ? "good" : act.source().equals("HANDED_OFF") ? "none" : "dim";
        h.append("<div class=\"act\"><div class=\"what\"><b>").append(esc(act.instruction())).append("</b><span class=\"chip ").append(tone).append("\">").append(source).append("</span></div>");
        if (!act.why().isBlank()) h.append("<p class=\"why\">Why: ").append(esc(act.why())).append("</p>");
        if (!act.trace().isEmpty()) {
            h.append("<details class=\"trace\"").append(act.shots().isEmpty() && act.trace().size() <= 3 ? " open" : "").append("><summary>").append(act.trace().size()).append(act.trace().size() == 1 ? " step" : " steps").append("</summary><ol>");
            for (Note note : act.trace()) {
                h.append("<li").append(note.error() ? " class=\"err\"" : "").append("><b>").append(esc(note.tool())).append("</b> ").append(esc(note.args()));
                if (!note.result().isBlank()) h.append(" → ").append(esc(note.result()));
                h.append("</li>");
            }
            h.append("</ol></details>");
        }
        if (!act.shots().isEmpty()) {
            h.append("<div class=\"shots\">");
            for (Shot shot : act.shots()) thumb(h, boxes, counter, shot.file(), shot.target(), shot.target().toLowerCase().contains("phone") || shot.target().toLowerCase().contains("android") || shot.target().toLowerCase().contains("app"));
            h.append("</div>");
        }
        h.append("</div>");
    }

    private static void thumb(StringBuilder h, StringBuilder boxes, int[] counter, String file, String label, boolean tall) {
        int id = ++counter[0];
        h.append("<a class=\"thumb").append(tall ? " tall" : "").append("\" href=\"#shot").append(id).append("\"><img loading=\"lazy\" src=\"").append(esc(file)).append("\" alt=\"")
                .append(esc(label)).append("\"><span>").append(esc(label)).append("</span></a>");
        boxes.append("<div class=\"lb\" id=\"shot").append(id).append("\"><a class=\"close\" href=\"#\">×</a><img loading=\"lazy\" src=\"").append(esc(file)).append("\" alt=\"").append(esc(label)).append("\"></div>");
    }

    public static void write(Data data, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, html(data), StandardCharsets.UTF_8);
        // Jenkins serves archived pages with a policy that ignores inline styles but allows a stylesheet from the same place.
        Files.writeString(out.resolveSibling(CSS_FILE), BrandedReport.css(), StandardCharsets.UTF_8);
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