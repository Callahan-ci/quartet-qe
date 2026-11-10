package io.quartetqe.generator;

import io.quartetqe.core.AgentLoop;
import java.util.ArrayList;
import java.util.List;

/** Audits what the AI did, not just what it produced: did it do its homework and check its own work? */
final class TraceAudit {
    private TraceAudit() {}

    static List<Finding> check(List<AgentLoop.Step> steps, Principles principles, ContextRepo context) {
        List<Finding> out = new ArrayList<>();
        require(out, principles, steps, "engineering-principles/", "AUDIT-READ-PRINCIPLES", "read the engineering principles");
        if (has(context, "contracts/")) require(out, principles, steps, "contracts/", "AUDIT-READ-CONTRACTS", "read the service contracts");
        if (has(context, "telemetry/")) require(out, principles, steps, "telemetry/", "AUDIT-READ-TELEMETRY", "read the service telemetry");

        int lastWrite = -1;
        int lastValidate = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).tool().equals("write_test") && !steps.get(i).error()) lastWrite = i;
            if (steps.get(i).tool().equals("validate_tests")) lastValidate = i;
        }
        if (lastWrite >= 0 && lastValidate < lastWrite) {
            principles.finding("AUDIT-SELF-CHECK", "trace", "The last edit was never validated. Call validate_tests.").ifPresent(out::add);
        }
        return out;
    }

    /** A team that has not written contracts or telemetry yet is not asked to read them. */
    private static boolean has(ContextRepo context, String prefix) {
        return context.list().stream().anyMatch(path -> path.startsWith(prefix));
    }

    private static void require(List<Finding> out, Principles principles, List<AgentLoop.Step> steps, String prefix, String rule, String action) {
        boolean done = steps.stream().anyMatch(step -> !step.error()
                && (step.tool().equals("context_read") && step.args().path("path").asText().startsWith(prefix)));
        if (!done) principles.finding(rule, "trace", "Before finishing you must " + action + " with context_read.").ifPresent(out::add);
    }
}
