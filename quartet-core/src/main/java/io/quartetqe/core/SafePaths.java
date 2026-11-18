package io.quartetqe.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps file access inside a folder, including through symbolic links, which a plain "starts with" check on the path
 * does not catch: a link inside the folder can point anywhere. Also names the files that hold secrets, so a reader
 * that is meant for source and notes never returns them.
 */
public final class SafePaths {
    private static final Set<String> SECRET_NAMES = Set.of(".git", ".ssh", ".aws", ".gnupg", ".kube", ".docker", ".npmrc", ".netrc", ".pgpass",
            ".pypirc", ".git-credentials", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "secrets.yaml", "secrets.yml", "secrets.json");
    private static final Set<String> SECRET_EXTENSIONS = Set.of("pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "ppk");

    private SafePaths() {}

    /** True for a file or folder that usually holds a credential: .env files, keys, certificates, .git, .ssh, .aws and the like. */
    public static boolean looksSecret(Path path) {
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (SECRET_NAMES.contains(name) || name.startsWith(".env") || name.startsWith("id_rsa") || name.startsWith("id_ed25519")) return true;
        }
        String file = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = file.lastIndexOf('.');
        return dot > 0 && SECRET_EXTENSIONS.contains(file.substring(dot + 1));
    }

    /**
     * A file to read, given relative to a folder. Refuses absolute paths, "..", a path whose real location is outside
     * the folder because of a symbolic link, and files that look like credentials.
     */
    public static Path readable(Path root, String relative) throws IOException {
        Path candidate = within(root, relative);
        Path normalRoot = root.toAbsolutePath().normalize();
        boolean secret = looksSecret(normalRoot.relativize(candidate));
        if (Files.exists(candidate)) {
            Path realRoot = root.toRealPath();
            Path real = candidate.toRealPath();
            requireInside(realRoot, real, relative);
            // A link named like a note that points at a credential file is still a credential file.
            secret = secret || looksSecret(realRoot.relativize(real));
        }
        if (secret) throw new IllegalArgumentException(relative + " looks like it holds a credential, so it is not shown.");
        return candidate;
    }

    /**
     * Checks that writing a file the program chose would land inside the folder: no symbolic link in the way, and the
     * file itself is not a link that would send the write somewhere else.
     */
    public static Path writable(Path root, Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path normalRoot = root.toAbsolutePath().normalize();
        if (!absolute.startsWith(normalRoot)) throw new IllegalArgumentException("Refusing to write outside " + root + ": " + file);
        if (Files.isSymbolicLink(absolute)) throw new IllegalArgumentException(file.getFileName() + " is a symbolic link, so it is not written through.");
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) throw new IllegalArgumentException("Nowhere to write " + file);
        Path boundary = normalRoot;
        while (boundary != null && !Files.exists(boundary)) boundary = boundary.getParent();
        requireInside((boundary == null ? normalRoot : boundary).toRealPath(), existing.toRealPath(), file.toString());
        return absolute;
    }

    /** As {@link #writable(Path, Path)} for a path given relative to the folder. */
    public static Path writable(Path root, String relative) throws IOException {
        return writable(root, within(root, relative));
    }

    private static Path within(Path root, String relative) {
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("A path is needed.");
        if (relative.startsWith("/") || relative.startsWith("\\") || relative.matches("^[A-Za-z]:.*") || relative.contains("\0")) {
            throw new IllegalArgumentException("The path must be relative to the folder: " + relative);
        }
        Path normalRoot = root.toAbsolutePath().normalize();
        Path candidate = normalRoot.resolve(relative).normalize();
        if (!candidate.startsWith(normalRoot)) throw new IllegalArgumentException("The path is outside the folder: " + relative);
        return candidate;
    }

    private static void requireInside(Path realRoot, Path real, String shown) {
        if (!real.startsWith(realRoot)) throw new IllegalArgumentException(shown + " is outside the folder (a symbolic link points elsewhere), so it is not used.");
    }

    /** True when a file in the folder is a regular file that really lives inside it, so a walk can skip links that lead out. */
    public static boolean staysInside(Path realRoot, Path file) {
        try {
            return Files.isRegularFile(file) && file.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }
}
