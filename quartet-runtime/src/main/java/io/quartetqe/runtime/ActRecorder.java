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
 * A recording is written only after the test passes, so an unproven flow is never replayed.
 */
final class ActRecorder {
    static final AtomicInteger REPLAYED = new AtomicInteger();
    static final AtomicInteger HANDED_OFF = new AtomicInteger();
    static final AtomicInteger MISSED = new AtomicInteger();
    static final AtomicInteger NOT_SAVED = new AtomicInteger();
    static final AtomicInteger TOKENS = new AtomicInteger();
    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.printf(
                "%n[quartet] Recordings: %d replayed, %d handed off, %d missed, %d not saved | AI: %d model calls, %d tokens%n",
                REPLAYED.get(), HANDED_OFF.get(), MISSED.get(), NOT_SAVED.get(), MODEL_CALLS.get(), TOKENS.get())));
    }

    private record Pending(Path file, JsonNode recording) {}

    private final Path cacheDir;
    private final Path reportFile;
    private final List<Pending> pending = new ArrayList<>();

    ActRecorder(Path cacheDir, Path reportFile) {
        this.cacheDir = cacheDir;
        this.reportFile = reportFile;
    }

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
        pending.add(new Pending(file, recording));
    }

    /** Called when the test passes. The assertions have now verified these flows. */
    void commit() {
        try {
            for (Pending item : pending) {
                Files.createDirectories(item.file().getParent());
                Files.writeString(item.file(), Redactor.clean(Json.pretty(item.recording())) + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pending.clear();
    }

    /** Called when the test fails. Existing recordings stay, new unverified ones are dropped. */
    void discard() {
        NOT_SAVED.addAndGet(pending.size());
        pending.clear();
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
}
