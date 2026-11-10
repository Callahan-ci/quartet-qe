package io.quartetqe.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Keyword search over the context repo. BM25 ranking, so no embedding service or extra key is needed. */
public final class ContextRepo {
    private static final Set<String> EXTENSIONS = Set.of("md", "yaml", "yml", "json", "txt");
    private static final int CHUNK_CHARS = 1800;

    public record Hit(String path, double score, String text) {}

    private record Chunk(String path, String text, Map<String, Integer> terms, int length) {}

    private final Path root;
    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    public ContextRepo(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(this.root)) {
            paths.filter(Files::isRegularFile).filter(ContextRepo::supported).sorted().forEach(this::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        averageLength = chunks.stream().mapToInt(Chunk::length).average().orElse(1);
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString();
        return EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase());
    }

    private void add(Path file) {
        try {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String content = Files.readString(file);
            files.put(relative, content);
            for (String piece : split(content)) {
                Map<String, Integer> terms = new HashMap<>();
                tokens(piece + " " + relative).forEach(token -> terms.merge(token, 1, Integer::sum));
                terms.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
                chunks.add(new Chunk(relative, piece, terms, Math.max(1, terms.values().stream().mapToInt(i -> i).sum())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> split(String content) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n")) {
            boolean heading = line.startsWith("#") && current.length() > 200;
            if (heading || current.length() + line.length() > CHUNK_CHARS) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) pieces.add(current.toString());
        return pieces;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() > 1) tokens.add(token);
        }
        return tokens;
    }

    public List<Hit> search(String query, int limit) {
        int n = chunks.size();
        record Scored(Chunk chunk, double score) {}
        List<Scored> scored = new ArrayList<>();
        List<String> queryTerms = tokens(query);
        for (Chunk chunk : chunks) {
            double score = 0;
            for (String term : queryTerms) {
                int frequency = chunk.terms().getOrDefault(term, 0);
                if (frequency == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * chunk.length() / averageLength));
            }
            if (score > 0) scored.add(new Scored(chunk, score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(limit).map(s -> new Hit(s.chunk().path(), s.score(), s.chunk().text())).toList();
    }

    public String read(String relative) {
        String content = files.get(relative);
        if (content == null) throw new IllegalArgumentException("No context file at " + relative + ". Use context_list.");
        return content;
    }

    public List<String> list() {
        return List.copyOf(files.keySet());
    }

    public Path root() {
        return root;
    }

    /** True when the exact text appears somewhere in the context. Used to catch invented facts. */
    public boolean mentions(String text) {
        return files.values().stream().anyMatch(content -> content.contains(text));
    }
}
