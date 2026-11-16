package io.quartetqe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a program that Quartet starts, such as the team's test run, gets to see of the environment. The tests are code
 * the AI wrote, so they get what they need to run and the variables the environment file names, not every variable the
 * user's session happens to hold: not the tracker, git host and Jenkins tokens, and not unrelated cloud keys.
 * QUARTET_PASS_ENV adds names, and QUARTET_INHERIT_ENV=true turns the filter off.
 */
public final class ChildEnvironment {
    private static final Set<String> NAMES = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "TMPDIR", "TEMP", "TMP", "TERM", "TZ", "LANG", "LANGUAGE",
            "JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "CURL_CA_BUNDLE", "NODE_EXTRA_CA_CERTS",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "APPDATA", "LOCALAPPDATA", "USERPROFILE", "PROGRAMFILES", "PROGRAMDATA", "OS");
    private static final Set<String> PREFIXES = Set.of("LC_", "XDG_", "GRADLE_", "ANDROID_", "PLAYWRIGHT_", "QUARTET_", "AI_");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)");

    private ChildEnvironment() {}

    /** True for a variable a test run always needs: the shell basics, Java, Gradle, the browser and device tools, proxies and Quartet's own settings. */
    public static boolean always(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (NAMES.contains(upper) || NAMES.contains(name)) return true;
        return PREFIXES.stream().anyMatch(upper::startsWith);
    }

    /** The variable names an environment file reads, written as ${NAME} or ${NAME:-default}. */
    public static Set<String> referencedBy(Path environmentFile) {
        Set<String> names = new HashSet<>();
        try {
            if (environmentFile != null && Files.isRegularFile(environmentFile)) {
                Matcher matcher = REFERENCE.matcher(Files.readString(environmentFile));
                while (matcher.find()) names.add(matcher.group(1));
            }
        } catch (java.io.IOException ignored) {
            // An unreadable file names nothing; the run will report the real problem.
        }
        return names;
    }

    /** Names the user asked to pass on, in QUARTET_PASS_ENV, comma separated. */
    public static Set<String> passedOn(Map<String, String> environment) {
        Set<String> names = new HashSet<>();
        for (String name : environment.getOrDefault("QUARTET_PASS_ENV", "").split(",")) {
            if (!name.isBlank()) names.add(name.strip());
        }
        return names;
    }

    /** True when the filter is switched off. */
    public static boolean inheritsEverything(Map<String, String> environment) {
        return "true".equalsIgnoreCase(environment.getOrDefault("QUARTET_INHERIT_ENV", ""));
    }

    /**
     * Removes from {@code child} every variable that is not needed. Kept: the basics, anything in {@code explicit} (what
     * Quartet itself set for the run), the names the environment file references, and QUARTET_PASS_ENV.
     */
    public static void restrict(Map<String, String> child, Set<String> explicit, Set<String> referenced, Map<String, String> parent) {
        if (inheritsEverything(parent)) return;
        Set<String> extra = passedOn(parent);
        child.keySet().removeIf(name -> !always(name) && !explicit.contains(name) && !referenced.contains(name) && !extra.contains(name));
    }

    /** Which saved secrets a test run may see: only those the environment file reads, plus the basics. */
    public static boolean mayReachTests(String name, Set<String> referenced, Map<String, String> parent) {
        return always(name) || referenced.contains(name) || passedOn(parent).contains(name);
    }
}
