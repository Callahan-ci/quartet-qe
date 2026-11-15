package io.quartetqe.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

/**
 * What a first-time user gets when Quartet starts where there is no tests repo yet, as a release install does.
 *
 * Quartet keeps a small private workspace of its own and asks the user where their tests should live. The
 * workspace holds only that answer. The tests repo is created from the skeleton that ships with the release.
 */
final class Starter {
    static final String MARKER = ".quartet-starter";

    private Starter() {}

    /** Where the installed release lives: the folder holding bin, lib, scripts and skeleton. */
    static Path installHome() {
        String given = System.getProperty("quartet.home");
        if (given != null && !given.isBlank()) {
            Path home = Path.of(given).toAbsolutePath();
            return Files.isDirectory(home.resolve("skeleton")) ? home : null;
        }
        try {
            Path jar = Path.of(Starter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path home = jar.getParent() == null ? null : jar.getParent().getParent();
            if (home != null && Files.isDirectory(home.resolve("skeleton"))) return home;
        } catch (URISyntaxException | RuntimeException ignored) {
            // Not running from an installed release.
        }
        return null;
    }

    static boolean isStarter(Path root) {
        return Files.exists(root.resolve(MARKER));
    }

    /** The folder everything the user asks to have created goes in by default. */
    static Path testsFolder() {
        return Path.of(System.getProperty("user.home"), "quartet-tests");
    }

    static Path workspace() {
        String given = System.getProperty("quartet.starter");
        return given != null && !given.isBlank() ? Path.of(given).toAbsolutePath()
                : Path.of(System.getProperty("user.home"), ".quartet", "starter");
    }

    /** Creates the workspace from the release's skeleton if it is not there yet, and returns it. */
    static Path prepare() {
        Path workspace = workspace();
        try {
            if (!Files.isRegularFile(workspace.resolve("quartet.yaml"))) {
                Path home = installHome();
                if (home == null) {
                    throw new IllegalStateException("There is no tests repo here and this is not an installed release, so there is nothing to start from. "
                            + "Run Quartet QE inside a tests repo, or from a release download.");
                }
                Files.createDirectories(workspace);
                try (Stream<Path> files = Files.walk(home.resolve("skeleton"))) {
                    for (Path source : files.toList()) {
                        Path target = workspace.resolve(home.resolve("skeleton").relativize(source).toString());
                        if (Files.isDirectory(source)) Files.createDirectories(target);
                        else Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(workspace.resolve(MARKER), "This is Quartet QE's own workspace. Your tests live in their own repo.\n");
            }
            try {
                Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system.
            }
            return workspace;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
