package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One W3C trace for one test, so a transaction can be followed through every system it touches.
 *
 * Every HTTP request, page request and Kafka event Quartet sends carries a {@code traceparent} header with this
 * test's trace id. A service that propagates trace context passes it on, so when Quartet later finds the event
 * that service published, the event carries the same trace id: the service linked the hop itself, and the test
 * can prove it with {@link #reached(String)}. Each hop is also written to the audit log for the run report.
 *
 * The header is added by the driver, never by the model, so recordings stay the same from run to run.
 */
public final class TransactionTrace {
    /** How a hop relates to this test's trace. */
    public enum Link {
        /** Quartet sent this test's trace to the system. */
        SENT,
        /** The system's output carried this test's trace: the system linked the hop itself. */
        SAME,
        /** The system's output carried a different trace. */
        OTHER,
        /** The system's output carried no trace. */
        NONE,
        /** Quartet read the system directly. Databases carry no trace, so these are linked by shared values. */
        READ
    }

    /** One system an act touched. */
    public record Hop(String target, String kind, String detail, Link link) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACEPARENT = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String INVALID_TRACE = "0".repeat(32);

    private final String traceId;
    private final List<Hop> hops = new ArrayList<>();
    private int drained;

    TransactionTrace() {
        this(randomHex(16));
    }

    TransactionTrace(String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}") || traceId.equals(INVALID_TRACE)) {
            throw new IllegalArgumentException("A trace id is 32 lowercase hex characters, not all zero");
        }
        this.traceId = traceId;
    }

    /** This test's trace id, 32 hex characters. */
    public String traceId() {
        return traceId;
    }

    /** A fresh {@code traceparent} header value: this trace, a new span, sampled. */
    public String traceparent() {
        return "00-" + traceId + "-" + randomHex(8) + "-01";
    }

    /** The trace id inside a {@code traceparent} header, or null when the header is missing or malformed. */
    public static String traceIdOf(String traceparent) {
        if (traceparent == null) return null;
        Matcher m = TRACEPARENT.matcher(traceparent.trim().toLowerCase());
        if (!m.matches() || m.group(1).equals("ff") || m.group(2).equals(INVALID_TRACE) || m.group(3).equals("0".repeat(16))) return null;
        return m.group(2);
    }

    /** True when some system's output on this target carried this test's trace. */
    public synchronized boolean reached(String target) {
        return hops.stream().anyMatch(h -> h.target().equals(target) && h.link() == Link.SAME);
    }

    /** The targets whose output carried this test's trace, in the order they were reached. */
    public synchronized List<String> targetsReached() {
        Set<String> reached = new LinkedHashSet<>();
        hops.stream().filter(h -> h.link() == Link.SAME).forEach(h -> reached.add(h.target()));
        return List.copyOf(reached);
    }

    /** Every hop so far, oldest first. */
    public synchronized List<Hop> hops() {
        return List.copyOf(hops);
    }

    synchronized void sent(String target, String kind, String detail) {
        hops.add(new Hop(target, kind, detail, Link.SENT));
    }

    synchronized void read(String target, String kind, String detail) {
        hops.add(new Hop(target, kind, detail, Link.READ));
    }

    /** Records output found on a target, with the trace id it carried (null for none). */
    synchronized void seen(String target, String kind, String detail, String carriedTraceId) {
        Link link = carriedTraceId == null ? Link.NONE : carriedTraceId.equals(traceId) ? Link.SAME : Link.OTHER;
        hops.add(new Hop(target, kind, detail, link));
    }

    /** How many hops have been noted so far. */
    synchronized int size() {
        return hops.size();
    }

    /** The hops since the last call, for one act's audit entry. */
    synchronized ArrayNode drain() {
        ArrayNode out = Json.arr();
        for (Hop hop : hops.subList(drained, hops.size())) {
            ObjectNode node = out.addObject();
            node.put("target", hop.target()).put("kind", hop.kind()).put("detail", hop.detail()).put("link", hop.link().name());
        }
        drained = hops.size();
        return out;
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (isZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean isZero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }
}
