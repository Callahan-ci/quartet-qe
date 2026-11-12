package io.quartetqe.runtime;

/**
 * The machine is not ready: a tool is missing, or no device is connected. Not a problem with the app or the test,
 * so it is reported as it is, with what to do, and never handed to the AI to retry.
 */
public final class EnvironmentProblem extends RuntimeException {
    public EnvironmentProblem(String message) {
        super(message);
    }
}
