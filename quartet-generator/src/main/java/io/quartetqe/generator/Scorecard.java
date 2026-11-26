package io.quartetqe.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the one page a person reviews: what needs attention first, what was tested, then the detail. */
public final class Scorecard {
    public record Summary(boolean approved, int written, long passed, long failed, long skipped, int attention) {}

    private Scorecard() {}

    public static Summary write(Path directory, WorkItem story, Principles principles, List<Finding> storyQuestions,
                                TestGenerator.Result generation, TestRunner.Result run, FaultEvaluator.Evaluation faults) throws IOException {
        Files.createDirectories(directory);
        List<Finding> all = new ArrayList<>(storyQuestions);
        all.addAll(generation.staticFindings());
        all.addAll(generation.reviewFindings());
        all.addAll(generation.auditFindings());

        List<String> attention = new ArrayList<>();
        if (run != null) {
            run.failures().forEach(f -> attention.add("**Failed:** `" + f.method() + "`: " + firstLines(f.failure())));
            if (run.tests().isEmpty()) attention.add("**No tests ran.** Check the build output.");
        }
        all.stream().filter(Finding::blocking).forEach(f -> attention.add("**" + f.principle() + ":** " + f.message()));
        generation.notCovered().forEach(row -> attention.add("**Not covered:** " + row.path("ac").asText() + ". " + row.path("reason").asText()));
        if (faults != null && !faults.passed()) {
            faults.faults().stream().filter(f -> !f.good()).forEach(f -> attention.add(
                    f.benign() ? "**False alarm:** a harmless change failed a test: " + f.description() : "**Missed bug:** " + f.description()));
        }
        boolean approved = attention.isEmpty();
        // Findings that do not block merging still deserve a look, so they are listed here, not left in the collapsed table.
        List<String> notes = new ArrayList<>();
        all.stream().filter(f -> !f.blocking() && !storyQuestions.contains(f))
                .map(f -> "**" + f.principle() + ":** " + f.message()).distinct().forEach(notes::add);

        Map<String, List<String>> criteriaByTest = StaticRules.criteriaByTest(generation.files());
        StringBuilder md = new StringBuilder("# Review: ").append(story.id()).append(' ').append(story.title()).append("\n\n");
        md.append(approved ? "**Ready to merge.**" : "**Needs your attention.**").append("\n\n## Needs your attention\n\n");
        if (attention.isEmpty()) md.append(notes.isEmpty() ? "Nothing.\n" : "Nothing blocks merging.\n");
        attention.forEach(item -> md.append("- ").append(item).append('\n'));
        if (!notes.isEmpty()) {
            md.append("\n**Worth a look** (does not block merging):\n\n");
            notes.forEach(item -> md.append("- ").append(item).append('\n'));
        }

        md.append("\n## What was tested\n\n| Acceptance criterion | Test | Result |\n| --- | --- | --- |\n");
        for (var criterion : story.acceptanceCriteria().entrySet()) {
            List<String> tests = criteriaByTest.entrySet().stream().filter(e -> e.getValue().contains(criterion.getKey())).map(Map.Entry::getKey).toList();
            if (tests.isEmpty()) md.append("| ").append(criterion.getKey()).append(": ").append(cell(criterion.getValue())).append(" | None | Not covered |\n");
            for (String test : tests) {
                md.append("| ").append(criterion.getKey()).append(": ").append(cell(criterion.getValue())).append(" | `").append(test).append("` | ")
                        .append(run == null ? "Not run" : results(test, run)).append(" |\n");
            }
        }

        List<Finding> questions = storyQuestions.stream().filter(f -> !f.blocking()).toList();
        if (!questions.isEmpty()) {
            md.append("\n## Questions for the story author\n\n");
            questions.forEach(f -> md.append("- ").append(f.location()).append(": ").append(f.message()).append('\n'));
        }

        md.append("\n<details>\n<summary>How this was checked</summary>\n\n| Engineering principle | Status | Notes |\n| --- | --- | --- |\n");
        ObjectNode json = Json.obj().put("story", story.id()).put("approved", approved);
        for (String name : principles.names()) {
            List<Finding> matching = all.stream().filter(f -> f.principle().equals(name)).toList();
            String status = matching.stream().anyMatch(Finding::blocking) ? "FAIL" : matching.isEmpty() ? "PASS" : "NOTE";
            md.append("| ").append(name).append(" | ").append(status).append(" | ")
                    .append(matching.isEmpty() ? "" : String.join("<br>", matching.stream().map(f -> cell(f.message())).toList())).append(" |\n");
        }
        md.append("\n").append(aiUse(generation.turns(), generation.inputTokens() + generation.outputTokens()))
                .append(" Its own checks sent back ").append(generation.selfCorrections()).append(" draft(s) before review.\n");
        if (faults != null) {
            md.append(String.format("%nBugs were planted to prove the tests catch them: %.0f%% caught, %d false alarm(s).%n%n", faults.detectionRate() * 100, faults.falseAlarms()));
            md.append("| Planted change | Result |\n| --- | --- |\n");
            for (FaultEvaluator.FaultResult fault : faults.faults()) {
                md.append("| ").append(fault.description()).append(" | ")
                        .append(fault.benign() ? (fault.detected() ? "FALSE ALARM" : "Tests still passed, as they should") : (fault.detected() ? "Caught" : "MISSED")).append(" |\n");
            }
            json.put("detectionRate", faults.detectionRate()).put("falseAlarms", faults.falseAlarms());
        }
        md.append("\n</details>\n");

        long passed = run == null ? 0 : run.tests().stream().filter(TestRunner.TestOutcome::passed).count();
        long skipped = run == null ? 0 : run.tests().stream().filter(TestRunner.TestOutcome::skipped).count();
        long failed = run == null ? 0 : run.failures().size();
        json.put("passed", passed).put("failed", failed).put("skipped", skipped);
        Files.writeString(directory.resolve("review.md"), md.toString());
        Files.writeString(directory.resolve("review.html"), BrandedReport.render(model(story, approved, attention, notes, criteriaByTest, run, principles, all, questions,
                faults, generation, passed, failed, skipped)));
        Files.writeString(directory.resolve("review.json"), Json.pretty(json) + "\n");
        return new Summary(approved, criteriaByTest.size(), passed, failed, skipped, attention.size());
    }

    /** The same facts as the markdown, shaped for the branded page. */
    /**
     * How much the AI was used. On the chat path the AI in the user's chat tool did the work, so its tokens are
     * counted there, not here: saying "0 tokens" would read like a fault.
     */
    static String aiUse(int turns, long tokens) {
        if (turns > 0 && tokens == 0) {
            return "The AI in your chat tool took " + turns + " model turn" + (turns == 1 ? "" : "s") + "; your chat tool counts its tokens.";
        }
        return "The AI used " + turns + " model turns and " + tokens + " tokens.";
    }

    private static BrandedReport.Model model(WorkItem story, boolean approved, List<String> attention, List<String> notes, Map<String, List<String>> criteriaByTest,
                                             TestRunner.Result run, Principles principles, List<Finding> all, List<Finding> questions,
                                             FaultEvaluator.Evaluation faults, TestGenerator.Result generation, long passed, long failed, long skipped) {
        List<BrandedReport.Criterion> criteria = new ArrayList<>();
        for (var criterion : story.acceptanceCriteria().entrySet()) {
            List<BrandedReport.Test> tests = new ArrayList<>();
            criteriaByTest.entrySet().stream().filter(e -> e.getValue().contains(criterion.getKey())).map(Map.Entry::getKey)
                    .forEach(test -> tests.addAll(outcomes(test, run)));
            criteria.add(new BrandedReport.Criterion(criterion.getKey(), criterion.getValue(), tests));
        }
        List<BrandedReport.Principle> checks = new ArrayList<>();
        for (String name : principles.names()) {
            List<Finding> matching = all.stream().filter(f -> f.principle().equals(name)).toList();
            String status = matching.stream().anyMatch(Finding::blocking) ? "FAIL" : matching.isEmpty() ? "PASS" : "NOTE";
            checks.add(new BrandedReport.Principle(name, status, matching.stream().map(Finding::message).toList()));
        }
        List<BrandedReport.Fault> planted = null;
        if (faults != null) {
            planted = faults.faults().stream().map(f -> new BrandedReport.Fault(f.description(),
                    f.benign() ? (f.detected() ? "FALSE ALARM" : "OK") : (f.detected() ? "CAUGHT" : "MISSED"))).toList();
        }
        double seconds = run == null ? 0 : run.tests().stream().mapToDouble(TestRunner.TestOutcome::seconds).sum();
        return new BrandedReport.Model(story.id(), story.title(), approved, attention, notes, criteria, checks,
                questions.stream().filter(f -> !f.blocking()).map(f -> f.location() + ": " + f.message()).toList(), planted,
                faults == null ? 0 : faults.detectionRate(), faults == null ? 0 : faults.falseAlarms(),
                generation.turns(), generation.inputTokens() + generation.outputTokens(), generation.selfCorrections(),
                run != null, (int) passed, (int) failed, (int) skipped, seconds);
    }

    /** One row per run of a test, with the device it ran on when it ran on several. */
    private static List<BrandedReport.Test> outcomes(String test, TestRunner.Result run) {
        List<BrandedReport.Test> rows = new ArrayList<>();
        if (run == null) return List.of(new BrandedReport.Test(test, "", "NOT_RUN", 0, null));
        for (TestRunner.TestOutcome outcome : run.tests()) {
            String name = outcome.method();
            if (!name.equals(test) && !name.startsWith(test + " [")) continue;
            String device = name.contains(" [") ? name.substring(name.indexOf('[') + 1, name.length() - 1) : "";
            rows.add(new BrandedReport.Test(test, device, outcome.skipped() ? "SKIPPED" : outcome.passed() ? "PASSED" : "FAILED",
                    outcome.seconds(), outcome.passed() || outcome.skipped() ? null : firstLines(outcome.failure()).replace("\\|", "|")));
        }
        return rows.isEmpty() ? List.of(new BrandedReport.Test(test, "", "NOT_RUN", 0, null)) : rows;
    }

    /** Results for one test, grouped by outcome, with device names where it ran on several. */
    private static String results(String test, TestRunner.Result run) {
        Map<String, List<String>> byOutcome = new LinkedHashMap<>();
        for (TestRunner.TestOutcome outcome : run.tests()) {
            String name = outcome.method();
            if (!name.equals(test) && !name.startsWith(test + " [")) continue;
            String device = name.contains(" [") ? name.substring(name.indexOf('[') + 1, name.length() - 1) : "";
            byOutcome.computeIfAbsent(outcome.skipped() ? "Skipped" : outcome.passed() ? "Passed" : "**Failed**", k -> new ArrayList<>()).add(device);
        }
        if (byOutcome.isEmpty()) return "Not run";
        List<String> parts = new ArrayList<>();
        byOutcome.forEach((outcome, devices) -> {
            List<String> named = devices.stream().filter(d -> !d.isEmpty()).toList();
            parts.add(outcome + (named.isEmpty() ? "" : " (" + String.join(", ", named) + ")"));
        });
        return String.join("<br>", parts);
    }

    private static String firstLines(String failure) {
        if (failure == null) return "";
        String text = failure.replaceFirst("^[\\w.$]+(Error|Exception):\\s*", "").split("\n\tat ")[0].replaceAll("\\s+", " ").trim();
        return cell(text.length() > 220 ? text.substring(0, 220) + "..." : text);
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    public static void writeStoryCheck(Path directory, WorkItem story, List<Finding> findings) throws IOException {
        Files.createDirectories(directory);
        StringBuilder md = new StringBuilder("# Story check: ").append(story.id()).append("\n\n");
        if (findings.isEmpty()) md.append("No questions.\n");
        findings.forEach(f -> md.append("- ").append(f.blocking() ? "**Must fix:** " : "").append(f.location()).append(": ").append(f.message()).append('\n'));
        Files.writeString(directory.resolve("story-check.md"), md.toString());
    }
}
