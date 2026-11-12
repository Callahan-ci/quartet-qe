package io.quartetqe.generator;

import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Runs tests through the team's own build and reads the JUnit XML it writes. */
public final class TestRunner {
    public record TestOutcome(String className, String method, boolean passed, String failure, boolean skipped,
                              double seconds) {
        public TestOutcome(String className, String method, boolean passed, String failure) {
            this(className, method, passed, failure, false, 0);
        }

        public TestOutcome(String className, String method, boolean passed, String failure, boolean skipped) {
            this(className, method, passed, failure, skipped, 0);
        }
    }

    public record Result(int exitCode, List<TestOutcome> tests, String log) {
        public boolean allPassed() {
            List<TestOutcome> ran = tests.stream().filter(t -> !t.skipped()).toList();
            return exitCode == 0 && !ran.isEmpty() && ran.stream().allMatch(TestOutcome::passed);
        }

        public List<TestOutcome> failures() {
            return tests.stream().filter(t -> !t.passed() && !t.skipped()).toList();
        }
    }

    private final Path projectRoot;
    private final List<String> command;
    private final Path resultsDir;
    private final Map<String, String> baseEnvironment = new java.util.HashMap<>();

    public TestRunner(Path projectRoot, List<String> command, Path resultsDir) {
        this.projectRoot = projectRoot;
        this.command = command;
        this.resultsDir = resultsDir;
    }

    /** Environment variables applied to every run. */
    public TestRunner withEnvironment(Map<String, String> values) {
        baseEnvironment.putAll(values);
        return this;
    }

    public static TestRunner fromSettings(Path projectRoot) throws IOException {
        var settings = Json.parseYaml(Files.readString(projectRoot.resolve("quartet.yaml"))).path("tests");
        List<String> command = new ArrayList<>();
        settings.path("command").forEach(part -> command.add(part.asText()));
        return new TestRunner(projectRoot, command, projectRoot.resolve(settings.path("results").asText()));
    }

    public Result run(String selector, Map<String, String> env) {
        try {
            if (Files.isDirectory(resultsDir)) {
                try (Stream<Path> old = Files.list(resultsDir)) {
                    old.filter(p -> p.toString().endsWith(".xml")).forEach(p -> p.toFile().delete());
                }
            }
            List<String> resolved = command.stream().map(part -> part.replace("{selector}", selector)).toList();
            ProcessBuilder builder = new ProcessBuilder(resolved).directory(projectRoot.toFile()).redirectErrorStream(true);
            builder.environment().putAll(baseEnvironment);
            builder.environment().putAll(env);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new Result(exit, readResults(), output.length() > 6000 ? output.substring(output.length() - 6000) : output);
        } catch (IOException e) {
            throw new IllegalStateException("Could not run tests: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Reads every TEST-*.xml under a results directory, as the build wrote it. */
    public static List<TestOutcome> readResults(Path resultsDir) {
        List<TestOutcome> outcomes = new ArrayList<>();
        if (!Files.isDirectory(resultsDir)) return outcomes;
        try (Stream<Path> files = Files.list(resultsDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                NodeList cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element testCase = (Element) cases.item(i);
                    String name = testCase.getAttribute("name").replaceAll("\\(.*\\)$", "");
                    double seconds = testCase.getAttribute("time").isBlank()
                            ? 0 : Double.parseDouble(testCase.getAttribute("time"));
                    if (testCase.getElementsByTagName("skipped").getLength() > 0) {
                        outcomes.add(new TestOutcome(testCase.getAttribute("classname"), name, false, null, true, seconds));
                        continue;
                    }
                    NodeList failures = testCase.getElementsByTagName("failure");
                    String failure = failures.getLength() == 0 ? null : failures.item(0).getTextContent();
                    outcomes.add(new TestOutcome(testCase.getAttribute("classname"), name, failure == null, failure, false, seconds));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read test results: " + e.getMessage(), e);
        }
        return outcomes;
    }

    private List<TestOutcome> readResults() {
        return readResults(resultsDir);
    }
}
