package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.AgentLoop;
import io.quartetqe.core.Json;
import io.quartetqe.core.Llm;
import io.quartetqe.core.LlmFactory;
import io.quartetqe.runtime.Environment;
import io.quartetqe.runtime.ScreenInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Command line entry point. See README.md. */
public final class Main {
    private static final long CI_POLL_MS = 15_000;
    private static final long CI_TIMEOUT_MS = 3_600_000;

    private final Path root;
    private final JsonNode settings;
    private final ContextRepo context;
    private final Principles principles;
    private final Environment environment;
    private final Connectors connectors;
    private final String environmentPath;

    private Main(Path root, String environmentOverride) throws IOException {
        this.root = root;
        this.settings = Json.parseYaml(Files.readString(root.resolve("quartet.yaml")));
        this.context = new ContextRepo(root.resolve(settings.path("context").asText("context")));
        this.principles = Principles.load(context.root());
        this.environmentPath = environmentOverride != null ? environmentOverride : settings.path("environment").asText();
        this.environment = Environment.load(root.resolve(environmentPath), System.getenv());
        this.connectors = Connectors.load(root.resolve(settings.path("connectors").asText("connectors.yaml")));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        Map<String, String> options = options(args);
        int exit;
        try {
            exit = dispatch(args[0], options);
        } catch (Llm.LlmException | AgentLoop.LoopException | IllegalStateException | IllegalArgumentException e) {
            // Expected problems, such as a missing key or an unreachable service, are reported without a stack trace.
            System.out.println("Stopped  " + e.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    private static int dispatch(String command, Map<String, String> options) throws Exception {
        Main main = new Main(Path.of("").toAbsolutePath(), options.get("environment"));
        return switch (command) {
            case "run" -> main.run(options);
            case "publish" -> main.publish(options);
            case "repair" -> main.repair(options);
            case "check-story" -> main.checkStory(options);
            case "observe" -> main.observe(options);
            case "context-search" -> main.search(options);
            case "doctor" -> main.doctor();
            default -> {
                usage();
                yield 1;
            }
        };
    }

    private static void usage() {
        System.out.println("""
            Usage: quartet-qe <command>

              run --story <ref>          Write tests for a story, run them, and produce one page to review
                  [--prove]              Also plant bugs to prove the tests catch them
                  [--no-run]             Write the tests without running them
              publish --story <ref> --code <github|gitlab|ado> [--ci <jenkins|github|gitlab|ado>]
                                         Run, then open a pull request with the review as its description

              repair --file <Test.java> --selector <test filter>   Fix a brittle test without weakening it
              check-story --story <ref>  List questions about a story before writing tests
              observe --target <name> [--device <name>] [--path /]  Show what the agent sees on a screen
              context-search --query "text"
              doctor                     Check the AI, context and environment setup

            Story references: path/to/story.md, jira:KEY-1, ado:123, github:45, gitlab:7
            Any command accepts --environment <file>.""");
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
            options.put(args[i].substring(2), flag ? "true" : args[++i]);
        }
        return options;
    }

    private WorkItem story(String reference) {
        if (reference == null) throw new IllegalArgumentException("--story is required.");
        int split = reference.indexOf(':');
        if (split > 0 && !Files.exists(Path.of(reference))) {
            return connectors.workItems(reference.substring(0, split)).get(reference.substring(split + 1));
        }
        return WorkItem.fromFile(Path.of(reference));
    }

    private Path reportDir(WorkItem story) {
        return root.resolve(settings.path("reports").asText("reports")).resolve(story.id());
    }

    private Set<String> targets() {
        return new TreeSet<>(environment.targets().keySet());
    }

    private Path testSourceRoot() {
        return root.resolve(settings.path("tests").path("source").asText());
    }

    private TestRunner runner() throws IOException {
        return TestRunner.fromSettings(root).withEnvironment(Map.of("QUARTET_ENV", root.resolve(environmentPath).toString(), "QUARTET_MODE", "auto"));
    }

    private int checkStory(Map<String, String> options) throws IOException {
        WorkItem story = story(options.get("story"));
        List<Finding> findings = StoryReadiness.check(story, principles);
        Scorecard.writeStoryCheck(reportDir(story), story, findings);
        if (findings.isEmpty()) System.out.println("No questions about " + story.id() + ".");
        findings.forEach(f -> System.out.println((f.blocking() ? "Must fix  " : "Question  ") + f.location() + ": " + f.message()));
        return findings.stream().anyMatch(Finding::blocking) ? 2 : 0;
    }

    private record Outcome(WorkItem story, TestGenerator.Result generation, Scorecard.Summary summary) {}

    /** Context, story, tests, review. Every check runs inside, and only problems are shown. */
    private Outcome pipeline(WorkItem story, boolean run, boolean prove) throws IOException {
        Llm.Client llm = LlmFactory.fromEnv();
        System.out.printf("Context  %d files, %d engineering principles%n", context.list().size(), principles.names().size());
        System.out.printf("Story    %s: %s (%d acceptance criteria)%n", story.id(), story.title(), story.acceptanceCriteria().size());

        List<Finding> questions = StoryReadiness.check(story, principles);
        if (questions.stream().anyMatch(Finding::blocking)) {
            questions.stream().filter(Finding::blocking).forEach(f -> System.out.println("         " + f.message()));
            System.out.println("Stopped  The story cannot be tested yet.");
            return new Outcome(story, null, null);
        }

        TestRunner runner = runner();
        TestGenerator.Result result = new TestGenerator(llm, context, principles, targets(), connectors, testSourceRoot(), run ? runner : null).generate(story);
        String selector = "generated." + story.slug() + ".*";
        TestRunner.Result execution = run ? runner.run(selector, Map.of()) : null;
        if (execution != null && execution.tests().isEmpty()) System.out.println(execution.log());

        FaultEvaluator.Evaluation faults = null;
        if (prove) {
            try {
                faults = new FaultEvaluator(root.resolve(settings.path("faults").asText()), runner).evaluate(selector, new LinkedHashMap<>(result.files()));
            } catch (FaultEvaluator.BaselineFailed e) {
                System.out.println("         Bug-planting skipped: " + e.getMessage());
            }
        }

        Scorecard.Summary summary = Scorecard.write(reportDir(story), story, principles, questions, result, execution, faults);
        System.out.printf("Tests    %d written", summary.written());
        if (run) System.out.printf(": %d passed, %d failed, %d skipped", summary.passed(), summary.failed(), summary.skipped());
        if (faults != null) System.out.printf(". %.0f%% of planted bugs caught", faults.detectionRate() * 100);
        System.out.printf("%nReview   %s (%s)%n", root.relativize(reportDir(story).resolve("review.md")),
                summary.approved() ? "ready to merge" : summary.attention() + " item(s) need your attention");
        return new Outcome(story, result, summary);
    }

    private int run(Map<String, String> options) throws IOException {
        Outcome outcome = pipeline(story(options.get("story")), !options.containsKey("no-run"), options.containsKey("prove"));
        return outcome.summary() != null && outcome.summary().approved() ? 0 : 2;
    }

    private int repair(Map<String, String> options) throws IOException {
        Path file = Path.of(options.get("file"));
        BrittleRepair.Report report = new BrittleRepair(LlmFactory.fromEnv(), context, principles, new StaticRules(principles, context, targets()), runner())
                .repair(file, options.get("selector"));
        System.out.println("Diagnosis: " + report.diagnosis());
        report.before().forEach(f -> System.out.println("  found: " + f.message()));
        report.rejectedEdits().forEach(r -> System.out.println("  refused edit: " + r));
        switch (report.diagnosis()) {
            case POSSIBLE_PRODUCT_DEFECT -> System.out.println("The test was not changed. Raise this as a defect:\n" + report.detail());
            case ENVIRONMENT, MODEL_UNAVAILABLE -> System.out.println("The test was not changed. Fix the setup first:\n" + report.detail());
            case HEALTHY -> System.out.println("Nothing to repair.");
            default -> System.out.println(report.repaired() ? "Repaired, and passed 3 runs in a row." : report.detail());
        }
        return report.repaired() || report.diagnosis() == BrittleRepair.Diagnosis.HEALTHY ? 0 : 2;
    }

    /** Opens a pull request in the tests repo, with the review page as its description. */
    private int publish(Map<String, String> options) throws Exception {
        String reference = options.get("story");
        WorkItem story = story(reference);
        Outcome outcome = pipeline(story, true, options.containsKey("prove"));
        if (outcome.generation() == null) return 2;

        String review = Files.readString(reportDir(story).resolve("review.md"));
        String branch = "tests/" + story.slug();
        String base = options.getOrDefault("base", "main");
        Map<String, String> files = new LinkedHashMap<>();
        String prefix = root.relativize(testSourceRoot()) + "/generated/" + story.slug() + "/";
        outcome.generation().files().forEach((name, content) -> files.put(prefix + name, content));

        Connectors.CodeHost code = connectors.codeHost(options.get("code"));
        code.commitToNewBranch(branch, base, files, "Add end-to-end tests for " + story.id());
        String pullRequest = code.openPullRequest(branch, base, "Add end-to-end tests for " + story.id(), review);
        System.out.println("Pull request  " + pullRequest);

        String ciState = "not triggered";
        if (options.containsKey("ci")) {
            Connectors.Ci ci = connectors.ci(options.get("ci"));
            String run = ci.trigger(branch, Map.of("STORY", story.id()));
            long deadline = System.currentTimeMillis() + CI_TIMEOUT_MS;
            Connectors.CiStatus status = ci.status(run);
            while (!status.finished() && System.currentTimeMillis() < deadline) {
                Thread.sleep(CI_POLL_MS);
                status = ci.status(run);
            }
            ciState = status.state();
            System.out.println("CI            " + status.state() + " " + status.url());
        }
        int split = reference.indexOf(':');
        if (split > 0) {
            connectors.workItems(reference.substring(0, split)).comment(reference.substring(split + 1),
                    "End-to-end tests are ready for review: " + pullRequest + ". CI: " + ciState + ".");
        }
        return outcome.summary().approved() ? 0 : 2;
    }

    private int observe(Map<String, String> options) throws Exception {
        System.out.println(ScreenInspector.describe(environment, options.get("target"), options.get("device"), options.getOrDefault("path", "/")));
        return 0;
    }

    private int search(Map<String, String> options) {
        context.search(options.get("query"), 5).forEach(hit -> System.out.printf("%.2f %s%n%s%n---%n", hit.score(), hit.path(), hit.text()));
        return 0;
    }

    private int doctor() {
        System.out.println("Context      " + context.list().size() + " files, " + principles.names().size() + " principles");
        System.out.println("Environment  " + environment.name() + ", targets " + targets());
        try {
            System.out.println("AI           " + LlmFactory.fromEnv().model());
        } catch (Llm.LlmException e) {
            System.out.println("AI           " + e.getMessage());
            return 1;
        }
        return 0;
    }
}
