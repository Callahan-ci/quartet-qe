package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.HttpJson;
import io.quartetqe.core.Json;
import io.quartetqe.core.Redactor;
import io.quartetqe.runtime.Environment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Common interfaces for backlog, source control and CI, and the factory that builds them from connectors.yaml. */
public final class Connectors {
    public interface WorkItems {
        WorkItem get(String id);

        void comment(String id, String markdown);
    }

    public interface CodeHost {
        String readFile(String path, String ref);

        List<String> listFiles(String prefix, String ref);

        void commitToNewBranch(String branch, String base, Map<String, String> files, String message);

        String openPullRequest(String branch, String base, String title, String body);
    }

    public record CiStatus(boolean finished, boolean success, String state, String url) {}

    public interface Ci {
        String trigger(String branch, Map<String, String> parameters);

        CiStatus status(String runId);

        /** Makes sure the CI system has a job for the tests repo. Returns a one-line description, or null when nothing is needed. */
        default String ensureReady(String repoUrl) {
            return null;
        }

        /** Checks the address and credentials without changing anything. Returns what was found. */
        default String ping() {
            throw new UnsupportedOperationException("This CI system has no connection check.");
        }
    }

    private final JsonNode config;

    private Connectors(JsonNode config) {
        this.config = config;
    }

    public static Connectors load(Path file) {
        return load(file, System.getenv());
    }

    /** Reads connectors.yaml, filling ${NAME} from the given variables, such as the project's .env on top of the environment. */
    public static Connectors load(Path file, Map<String, String> env) {
        try {
            if (!Files.exists(file)) return new Connectors(Json.obj());
            return new Connectors(Environment.interpolate(Json.parseYaml(Files.readString(file)), env));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The settings as they are now, with variables filled in. Used to tell what is configured. */
    JsonNode config() {
        return config;
    }

    public static Connectors of(JsonNode config) {
        return new Connectors(config);
    }

    private JsonNode section(String name, JsonNode overrides) {
        JsonNode base = config.path(name);
        if (base.isMissingNode()) throw new IllegalStateException("connectors.yaml has no '" + name + "' section.");
        ObjectNode merged = base.deepCopy();
        if (overrides != null) overrides.fields().forEachRemaining(f -> merged.set(f.getKey(), f.getValue()));
        requireEncrypted(name, merged);
        return merged;
    }

    /** Credentials are never sent in the clear to another machine. Plain http is for this computer, or a network the team vouches for. */
    static void requireEncrypted(String name, JsonNode section) {
        for (String key : List.of("baseUrl", "orgUrl", "apiUrl")) {
            String address = section.path(key).asText("");
            if (!address.regionMatches(true, 0, "http://", 0, 7)) continue;
            String host;
            try {
                host = java.net.URI.create(address).getHost();
            } catch (IllegalArgumentException e) {
                host = null;
            }
            boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
            if (!local && !section.path("allowHttp").asBoolean(false)) {
                throw new IllegalStateException("The " + name + " address " + address + " uses plain http, which would send credentials across the network without "
                        + "encryption. Use https, or set allowHttp: true under " + name + " in connectors.yaml if this is a private network you trust.");
            }
        }
    }

    public WorkItems workItems(String provider) {
        return switch (provider) {
            case "jira" -> new JiraConnector(section("jira", null));
            case "ado", "azure-devops" -> new AzureDevOpsConnector(section("azureDevOps", null));
            case "github" -> new GitHubConnector(section("github", null));
            case "gitlab" -> new GitLabConnector(section("gitlab", null));
            default -> throw new IllegalArgumentException("Unknown backlog provider: " + provider);
        };
    }

    public CodeHost codeHost(String provider) {
        return codeHost(provider, null);
    }

    public CodeHost codeHost(String provider, JsonNode overrides) {
        return switch (provider) {
            case "ado", "azure-devops" -> new AzureDevOpsConnector(section("azureDevOps", overrides));
            case "github" -> new GitHubConnector(section("github", overrides));
            case "gitlab" -> new GitLabConnector(section("gitlab", overrides));
            default -> throw new IllegalArgumentException("Unknown code host: " + provider);
        };
    }

    public Ci ci(String provider) {
        return switch (provider) {
            case "jenkins" -> new JenkinsConnector(section("jenkins", null));
            case "ado", "azure-devops" -> new AzureDevOpsConnector(section("azureDevOps", null));
            case "github" -> new GitHubConnector(section("github", null));
            case "gitlab" -> new GitLabConnector(section("gitlab", null));
            default -> throw new IllegalArgumentException("Unknown CI provider: " + provider);
        };
    }

    /** Authenticated JSON calls. Failures include the service's reply with credentials removed. */
    static final class Rest {
        private final String base;
        private final Map<String, String> headers;
        private final HttpJson http = new HttpJson(2, 500);

        Rest(String base, Map<String, String> headers) {
            this.base = base.replaceAll("/$", "");
            this.headers = headers;
            headers.values().forEach(value -> {
                Redactor.register(value.replaceFirst("^(Bearer|Basic) ", ""));
                // A server can echo the raw token, not just its encoded form, so scrub that too.
                if (value.startsWith("Basic ")) {
                    String decoded = new String(Base64.getDecoder().decode(value.substring(6)), StandardCharsets.UTF_8);
                    Redactor.register(decoded.substring(decoded.indexOf(':') + 1));
                }
            });
        }

        static String basic(String user, String password) {
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        HttpJson.Resp send(String method, String path, Object body) {
            return sendRaw(method, path, body == null ? null : Json.str(body), body == null ? null : "application/json", Map.of());
        }

        /** Sends a body exactly as given, with its own content type and any extra headers such as a CSRF crumb. */
        HttpJson.Resp sendRaw(String method, String path, String body, String contentType, Map<String, String> extra) {
            Map<String, String> all = new LinkedHashMap<>(headers);
            all.putAll(extra);
            if (body != null && contentType != null) all.put("content-type", contentType);
            HttpJson.Resp response = http.send(method, base + path, all, body, Duration.ofSeconds(60));
            if (!response.ok()) {
                String reply = response.body().length() > 500 ? response.body().substring(0, 500) : response.body();
                throw new IllegalStateException(method + " " + path.replaceFirst("\\?.*", "") + " returned " + response.status() + ": " + Redactor.clean(reply));
            }
            return response;
        }

        JsonNode get(String path) {
            return send("GET", path, null).json();
        }

        JsonNode post(String path, Object body) {
            HttpJson.Resp response = send("POST", path, body);
            return response.body().isBlank() ? Json.obj() : response.json();
        }

        boolean exists(String path) {
            Map<String, String> all = new LinkedHashMap<>(headers);
            return http.send("GET", base + path, all, null, Duration.ofSeconds(60)).ok();
        }
    }
}
