package io.quartetqe.generator;

import io.quartetqe.generator.JavaInspector.ActCall;
import io.quartetqe.generator.JavaInspector.TestFile;
import io.quartetqe.generator.JavaInspector.TestMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks written tests against the engineering principles. Each rule is defined in principles.yaml. */
public final class StaticRules {
    private static final Pattern MECHANICS = Pattern.compile("(?i)(#[a-z][\\w-]*\\b|\\bxpath\\b|data-testid|\\.css\\b|\\bselect\\s+.+\\s+from\\b|https?://)");
    private static final Pattern HOSTS = Pattern.compile("\"[^\"]*(https?://|localhost|127\\.0\\.0\\.1|\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b)[^\"]*\"");
    private static final Pattern PII = Pattern.compile("[\\w.+-]+@(?!example\\.)[\\w-]+\\.[\\w.]+|\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b|\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern SECRET = Pattern.compile("(?i)(password\\s*=|bearer\\s+[a-z0-9._-]{8,}|api[_-]?key\\s*[=:]|AKIA[0-9A-Z]{16}|\\bsk-[a-zA-Z0-9_-]{16,})");
    private static final Pattern SHOULD_BE_KNOWN = Pattern.compile("(/api/[a-z0-9/_-]+|\\b[a-z]+\\.[a-z]+\\.[a-z]+\\b|\\bSKU-[A-Z0-9]+\\b)");
    private static final Set<String> WEAK = Set.of("isNotNull", "isNotEmpty", "isNotBlank", "isNotEqualTo");

    private final Principles principles;
    private final ContextRepo context;
    private final Set<String> targets;

    public StaticRules(Principles principles, ContextRepo context, Set<String> targets) {
        this.principles = principles;
        this.context = context;
        this.targets = targets;
    }

    private void add(List<Finding> out, String rule, String location, String message) {
        principles.finding(rule, location, message).ifPresent(out::add);
    }

    /** Parses and compiles the files, then applies every rule. */
    public List<Finding> check(Map<String, String> sources, WorkItem story) {
        List<Finding> out = new ArrayList<>();
        for (String error : JavaInspector.compile(sources)) add(out, "AUTO-COMPILE", "compiler", error);

        List<TestFile> files = new ArrayList<>();
        sources.forEach((path, source) -> files.add(JavaInspector.inspect(path, source)));

        // Reusable steps keep tests small, so a test is judged by everything it does, including the steps it calls.
        Map<String, JavaInspector.HelperMethod> helpers = new HashMap<>();
        files.forEach(file -> file.helpers().forEach((name, method) -> helpers.merge(name, method, (a, b) -> {
            List<ActCall> acts = new ArrayList<>(a.acts());
            acts.addAll(b.acts());
            List<String> calls = new ArrayList<>(a.calls());
            calls.addAll(b.calls());
            return new JavaInspector.HelperMethod(acts, calls);
        })));
        List<TestFile> resolved = files.stream().map(file -> withResolvedTests(file, helpers)).toList();

        int index = 0;
        for (String source : sources.values()) {
            checkFile(files.get(index), resolved.get(index), source, out);
            index++;
        }
        if (story != null) checkStory(resolved, files, story, out);
        return out;
    }

    private static TestFile withResolvedTests(TestFile file, Map<String, JavaInspector.HelperMethod> helpers) {
        List<TestMethod> tests = file.tests().stream().map(test -> resolve(test, helpers)).toList();
        return new TestFile(file.path(), file.className(), file.packageName(), file.hasStory(), file.story(), file.hasExtension(), file.ordered(),
                file.mutableStatics(), tests, file.helperActs(), file.helpers());
    }

    /** Adds the acts of every step a test calls, however deeply the steps call each other. */
    private static TestMethod resolve(TestMethod test, Map<String, JavaInspector.HelperMethod> helpers) {
        List<ActCall> acts = new ArrayList<>(test.acts());
        int stepCalls = 0;
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (String call : test.calls()) {
            if (helpers.containsKey(call)) {
                stepCalls++;
                queue.add(call);
            }
        }
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty() && visited.size() < 100) {
            String name = queue.poll();
            if (!visited.add(name)) continue;
            JavaInspector.HelperMethod helper = helpers.get(name);
            acts.addAll(helper.acts());
            helper.calls().stream().filter(helpers::containsKey).forEach(queue::add);
        }
        return new TestMethod(test.name(), test.criteria(), test.skeleton(), test.disabled(), test.hasTry(), test.sleeps(), test.assumes(), acts,
                test.assertions(), test.line(), test.lastActPosition(), test.lastAssertPosition(), test.calls(), test.acts().size() + stepCalls);
    }

    private void checkFile(TestFile file, TestFile resolved, String source, List<Finding> out) {
        String path = file.path();
        // A class with no tests is a steps class: reusable steps shared by tests. It needs no story wiring.
        if (!file.tests().isEmpty() && (!file.hasStory() || !file.hasExtension())) {
            add(out, "AUTO-WIRING", path, "The class needs @Story and @ExtendWith(AgentExtension.class).");
        }
        if (file.ordered()) add(out, "DECOUPLE-ISOLATED", path, "Tests must not rely on execution order.");
        file.mutableStatics().forEach(name -> add(out, "DECOUPLE-ISOLATED", path, "Mutable static field '" + name + "' shares state between tests."));

        scan(HOSTS, source, match -> add(out, "PROD-ENV-AGNOSTIC", path, "Hard-coded address " + match + ". Use a named target."));
        scan(PII, source, match -> add(out, "DATA-NO-PII", path, "Real-looking personal data. Use context/test-data."));
        scan(SECRET, source, match -> add(out, "SEC-NO-SECRETS", path, "Possible secret in test code. Reference it through the environment file."));
        if (source.matches("(?s).*\\b(TODO|FIXME)\\b.*")) add(out, "AUTO-NO-MANUAL", path, "Leftover TODO or FIXME.");

        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < file.tests().size(); i++) {
            checkTest(path, resolved.tests().get(i), file.tests().get(i), out);
            // Repetition is judged on what a test writes itself. Acts reached through a shared step are reuse, not repetition.
            file.tests().get(i).acts().forEach(act -> seen.merge(act.instruction(), 1, Integer::sum));
        }
        for (ActCall act : file.helperActs()) {
            checkAct(path, act, out);
            seen.merge(act.instruction(), 1, Integer::sum);
        }
        seen.forEach((instruction, count) -> {
            if (count >= 3) add(out, "REUSE-EMERGENT", path, "\"" + instruction + "\" repeats " + count + " times. Extract a helper.");
        });
    }

    /** test is what the test does including the steps it calls. own is only what is written in it, which is what its location refers to. */
    private void checkTest(String path, TestMethod test, TestMethod own, List<Finding> out) {
        String at = path + ":" + test.line() + " " + test.name();
        if (test.disabled()) add(out, "AUTO-NO-MANUAL", at, "Disabled tests provide no protection.");
        if (test.sleeps()) add(out, "PERF-NO-SLEEP", at, "Thread.sleep makes tests slow and flaky. Ask the agent to wait for the outcome.");
        if (test.hasTry()) add(out, "ASSERT-NO-SWALLOW", at, "try blocks can hide failures.");
        if (test.criteria().isEmpty()) add(out, "COVER-TRACE", at, "Add @AcceptanceCriteria.");
        if (test.criteria().size() > 2) add(out, "SIMPLE-FOCUS", at, "Test covers " + test.criteria().size() + " criteria. Keep one purpose per test.");
        if (test.steps() > 8) add(out, "SIMPLE-FOCUS", at, "Test has " + test.steps() + " steps. Split it, or move a run of steps into a reusable step.");

        boolean hasVerify = test.acts().stream().anyMatch(a -> a.kind().equals("verify"));
        if (test.assertions().isEmpty() && !hasVerify) add(out, "ASSERT-ORACLE", at, "The test has no assertion.");
        if (test.assertions().isEmpty() && hasVerify) add(out, "ASSERT-DETERMINISTIC", at, "Pair verify with at least one exact assertion.");
        if (!test.assertions().isEmpty() && test.assertions().stream().allMatch(a -> WEAK.contains(a.matcher()))) {
            add(out, "ASSERT-STRENGTH", at, "All assertions are weak existence checks. Assert the expected value.");
        }
        if (test.lastActPosition() > test.lastAssertPosition()) add(out, "ASSERT-ORACLE", at, "The final step is never checked.");

        // Acts reached through a step are checked once, in the file that holds the step, so a finding points at the right line.
        for (ActCall act : own.acts()) checkAct(path, act, out);
    }

    private void checkAct(String path, ActCall act, List<Finding> out) {
        String at = path + ":" + act.line();
        String instruction = act.instruction();
        if (MECHANICS.matcher(instruction).find()) add(out, "EVOL-INTENT", at, "State the intent. Selectors, SQL and URLs belong to the agent: \"" + instruction + "\"");
        if (instruction.matches("(?i).*\\b(and then|then also|after that)\\b.*") || instruction.split("\\s+").length > 30) {
            add(out, "SIMPLE-ONE-OUTCOME", at, "One act should have one outcome: \"" + instruction + "\"");
        }
        if (act.on().isEmpty()) add(out, "OBS-WHY", at, "Every act needs on(...).");
        if (act.on().size() > 1) add(out, "DECOUPLE-ONE-TARGET", at, "Act spans several systems. Split it so a failure points to one.");
        act.on().forEach(target -> {
            if (!targets.contains(target)) add(out, "GROUND-KNOWN", at, "Unknown target '" + target + "'. Known: " + targets);
        });
        // A step may take its reason as a parameter, why(reason). That cannot be read here, so the caller's own text is trusted.
        boolean dynamicWhy = JavaInspector.DYNAMIC.equals(act.why());
        if (act.why() == null || (!dynamicWhy && (act.why().split("\\s+").length < 4 || act.why().equalsIgnoreCase(instruction)))) {
            add(out, "OBS-WHY", at, "why(...) must give the business reason in at least four words.");
        }
        Matcher known = SHOULD_BE_KNOWN.matcher(instruction);
        while (known.find()) {
            if (!context.mentions(known.group(1))) add(out, "GROUND-KNOWN", at, "'" + known.group(1) + "' does not appear in the context repo.");
        }
    }

    private void checkStory(List<TestFile> files, List<TestFile> written, WorkItem story, List<Finding> out) {
        List<TestMethod> tests = files.stream().flatMap(f -> f.tests().stream()).toList();
        Set<String> covered = new HashSet<>();
        tests.forEach(t -> covered.addAll(t.criteria()));
        for (String id : covered) {
            if (!story.acceptanceCriteria().containsKey(id)) add(out, "COVER-TRACE", story.id(), "Tests cite " + id + ", which is not in the story.");
        }

        List<TestMethod> skeletons = tests.stream().filter(TestMethod::skeleton).toList();
        if (skeletons.size() != 1) {
            add(out, "PROD-WALKING-SKELETON", story.id(), "Expected one @WalkingSkeleton test, found " + skeletons.size() + ".");
        } else {
            Set<String> layers = new HashSet<>();
            skeletons.get(0).acts().forEach(act -> layers.addAll(act.on()));
            // It cannot cross more layers than the product has. A standalone app has one target, so one is enough there.
            int wanted = Math.min(2, Math.max(1, targets.size()));
            if (layers.size() < wanted) add(out, "PROD-WALKING-SKELETON", skeletons.get(0).name(), "The skeleton should cross at least two layers.");
        }

        // The same act written in several test classes belongs in a reusable step, so one recording serves them all.
        Map<String, Set<String>> writtenIn = new HashMap<>();
        for (TestFile file : written) {
            if (file.tests().isEmpty()) continue;
            file.tests().forEach(test -> test.acts().forEach(act -> writtenIn.computeIfAbsent(act.instruction(), k -> new java.util.TreeSet<>()).add(file.className())));
        }
        writtenIn.forEach((instruction, classes) -> {
            if (classes.size() >= 2) add(out, "REUSE-EMERGENT", story.id(), "\"" + instruction + "\" is written in " + classes + ". Move it into a reusable step (a steps class) and call it from each.");
        });

        List<ActCall> acts = new ArrayList<>(tests.stream().flatMap(t -> t.acts().stream()).toList());
        files.forEach(file -> acts.addAll(file.helperActs()));
        boolean observes = acts.stream().anyMatch(a -> a.instruction().toLowerCase().matches(".*\\b(metric|metrics|counter|telemetry)\\b.*"));
        if (!observes && context.list().stream().anyMatch(p -> p.startsWith("telemetry/"))) {
            add(out, "OBS-TELEMETRY", story.id(), "No test checks the service telemetry listed in the context repo.");
        }
        boolean latency = tests.stream().flatMap(t -> t.assertions().stream()).anyMatch(a -> a.text().contains("durationMs"));
        if (!latency) add(out, "PERF-SLO", story.id(), "No test compares latency with the service objective.");
    }

    /** Which criteria each test method proves, keyed by method name. */
    public static Map<String, List<String>> criteriaByTest(Map<String, String> sources) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        sources.forEach((path, source) -> JavaInspector.inspect(path, source).tests().forEach(t -> out.put(t.name(), t.criteria())));
        return out;
    }

    private interface OnMatch {
        void accept(String match);
    }

    private static void scan(Pattern pattern, String source, OnMatch onMatch) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) onMatch.accept(matcher.group());
    }
}
