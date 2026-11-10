package io.quartetqe.runtime;

/** The agent could not perform the action, or a required recording could not be replayed. */
public final class AgentActException extends RuntimeException {
    public AgentActException(String message) {
        super(message);
    }

    public AgentActException(String message, Throwable cause) {
        super(message, cause);
    }
}
