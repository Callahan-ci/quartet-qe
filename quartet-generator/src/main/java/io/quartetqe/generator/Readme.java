package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A README about this tests repo, not about Quartet: what the product is, what is tested and with what result,
 * how to run it, and what lives where. It is rebuilt from the repo's own files. Text a person writes outside the
 * markers is never touched.
 */
final class Readme {
    static final String START = "<!-- quartet:start (rebuilt by Quartet QE from this repo's files; write your own notes outside these markers) -->";
    static final String END = "<!-- quartet:end -->";
    private static final Pattern MARKERS = Pattern.compile("(?s)<!-- quartet:start.*?-->.*?<!-- quartet:end -->");
    private static final Pattern DEFAULT = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*(?::-([^}]*))?}");

    private Readme() {}

    /**
     * Rebuilds the generated part of README.md. A missing or empty README is created. A README with no markers is a
     * person's own and is left alone unless force is set. Returns true when the file changed.
     */
    static boolean update(Main main, boolean force) throws IOException {
        Path file = main.root.resolve("README.md");
        String block = START + "\n" + body(main) + "\n" + END;
        String current = Files.isRegularFile(file) ? Files.readString(file) : "";
        String updated;
        if (current.isBlank() || force) {
            updated = "# " + title(main) + "\n\n" + block + "\n";
        } else if (MARKERS.matcher(current).find()) {
            updated = MARKERS.matcher(current).replaceFirst(Matcher.quoteReplacement(block));
            // A title Quartet wrote follows the product name. A title a person wrote is theirs.
            String first = updated.lines().findFirst().orElse("");
            String folder = main.root.getFileName().toString();
            if (first.matches("# (__NAME__|" + Pattern.quote(folder) + "|.+ end-to-end tests)")) {
                updated = "# " + title(main) + updated.substring(first.length());
            }
        } else {
            return false;
        }
        if (updated.equals(current)) return false;
        Files.writeString(file, updated);
        return true;
    }

    private static String title(Main main) throws IOException {
        String product = manifest(main).path("product").asText("");
        return (product.isBlank() || product.contains("replace with your own") ? main.root.getFileName().toString() : product) + " end-to-end tests";
    }

    private static JsonNode manifest(Main main) throws IOException {
        Path file = main.root.resolve(main.settings.path("context").asText("context")).resolve("manifest.yaml");
        return Files.isRegularFile(file) ? Json.parseYaml(Files.readString(file)) : Json.obj();
    }

    static String body(Main main) throws IOException {
        StringBuilder out = new StringBuilder();
        JsonNode manifest = manifest(main);
        String product = manifest.path("product").asText("");
        boolean known = !product.isBlank() && !product.contains("replace with your own");
        String description = manifest.path("description").asText("");
        out.append(description.isBlank() ? "End-to-end tests" + (known ? " for " + product : "") + ", written with [Quartet QE](" + quartetUrl(main) + ") and reviewed by people."
                : description + "\n\nEnd-to-end tests, written with [Quartet QE](" + quartetUrl(main) + ") and reviewed by people.").append('\n');
        if (!manifest.path("owner").asText("").isBlank()) out.append("\nOwner: ").append(manifest.path("owner").asText()).append('\n');

        out.append("\n## What is tested\n\n");
        List<String> rows = stories(main);
        if (rows.isEmpty()) out.append("No stories yet. Ask your AI chat tool to write tests for one, or add a story file to `stories/`.\n");
        else {
            out.append("| Story | Criteria | Tests | Last result |\n| --- | --- | --- | --- |\n");
            rows.forEach(row -> out.append(row).append('\n'));
        }

        List<String> targets = targets(main);
        if (!targets.isEmpty()) {
            out.append("\n## Systems tested\n\n| Name | What | Address |\n| --- | --- | --- |\n");
            targets.forEach(row -> out.append(row).append('\n'));
        }

        out.append("""

            ## Run the tests

            ```bash
            ./gradlew test             # replays recorded steps: fast, no AI, no key
            ./gradlew test -Pwatch     # the same in a visible browser, for debugging
            ```

            ## Change or add tests

            Open this folder in your AI chat tool and ask, for example, *"Write tests for stories/NEW-STORY.md using Quartet QE"*. \
            Quartet QE has to be installed on your machine once; see its README for how. \
            A person reviews every change: the review page for each story is in `reports/`.

            ## What is here

            | Path | What |
            | --- | --- |
            """);
        Path root = main.root;
        row(out, root, "stories", "`stories/`", "The stories the tests are written for");
        row(out, root, main.testSourceRoot().toString().substring(root.toString().length() + 1) + "/generated", "`src/test/java/generated/`", "The tests, one folder per story");
        row(out, root, main.testSourceRoot().toString().substring(root.toString().length() + 1) + "/steps", "`src/test/java/steps/`", "Reusable steps the tests share, written once");
        row(out, root, "recordings", "`recordings/`", "Recorded steps that replay with no AI. Commit these");
        row(out, root, "reports", "`reports/`", "One review page per story");
        row(out, root, main.settings.path("context").asText("context"), "`context/`", "What the AI knows about " + (known ? product : "the product") + ": environments, contracts and notes. Quartet's built-in engineering principles apply unless changed here");
        row(out, root, "Jenkinsfile", "`Jenkinsfile`", "Runs these tests on every branch and pull request");
        row(out, root, "quartet.yaml", "`quartet.yaml`", "Settings for Quartet QE");
        return out.toString().stripTrailing();
    }

    private static void row(StringBuilder out, Path root, String relative, String label, String what) {
        if (Files.exists(root.resolve(relative))) out.append("| ").append(label).append(" | ").append(what).append(" |\n");
    }

    private static String quartetUrl(Main main) {
        String url = main.settings.path("quartetRepo").asText("");
        return url.isBlank() || url.startsWith("__") ? "https://github.com/goughliam1813/quartet-qe" : url;
    }

    private static List<String> stories(Main main) throws IOException {
        List<String> rows = new ArrayList<>();
        Path directory = main.root.resolve("stories");
        if (!Files.isDirectory(directory)) return rows;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                WorkItem story = WorkItem.fromFile(file);
                Path tests = main.testSourceRoot().resolve("generated").resolve(story.slug());
                int count = 0;
                if (Files.isDirectory(tests)) {
                    try (Stream<Path> sources = Files.list(tests)) {
                        for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                            count += JavaInspector.inspect(source.getFileName().toString(), Files.readString(source)).tests().size();
                        }
                    }
                }
                rows.add("| " + story.id() + (story.title().isBlank() ? "" : ": " + story.title().replace("|", "/")) + " | " + story.acceptanceCriteria().size() + " | "
                        + count + " | " + result(main, story) + " |");
            }
        }
        return rows;
    }

    private static String result(Main main, WorkItem story) throws IOException {
        Path review = main.reportDir(story).resolve("review.json");
        if (!Files.isRegularFile(review)) return "Not run yet";
        JsonNode json = Json.parseYaml(Files.readString(review));
        String verdict = json.path("approved").asBoolean(false) ? "Ready to merge" : "Needs attention";
        return verdict + ": " + json.path("passed").asInt() + " passed, " + json.path("failed").asInt() + " failed";
    }

    /** What is under test and where, taken from the environment file as written, so no secret is ever shown. */
    private static List<String> targets(Main main) throws IOException {
        List<String> rows = new ArrayList<>();
        Path file = main.root.resolve(main.environmentPath);
        if (!Files.isRegularFile(file)) return rows;
        JsonNode targets = Json.parseYaml(Files.readString(file)).path("targets");
        targets.fields().forEachRemaining(entry -> {
            JsonNode target = entry.getValue();
            if (target.path("description").asText("").startsWith("Replace with")) return;
            String address = "";
            for (String field : List.of("baseUrl", "url", "brokers")) {
                if (target.has(field)) {
                    address = plain(target.path(field).asText());
                    break;
                }
            }
            rows.add("| `" + entry.getKey() + "` | " + target.path("description").asText("").replace("|", "/") + " | " + (address.isBlank() ? "" : "`" + address + "`") + " |");
        });
        return rows;
    }

    /** Shows the default behind a ${NAME:-default} placeholder, and never a value that could be a secret. */
    private static String plain(String value) {
        Matcher matcher = DEFAULT.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) == null ? "" : matcher.group(1)));
        matcher.appendTail(out);
        return out.toString().replaceAll("://[^/@\\s]+@", "://");
    }
}
