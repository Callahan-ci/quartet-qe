package io.quartetqe.generator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the user decided about where tests live and whether Jenkins is wanted, kept in
 * quartet-setup.yaml so nobody is asked twice. In a tests repo this file is committed with the tests.
 */
final class SetupChoices {
    static final String FILE = "quartet-setup.yaml";

    private SetupChoices() {}

    /** The saved choices, or null when the user has not been asked yet. */
    static ObjectNode read(Path root) throws IOException {
        Path file = root.resolve(FILE);
        return Files.isRegularFile(file) ? (ObjectNode) Json.parseYaml(Files.readString(file)) : null;
    }

    static void write(Path root, String testsRepo, boolean jenkins) throws IOException {
        Files.writeString(root.resolve(FILE), """
            # Saved by Quartet QE after asking you. Edit it, or delete it to be asked again.
            # testsRepo: here, or the folder of the repo that holds the tests.
            testsRepo: %s
            jenkins: %s
            """.formatted(testsRepo, jenkins));
    }
}
