package io.quartetqe.generator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "Test my site" with nothing to set up. From a web address this writes an environment file with a
 * browser target (desktop, iPhone and Android phone) and an HTTP target, and allows only that site.
 * An existing file is never overwritten, so a team's edits are kept.
 */
final class SiteEnvironment {
    private SiteEnvironment() {}

    /**
     * Makes a tests repo use this environment from now on, by naming it in quartet.yaml. The build reads the same
     * setting, so a plain ./gradlew test finds the targets the tests were written against.
     */
    static void select(Path root, String environment) throws IOException {
        Path settings = root.resolve("quartet.yaml");
        if (!Files.isRegularFile(settings)) return;
        String text = Files.readString(settings);
        String updated = text.matches("(?s).*(^|\\n)environment:.*")
                ? text.replaceFirst("(?m)^environment:.*$", java.util.regex.Matcher.quoteReplacement("environment: " + environment))
                : text + (text.endsWith("\n") ? "" : "\n") + "environment: " + environment + "\n";
        if (!updated.equals(text)) Files.writeString(settings, updated);
    }

    /** Returns the environment file path relative to the project root, creating it if it does not exist. */
    static String ensure(Path root, Path contextDir, String address) throws IOException {
        URI uri;
        try {
            uri = URI.create(address.strip().contains("://") ? address.strip() : "https://" + address.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + address + "' is not a web address. Use something like https://www.example.com.");
        }
        String host = uri.getHost();
        if (host == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("'" + address + "' is not a web address. Use something like https://www.example.com.");
        }
        String base = uri.getScheme() + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String bare = host.startsWith("www.") ? host.substring(4) : host;
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add(host);
        hosts.add(bare);
        hosts.add("www." + bare);

        String slug = bare.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
        Path file = contextDir.resolve("environments").resolve(slug + ".yaml");
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                name: %1$s
                # Created by Quartet QE for a public site. The site is tested read-only, and only these hosts can be visited.
                guard:
                  production: false
                  allowHosts: [%2$s]
                targets:
                  site-web:
                    type: screen
                    description: The pages of %1$s, in a browser on each device.
                    baseUrl: "%3$s"
                    devices:
                      desktop: {kind: web, engine: chromium, preset: desktop}
                      iphone: {kind: web, engine: webkit, preset: iphone}
                      android-phone: {kind: web, engine: chromium, preset: android}
                  site-http:
                    type: api
                    description: Plain HTTP responses from %1$s, for status codes and response times.
                    baseUrl: "%3$s"
                """.formatted(host, String.join(", ", hosts), base));
        }
        return root.relativize(file).toString();
    }
}
