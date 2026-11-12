package io.quartetqe.generator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quartet's own engineering principles and playbooks, shipped inside the tool. A tests repo uses them as they
 * are and only holds a file of its own when it changes one, so the repo carries nothing it did not write.
 */
final class Defaults {
    private static final String BASE = "/defaults/context/";
    private static Map<String, String> files;

    private Defaults() {}

    /** Every default, keyed by its path inside the context folder, such as engineering-principles/principles.yaml. */
    static synchronized Map<String, String> files() {
        if (files == null) {
            Map<String, String> loaded = new LinkedHashMap<>();
            String index = read(BASE + "index.txt");
            if (index != null) {
                for (String path : index.split("\n")) {
                    if (path.isBlank()) continue;
                    String content = read(BASE + path.strip());
                    if (content != null) loaded.put(path.strip(), content);
                }
            }
            files = Collections.unmodifiableMap(loaded);
        }
        return files;
    }

    static String get(String path) {
        return files().get(path);
    }

    private static String read(String resource) {
        try (InputStream in = Defaults.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
