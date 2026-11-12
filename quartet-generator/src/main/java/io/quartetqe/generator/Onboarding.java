package io.quartetqe.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.DotEnv;
import io.quartetqe.core.Json;
import io.quartetqe.core.Redactor;
import io.quartetqe.core.Tool;
import io.quartetqe.runtime.Environment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-time setup, driven by the AI in a chat. It tells the AI what is missing and what to ask, writes the
 * files from what the user says, and checks each connection for real.
 *
 * Secrets never pass through here. Settings can name a secret ("JIRA_TOKEN") but never contain one: the user
 * saves the value with ./set-secret.sh, which writes it to .env without showing it.
 */
final class Onboarding {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(?::-([^}]*))?}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|password|secret|apikey|credential).*");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<String> TARGET_TYPES = Set.of("api", "screen", "kafka", "postgres", "mongo");

    /** Each connector's settings that must be real, and which of them is a secret. */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "jira", List.of("baseUrl", "email", "token"),
            "azureDevOps", List.of("orgUrl", "project", "repository", "token"),
            "github", List.of("owner", "repo", "token"),
            "gitlab", List.of("project", "token"),
            "jenkins", List.of("baseUrl", "user", "token"));
    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile("(?i).*\\b(your-[a-z-]+|your-company)\\b.*");

    private final Supplier<Main> fresh;

    Onboarding(Supplier<Main> fresh) {
        this.fresh = fresh;
    }

    List<Tool> tools() {
        return List.of(
                Tool.of("quartet_onboarding", "What is set up for this product and what is not, as a checklist. Walk the user through the open items one at a time: ask only what is missing, then use quartet_configure. Call it at the start of setup and again after each change.", "{\"type\":\"object\",\"properties\":{}}",
                        args -> checklist()),
                Tool.of("quartet_configure", "Write one part of the setup from what the user told you. Sections: product, environment_target (a URL, API, database or Kafka the tests use), repo (source code the AI may read), connector (non-secret settings for Jira, Azure DevOps, GitHub, GitLab or Jenkins), context_file (a note about the system), contract_from_url (download an OpenAPI or schema file). Never put a token or password in values: name the variable (password_env) and have the user run ./set-secret.sh.", """
                    {"type":"object","required":["section","values"],"properties":{
                      "section":{"type":"string","enum":["product","environment_target","repo","connector","context_file","contract_from_url"]},
                      "values":{"type":"object","description":"product: name, owner, description. environment_target: name, type (api|screen|kafka|postgres|mongo), description, baseUrl or url or brokers, database (mongo), user, user_env, password_env, devices, schemas, context, howToStart. repo: name, provider (local|github|gitlab|ado), path or owner and repo and ref, purpose. connector: kind (jira|azureDevOps|github|gitlab|jenkins) and settings (key and value pairs such as baseUrl, project, owner, repo). context_file: path and content. contract_from_url: url and optional path."}}}""",
                        this::configure),
                Tool.of("quartet_secrets_status", "Which secrets the setup needs and which are saved, by name only. Values are never shown. Tell the user to run ./set-secret.sh NAME in a terminal for each missing one.", "{\"type\":\"object\",\"properties\":{}}",
                        args -> secretsStatus()),
                Tool.of("quartet_verify", "Check a connection for real: environment (every URL, API, database and broker answers), tracker (read one story: give provider and sample id), code_host (read the repository: give provider), jenkins, or repos. Run it after the user saves a secret.", """
                    {"type":"object","required":["what"],"properties":{
                      "what":{"type":"string","enum":["environment","tracker","code_host","jenkins","repos"]},
                      "provider":{"type":"string","description":"jira, ado, github or gitlab"},
                      "sample":{"type":"string","description":"For tracker: the id of a real story, such as SHOP-1"},
                      "ref":{"type":"string","description":"For code_host: the branch to read. Default main."}}}""",
                        this::verify));
    }

    // ------------------------------------------------------------------ checklist

    private Path contextDir(Main main) {
        return main.root.resolve(main.settings.path("context").asText("context"));
    }

    private static ObjectNode item(ArrayNode items, String id, String title, String status, String detail) {
        return items.addObject().put("id", id).put("title", title).put("status", status).put("detail", detail);
    }

    private JsonNode checklist() throws IOException {
        Main main = fresh.get();
        ObjectNode out = Json.obj();
        ArrayNode items = out.putArray("items");

        Path manifest = contextDir(main).resolve("manifest.yaml");
        JsonNode product = Files.isRegularFile(manifest) ? Json.parseYaml(Files.readString(manifest)) : Json.obj();
        boolean productDone = !product.path("product").asText("").isBlank() && !product.path("product").asText().contains("replace with your own");
        item(items, "product", "What the product is", productDone ? "done" : "todo",
                productDone ? "Product: " + product.path("product").asText() + ", owner " + product.path("owner").asText("not set") : "No product name or owner yet.")
                .put("ask", "What is the product called, who owns it, and what does it do in a sentence?")
                .put("how", "quartet_configure section=product");

        List<String> realTargets = new ArrayList<>();
        main.environment.targets().forEach((name, target) -> {
            if (!target.path("description").asText("").startsWith("Replace with")) realTargets.add(name);
        });
        boolean envDone = !realTargets.isEmpty();
        item(items, "environment", "The systems the tests will use (URLs)", envDone ? "done" : "todo",
                envDone ? "Targets: " + realTargets + " in " + main.environmentPath : "No real targets yet. Only the placeholder example-api.")
                .put("ask", "What should be tested? For each: its web address or API address, database, or Kafka brokers. Is it a test or staging environment? (Quartet only runs against hosts you allow, and not production unless you say so.)")
                .put("how", "quartet_configure section=environment_target, then quartet_verify what=environment");

        boolean hasContracts = Files.isDirectory(contextDir(main).resolve("contracts")) || Files.isDirectory(contextDir(main).resolve("services"));
        item(items, "context", "What the AI should know about the system", hasContracts ? "done" : "optional",
                hasContracts ? "Contracts or service descriptions are present." : "Nothing yet. Tests are only as good as what the AI can read: API specs, event schemas, what each service does.")
                .put("ask", "Do you have an OpenAPI spec (a URL or file), event schemas, or notes about how the system works? Anything about key journeys, test data, or things that must never be tested?")
                .put("how", "quartet_configure section=contract_from_url or context_file");

        boolean principles = Files.isRegularFile(contextDir(main).resolve("engineering-principles/principles.yaml"));
        item(items, "principles", "Your engineering principles", principles ? "done" : "todo",
                principles ? "The default principles are in place. Each can be set to block, warn or off in engineering-principles/principles.yaml." : "No principles file.")
                .put("ask", "The defaults are the quartet's 12 principles. Do you want to change any rule's strictness?");

        JsonNode repos = Files.isRegularFile(contextDir(main).resolve("repos/repos.yaml")) ? Json.parseYaml(Files.readString(contextDir(main).resolve("repos/repos.yaml"))).path("repos") : Json.arr();
        item(items, "source_repos", "Source code the AI may read", repos.size() > 0 ? "done" : "optional",
                repos.size() > 0 ? repos.size() + " repository entries." : "None. Reading the service's source lets the AI avoid guessing.")
                .put("ask", "Where does the code for this product live (a local folder, or a GitHub, GitLab or Azure DevOps repo)?")
                .put("how", "quartet_configure section=repo");

        Map<String, String> variables = DotEnv.merged(main.root);
        ObjectNode connectors = out.putObject("integrations");
        for (String kind : List.of("jira", "azureDevOps", "github", "gitlab", "jenkins")) {
            ObjectNode state = connectors.putObject(kind);
            List<String> missing = new ArrayList<>();
            List<String> secrets = new ArrayList<>();
            JsonNode section = main.connectors.config().path(kind);
            for (String key : REQUIRED.get(kind)) {
                String value = section.path(key).asText("");
                boolean secret = SECRET_KEY.matcher(key).matches();
                boolean missingValue = value.isBlank() || PLACEHOLDER_VALUE.matcher(value).matches();
                if (missingValue) {
                    missing.add(key);
                    if (secret) secrets.addAll(secretNames(main, kind, key));
                }
            }
            state.put("configured", missing.isEmpty());
            state.putArray("missing").addAll(toArray(missing));
            state.putArray("secrets_to_save").addAll(toArray(secrets.stream().distinct().toList()));
        }
        boolean anyTracker = isConfigured(connectors, "jira") || isConfigured(connectors, "azureDevOps") || isConfigured(connectors, "github") || isConfigured(connectors, "gitlab");
        item(items, "tracker_and_git", "Where stories live, and git access for the tests repo", anyTracker ? "done" : "optional",
                anyTracker ? "At least one of Jira, Azure DevOps, GitHub or GitLab is configured." : "None yet. Without one you write stories as files, and cannot open pull requests.")
                .put("ask", "Where do your stories live (Jira, Azure DevOps, GitHub issues, GitLab issues) and where is your code hosted? I will need the project details, and you will save the access token yourself.")
                .put("how", "quartet_configure section=connector, then ./set-secret.sh, then quartet_verify what=tracker or code_host");
        item(items, "jenkins", "A Jenkins pipeline", isConfigured(connectors, "jenkins") ? "done" : "optional",
                isConfigured(connectors, "jenkins") ? "Jenkins is configured." : "Not configured. Quartet creates the job once it has the address and a token.")
                .put("ask", "Do you use Jenkins? If so, what is its address and the user to log in as?")
                .put("how", "quartet_configure section=connector kind=jenkins, then ./set-secret.sh JENKINS_TOKEN, then quartet_verify what=jenkins");

        boolean ai = variables.containsKey("AI_API_KEY") || variables.containsKey("ANTHROPIC_API_KEY") || variables.containsKey("OPENAI_API_KEY") || variables.containsKey("GEMINI_API_KEY");
        item(items, "ai", "An AI key", "optional", ai ? "A key is saved, for unattended runs." : "No key, and none is needed: you, the AI in this chat, write the tests. A key is only for runs with nobody chatting, such as a pipeline.");

        boolean origin = hasOrigin(main);
        item(items, "tests_repo_remote", "A remote for the tests repo", origin ? "done" : "optional",
                origin ? "The tests repo has a remote, so pull requests and Jenkins can use it." : "No remote yet. Create an empty repo on the git host and run git remote add origin <url>. Needed for pull requests and Jenkins.")
                .put("ask", "Have you created the repo for these tests on your git host? What is its address?");

        boolean ready = productDone && envDone;
        out.put("ready_to_write_first_test", ready);
        out.put("next", ready
                ? "The minimum is in place. Tell the user, and offer the optional items one at a time. Then they can ask for their first test."
                : "Work through the items marked todo, one at a time, asking only what is missing. Use quartet_configure to write each answer, then quartet_onboarding again.");
        out.put("rules", "Never ask for or accept a token, password or key in chat. For a secret, name the variable and tell the user to run ./set-secret.sh NAME in a terminal, then use quartet_verify.");
        return out;
    }

    /** The minimum for a first test that is still missing, so quartet_start can point at onboarding. */
    static List<String> missingBasics(Main main) throws IOException {
        List<String> missing = new ArrayList<>();
        Path manifest = main.root.resolve(main.settings.path("context").asText("context")).resolve("manifest.yaml");
        JsonNode product = Files.isRegularFile(manifest) ? Json.parseYaml(Files.readString(manifest)) : Json.obj();
        String name = product.path("product").asText("");
        if (name.isBlank() || name.contains("replace with your own")) missing.add("product");
        boolean real = main.environment.targets().values().stream().anyMatch(t -> !t.path("description").asText("").startsWith("Replace with"));
        if (!real) missing.add("environment");
        return missing;
    }

    private static boolean isConfigured(ObjectNode connectors, String kind) {
        return connectors.path(kind).path("configured").asBoolean(false);
    }

    private static boolean hasOrigin(Main main) {
        try {
            Process process = new ProcessBuilder("git", "remote", "get-url", "origin").directory(main.root.toFile()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ArrayNode toArray(List<String> values) {
        ArrayNode array = Json.arr();
        values.forEach(array::add);
        return array;
    }

    /** The variable a setting reads from, taken from the raw connectors.yaml. */
    private List<String> secretNames(Main main, String kind, String key) {
        Path file = main.root.resolve(main.settings.path("connectors").asText("connectors.yaml"));
        try {
            JsonNode raw = Files.isRegularFile(file) ? Json.parseYaml(Files.readString(file)).path(kind).path(key) : Json.MAPPER.missingNode();
            Matcher matcher = PLACEHOLDER.matcher(raw.asText(""));
            List<String> names = new ArrayList<>();
            while (matcher.find()) names.add(matcher.group(1));
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ secrets

    private JsonNode secretsStatus() throws IOException {
        Main main = fresh.get();
        Map<String, String> variables = DotEnv.merged(main.root);
        Set<String> names = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        files.add(main.root.resolve(main.settings.path("connectors").asText("connectors.yaml")));
        files.add(main.root.resolve(main.environmentPath));
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            Matcher matcher = PLACEHOLDER.matcher(Files.readString(file));
            while (matcher.find()) {
                String name = matcher.group(1);
                if (SECRET_KEY.matcher(name).matches()) names.add(name);
            }
        }
        ObjectNode out = Json.obj();
        ArrayNode list = out.putArray("secrets");
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            boolean set = variables.containsKey(name) && !variables.get(name).isBlank();
            list.addObject().put("name", name).put("saved", set);
            if (!set) missing.add(name);
        }
        out.put("env_file", Files.isRegularFile(main.root.resolve(".env")) ? "present, and ignored by git" : "not created yet");
        out.put("next", missing.isEmpty() ? "Nothing to save. Use quartet_verify to check the connections."
                : "Ask the user to run these in a terminal in " + main.root + ", one at a time. The value is typed hidden and never shown to you: "
                + missing.stream().map(n -> "./set-secret.sh " + n).toList() + ". Then call quartet_verify.");
        return out;
    }

    // ------------------------------------------------------------------ configure

    private JsonNode configure(JsonNode args) throws Exception {
        Main main = fresh.get();
        JsonNode values = args.path("values");
        rejectSecrets(values);
        return switch (args.path("section").asText()) {
            case "product" -> product(main, values);
            case "environment_target" -> environmentTarget(main, values);
            case "repo" -> repo(main, values);
            case "connector" -> connector(main, values);
            case "context_file" -> contextFile(main, values);
            case "contract_from_url" -> contractFromUrl(main, values);
            default -> throw new IllegalArgumentException("section must be product, environment_target, repo, connector, context_file or contract_from_url.");
        };
    }

    /** A secret typed into a chat is a leak. Refuse it and say what to do instead. */
    private static void rejectSecrets(JsonNode values) {
        values.fields().forEachRemaining(field -> {
            String key = field.getKey();
            if (SECRET_KEY.matcher(key).matches() && !key.endsWith("_env") && !key.equals("credentialsId")) {
                throw new IllegalArgumentException("Do not put '" + key + "' in a chat. Name a variable instead (for a database, use " + key + "_env, such as ORDERS_DB_PASSWORD; "
                        + "an integration already has one, such as JIRA_TOKEN) and ask the user to run ./set-secret.sh NAME in a terminal. The value then never reaches you.");
            }
            if (field.getValue().isObject()) rejectSecrets(field.getValue());
        });
    }

    private static String need(JsonNode values, String field) {
        String value = values.path(field).asText("").strip();
        if (value.isBlank()) throw new IllegalArgumentException("'" + field + "' is needed. Ask the user for it.");
        return value;
    }

    private JsonNode product(Main main, JsonNode values) throws IOException {
        Path file = contextDir(main).resolve("manifest.yaml");
        ObjectNode manifest = Files.isRegularFile(file) ? (ObjectNode) Json.parseYaml(Files.readString(file)) : Json.obj();
        manifest.put("product", need(values, "name"));
        manifest.put("owner", need(values, "owner"));
        if (!values.path("description").asText("").isBlank()) manifest.put("description", values.path("description").asText());
        if (!manifest.has("read_first")) {
            ArrayNode readFirst = manifest.putArray("read_first");
            for (String candidate : List.of("engineering-principles/principles.yaml", "engineering-principles/quality-engineering.md", "engineering-principles/tech-stack.yaml")) {
                if (Files.isRegularFile(contextDir(main).resolve(candidate))) readFirst.add(candidate);
            }
        }
        if (!manifest.has("services")) manifest.putArray("services");
        write(file, Json.YAML.writeValueAsString(manifest));
        return Json.obj().put("saved", main.root.relativize(file).toString()).put("next", "Call quartet_onboarding to see what is left.");
    }

    private JsonNode environmentTarget(Main main, JsonNode values) throws Exception {
        String name = need(values, "name");
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("A target name is lowercase letters, digits and hyphens, such as orders-api.");
        String type = need(values, "type");
        if (!TARGET_TYPES.contains(type)) throw new IllegalArgumentException("type must be one of " + TARGET_TYPES + ".");

        ObjectNode target = Json.obj().put("type", type);
        target.put("description", values.path("description").asText(name));
        List<String> addresses = new ArrayList<>();
        switch (type) {
            case "api", "screen" -> {
                String base = need(values, "baseUrl");
                webAddress(base);
                target.put("baseUrl", base);
                addresses.add(base);
                if (type.equals("screen")) {
                    target.set("devices", values.has("devices") ? values.get("devices") : Json.parseYaml("desktop: {kind: web, engine: chromium, preset: desktop}"));
                }
            }
            case "kafka" -> {
                String brokers = need(values, "brokers");
                target.put("brokers", brokers);
                addresses.add(brokers);
                if (values.has("schemas")) target.set("schemas", values.get("schemas"));
            }
            case "postgres", "mongo" -> {
                String url = need(values, "url");
                target.put("url", url);
                addresses.add(url);
                if (type.equals("mongo")) target.put("database", need(values, "database"));
                if (!values.path("user").asText("").isBlank()) target.put("user", values.path("user").asText());
                if (!values.path("user_env").asText("").isBlank()) target.put("user", "${" + envName(values.path("user_env").asText()) + "}");
                if (!values.path("password_env").asText("").isBlank()) target.put("password", "${" + envName(values.path("password_env").asText()) + "}");
            }
            default -> throw new IllegalStateException(type);
        }
        if (values.has("context")) target.set("context", values.get("context"));

        Path file = main.root.resolve(main.environmentPath);
        String before = Files.isRegularFile(file) ? Files.readString(file) : null;
        ObjectNode environment = before == null ? Json.obj().put("name", main.root.getFileName().toString()) : (ObjectNode) Json.parseYaml(before);
        ObjectNode targets = environment.has("targets") ? (ObjectNode) environment.get("targets") : environment.putObject("targets");
        for (String existing : new ArrayList<>(iterable(targets.fieldNames()))) {
            if (targets.path(existing).path("description").asText("").startsWith("Replace with")) targets.remove(existing);
        }
        targets.set(name, target);
        ObjectNode guard = environment.has("guard") ? (ObjectNode) environment.get("guard") : environment.putObject("guard");
        guard.put("production", guard.path("production").asBoolean(false));
        ArrayNode allow = guard.has("allowHosts") ? (ArrayNode) guard.get("allowHosts") : guard.putArray("allowHosts");
        Set<String> hosts = new LinkedHashSet<>();
        allow.forEach(h -> hosts.add(h.asText()));
        List<String> added = new ArrayList<>();
        for (String address : addresses) {
            for (String host : Preflight.address(address).keySet()) {
                if (hosts.add(host)) {
                    allow.add(host);
                    added.add(host);
                }
            }
        }
        if (!values.path("howToStart").asText("").isBlank()) environment.put("howToStart", values.path("howToStart").asText());

        write(file, Json.YAML.writeValueAsString(environment));
        try {
            Environment.load(file, DotEnv.merged(main.root));
        } catch (RuntimeException e) {
            if (before == null) Files.deleteIfExists(file);
            else Files.writeString(file, before);
            throw new IllegalArgumentException("That target would not load, so nothing was changed: " + e.getMessage());
        }
        ObjectNode out = Json.obj().put("saved", main.root.relativize(file).toString()).put("target", name);
        out.putArray("hosts_now_allowed").addAll(toArray(added));
        List<String> secrets = new ArrayList<>();
        for (String field : List.of("user_env", "password_env")) {
            String variable = values.path(field).asText("");
            if (!variable.isBlank() && DotEnv.merged(main.root).getOrDefault(variable, "").isBlank()) secrets.add(variable);
        }
        out.putArray("secrets_to_save").addAll(toArray(secrets));
        out.put("next", secrets.isEmpty() ? "Call quartet_verify what=environment to check it answers."
                : "Ask the user to run " + secrets.stream().map(s -> "./set-secret.sh " + s).toList() + " in a terminal, then call quartet_verify what=environment.");
        return out;
    }

    private static <T> List<T> iterable(java.util.Iterator<T> iterator) {
        List<T> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }

    private static String envName(String value) {
        if (!ENV_NAME.matcher(value).matches()) throw new IllegalArgumentException("'" + value + "' is not a variable name. Use capital letters, digits and underscores, such as ORDERS_DB_PASSWORD.");
        return value;
    }

    private static void webAddress(String address) {
        if (!address.matches("(?i)https?://[^\\s]+") && !address.contains("${")) {
            throw new IllegalArgumentException("'" + address + "' is not a web address. Use something like https://staging.example.com.");
        }
    }

    private JsonNode repo(Main main, JsonNode values) throws IOException {
        String name = need(values, "name");
        String provider = need(values, "provider");
        if (!Set.of("local", "github", "gitlab", "ado").contains(provider)) throw new IllegalArgumentException("provider must be local, github, gitlab or ado.");
        ObjectNode entry = Json.obj().put("name", name).put("provider", provider);
        if (provider.equals("local")) {
            entry.put("path", need(values, "path"));
        } else {
            for (String field : List.of("owner", "repo", "ref", "project", "repository")) {
                if (!values.path(field).asText("").isBlank()) entry.put(field, values.path(field).asText());
            }
        }
        if (!values.path("purpose").asText("").isBlank()) entry.put("purpose", values.path("purpose").asText());
        Path file = contextDir(main).resolve("repos/repos.yaml");
        ObjectNode root = Files.isRegularFile(file) ? (ObjectNode) Json.parseYaml(Files.readString(file)) : Json.obj();
        ArrayNode repos = root.has("repos") && root.get("repos").isArray() ? (ArrayNode) root.get("repos") : root.putArray("repos");
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).path("name").asText().equals(name)) {
                repos.remove(i);
                break;
            }
        }
        repos.add(entry);
        write(file, Json.YAML.writeValueAsString(root));
        return Json.obj().put("saved", main.root.relativize(file).toString()).put("next", "Call quartet_verify what=repos to check it can be read.");
    }

    /** Fills in a connector's non-secret settings by changing the defaults in connectors.yaml. */
    private JsonNode connector(Main main, JsonNode values) throws IOException {
        String kind = need(values, "kind");
        if (!REQUIRED.containsKey(kind)) throw new IllegalArgumentException("kind must be one of " + new java.util.TreeSet<>(REQUIRED.keySet()) + ".");
        JsonNode settings = values.path("settings");
        if (!settings.isObject() || settings.isEmpty()) throw new IllegalArgumentException("settings is needed, for example {\"baseUrl\": \"https://company.atlassian.net\"}.");
        Path file = main.root.resolve(main.settings.path("connectors").asText("connectors.yaml"));
        if (!Files.isRegularFile(file)) throw new IllegalStateException(file.getFileName() + " does not exist in this project.");
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        List<String> changed = new ArrayList<>();
        Set<String> secretsNeeded = new LinkedHashSet<>();
        Map<String, String> variables = DotEnv.merged(main.root);

        var fields = settings.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            if (SECRET_KEY.matcher(key).matches() && !key.equals("credentialsId")) {
                throw new IllegalArgumentException("Do not put '" + key + "' in a chat. The user saves it with ./set-secret.sh, and connectors.yaml already reads it from the environment.");
            }
            String value = field.getValue().asText();
            if (value.contains("\"") || value.contains("\n") || value.contains("${")) throw new IllegalArgumentException("'" + value + "' is not a valid value for " + key + ".");
            boolean section = false;
            boolean done = false;
            for (int i = 0; i < lines.size() && !done; i++) {
                String line = lines.get(i);
                if (line.matches("^[A-Za-z]\\w*:\\s*(#.*)?$")) section = line.startsWith(kind + ":");
                else if (section && line.matches("^\\s+" + Pattern.quote(key) + ":.*")) {
                    String indent = line.substring(0, line.indexOf(key));
                    Matcher placeholder = PLACEHOLDER.matcher(line);
                    String replacement = placeholder.find()
                            ? indent + key + ": \"${" + placeholder.group(1) + ":-" + value + "}\""
                            : indent + key + ": \"" + value + "\"";
                    String comment = line.contains("#") ? "   " + line.substring(line.indexOf('#')) : "";
                    lines.set(i, replacement + comment);
                    done = true;
                }
            }
            if (!done) throw new IllegalArgumentException("'" + key + "' is not a setting of " + kind + " in " + file.getFileName() + ".");
            changed.add(key);
        }
        Files.write(file, lines);

        Main reloaded = fresh.get();
        for (String key : REQUIRED.get(kind)) {
            if (SECRET_KEY.matcher(key).matches()) {
                for (String name : secretNames(reloaded, kind, key)) {
                    if (variables.getOrDefault(name, "").isBlank()) secretsNeeded.add(name);
                }
            }
        }
        ObjectNode out = Json.obj().put("saved", file.getFileName().toString()).put("kind", kind);
        out.putArray("settings_changed").addAll(toArray(changed));
        out.putArray("secrets_to_save").addAll(toArray(new ArrayList<>(secretsNeeded)));
        out.put("next", secretsNeeded.isEmpty() ? "Call quartet_verify to check the connection."
                : "Ask the user to run " + secretsNeeded.stream().map(s -> "./set-secret.sh " + s).toList() + " in a terminal. The value is hidden and never shown to you. Then call quartet_verify.");
        return out;
    }

    private JsonNode contextFile(Main main, JsonNode values) throws IOException {
        String relative = need(values, "path");
        String content = values.path("content").asText("");
        Path dir = contextDir(main).toAbsolutePath().normalize();
        Path file = dir.resolve(relative).normalize();
        if (!file.startsWith(dir) || relative.startsWith("/") || relative.contains("..")) throw new IllegalArgumentException("The path must be inside the context folder.");
        if (!relative.matches("(?i).+\\.(md|yaml|yml|json|txt)")) throw new IllegalArgumentException("A context file is .md, .yaml, .yml, .json or .txt.");
        if (content.length() > 200_000) throw new IllegalArgumentException("That file is too large for a context note.");
        if (relative.matches("(?i).+\\.(yaml|yml)")) Json.parseYaml(content);
        if (relative.matches("(?i).+\\.json")) Json.parse(content);
        write(file, content.endsWith("\n") ? content : content + "\n");
        return Json.obj().put("saved", main.root.relativize(file).toString()).put("next", "Mention it in the story, or in a service or contract file, so the AI reads it.");
    }

    /** Downloads an OpenAPI or schema file into the context. Only plain web addresses, no credentials, a size limit. */
    private JsonNode contractFromUrl(Main main, JsonNode values) throws Exception {
        String url = need(values, "url");
        URI uri = URI.create(url);
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Give a plain http or https address with no username or password in it.");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IllegalStateException(url + " answered " + response.statusCode() + ". If it needs a login, download the file yourself and ask the user to put it in context/contracts/.");
        if (response.body().length > 2_000_000) throw new IllegalStateException("That file is larger than 2 MB.");
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode parsed;
        try {
            parsed = body.stripLeading().startsWith("{") ? Json.parse(body) : Json.parseYaml(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("That is not valid JSON or YAML, so it was not saved.");
        }
        String last = uri.getPath() == null || uri.getPath().isBlank() || uri.getPath().endsWith("/") ? "api" : uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        String name = values.path("path").asText("").isBlank()
                ? "contracts/openapi/" + last.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("\\.(json|ya?ml)$", "") + (body.stripLeading().startsWith("{") ? ".json" : ".yaml")
                : values.path("path").asText();
        ObjectNode saved = (ObjectNode) contextFile(main, Json.obj().put("path", name).put("content", body));
        saved.put("bytes", response.body().length);
        if (parsed.has("openapi") || parsed.has("swagger")) {
            saved.put("looks_like", "OpenAPI " + parsed.path("info").path("title").asText("") + " with " + parsed.path("paths").size() + " paths");
        }
        return saved;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ------------------------------------------------------------------ verify

    private JsonNode verify(JsonNode args) throws IOException {
        Main main = fresh.get();
        String what = args.path("what").asText();
        ObjectNode out = Json.obj().put("what", what);
        // Mistakes in the request are the AI's to fix. A failing connection is a result to report.
        switch (what) {
            case "environment", "jenkins", "repos" -> { }
            case "tracker" -> {
                if (args.path("provider").asText("").isBlank() || args.path("sample").asText("").isBlank()) {
                    throw new IllegalArgumentException("Give provider (jira, ado, github or gitlab) and the id of a real story as sample.");
                }
            }
            case "code_host" -> {
                if (args.path("provider").asText("").isBlank()) throw new IllegalArgumentException("Give provider (github, gitlab or ado).");
            }
            default -> throw new IllegalArgumentException("what must be environment, tracker, code_host, jenkins or repos.");
        }
        try {
            switch (what) {
                case "environment" -> {
                    List<String> down = Preflight.unreachable(main.environment.targets(), 3000);
                    out.put("ok", down.isEmpty());
                    out.putArray("not_reachable").addAll(toArray(down));
                    out.put("checked", main.environment.targets().keySet().toString());
                    if (!down.isEmpty()) out.put("hint", "Check the address, that the system is running, and any VPN. Fix it with quartet_configure section=environment_target.");
                }
                case "tracker" -> {
                    String provider = args.path("provider").asText("");
                    String sample = args.path("sample").asText("");
                    WorkItem story = main.connectors.workItems(provider).get(sample);
                    out.put("ok", true).put("found", story.id() + ": " + story.title()).put("acceptance_criteria", story.acceptanceCriteria().size());
                }
                case "code_host" -> {
                    String provider = args.path("provider").asText("");
                    List<String> files = main.connectors.codeHost(provider).listFiles("", args.path("ref").asText("main"));
                    out.put("ok", true).put("files_seen", files.size());
                }
                case "jenkins" -> out.put("ok", true).put("found", main.connectors.ci("jenkins").ping());
                case "repos" -> {
                    Path file = contextDir(main).resolve("repos/repos.yaml");
                    JsonNode repos = Files.isRegularFile(file) ? Json.parseYaml(Files.readString(file)).path("repos") : Json.arr();
                    ArrayNode results = out.putArray("repos");
                    boolean all = true;
                    for (JsonNode repo : repos) {
                        ObjectNode result = results.addObject().put("name", repo.path("name").asText());
                        try {
                            if (repo.path("provider").asText().equals("local")) {
                                Path path = contextDir(main).resolve(repo.path("path").asText()).normalize();
                                result.put("ok", Files.isDirectory(path));
                                if (!Files.isDirectory(path)) all = false;
                            } else {
                                int count = main.connectors.codeHost(repo.path("provider").asText(), repo).listFiles("", repo.path("ref").asText("main")).size();
                                result.put("ok", true).put("files_seen", count);
                            }
                        } catch (RuntimeException e) {
                            result.put("ok", false).put("problem", Redactor.clean(String.valueOf(e.getMessage())));
                            all = false;
                        }
                    }
                    out.put("ok", all && !repos.isEmpty());
                    if (repos.isEmpty()) out.put("hint", "No repositories are listed. Add one with quartet_configure section=repo.");
                }
                default -> throw new IllegalStateException(what);
            }
        } catch (RuntimeException e) {
            String problem = Redactor.clean(String.valueOf(e.getMessage()));
            out.put("ok", false).put("problem", problem);
            out.put("hint", hint(problem));
        }
        return out;
    }

    private static String hint(String problem) {
        if (problem.contains("401") || problem.contains("403")) {
            return "The service rejected the credentials or they lack access. Ask the user to check the token has the right permissions and was saved with ./set-secret.sh. Never ask them to paste it here.";
        }
        if (problem.contains("404")) return "Not found. Check the project, repository or id with the user.";
        if (problem.toLowerCase().contains("no 'jira'") || problem.contains("has no '")) return "That integration is not in connectors.yaml. Add its settings with quartet_configure section=connector.";
        return "Check the address and that the service is reachable from this machine (VPN, firewall), then try again.";
    }
}
