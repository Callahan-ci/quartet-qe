package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.SafePaths;
import io.quartetqe.core.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Read-only tools over the context repo and the referenced source repositories. */
final class ContextTools {
    private ContextTools() {}

    static List<Tool> context(ContextRepo repo) {
        return List.of(
                Tool.of("context_list", "List every file in the context repo.", "{\"type\":\"object\",\"properties\":{}}", args -> {
                    ObjectNode out = Json.obj();
                    ArrayNode files = out.putArray("files");
                    repo.list().forEach(files::add);
                    return out;
                }),
                Tool.of("context_search", "Search the context repo by keyword.", """
                    {"type":"object","required":["query"],"properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}""", args -> {
                    ObjectNode out = Json.obj();
                    ArrayNode hits = out.putArray("hits");
                    repo.search(args.path("query").asText(), args.path("limit").asInt(5)).forEach(hit ->
                            hits.addObject().put("path", hit.path()).put("text", hit.text()));
                    return out;
                }),
                Tool.of("context_read", "Read one context file in full.", """
                    {"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""",
                        args -> Json.obj().put("path", args.path("path").asText()).put("content", repo.read(args.path("path").asText()))));
    }

    /** Lists and reads repositories named in context/repos/repos.yaml. */
    static List<Tool> repos(ContextRepo repo, Connectors connectors) {
        return List.of(
                Tool.of("repo_list", "List files in a referenced repository.", """
                    {"type":"object","required":["repo"],"properties":{"repo":{"type":"string"},"prefix":{"type":"string"}}}""", args -> {
                    JsonNode reference = reference(repo, args.path("repo").asText());
                    ObjectNode out = Json.obj();
                    ArrayNode files = out.putArray("files");
                    String prefix = args.path("prefix").asText("");
                    if ("local".equals(reference.path("provider").asText())) {
                        Path root = localRoot(repo, reference);
                        try (Stream<Path> paths = Files.walk(root)) {
                            paths.filter(p -> SafePaths.staysInside(root, p)).map(p -> root.relativize(p))
                                    .filter(p -> !SafePaths.looksSecret(p)).map(Path::toString).filter(p -> p.startsWith(prefix)).sorted().forEach(files::add);
                        }
                    } else {
                        connectors.codeHost(reference.path("provider").asText(), reference).listFiles(prefix, reference.path("ref").asText("main")).forEach(files::add);
                    }
                    return out;
                }),
                Tool.of("repo_read", "Read one file from a referenced repository.", """
                    {"type":"object","required":["repo","path"],"properties":{"repo":{"type":"string"},"path":{"type":"string"}}}""", args -> {
                    JsonNode reference = reference(repo, args.path("repo").asText());
                    String path = args.path("path").asText();
                    String content;
                    if ("local".equals(reference.path("provider").asText())) {
                        Path root = localRoot(repo, reference);
                        content = Files.readString(SafePaths.readable(root, path));
                    } else {
                        content = connectors.codeHost(reference.path("provider").asText(), reference).readFile(path, reference.path("ref").asText("main"));
                    }
                    return Json.obj().put("path", path).set("content", TextNode.valueOf(content));
                }));
    }

    private static Path localRoot(ContextRepo repo, JsonNode reference) throws IOException {
        // Checked every time it is read, since repos.yaml can come from a repo someone else wrote.
        return SourceCheckouts.require(repo.root().resolve(reference.path("path").asText()));
    }

    private static JsonNode reference(ContextRepo repo, String name) {
        List<String> known = new ArrayList<>();
        for (JsonNode candidate : Json.parseYaml(repo.read("repos/repos.yaml")).path("repos")) {
            if (candidate.path("name").asText().equals(name)) return candidate;
            known.add(candidate.path("name").asText());
        }
        throw new IllegalArgumentException("Unknown repo '" + name + "'. Known: " + known);
    }
}
