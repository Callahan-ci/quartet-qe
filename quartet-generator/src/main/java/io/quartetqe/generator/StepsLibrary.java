package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reusable steps: Quartet's version of a page object. A steps class in the package "steps" holds journeys such as
 * placeOrder or openTheHomePage, written once as agent.act calls and used by any test. Tests stay a few lines long,
 * a fix is made in one place, and one recording serves every test that uses the step.
 */
final class StepsLibrary {
    static final String PACKAGE = "steps";

    private StepsLibrary() {}

    /** The steps classes already in the repo, keyed "steps/Name.java". */
    static Map<String, String> load(Path testSourceRoot) throws IOException {
        Map<String, String> steps = new LinkedHashMap<>();
        Path dir = testSourceRoot.resolve(PACKAGE);
        if (!Files.isDirectory(dir)) return steps;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                steps.put(PACKAGE + "/" + file.getFileName(), Files.readString(file));
            }
        }
        return steps;
    }

    /** Writes one steps class. It holds steps only: tests live in the story's own package. */
    static JsonNode write(Path testSourceRoot, Map<String, String> steps, String fileName, String content) throws IOException {
        if (!fileName.matches("[A-Z][A-Za-z0-9_]*\\.java")) throw new IllegalArgumentException("file_name must be a class name such as Orders.java.");
        if (!content.contains("package " + PACKAGE + ";")) throw new IllegalArgumentException("A steps class must start with: package " + PACKAGE + ";");
        if (content.contains("@Test") || content.contains("@OnEverySurface")) {
            throw new IllegalArgumentException("A steps class holds reusable steps, not tests. Put tests in the story's own package.");
        }
        if (content.length() > 100_000) throw new IllegalArgumentException("That steps class is too large. Split it by area, such as Orders and Catalog.");
        Path directory = testSourceRoot.resolve(PACKAGE);
        io.quartetqe.core.SafePaths.writable(testSourceRoot, directory.resolve(fileName));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), content);
        steps.put(PACKAGE + "/" + fileName, content);
        return Json.obj().put("written", PACKAGE + "/" + fileName);
    }

    /** What the AI needs to reuse before writing new steps: each class, its steps, and what each step does. */
    static ObjectNode describe(Map<String, String> steps) {
        ObjectNode out = Json.obj();
        ArrayNode classes = out.putArray("steps_classes");
        for (var entry : steps.entrySet()) {
            JavaInspector.TestFile file = JavaInspector.inspect(entry.getKey(), entry.getValue());
            ObjectNode item = classes.addObject().put("path", entry.getKey()).put("class", file.className());
            ArrayNode methods = item.putArray("steps");
            file.helpers().forEach((name, method) -> {
                ObjectNode step = methods.addObject().put("name", name);
                ArrayNode instructions = step.putArray("does");
                method.acts().forEach(act -> instructions.add(act.instruction() + " [" + String.join(",", act.on()) + "]"));
            });
            String source = entry.getValue();
            item.put("source", source.length() > 6000 ? source.substring(0, 6000) + "\n// ...shortened" : source);
        }
        out.put("how_to_use", classes.isEmpty()
                ? "No steps classes yet. When a journey is used by more than one test, or is likely to be, put it in a class in package steps and call it from the tests."
                : "Reuse these before writing new steps: new Orders(agent).placeOrder(...). Add a step to an existing class when it belongs there. Instructions with {{variables}} (using with(...)) let one recording serve many values.");
        return out;
    }

    static List<String> names(Map<String, String> steps) {
        return List.copyOf(steps.keySet());
    }
}
