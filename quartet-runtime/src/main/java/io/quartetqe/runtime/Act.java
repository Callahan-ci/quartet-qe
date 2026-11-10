package io.quartetqe.runtime;

import java.util.List;

/** Options for {@code agent.act("do x", why("z"), on("app"))}. Import statically. */
public final class Act {
    private Act() {}

    public sealed interface Option permits Why, On, With, Returns {}

    public record Why(String reason) implements Option {}

    public record On(List<String> targets) implements Option {}

    public record With(String name, Object value) implements Option {}

    public record Returns(List<String> fields) implements Option {}

    /** The business reason for this action. The agent uses it to make judgement calls. */
    public static Why why(String reason) {
        return new Why(reason);
    }

    /** The system to act on, as named in the environment file. */
    public static On on(String... targets) {
        return new On(List.of(targets));
    }

    /** A value substituted for {{name}} in the instruction. Recordings stay valid when it changes. */
    public static With with(String name, Object value) {
        return new With(name, value);
    }

    /** Fields the agent must return. Every value must be observed in a tool result. */
    public static Returns returns(String... fields) {
        return new Returns(List.of(fields));
    }
}
