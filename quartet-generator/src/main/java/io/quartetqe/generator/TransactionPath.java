package io.quartetqe.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One transaction followed through every system a test touched.
 *
 * A test threads a value from one act to the next with {@code with("orderId", ...)}. The act that first returned
 * that value and every act given it are one transaction: for example the order placed through the API, the row
 * found in Postgres and the event found on Kafka. Each step says whether the trace went out, came back from the
 * system itself (the strongest link) or, for a database, was linked by the shared value. Everything comes from
 * the audit log, never from a model.
 */
public final class TransactionPath {
    private TransactionPath() {}

    /** How a step is linked to the transaction, strongest first. */
    public enum Link {
        SAME("same trace", "The system passed this test's trace on"),
        SENT("trace sent", "Quartet sent this test's trace"),
        READ("linked by value", "Read directly, linked by the shared value"),
        OTHER("different trace", "The system's output carried another trace"),
        NONE("no trace", "The system's output carried no trace");

        final String label;
        final String meaning;

        Link(String label, String meaning) {
            this.label = label;
            this.meaning = meaning;
        }
    }

    /** One system on the path. */
    public record Step(String target, String type, String detail, Link link, String instruction) {}

    /** A followed transaction: the shared value, the trace id and the systems in order. */
    public record Path(String name, String value, String traceId, List<Step> steps) {
        public long systems() {
            return steps.stream().map(Step::target).distinct().count();
        }

        /** Targets that passed the trace on themselves. */
        public List<String> carriedBy() {
            return steps.stream().filter(s -> s.link() == Link.SAME).map(Step::target).distinct().toList();
        }
    }

    /** The transaction a test's acts share, or empty when no value was threaded through two or more systems. */
    public static Optional<Path> of(List<RunReport.Act> acts) {
        // Every value given to an act with with(...), and the acts that were given it.
        Map<String, List<Integer>> givenTo = new LinkedHashMap<>();
        Map<String, String> nameOf = new LinkedHashMap<>();
        for (int i = 0; i < acts.size(); i++) {
            for (var entry : acts.get(i).vars().entrySet()) {
                if (!meaningful(entry.getValue())) continue;
                givenTo.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(i);
                nameOf.putIfAbsent(entry.getValue(), entry.getKey());
            }
        }
        String best = null;
        List<Integer> bestActs = List.of();
        for (var entry : givenTo.entrySet()) {
            List<Integer> path = new ArrayList<>();
            Integer origin = originOf(acts, entry.getKey(), entry.getValue().get(0));
            if (origin != null) path.add(origin);
            entry.getValue().forEach(i -> { if (!path.contains(i)) path.add(i); });
            if (path.size() > bestActs.size()) {
                best = entry.getKey();
                bestActs = path;
            }
        }
        if (best == null || bestActs.size() < 2) return Optional.empty();

        List<Step> steps = new ArrayList<>();
        String traceId = null;
        for (int index : bestActs) {
            RunReport.Act act = acts.get(index);
            if (traceId == null && act.traceId() != null && !act.traceId().isBlank()) traceId = act.traceId();
            steps.addAll(stepsOf(act));
        }
        if (steps.stream().map(Step::target).distinct().count() < 2) return Optional.empty();
        return Optional.of(new Path(nameOf.get(best), best, traceId, steps));
    }

    /** The first act, before the first act that was given the value, that returned it. */
    private static Integer originOf(List<RunReport.Act> acts, String value, int firstUse) {
        for (int i = 0; i < firstUse; i++) {
            if (acts.get(i).data().containsValue(value)) return i;
        }
        return null;
    }

    /** One step per system an act touched, with its strongest link and the most telling detail. */
    private static List<Step> stepsOf(RunReport.Act act) {
        Map<String, Step> byTarget = new LinkedHashMap<>();
        for (RunReport.Hop hop : act.hops()) {
            Link link = link(hop.link());
            Step current = byTarget.get(hop.target());
            if (current == null || link.ordinal() < current.link().ordinal()) {
                byTarget.put(hop.target(), new Step(hop.target(), hop.kind(), hop.detail(), link, act.instruction()));
            }
        }
        if (byTarget.isEmpty()) {
            // An older audit line without hops: show the systems the act was on, linked by value.
            act.types().forEach((target, type) -> byTarget.put(target, new Step(target, type, "", Link.READ, act.instruction())));
        }
        return new ArrayList<>(byTarget.values());
    }

    private static Link link(String name) {
        try {
            return Link.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Link.READ;
        }
    }

    /** Values that identify something. Short values, booleans and plain small numbers match by chance. */
    static boolean meaningful(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 4) return false;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("null")) return false;
        return !v.matches("-?\\d{1,5}(\\.\\d+)?");
    }
}
