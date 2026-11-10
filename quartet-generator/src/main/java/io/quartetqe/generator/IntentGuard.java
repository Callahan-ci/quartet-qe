package io.quartetqe.generator;

import io.quartetqe.generator.JavaInspector.TestFile;
import io.quartetqe.generator.JavaInspector.TestMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stops a repair from making a test pass by proving less.
 *
 * How a test acts may change. What it proves may not, unless the context repo documents a new expected value.
 */
public final class IntentGuard {
    public record Evidence(String oldValue, String newValue, String contextPath) {}

    private final ContextRepo context;

    public IntentGuard(ContextRepo context) {
        this.context = context;
    }

    /** Returns the reasons this edit weakens the test. Empty means it is allowed. */
    public List<String> violations(String path, String before, String after, List<Evidence> evidence) {
        TestFile old = JavaInspector.inspect(path, before);
        TestFile next = JavaInspector.inspect(path, after);
        Map<String, TestMethod> nextByName = next.tests().stream().collect(Collectors.toMap(TestMethod::name, t -> t, (a, b) -> a));
        List<String> problems = new ArrayList<>();

        for (TestMethod original : old.tests()) {
            TestMethod updated = nextByName.get(original.name());
            if (updated == null) {
                problems.add("Test '" + original.name() + "' was removed or renamed.");
                continue;
            }
            if (!updated.criteria().containsAll(original.criteria())) problems.add(original.name() + " no longer covers " + original.criteria() + ".");
            if (updated.assertions().size() < original.assertions().size()) {
                problems.add(original.name() + " went from " + original.assertions().size() + " assertions to " + updated.assertions().size() + ".");
            }
            if (updated.disabled() && !original.disabled()) problems.add(original.name() + " was disabled.");
            if (updated.hasTry() && !original.hasTry()) problems.add(original.name() + " added a try block that can hide failures.");
            if (updated.assumes() && !original.assumes()) problems.add(original.name() + " added an assumption that can skip the test.");
            if (original.skeleton() && !updated.skeleton()) problems.add(original.name() + " lost @WalkingSkeleton.");
            checkExpectedValues(original, updated, evidence, problems);
        }
        return problems;
    }

    private void checkExpectedValues(TestMethod original, TestMethod updated, List<Evidence> evidence, List<String> problems) {
        List<String> remaining = new ArrayList<>(updated.assertions().stream().flatMap(a -> a.literals().stream()).toList());
        for (String value : original.assertions().stream().flatMap(a -> a.literals().stream()).toList()) {
            if (remaining.remove(value)) continue;
            boolean documented = evidence.stream().anyMatch(e -> e.oldValue().equals(value) && contains(e));
            if (!documented) {
                problems.add(original.name() + " changed expected value \"" + value + "\" without evidence from a context file that documents the new value.");
            }
        }
    }

    private boolean contains(Evidence item) {
        try {
            return context.read(item.contextPath()).contains(item.newValue());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
