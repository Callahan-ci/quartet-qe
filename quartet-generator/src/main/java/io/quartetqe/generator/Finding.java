package io.quartetqe.generator;

/** One quality result, always tied to the engineering principle it protects. */
public record Finding(String rule, String principle, Severity severity, String location, String message) {
    public enum Severity { BLOCK, WARN }

    public boolean blocking() {
        return severity == Severity.BLOCK;
    }

    @Override
    public String toString() {
        return severity + " " + rule + " [" + principle + "] " + location + ": " + message;
    }
}
