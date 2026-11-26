package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Redactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores recordings and the audit log of every act.
 *
 * A recording is proven only when its test passes. When a test fails, often because it found a real bug, its
 * steps are kept as unproven instead of thrown away. A replay runs them with no AI, so CI shows the real failure
 * rather than "no recording", and the first passing run, once the bug is fixed, promotes them to a proven recording.
 * An unproven recording can never make a test pass on its own: the test's assertions still decide.
 */
final class ActRecorder {
    static final AtomicInteger REPLAYED = new AtomicInteger();
    static final AtomicInteger HANDED_OFF = new AtomicInteger();
    static final AtomicInteger MISSED = new AtomicInteger();
    static final AtomicInteger NOT_SAVED = new AtomicInteger();
    static final AtomicInteger UNPROVEN = new AtomicInteger();
    static final AtomicInteger TOKENS = new AtomicInteger();
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.printf(
                "%n[quartet] Recordings: %d replayed, %d handed off, %d missed, %d not saved%s | AI: %d model calls, %d tokens%n",
                REPLAYED.get(), HANDED_OFF.get(), MISSED.get(), NOT_SAVED.get(),
                UNPROVEN.get() == 0 ? "" : ", " + UNPROVEN.get() + " replayed from a failed run",
                MODEL_CALLS.get(), TOKENS.get())));
    }

    /** A recording waiting for its test's verdict: proven at {@code file} on a pass, kept at {@code unproven} on a failure. */
    private record Pending(Path file, JsonNode recording, Path unproven) {}

    private final Path cacheDir;
    private final Path reportFile;
    private final List<Pending> pending = new ArrayList<>();

    ActRecorder(Path cacheDir, Path reportFile) {
        this.cacheDir = cacheDir;
        this.reportFile = reportFile;
    }

    /**
     * Where a step is stored once for every test that uses it. A step is the same wherever it is called from, so
     * a login or "open the home page" is recorded and repaired in one place, not once per test.
     */
    Path sharedFile(String key) {
        return cacheDir.resolve("_shared").resolve(key + ".json");
    }

    /** Where a step from a failed test is kept until a passing run proves it. */
    Path unprovenFile(String key) {
        return cacheDir.resolve("_unproven").resolve(key + ".json");
    }

    /** The older per-test location. Still read, so existing recordings keep working. */
    Path fileFor(String testId, String key) {
        return cacheDir.resolve(testId.replaceAll("[^A-Za-z0-9_.-]", "_")).resolve(key + ".json");
    }

    JsonNode load(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return Json.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void stage(Path file, JsonNode recording) {
        stage(file, recording, null);
    }

    void stage(Path file, JsonNode recording, Path unproven) {
        ObjectNode proven = recording.deepCopy();
        proven.remove("unproven");
        pending.add(new Pending(file, proven, unproven));
    }

    /** Called when the test passes. The assertions have now verified these flows. */
    void commit() {
        try {
            for (Pending item : pending) {
                Files.createDirectories(item.file().getParent());
                Files.writeString(item.file(), Redactor.clean(Json.pretty(item.recording())) + "\n");
                if (item.unproven() != null) Files.deleteIfExists(item.unproven());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pending.clear();
    }

    /** Called when the test fails. Proven recordings stay as they are; this run's new steps are kept as unproven. */
    void discard() {
        discard(null);
    }

    void discard(String failure) {
        NOT_SAVED.addAndGet(pending.size());
        try {
            for (Pending item : pending) {
                if (item.unproven() == null) continue;
                ObjectNode kept = item.recording().deepCopy();
                kept.putObject("unproven").put("failure", brief(failure)).put("since", java.time.LocalDate.now().toString());
                Files.createDirectories(item.unproven().getParent());
                Files.writeString(item.unproven(), Redactor.clean(Json.pretty(kept)) + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pending.clear();
    }

    /** The first line of why the test failed, short enough to read in a message. */
    static String brief(String failure) {
        if (failure == null || failure.isBlank()) return "the test failed";
        String line = failure.replaceFirst("^[\\w.$]+(Error|Exception):\\s*", "").split("\n\\s*at ")[0].replaceAll("\\s+", " ").trim();
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    void audit(ObjectNode entry) {
        try {
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, Redactor.clean(entry.toString()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Saves a screenshot next to the audit log and returns its path relative to it, such as screenshots/T.m.act1.site.png. */
    String saveScreenshot(String name, byte[] png) {
        try {
            Path dir = (reportFile.getParent() == null ? Path.of(".") : reportFile.getParent()).resolve("screenshots");
            Files.createDirectories(dir);
            String file = name.replaceAll("[^A-Za-z0-9._@-]", "_") + ".png";
            Files.write(dir.resolve(file), png);
            return "screenshots/" + file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
