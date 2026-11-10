package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.HttpJson;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures whether the tests can catch bugs, by planting faults and running them.
 *
 * A harmful fault must make the expected criterion fail. A benign change must not fail any test.
 */
public final class FaultEvaluator {
    public record FaultResult(String id, String description, boolean benign, boolean detected, List<String> expected, List<String> failedTests) {
        public boolean good() {
            return benign ? !detected : detected;
        }
    }

    public record Evaluation(List<FaultResult> faults, double detectionRate, int falseAlarms, double threshold) {
        public boolean passed() {
            return detectionRate >= threshold && falseAlarms == 0;
        }
    }

    public static final class BaselineFailed extends RuntimeException {
        BaselineFailed(String message) {
            super(message);
        }
    }

    private final HttpJson http = new HttpJson();
    private final JsonNode config;
    private final TestRunner runner;

    public FaultEvaluator(Path configFile, TestRunner runner) throws IOException {
        this.config = Json.parseYaml(Files.readString(configFile));
        this.runner = runner;
    }

    public Evaluation evaluate(String selector, Map<String, String> sources) throws IOException {
        Map<String, List<String>> criteria = StaticRules.criteriaByTest(sources);
        // A test that already fails proves nothing about a planted bug, so a clean baseline is required.
        control("none");
        TestRunner.Result baseline = runner.run(selector, Map.of("QUARTET_MODE", "replay"));
        if (!baseline.allPassed()) {
            throw new BaselineFailed("Fault evaluation needs every test to pass in replay first. Failing: "
                    + baseline.failures().stream().map(TestRunner.TestOutcome::method).toList());
        }

        List<FaultResult> results = new ArrayList<>();
        for (JsonNode fault : config.path("faults")) {
            boolean benign = fault.path("benign").asBoolean(false);
            Path temporaryCache = benign ? copyCache() : null;
            control(fault.path("id").asText());
            TestRunner.Result run;
            try {
                // Harmful faults run replay-only: no model can mask a detection. Benign changes may heal.
                run = runner.run(selector, benign
                        ? Map.of("QUARTET_MODE", "auto", "QUARTET_RECORDINGS", temporaryCache.toString())
                        : Map.of("QUARTET_MODE", "replay"));
            } finally {
                control("none");
            }
            List<String> expected = new ArrayList<>();
            fault.path("expectedToFail").forEach(ac -> expected.add(ac.asText()));
            List<String> failed = run.failures().stream().map(TestRunner.TestOutcome::method).toList();
            if (run.tests().stream().allMatch(TestRunner.TestOutcome::skipped)) throw new IllegalStateException("No test results for fault " + fault.path("id").asText() + ":\n" + run.log());
            boolean detected = benign ? !failed.isEmpty()
                    : failed.stream().anyMatch(method -> criteria.getOrDefault(method.replaceAll(" \\[.*]$", ""), List.of()).stream().anyMatch(expected::contains));
            results.add(new FaultResult(fault.path("id").asText(), fault.path("description").asText(), benign, detected, expected, failed));
        }
        List<FaultResult> harmful = results.stream().filter(f -> !f.benign()).toList();
        double rate = harmful.isEmpty() ? 0 : harmful.stream().filter(FaultResult::detected).count() / (double) harmful.size();
        int falseAlarms = (int) results.stream().filter(f -> f.benign() && f.detected()).count();
        return new Evaluation(results, rate, falseAlarms, config.path("threshold").asDouble(0.8));
    }

    private void control(String fault) {
        JsonNode control = config.path("control");
        HttpJson.Resp response = http.send("POST", control.path("url").asText(), Map.of("content-type", "application/json"),
                Json.obj().put("fault", fault).toString(), Duration.ofSeconds(10));
        if (!response.ok()) throw new IllegalStateException("Could not set fault '" + fault + "': HTTP " + response.status());
    }

    private Path copyCache() throws IOException {
        Path source = Path.of(config.path("cacheDir").asText()).toAbsolutePath();
        Path target = Files.createTempDirectory("agent-cache-benign");
        if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else Files.copy(path, destination);
                }
            }
        }
        return target;
    }
}
