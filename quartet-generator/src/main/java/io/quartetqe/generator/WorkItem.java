package io.quartetqe.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A story or specification from any source, in one shape. */
public record WorkItem(String id, String title, String description, Map<String, String> acceptanceCriteria, String url) {
    private static final Pattern NAMED_AC = Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?(?:\\*\\*)?(AC[- ]?\\d+)(?:\\*\\*)?\\s*[:.)-]\\s*(.+)$");
    private static final Pattern AC_HEADING = Pattern.compile("(?im)^\\W*acceptance criteria\\W*$");

    public static WorkItem fromFile(Path file) {
        try {
            String content = Files.readString(file);
            String firstLine = content.lines().findFirst().orElse("").replaceFirst("^#\\s*", "");
            String id = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            String title = firstLine.replaceFirst("^" + Pattern.quote(id) + "\\s*:?\\s*", "");
            return of(id, title, content, file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static WorkItem of(String id, String title, String description, String url) {
        return new WorkItem(id, title, description, parseCriteria(description), url);
    }

    /** Reads AC1-style criteria, or numbers the bullets under an "Acceptance criteria" heading. */
    static Map<String, String> parseCriteria(String text) {
        Map<String, String> criteria = new LinkedHashMap<>();
        Matcher named = NAMED_AC.matcher(text);
        while (named.find()) criteria.put(named.group(1).replaceAll("[- ]", "").toUpperCase(), named.group(2).trim());
        if (!criteria.isEmpty()) return criteria;

        Matcher heading = AC_HEADING.matcher(text);
        if (!heading.find()) return criteria;
        List<String> bullets = new ArrayList<>();
        for (String line : text.substring(heading.end()).split("\n")) {
            if (line.matches("\\s*([-*]|\\d+[.)])\\s+.+")) bullets.add(line.replaceFirst("\\s*([-*]|\\d+[.)])\\s+", "").trim());
            else if (line.startsWith("#")) break;
        }
        for (int i = 0; i < bullets.size(); i++) criteria.put("AC" + (i + 1), bullets.get(i));
        return criteria;
    }

    public String slug() {
        return id.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public String asPrompt() {
        StringBuilder out = new StringBuilder("Work item ").append(id).append(": ").append(title).append("\n\n").append(description).append("\n\nAcceptance criteria:\n");
        acceptanceCriteria.forEach((key, text) -> out.append(key).append(": ").append(text).append('\n'));
        return out.toString();
    }
}
