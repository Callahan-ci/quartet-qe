package io.quartetqe.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Files a tests repo gets only when it asks for them, so a new repo starts without them. */
final class Templates {
    private Templates() {}

    static String jenkinsfile() {
        return read("/templates/Jenkinsfile");
    }

    /** The connectors.yaml for Jira, Azure DevOps, GitHub, GitLab and Jenkins, with every setting read from the environment. */
    static String connectors() {
        return read("/templates/connectors.yaml");
    }

    private static String read(String resource) {
        try (InputStream in = Templates.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("The template " + resource + " is missing from this build.");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
