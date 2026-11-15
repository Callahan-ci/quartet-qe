package io.quartetqe.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The .env file in a project: where secrets live, never committed. Real environment variables win.
 * Reading and writing here is the only way a token reaches Quartet QE, so it never passes through a chat.
 */
public final class DotEnv {
    private DotEnv() {}

    /** The entries of a .env file, or none if it does not exist. */
    public static Map<String, String> read(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0 && !line.strip().startsWith("#")) {
                    values.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip().replaceAll("^[\"']|[\"']$", ""));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }

    /** The project's .env with the real environment on top. */
    public static Map<String, String> merged(Path root) {
        Map<String, String> merged = new HashMap<>(read(root.resolve(".env")));
        merged.putAll(environment());
        return merged;
    }

    /** The real environment without settings a launcher left empty or unfilled. */
    public static Map<String, String> environment() {
        Map<String, String> given = new HashMap<>();
        System.getenv().forEach((name, value) -> {
            if (!blankOrUnfilled(value)) given.put(name, value);
        });
        return given;
    }

    /**
     * A launcher that fills settings in from a form can pass an empty value, or the placeholder itself when the
     * user left a field blank. Neither is something the user chose, so the setting counts as not given.
     */
    static boolean blankOrUnfilled(String value) {
        return value == null || value.isBlank() || value.matches("\\$\\{[A-Za-z_]+\\.[A-Za-z0-9_.]+}");
    }

    /** Adds or replaces one entry, keeping the rest of the file, readable by the owner only. */
    public static void set(Path file, String name, String value) {
        if (!name.matches("[A-Z][A-Z0-9_]*")) throw new IllegalArgumentException("A secret name is capital letters, digits and underscores, such as JIRA_TOKEN.");
        if (value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("A secret cannot contain a line break.");
        try {
            List<String> lines = new ArrayList<>(Files.isRegularFile(file) ? Files.readAllLines(file) : List.of());
            lines.removeIf(line -> line.strip().startsWith(name + "="));
            lines.add(name + "=" + value);
            if (!Files.exists(file)) {
                Files.createFile(file);
                try {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Not a POSIX file system, so there is nothing to restrict.
                }
            }
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
