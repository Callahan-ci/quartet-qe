package io.quartetqe.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which local folders the AI may be given to read as source code. Anything it can be pointed at can be read back to the
 * chat, so only a git checkout inside the user's home folder qualifies: not the whole disk, not the home folder
 * itself, and nothing hidden, where keys and tokens live.
 */
final class SourceCheckouts {
    private SourceCheckouts() {}

    /** The real folder, or an explanation of why it cannot be used. */
    static Path require(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) throw new IllegalArgumentException(folder + " is not a folder.");
        Path real = folder.toRealPath();
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        if (!real.startsWith(home) || real.equals(home)) {
            throw new IllegalArgumentException("A local repo must be a folder inside your home folder, not " + real + ".");
        }
        for (Path part : home.relativize(real)) {
            if (part.toString().startsWith(".")) throw new IllegalArgumentException("A local repo cannot be inside a hidden folder (" + part + "), where credentials are kept.");
        }
        // The folder you cloned, or a folder inside it.
        boolean checkout = false;
        for (Path at = real; at != null && at.startsWith(home) && !at.equals(home); at = at.getParent()) {
            if (Files.exists(at.resolve(".git"))) {
                checkout = true;
                break;
            }
        }
        if (!checkout) throw new IllegalArgumentException(real + " is not inside a git checkout (no .git here or above). Point at the folder you cloned.");
        return real;
    }
}
